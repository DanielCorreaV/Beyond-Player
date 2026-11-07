/*
 * Copyright (c) 2026 Auxio Project
 * SongMatcher.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */
 
package org.oxycblt.auxio.music.youtube

import java.text.Normalizer
import kotlin.math.abs
import kotlin.math.max
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.tag.Name

/** Result of matching a [RemotePlaylist] against the local library. */
data class MatchResult(
    /** Matched local songs, in remote order, without duplicates. */
    val songs: List<Song>,
    /** Remote tracks that could not be matched to a local song. */
    val missing: List<RemoteTrack>,
    /** Amount of remote tracks that were matched. */
    val matchedCount: Int,
    /** Each matched remote track with the local song it was matched to (for diagnostics). */
    val pairs: List<Pair<RemoteTrack, Song>> = emptyList(),
    /** The subset of [pairs] found by the last-resort tier (artist and duration only). */
    val weakPairs: List<Pair<RemoteTrack, Song>> = emptyList(),
    /** Local songs that no remote track was matched to (for diagnostics). */
    val unmatched: List<Song> = emptyList(),
)

/**
 * Matches remote tracks to local songs. Tags written by downloaders rarely look like what YouTube
 * lists (localized "con" instead of "feat.", "Artist - Title" video names, "Japanese - Romaji"
 * titles, translated titles), so the matching works in three steps:
 * 1. Every (remote, local) pair that looks alike is an edge: same cleaned title and artist
 *    (priority 0), shared title variant (1) or similar title (2), always confirmed by duration
 *    within [DURATION_TOLERANCE_SEC]. Edges are ranked by duration difference, then priority.
 * 2. A one-to-one assignment takes the best edges first, so two playlist entries with similar names
 *    get two different files. Entries left over may reuse a file already taken.
 * 3. Entries still without a file are paired with an unused local song of the same artist whose
 *    duration is within 1 s, but only if the titles are related (they share words, or one is in
 *    another script, as in a translation or romanization). Entries and files that fit each other
 *    form groups; a group is only paired when it has as many entries as files, since then every
 *    entry is known to be on the phone even if the exact pairing inside the group is a guess. A new
 *    song that is not on the phone stays missing even if the artist has other songs.
 */
object SongMatcher {
    const val SIMILARITY_THRESHOLD = 0.8
    const val DURATION_TOLERANCE_SEC = 3L
    private const val WEAK_TOLERANCE_SEC = 1L
    private const val MIN_SHARED_RUN_CHARS = 7

