/*
 * Copyright (c) 2026 Auxio Project
 * YouTubeLinkStore.kt is part of Auxio.
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

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber as L

/**
 * What we remember about a local playlist that came from YouTube Music.
 *
 * @property ytId The YouTube playlist ID.
 * @property playlistUid The string form of the local playlist's [org.oxycblt.musikr.Music.UID].
 * @property missing Tracks of the last import that were not found in the library.
 * @property syncedUids String forms of the song UIDs the last sync put in the playlist. Songs of the
 *   playlist that are not in here were added by the user and survive the next sync. Null for links
 *   saved before this was tracked.
 */
data class YouTubeLink(
    val ytId: String,
    val playlistUid: String,
    val title: String,
    val missing: List<RemoteTrack>,
    val syncedUids: List<String>? = null,
)

/**
 * Persists the YouTube playlist ID <-> local playlist link as a small JSON file, kept apart from
 * the playlist database so a mistake here can never touch the user's existing playlists.
 */
class YouTubeLinkStore(context: Context) {
    private val file = File(context.applicationContext.filesDir, "youtube_links.json")
    private var cache: Map<String, YouTubeLink>? = null
    private val _version = MutableStateFlow(0)

    /** Increases every time the stored links change, so screens can refresh themselves. */
    val version: StateFlow<Int> = _version

    @Synchronized fun get(ytId: String): YouTubeLink? = readAll()[ytId]

    @Synchronized fun getByPlaylist(playlistUid: String): YouTubeLink? =
        readAll().values.firstOrNull { it.playlistUid == playlistUid }

    @Synchronized fun all(): List<YouTubeLink> = readAll().values.toList()

    @Synchronized
    fun put(link: YouTubeLink) {
        val all = readAll().toMutableMap()
        all[link.ytId] = link
        writeAll(all)
    }

    private fun readAll(): Map<String, YouTubeLink> {
        cache?.let {
            return it
        }
        return readFile().also { cache = it }
    }

    private fun readFile(): Map<String, YouTubeLink> {
        if (!file.exists()) return emptyMap()
        return try {
            val arr = JSONArray(file.readText())
            buildMap {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    val missing = o.optJSONArray("missing") ?: JSONArray()
                    val link =
                        YouTubeLink(
                            ytId = o.getString("ytId"),
                            playlistUid = o.getString("playlistUid"),
                            title = o.optString("title"),
                            syncedUids =
                                o.optJSONArray("synced")?.let { arr ->
                                    (0 until arr.length()).map { arr.getString(it) }
                                },
                            missing =
                                (0 until missing.length()).map {
                                    val m = missing.getJSONObject(it)
                                    RemoteTrack(
                                        m.optString("videoId").ifEmpty { null },
                                        m.optString("title"),
                                        m.optString("artist"),
                                        m.optLong("durationSec", -1),
                                    )
                                },
                        )
                    put(link.ytId, link)
                }
            }
        } catch (e: Exception) {
            L.e(e, "Could not read YouTube link store")
            emptyMap()
        }
    }

    private fun writeAll(all: Map<String, YouTubeLink>) {
        cache = all
        _version.value += 1
        val arr = JSONArray()
        for (l in all.values) {
            arr.put(
                JSONObject()
                    .put("ytId", l.ytId)
                    .put("playlistUid", l.playlistUid)
                    .put("title", l.title)
                    .apply { l.syncedUids?.let { put("synced", JSONArray(it)) } }
                    .put(
                        "missing",
                        JSONArray(
                            l.missing.map {
                                JSONObject()
                                    .put("videoId", it.videoId ?: "")
                                    .put("title", it.title)
                                    .put("artist", it.artist)
                                    .put("durationSec", it.durationSec)
                            }
                        ),
                    )
            )
        }
        val tmp = File(file.parentFile, "youtube_links.json.tmp")
        tmp.writeText(arr.toString())
        if (!tmp.renameTo(file)) {
            file.writeText(arr.toString())
            tmp.delete()
        }
    }

    companion object {
        @Volatile private var instance: YouTubeLinkStore? = null

        /** The one shared store, so every screen sees the same data. */
        fun from(context: Context): YouTubeLinkStore =
            instance
                ?: synchronized(this) {
                    instance ?: YouTubeLinkStore(context.applicationContext).also { instance = it }
                }
    }
}
