/*
 * Copyright (c) 2026 Beyond Player
 * YtExplore.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject

enum class ExploreKind {
    SONG,
    ALBUM,
    PLAYLIST,
    ARTIST,
    MOOD,
}

/** One card of the Explore page. Which ids are filled depends on [kind]. */
data class ExploreItem(
    val kind: ExploreKind,
    val title: String,
    val subtitle: String,
    val artist: String,
    val thumbnail: String?,
    val videoId: String? = null,
    val browseId: String? = null,
    val params: String? = null,
    val playlistId: String? = null,
    val durationSec: Long = -1,
    val color: Int = 0,
) {
    /** The song this card plays, or null when it is not a song. */
    fun toTrack(): YtTrack? = videoId?.let { YtTrack(it, title, artist, durationSec, thumbnail) }
}

data class ExploreSection(val title: String, val items: List<ExploreItem>, val chips: Boolean)

/** The four parts of the Explore page; each is one request, so they load side by side. */
enum class ExploreGroup(val browseId: String, val maxSections: Int) {
    NEW("FEmusic_new_releases_albums", 3),
    MOODS("FEmusic_moods_and_genres", 4),
    MIXES("FEmusic_home", 6),
    CHARTS("FEmusic_charts", 4),
}

/**
 * YouTube Music's own pages (home, new releases, moods, charts) read as the web player does. The
 * answers are deeply nested and change shape now and then, so nothing here relies on a fixed path:
 * it looks for the shelves anywhere in the answer and for the fields inside each card by name.
 * The raw answer is saved in the cache folder so a change can be looked at later.
 */
object YtExplore {
    private const val TTL_MS = 30 * 60 * 1000L
    private const val MAX_ITEMS = 20
    private const val MAX_CHIPS = 60
    private val SHELVES =
        setOf("musicCarouselShelfRenderer", "musicShelfRenderer", "gridRenderer")
    private val SEPARATORS = setOf("•", "·", "&", ",", "-", "–")
    private val TYPE_WORDS =
        setOf(
            "song", "video", "single", "ep", "album", "playlist", "artist", "mix", "radio",
            "canción", "sencillo", "álbum", "lista", "artista",
        )

    private val cache = ConcurrentHashMap<String, Pair<Long, List<ExploreSection>>>()

    /** Blocking. Throws when the request fails or nothing readable came back. */
    fun load(group: ExploreGroup, dumpDir: File?): List<ExploreSection> {
        val now = System.currentTimeMillis()
        cache[group.browseId]?.takeIf { now - it.first < TTL_MS }?.let { return it.second }
        val text = post(group.browseId)
        if (dumpDir != null) {
            runCatching {
                dumpDir.mkdirs()
                File(dumpDir, group.name.lowercase() + ".json").writeText(text)
            }
        }
        val sections = parse(text).take(group.maxSections)
        if (sections.isEmpty()) throw IOException("Nothing readable in ${group.browseId}")
        cache[group.browseId] = now to sections
        return sections
    }

    // ---- request ----