    fun match(tracks: List<RemoteTrack>, library: Collection<Song>): MatchResult {
        val candidates = library.map { Candidate(it) }
        val remotes = tracks.map { Remote(it) }

        val edges = ArrayList<Edge>()
        for ((i, remote) in remotes.withIndex()) {
            if (remote.keys.full.isEmpty()) continue
            for ((j, candidate) in candidates.withIndex()) {
                edge(remote, candidate, i, j)?.let { edges.add(it) }
            }
        }
        val sorted =
            edges.sortedWith(
                compareBy<Edge>({ it.fit }, { it.diff }, { it.priority }, { -it.score })
            )

        val assigned = IntArray(remotes.size) { -1 }
        val taken = HashSet<Int>()
        for (e in sorted) {
            if (assigned[e.remote] == -1 && e.candidate !in taken) {
                assigned[e.remote] = e.candidate
                taken.add(e.candidate)
            }
        }
        // The same song listed twice may share one file, but only if it is really the same song.
        for (e in sorted) {
            if (assigned[e.remote] == -1 && e.priority <= 1 && e.diff <= 1) {
                assigned[e.remote] = e.candidate
            }
        }

        // Remote artist -> local artists, learned from the confident pairs.
        val alias = HashMap<String, MutableSet<String>>()
        for ((i, remote) in remotes.withIndex()) {
            val j = assigned[i]
            if (j != -1 && remote.artist.isNotEmpty()) {
                alias.getOrPut(remote.artist) { HashSet() }.addAll(candidates[j].artists)
            }
        }

        val candidatesByEntry = HashMap<Int, MutableList<Pair<Int, Int>>>()
        for ((i, remote) in remotes.withIndex()) {
            if (assigned[i] != -1 || remote.durationSec < 0) continue
            for ((j, candidate) in candidates.withIndex()) {
                if (j in taken) continue
                val diff = abs(candidate.durationSec - remote.durationSec)
                if (diff > WEAK_TOLERANCE_SEC) continue
                val artistFits =
                    if (candidate.artists.isEmpty()) {
                        diff == 0L
                    } else {
                        (remote.artist.isNotEmpty() && artistOk(remote, candidate)) ||
                            candidate.artists.any { it in alias[remote.artist].orEmpty() }
                    }
                val strength = if (artistFits) relation(remote, candidate) else 0
                if (strength > 0) {
                    candidatesByEntry.getOrPut(i) { ArrayList() }.add(j to strength)
                }
            }
        }
        // Keep only the files whose title is related in the strongest way for each entry.
        val fits = HashMap<Int, List<Int>>()
        for ((i, list) in candidatesByEntry) {
            val top = list.maxOf { it.second }
            fits[i] = list.filter { it.second == top }.map { it.first }
        }
        // Group entries and files that fit each other (union-find over both kinds of nodes).
        val parent = IntArray(remotes.size + candidates.size) { it }
        fun find(x: Int): Int {
            var root = x
            while (parent[root] != root) root = parent[root]
            var node = x
            while (parent[node] != root) {
                val next = parent[node]
                parent[node] = root
                node = next
            }
            return root
        }
        for ((i, js) in fits) for (j in js) parent[find(i)] = find(remotes.size + j)
        val groups = HashMap<Int, MutableList<Int>>()
        for (i in fits.keys) groups.getOrPut(find(i)) { ArrayList() }.add(i)

        val weak = HashSet<Int>()
        for (group in groups.values) {
            val files = group.flatMap { fits.getValue(it) }.toSet()
            if (group.size != files.size) continue
            val options =
                group
                    .flatMap { i ->
                        fits.getValue(i).map { j ->
                            Triple(abs(candidates[j].durationSec - remotes[i].durationSec), i, j)
                        }
                    }
                    .sortedBy { it.first }
            val chosen = HashMap<Int, Int>()
            val claimed = HashSet<Int>()
            for ((_, i, j) in options) {
                if (i !in chosen && j !in claimed) {
                    chosen[i] = j
                    claimed.add(j)
                }
            }
            if (chosen.size != group.size) continue
            for ((i, j) in chosen) {
                assigned[i] = j
                taken.add(j)
                weak.add(i)
            }
        }

        val matched = mutableListOf<Song>()
        val missing = mutableListOf<RemoteTrack>()
        val pairs = mutableListOf<Pair<RemoteTrack, Song>>()
        val weakPairs = mutableListOf<Pair<RemoteTrack, Song>>()
        val usedFiles = HashSet<Int>()
        for ((i, track) in tracks.withIndex()) {
            val j = assigned[i]
            if (j == -1) {
                missing.add(track)
            } else {
                usedFiles.add(j)
                matched.add(candidates[j].song)
                pairs.add(track to candidates[j].song)
                if (i in weak) weakPairs.add(track to candidates[j].song)
            }
        }
        val unmatched = candidates.filterIndexed { j, _ -> j !in usedFiles }.map { it.song }
        return MatchResult(matched.distinct(), missing, pairs.size, pairs, weakPairs, unmatched)
    }

    private class Edge(
        val remote: Int,
        val candidate: Int,
        val fit: Int,
        val diff: Long,
        val priority: Int,
        val score: Double,
    )

