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
    private const val DETAIL_ITEMS = 300
    private val SHELVES =
        setOf(
            "musicCarouselShelfRenderer",
            "musicShelfRenderer",
            "musicPlaylistShelfRenderer",
            "gridRenderer",
        )
    private val SEPARATORS = setOf("•", "·", "&", ",", "-", "–")
    private val TYPE_WORDS =
        setOf(
            "song", "video", "single", "ep", "album", "playlist", "artist", "mix", "radio",
            "canción", "sencillo", "álbum", "lista", "artista",
        )

    private val cache = ConcurrentHashMap<String, Pair<Long, List<ExploreSection>>>()

    /** Forget what was loaded (when the account changes, the pages are not the same). */
    fun clearCache() {
        cache.clear()
        detailCache.clear()
    }

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

    /** One account page (home, library, history) as shelves. Empty list when it has none. */
    fun page(browseId: String, dumpDir: File?, maxItems: Int): List<ExploreSection> {
        val text = post(browseId)
        if (dumpDir != null) {
            runCatching {
                dumpDir.mkdirs()
                File(dumpDir, "page_" + browseId.lowercase() + ".json").writeText(text)
            }
        }
        return parse(JSONObject(text), maxItems)
    }

    /** The account menu of YouTube Music: name, email and photo of the active account. */
    fun accountMenu(): String = postJson("account/account_menu", JSONObject())

    // ---- request ----

    private fun post(browseId: String, params: String? = null): String {
        val body = JSONObject().put("browseId", browseId)
        if (params != null) body.put("params", params)
        return postJson("browse", body)
    }

    private fun postJson(endpoint: String, fields: JSONObject): String {
        val locale = Locale.getDefault()
        val version = "1." + SimpleDateFormat("yyyyMMdd", Locale.US).format(Date()) + ".01.00"
        val client =
            JSONObject()
                .put("clientName", "WEB_REMIX")
                .put("clientVersion", version)
                .put("hl", locale.language.ifEmpty { "en" })
                .put("gl", locale.country.ifEmpty { "US" })
        YtSession.visitorData()?.let { client.put("visitorData", it) }
        val body = fields.put("context", JSONObject().put("client", client).put("user", JSONObject()))
        val conn =
            URL("https://music.youtube.com/youtubei/v1/$endpoint?prettyPrint=false").openConnection()
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
            // Signed in: the cookies and the signature of the account replace the plain cookie.
            YtSession.authHeaders()?.forEach { (name, value) -> conn.setRequestProperty(name, value) }
            conn.setRequestProperty(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0",
            )
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code == 401) YtSession.onAuthRejected()
            if (code != 200) throw IOException("HTTP $code for $endpoint")
            return conn.inputStream.bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            conn.disconnect()
            throw e
        }
    }

    // ---- reading the answer ----

    /** All shelves of an answer, in page order. */
    fun parse(text: String): List<ExploreSection> = parse(JSONObject(text), MAX_ITEMS)

    private fun parse(root: JSONObject, maxItems: Int): List<ExploreSection> {
        val out = mutableListOf<ExploreSection>()
        collect(root, out, maxItems)
        return out
    }

    private fun collect(node: Any?, out: MutableList<ExploreSection>, maxItems: Int) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val value = node.opt(key)
                    if (key in SHELVES && value is JSONObject) {
                        shelf(value, maxItems)?.let { out.add(it) }
                    } else {
                        collect(value, out, maxItems)
                    }
                }
            }
            is JSONArray -> for (i in 0 until node.length()) collect(node.opt(i), out, maxItems)
        }
    }

    private fun shelf(obj: JSONObject, maxItems: Int): ExploreSection? {
        val title = text(findObject(obj.optJSONObject("header"), "title"))
        val contents = obj.optJSONArray("contents") ?: obj.optJSONArray("items") ?: return null
        val items = mutableListOf<ExploreItem>()
        for (i in 0 until contents.length()) {
            val entry = contents.optJSONObject(i) ?: continue
            item(entry)?.let { items.add(it) }
        }
        if (items.isEmpty()) return null
        val chips = items.all { it.kind == ExploreKind.MOOD }
        return ExploreSection(title, items.take(if (chips) MAX_CHIPS else maxItems), chips)
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


    // ---- detail pages: albums, playlists, artists, mixes, moods ----

    /** What opens when a card that is not a song is tapped. */
    data class ExploreDetail(
        val title: String,
        val artist: String,
        val thumbnail: String?,
        /** The songs to play (empty for a mood page). */
        val tracks: List<YtTrack>,
        /** More shelves to show (artist and mood pages). */
        val sections: List<ExploreSection>,
    )

    private val detailCache = ConcurrentHashMap<String, Pair<Long, ExploreDetail>>()

    /** Blocking. Throws when the request fails or nothing readable came back. */
    fun detail(item: ExploreItem, dumpDir: File?): ExploreDetail {
        val key = listOf(item.kind, item.browseId, item.params, item.playlistId).joinToString("|")
        val now = System.currentTimeMillis()
        detailCache[key]?.takeIf { now - it.first < TTL_MS }?.let { return it.second }
        val result =
            when {
                item.browseId != null -> fromBrowse(item, dumpDir)
                item.playlistId != null ->
                    fromRadio(item, dumpDir).takeIf { it.tracks.isNotEmpty() }
                        ?: fromBrowse(item.copy(browseId = "VL" + item.playlistId), dumpDir)
                else -> throw IOException("Nothing to open")
            }
        if (result.tracks.isEmpty() && result.sections.isEmpty()) {
            throw IOException("Nothing readable in ${item.title}")
        }
        detailCache[key] = now to result
        return result
    }

    private fun dump(dir: File?, name: String, text: String) {
        if (dir == null) return
        runCatching {
            dir.mkdirs()
            File(dir, name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".json").writeText(text)
        }
    }

    private fun fromBrowse(item: ExploreItem, dumpDir: File?): ExploreDetail {
        var id = item.browseId ?: throw IOException("No page")
        if (item.kind == ExploreKind.PLAYLIST && !id.startsWith("VL") && !id.startsWith("FE")) {
            id = "VL$id"
        }
        val text = post(id, item.params)
        dump(dumpDir, "detail_$id", text)
        return browseDetail(item, text)
    }

    internal fun browseDetail(item: ExploreItem, text: String): ExploreDetail {
        val root = JSONObject(text)
        val sections = parse(root, DETAIL_ITEMS)
        val header = root.optJSONObject("header")
        val artist =
            pickArtist(runs(findObject(header, "straplineTextOne")))
                .ifEmpty { pickArtist(runs(findObject(header, "subtitle"))) }
                .ifEmpty { if (item.kind == ExploreKind.ARTIST) item.title else item.artist }
        val cover = thumb(header) ?: item.thumbnail
        val songs = sections.firstOrNull { s -> s.items.any { it.kind == ExploreKind.SONG } }
        val tracks =
            songs?.items.orEmpty().filter { it.kind == ExploreKind.SONG }.mapNotNull {
                it.copy(artist = it.artist.ifBlank { artist }, thumbnail = it.thumbnail ?: cover)
                    .toTrack()
            }
        val shown =
            if (item.kind == ExploreKind.ARTIST || item.kind == ExploreKind.MOOD) sections
            else emptyList()
        return ExploreDetail(
            title = text(findObject(header, "title")).ifEmpty { item.title },
            artist = artist,
            thumbnail = cover,
            tracks = tracks,
            sections = shown,
        )
    }

    /** A mix or radio: the songs YouTube lines up for it, from the "next" service. */
    private fun fromRadio(item: ExploreItem, dumpDir: File?): ExploreDetail {
        val id = item.playlistId ?: throw IOException("No mix")
        val body = JSONObject().put("playlistId", id).put("isAudioOnly", true)
        if (item.params != null) body.put("params", item.params)
        val text = postJson("next", body)
        dump(dumpDir, "detail_next_$id", text)
        return radioDetail(item, text)
    }

    internal fun radioDetail(item: ExploreItem, text: String): ExploreDetail {
        val found = mutableListOf<JSONObject>()
        gather(JSONObject(text), "playlistPanelVideoRenderer", found)
        val tracks =
            found.mapNotNull { r ->
                val video = r.optString("videoId")
                if (video.isEmpty()) return@mapNotNull null
                YtTrack(
                    video,
                    text(r.optJSONObject("title")),
                    pickArtist(runs(r.optJSONObject("longBylineText"))),
                    parseDuration(text(r.optJSONObject("lengthText"))),
                    thumb(r.optJSONObject("thumbnail")) ?: item.thumbnail,
                )
            }.distinctBy { it.videoId }
        return ExploreDetail(item.title, item.artist, item.thumbnail, tracks, emptyList())
    }

    private fun gather(node: Any?, key: String, out: MutableList<JSONObject>) {
        when (node) {
            is JSONObject -> {
                val keys = node.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = node.opt(k)
                    if (k == key && v is JSONObject) out.add(v) else gather(v, key, out)
                }
            }
            is JSONArray -> for (i in 0 until node.length()) gather(node.opt(i), key, out)
        }
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
