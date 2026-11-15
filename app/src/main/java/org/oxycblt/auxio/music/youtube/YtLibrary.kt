/*
 * Copyright (c) 2026 Auxio Project
 * YtLibrary.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.ResolvingDataSource
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONArray
import org.json.JSONObject
import org.oxycblt.musikr.RemoteSong
import org.oxycblt.musikr.RemoteSongs

/**
 * The YouTube songs you added to your library. Only metadata is kept (a small JSON file); the audio
 * is streamed when played. [install] connects it to the library builder.
 */
object YtLibrary {
    private val lock = Any()
    private var file: File? = null
    private var entries: List<Entry> = emptyList()

    private data class Entry(val track: YtTrack, val addedMs: Long)

    fun install(context: Context) {
        val app = context.applicationContext
        synchronized(lock) {
            file = File(app.filesDir, "yt_library.json")
            entries = read(file!!)
        }
        RemoteSongs.provider = {
            val snapshot = synchronized(lock) { entries }
            snapshot
                // A song you downloaded is a real file in the library: don't list it twice.
                .filter { YtDownloadIndex.get(app, it.track.videoId) == null }
                .map {
                    RemoteSong(
                        id = it.track.videoId,
                        title = it.track.title,
                        artist = it.track.artist,
                        album = null,
                        durationMs = if (it.track.durationSec < 0) 0 else it.track.durationSec * 1000,
                        addedMs = it.addedMs,
                    )
                }
        }
    }

    /** Adds the song if it was not there. Returns true when it was new. */
    fun add(track: YtTrack): Boolean =
        synchronized(lock) {
            if (entries.any { it.track.videoId == track.videoId }) return false
            entries = entries + Entry(track, System.currentTimeMillis())
            write()
            true
        }

    fun contains(videoId: String): Boolean =
        synchronized(lock) { entries.any { it.track.videoId == videoId } }

    private fun write() {
        val f = file ?: return
        val array = JSONArray()
        entries.forEach {
            array.put(
                JSONObject()
                    .put("id", it.track.videoId)
                    .put("title", it.track.title)
                    .put("artist", it.track.artist)
                    .put("sec", it.track.durationSec)
                    .put("thumb", it.track.thumbnailUrl ?: JSONObject.NULL)
                    .put("added", it.addedMs)
            )
        }
        // Write to a temp file and rename, so a crash never leaves a half-written library.
        val tmp = File(f.parentFile, f.name + ".tmp")
        tmp.writeText(array.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(array.toString())
            tmp.delete()
        }
    }

    private fun read(f: File): List<Entry> {
        if (!f.exists()) return emptyList()
        return try {
            val array = JSONArray(f.readText())
            (0 until array.length()).map {
                val o = array.getJSONObject(it)
                Entry(
                    YtTrack(
                        videoId = o.getString("id"),
                        title = o.getString("title"),
                        artist = o.optString("artist", ""),
                        durationSec = o.optLong("sec", -1),
                        thumbnailUrl = if (o.isNull("thumb")) null else o.getString("thumb"),
                    ),
                    o.optLong("added", 0),
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }
}

/**
 * Turns a yt://videoId address into the real audio stream address, right when the player opens it
 * (stream addresses expire, so they cannot be stored). Any other address passes through untouched.
 */
@OptIn(UnstableApi::class)
class YtStreamResolver : ResolvingDataSource.Resolver {
    private val source = NewPipeYtSource()
    private val urls = ConcurrentHashMap<String, Pair<String, Long>>()

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != "yt") return dataSpec
        val id = dataSpec.uri.toString().removePrefix("yt://")
        val now = SystemClock.elapsedRealtime()
        val cached = urls[id]?.takeIf { it.second > now }?.first
        val url =
            cached
                ?: try {
                    source.resolveAudio(id).url.also { urls[id] = it to now + URL_TTL_MS }
                } catch (e: Exception) {
                    throw IOException("YouTube: could not open $id (${e.message})", e)
                }
        return dataSpec.withUri(Uri.parse(url))
    }

    private companion object {
        const val URL_TTL_MS = 20 * 60 * 1000L
    }
}