    private fun edge(remote: Remote, candidate: Candidate, i: Int, j: Int): Edge? {
        val diff =
            if (remote.durationSec >= 0) abs(candidate.durationSec - remote.durationSec) else -1L
        val durationKnown = diff >= 0
        val durationOk = !durationKnown || diff <= DURATION_TOLERANCE_SEC
        val priority: Int
        var score = 1.0
        if (remote.keys.full == candidate.keys.full && artistOk(remote, candidate)) {
            priority = 0
        } else if (durationOk && remote.keys.variants.any { it in candidate.keys.variants }) {
            priority = 1
        } else if (durationKnown && durationOk) {
            score =
                max(
                    max(
                        similarity(remote.keys.full, candidate.keys.full),
                        similarity(remote.combined, candidate.combined),
                    ),
                    max(
                        similarity(remote.keys.full, candidate.combined),
                        similarity(remote.combined, candidate.keys.full),
                    ),
                )
            if (score < SIMILARITY_THRESHOLD) return null
            priority = 2
        } else {
            return null
        }
        return Edge(i, j, if (durationOk) 0 else 1, max(diff, 0L), priority, score)
    }

    /**
     * How related the titles of an artist/duration match are: 2 if they share a run of words (or a
     * word of 3+ CJK characters), 1 if one is written in another script (translation,
     * romanization), 0 if they have nothing in common.
     */
    private fun relation(remote: Remote, candidate: Candidate): Int {
        for (variant in candidate.keys.variants) {
            if (
                (' ' in variant || variant.length >= 5) &&
                    commonRun(remote.rawTitle, variant) >= MIN_SHARED_RUN_CHARS
            ) {
                return 2
            }
            if (sharesCjkWord(remote.rawTitle, variant)) return 2
        }
        return if (hasCjk(remote.rawTitle) != hasCjk(candidate.keys.full)) 1 else 0
    }

    private fun sharesCjkWord(raw: String, variant: String): Boolean {
        val words = raw.split(' ').toHashSet()
        return variant.split(' ').any { w ->
            w.length >= 3 && w.all { it.code > 0x2E7F } && w in words
        }
    }

    private fun hasCjk(s: String) = s.any { it.code > 0x2E7F }

    /** Length in characters of the longest run of whole words shared by [a] and [b]. */
    private fun commonRun(a: String, b: String): Int {
        val ta = a.split(' ').filter { it.isNotEmpty() }
        val tb = b.split(' ').filter { it.isNotEmpty() }
        var best = 0
        var prev = IntArray(tb.size + 1)
        for (i in 1..ta.size) {
            val cur = IntArray(tb.size + 1)
            for (j in 1..tb.size) {
                if (ta[i - 1] == tb[j - 1]) {
                    cur[j] = prev[j - 1] + 1
                    val chars = ta.subList(i - cur[j], i).joinToString(" ").length
                    if (chars > best) best = chars
                }
            }
            prev = cur
        }
        return best
    }

    private fun artistOk(remote: Remote, candidate: Candidate): Boolean {
        if (remote.artist.isEmpty() || candidate.artists.isEmpty()) return true
        return candidate.artists.any {
            it == remote.artist ||
                remote.artist.contains(it) ||
                it.contains(remote.artist) ||
                remote.keys.full.contains(it)
        }
    }

    private class TitleKeys(val full: String, val variants: Set<String>)

    private class Remote(track: RemoteTrack) {
        val keys = titleKeys(track.title)
        val rawTitle = squash(base(track.title))
        val artist = normalizeArtist(track.artist)
        val combined = "$artist ${keys.full}".trim()
        val durationSec = track.durationSec
    }

    private class Candidate(val song: Song) {
        val keys = titleKeys(song.name.raw)
        val artists =
            song.artists
                .mapNotNull { (it.name as? Name.Known)?.raw }
                .map { normalizeArtist(it) }
                .filter { it.isNotEmpty() }
        val combined = "${artists.joinToString(" ")} ${keys.full}".trim()
        val durationSec = song.durationMs / 1000
    }

