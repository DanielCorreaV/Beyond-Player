/*
 * Copyright (c) 2026 Auxio Project
 * YtSuggest.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import org.json.JSONArray

/** Related searches for the text typed so far (the service behind YouTube's own search box). */
object YtSuggest {
    private val cache =
        object : LinkedHashMap<String, List<String>>(32, 0.75f, true) {
            override fun removeEldestEntry(
                eldest: MutableMap.MutableEntry<String, List<String>>
            ) = size > 64
        }

    /** Blocking. An empty list when there is nothing or the request failed. */
    fun suggest(query: String): List<String> {
        val key = query.trim().lowercase()
        if (key.length < 2) return emptyList()
        synchronized(cache) { cache[key]?.let { return it } }
        val found =
            try {
                fetch(key)
            } catch (e: Exception) {
                return emptyList()
            }
        synchronized(cache) { cache[key] = found }
        return found
    }

    private fun fetch(query: String): List<String> {
        val lang = Locale.getDefault().language
        val url =
            URL(
                "https://suggestqueries-clients6.youtube.com/complete/search" +
                    "?client=youtube&ds=yt&xhr=t&hl=$lang&q=" +
                    URLEncoder.encode(query, "UTF-8")
            )
        val conn = url.openConnection() as HttpURLConnection
        conn.connectTimeout = 4_000
        conn.readTimeout = 4_000
        conn.setRequestProperty("Origin", "https://www.youtube.com")
        conn.setRequestProperty("Referer", "https://www.youtube.com")
        conn.setRequestProperty("Accept-Language", lang)
        if (conn.responseCode != 200) return emptyList()
        val body = conn.inputStream.bufferedReader().use { it.readText() }
        // [query, [[suggestion, 0, [...]], ...], {...}]
        val list = JSONArray(body).getJSONArray(1)
        return (0 until list.length())
            .mapNotNull { runCatching { list.getJSONArray(it).getString(0) }.getOrNull() }
            .filter { it.isNotBlank() }
    }
}
