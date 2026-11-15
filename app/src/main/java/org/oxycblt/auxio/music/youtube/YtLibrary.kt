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
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

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
                        coverFile = coverFile(app, it.track.videoId),
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

    fun coverFile(context: Context, videoId: String) =
        File(File(context.applicationContext.filesDir, "yt_covers"), "$videoId.jpg")

    /** Downloads the cover of every stored song that has none yet. Blocking. True if any arrived. */
    fun ensureCovers(context: Context): Boolean {
        var any = false
        val tracks = synchronized(lock) { entries.map { it.track } }
        for (track in tracks) {
            val url = track.thumbnailUrl ?: continue
            val target = coverFile(context, track.videoId)
            if (target.isFile) continue
            if (download(bigCover(url), target)) any = true
        }
        return any
    }

    private val SIZE_SUFFIX = Regex("""=w\d+-h\d+[^/?]*$""")

    /** YouTube Music covers take a size in the address; ask for one big enough for the player. */
    private fun bigCover(url: String) = SIZE_SUFFIX.replace(url, "=w544-h544-l90-rj")

    private fun download(url: String, target: File): Boolean {
        val tmp = File(target.parentFile, target.name + ".tmp")
        return try {
            target.parentFile?.mkdirs()
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            try {
                if (conn.responseCode != 200) return false
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            } finally {
                conn.disconnect()
            }
            tmp.length() > 0 && tmp.renameTo(target)
        } catch (e: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

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
        return dataSpec.buildUpon().setUri(Uri.parse(url)).setKey("yt-$id").build()
    }

    private companion object {
        const val URL_TTL_MS = 20 * 60 * 1000L
    }
}

/** The disk cache of streamed audio: replaying a song costs no data. */
@OptIn(UnstableApi::class)
object YtAudioCache {
    private const val MAX_BYTES = 512L * 1024 * 1024
    @Volatile private var cache: SimpleCache? = null

    fun get(context: Context): SimpleCache =
        cache
            ?: synchronized(this) {
                cache
                    ?: SimpleCache(
                            File(context.applicationContext.cacheDir, "yt_audio"),
                            LeastRecentlyUsedCacheEvictor(MAX_BYTES),
                            StandaloneDatabaseProvider(context.applicationContext),
                        )
                        .also { cache = it }
            }
}