    private val LATIN_MARKS = Regex("""[̀-ͯ]""")
    private val GROUPS = Regex("""[(\[【「『][^)\]】」』]*[)\]】」』]""")
    private val TRAILING_FEAT = Regex("""\s+\(?(feat\.?|ft\.?|featuring)\s.*$""")
    private val FEAT_START = Regex("""^(feat\.?|ft\.?|featuring|con|with|prod\.?|w/)(\s|$)""")
    private val NOISE_PHRASES =
        Regex(
            """\b(official (music |lyric |hd |animated )?video|official audio|video oficial|""" +
                """audio oficial|lyrics?|lyric video|letra|visuali[sz]er|music video|hd|hq|""" +
                """4k|audio|oficial|official)\b"""
        )
    private val NOISE_TOKENS =
        setOf(
            "official",
            "video",
            "audio",
            "music",
            "lyric",
            "lyrics",
            "hd",
            "hq",
            "4k",
            "8k",
            "mv",
            "visualizer",
            "visualiser",
            "animated",
            "remastered",
            "remaster",
            "oficial",
            "letra",
            "sub",
            "subtitulado",
            "esp",
            "eng",
            "español",
            "espanol",
        )
    private val NON_ALNUM = Regex("""[^\p{L}\p{N}]+""")
    private val SEPARATORS = Regex("""\s+[-–—|]\s+|\s*[/|]\s*""")
    private val ARTIST_NOISE =
        Regex(
            """\s-\s+topic\b|\bofficial( youtube)?( channel)?\b|\bvevo\b|\boficial\b|\bchannel\b"""
        )
    private const val BRACKET_CHARS = "()[]【】「」『』 "

    /** Lowercase, compatibility-normalized (full-width forms), without Latin accents. */
    private fun base(raw: String): String {
        val compat = Normalizer.normalize(raw, Normalizer.Form.NFKC).lowercase()
        val decomposed = Normalizer.normalize(compat, Normalizer.Form.NFD)
        // Only Latin accents are dropped: Japanese dakuten (U+3099) must survive.
        return Normalizer.normalize(LATIN_MARKS.replace(decomposed, ""), Normalizer.Form.NFC)
    }

    private fun squash(s: String) = NON_ALNUM.replace(s, " ").trim()

    private fun droppable(group: String): Boolean {
        val inner = group.trim { it in BRACKET_CHARS }
        if (FEAT_START.containsMatchIn(inner)) return true
        val tokens = NON_ALNUM.split(inner).filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return true
        return tokens.any { it in NOISE_TOKENS } &&
            tokens.all { it in NOISE_TOKENS || it.all { c -> c.isDigit() } }
    }

    /** Removes featuring credits and pure noise such as "(Official Video)". */
    private fun clean(raw: String): String {
        var s = base(raw)
        s = GROUPS.replace(s) { if (droppable(it.value)) " " else it.value }
        s = TRAILING_FEAT.replace(s, " ")
        return NOISE_PHRASES.replace(s, " ")
    }

    private fun useful(v: String) = v.length >= 3 || (v.length == 2 && v.any { it.code > 0x2E7F })

    private fun titleKeys(raw: String): TitleKeys {
        val cleaned = clean(raw)
        val full = squash(cleaned)
        val variants = LinkedHashSet<String>()
        variants.add(full)
        variants.add(full.replace(" ", ""))
        fun add(x: String) {
            val v = squash(x)
            if (useful(v)) {
                variants.add(v)
                variants.add(v.replace(" ", ""))
            }
        }
        add(GROUPS.replace(cleaned, " "))
        for (m in GROUPS.findAll(cleaned)) add(m.value.trim { it in BRACKET_CHARS })
        for (part in SEPARATORS.split(cleaned)) {
            add(part)
            add(GROUPS.replace(part, " "))
            for (m in GROUPS.findAll(part)) add(m.value.trim { it in BRACKET_CHARS })
        }
        variants.remove("")
        return TitleKeys(full, variants)
    }

    private fun normalizeArtist(raw: String): String = squash(ARTIST_NOISE.replace(base(raw), " "))

    /** 1 - normalized Levenshtein distance, in 0..1. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        if (a == b) return 1.0
        val maxLen = maxOf(a.length, b.length)
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev
            prev = cur
            cur = t
        }
        return prev[b.length]
    }
}