    private fun post(browseId: String): String {
        val locale = Locale.getDefault()
        val version = "1." + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".01.00"
        val client =
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", version)
                .put("hl", locale.language.ifEmpty { "en" })
                .put("gl", locale.country.ifEmpty { "US" })
        val body =
            JSONObject()
                .put("context", JSONObject().put("client", client).put("user", JSONObject()))
                .put("browseId", browseId)
        val conn =
            URL("https://music.youtube.com/youtubei/v1/browse?prettyPrint=false").openConnection()
                as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 20_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Origin", "https://music.youtube.com")
            conn.setRequestProperty("Referer", "https://music.youtube.com/")
            conn.setRequestProperty("Cookie", "SOCS=CAI")
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
            )
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) throw IOException("HTTP $code for $browseId")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    // ---- reading the answer ----

    /** All shelves of an answer, in page order. */
    fun parse(text: String): List<ExploreSection> {
        val out = mutableListOf<ExploreSection>()
        collect(JSONObject(text), out)
        return out
    }

    private fun collect(node: Any?, out: MutableList<ExploreSection>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (key in SHELVES && value is JSONObject) {
                        shelf(value)?.let { out.add(it) }
                    } else {
                        collect(value, out)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out)
        }
    }

    private fun shelf(obj: JSONObject): ExploreSection? {
        val title = text(findObject(obj.optJSONObject("header"), "title"))
        val contents = obj.optJSONArray("contents") ?: obj.optJSONArray("items") ?: return null
        val items = mutableListOf<ExploreItem>()
        for (i in 0 until contents.length()) {
            val entry = contents.optJSONObject(i) ?: continue
            item(entry)?.let { items.add(it) }
        }
        if (items.isEmpty()) return null
        val chips = items.all { it.kind == ExploreKind.MOOD }
        return ExploreSection(title, items.take(if (chips) MAX_CHIPS else MAX_ITEMS), chips)
    }

    private fun item(entry: JSONObject): ExploreItem? {
        entry.optJSONObject("musicTwoRowItemRenderer")?.let { return twoRow(it) }
        entry.optJSONObject("musicResponsiveListItemRenderer")?.let { return responsive(it) }
        entry.optJSONObject("musicNavigationButtonRenderer")?.let { return button(it) }
        return null
    }

    private class Target(
        val kind: ExploreKind,
        val videoId: String? = null,
        val browseId: String? = null,
        val playlistId: String? = null,
        val params: String? = null,
    )

    private fun classify(nav: JSONObject?): Target? {
        if (nav == null) return null
        nav.optJSONObject("watchEndpoint")?.let {
            val video = it.optString("videoId")
            val list = it.optString("playlistId")
            return when {
                video.isNotEmpty() -> Target(ExploreKind.SONG, videoId = video, playlistId = list.ifEmpty { null })
                list.isNotEmpty() -> Target(ExploreKind.PLAYLIST, playlistId = list)
                else -> null
            }
        }
        nav.optJSONObject("watchPlaylistEndpoint")?.let {
            val list = it.optString("playlistId")
            if (list.isNotEmpty()) {
                return Target(ExploreKind.PLAYLIST, playlistId = list, params = it.optString("params").ifEmpty { null })
            }
        }
        nav.optJSONObject("browseEndpoint")?.let {
            val id = it.optString("browseId")
            if (id.isEmpty()) return null
            val page = findString(it, "pageType").orEmpty()
            val kind =
                when {
                    page.contains("ALBUM") -> ExploreKind.ALBUM
                    page.contains("ARTIST") || page.contains("USER_CHANNEL") -> ExploreKind.ARTIST
                    page.contains("PLAYLIST") -> ExploreKind.PLAYLIST
                    id.startsWith("MPRE") -> ExploreKind.ALBUM
                    id.startsWith("UC") -> ExploreKind.ARTIST
                    else -> ExploreKind.PLAYLIST
                }
            return Target(kind, browseId = id, params = it.optString("params").ifEmpty { null })
        }
        return null
    }

    private fun twoRow(r: JSONObject): ExploreItem? {
        val target = classify(r.optJSONObject("navigationEndpoint")) ?: return null
        val sub = runs(r.optJSONObject("subtitle"))
        return ExploreItem(
            kind = target.kind,
            title = text(r.optJSONObject("title")),
            subtitle = sub.joinToString("") { it.optString("text") },
            artist = pickArtist(sub),
            thumbnail = thumb(r.optJSONObject("thumbnailRenderer")),
            videoId = target.videoId,
            browseId = target.browseId,
            params = target.params,
            playlistId = target.playlistId,
        )
    }

    private fun responsive(r: JSONObject): ExploreItem? {
        val flex = r.optJSONArray("flexColumns")
        fun column(i: Int): JSONObject? =
            flex?.optJSONObject(i)?.optJSONObject("musicResponsiveListItemFlexColumnRenderer")
                ?.optJSONObject("text")
        val titleRuns = runs(column(0))
        val sub = runs(column(1))
        var target: Target? = null
        val video = r.optJSONObject("playlistItemData")?.optString("videoId").orEmpty()
        if (video.isNotEmpty()) target = Target(ExploreKind.SONG, videoId = video)
        if (target == null) target = classify(r.optJSONObject("navigationEndpoint"))
        if (target == null) {
            target = classify(titleRuns.firstOrNull()?.optJSONObject("navigationEndpoint"))
        }
        if (target == null) {
            findString(r.optJSONObject("overlay"), "videoId")?.let {
                target = Target(ExploreKind.SONG, videoId = it)
            }
        }
        val t = target ?: return null
        val fixed =
            r.optJSONArray("fixedColumns")?.optJSONObject(0)
                ?.optJSONObject("musicResponsiveListItemFixedColumnRenderer")
                ?.optJSONObject("text")
        return ExploreItem(
            kind = t.kind,
            title = titleRuns.joinToString("") { it.optString("text") },
            subtitle = sub.joinToString("") { it.optString("text") },
            artist = pickArtist(sub),
            thumbnail = thumb(r.optJSONObject("thumbnail")),
            videoId = t.videoId,
            browseId = t.browseId,
            params = t.params,
            playlistId = t.playlistId,
            durationSec = parseDuration(text(fixed)),
        )
    }

    private fun button(r: JSONObject): ExploreItem? {
        val title = text(r.optJSONObject("buttonText"))
        val endpoint = r.optJSONObject("clickCommand")?.optJSONObject("browseEndpoint") ?: return null
        val id = endpoint.optString("browseId")
        if (title.isEmpty() || id.isEmpty()) return null
        return ExploreItem(
            kind = ExploreKind.MOOD,
            title = title,
            subtitle = "",
            artist = "",
            thumbnail = null,
            browseId = id,
            params = endpoint.optString("params").ifEmpty { null },
            color = r.optJSONObject("solid")?.optLong("leftStripeColor", 0L)?.toInt() ?: 0,
        )
    }

    // ---- small helpers ----

    private fun runs(o: JSONObject?): List<JSONObject> {
        val a = o?.optJSONArray("runs") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    }

    private fun text(o: JSONObject?): String = runs(o).joinToString("") { it.optString("text") }

    /** The artist among the pieces of a subtitle ("Song • Artist • Album"). */
    private fun pickArtist(sub: List<JSONObject>): String {
        val real = sub.filter {
            val t = it.optString("text").trim()
            t.isNotEmpty() && t !in SEPARATORS
        }
        real.firstOrNull {
            it.optJSONObject("navigationEndpoint")?.optJSONObject("browseEndpoint")
                ?.optString("browseId").orEmpty().startsWith("UC")
        }?.let { return it.optString("text") }
        return real.map { it.optString("text").trim() }
            .firstOrNull { it.lowercase() !in TYPE_WORDS && it.none(Char::isDigit) }
            .orEmpty()
    }

    private fun parseDuration(s: String): Long {
        val parts = s.trim().split(":")
        if (parts.size !in 2..3) return -1
        var total = 0L
        for (p in parts) total = total * 60 + (p.toLongOrNull() ?: return -1)
        return total
    }

    private fun thumb(o: JSONObject?): String? {
        val arr = findArray(o, "thumbnails") ?: return null
        return (0 until arr.length())
            .mapNotNull { arr.optJSONObject(it)?.optString("url") }
            .lastOrNull { it.isNotBlank() }
    }

    private fun findArray(node: Any?, key: String): JSONArray? =
        find(node, key) as? JSONArray

    private fun findObject(node: Any?, key: String): JSONObject? =
        find(node, key) as? JSONObject

    private fun findString(node: Any?, key: String): String? =
        (find(node, key) as? String)?.takeIf { it.isNotEmpty() }

    /** First value stored under [key] anywhere inside [node] (depth first, in order). */
    private fun find(node: Any?, key: String): Any? {
        when (node) {
            is JSONObject -> {
                if (node.has(key)) return node.opt(key)
                val keys = node.keys()
                while (keys.hasNext()) find(node.opt(keys.next()), key)?.let { return it }
            }
            is JSONArray -> for (i in 0 until node.length()) find(node.opt(i), key)?.let { return it }
        }
        return null
    }
}
