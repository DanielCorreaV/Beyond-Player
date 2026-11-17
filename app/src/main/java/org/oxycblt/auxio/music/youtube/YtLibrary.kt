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
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata

import androidx.media3.datasource.cache.SimpleCache
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit


import android.util.Log
import java.io.File
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutionException

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

    @Volatile private var appContext: Context? = null

    /** The "Buscar en YouTube Music" switch of the settings (on by default). */
    fun searchEnabled(): Boolean {
        val context = appContext ?: return true
        return androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean("yt_search_enabled", true)
    }

    /** Takes a streaming song out of the YouTube list and deletes its cover. */
    fun remove(context: Context, videoId: String) {
        synchronized(lock) {
            if (entries.none { it.track.videoId == videoId }) return
            entries = entries.filterNot { it.track.videoId == videoId }
            write()
        }
        runCatching { coverFile(context, videoId).delete() }
    }

    fun install(context: Context) {
        val app = context.applicationContext
        appContext = app

        synchronized(lock) {
            file = File(app.filesDir, "yt_library.json")
            entries = read(file!!)
        }
        // Get YouTube's visitor id ready now so the first song you tap does not wait for it.
        if (entries.isNotEmpty()) {
            Thread {
                    runCatching { YtFastResolve.visitorData() }
                    runCatching { YtFastResolve.warmConnection() }
                }
                .start()
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

    /** The stored track of a streaming song, or null. */
    fun trackOf(videoId: String): YtTrack? =
        synchronized(lock) { entries.firstOrNull { it.track.videoId == videoId }?.track }

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
            val full = File(target.parentFile, track.videoId + ".hq")
            if (full.isFile) continue
            // A small cover borrowed from the search may already be there: the full-size one
            // replaces it in the same file, so nothing that shows it has to be rebuilt.
            val hadCover = target.isFile
            if (download(bigCover(url), target)) {
                full.createNewFile()
                if (!hadCover) any = true
            }

        }
        return any
    }

    /** Uses the small cover the search row already downloaded, so a new song has one at once. */
    fun adoptCover(context: Context, track: YtTrack) {
        val target = coverFile(context, track.videoId)
        if (target.isFile) return
        val url = track.thumbnailUrl ?: return
        val cached = YtCovers.cached(context, url) ?: return
        try {
            target.parentFile?.mkdirs()
            cached.copyTo(target, overwrite = true)
        } catch (e: Exception) {
            // No cover for now; the full-size download will bring one.
        }
    }

    /** The library's view of a stored song, or null when it is not stored. */
    fun remoteSong(context: Context, videoId: String): RemoteSong? {
        val entry = synchronized(lock) { entries.firstOrNull { it.track.videoId == videoId } }
        return entry?.let {
            RemoteSong(
                id = it.track.videoId,
                title = it.track.title,
                artist = it.track.artist,
                album = null,
                durationMs = if (it.track.durationSec < 0) 0 else it.track.durationSec * 1000,
                addedMs = it.addedMs,
                coverFile = coverFile(context, it.track.videoId),
            )
        }
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
    

    override fun resolveDataSpec(dataSpec: DataSpec): DataSpec {
        if (dataSpec.uri.scheme != "yt") return dataSpec
        val id = dataSpec.uri.toString().removePrefix("yt://")
        android.util.Log.d("YtStream", "YT open $id: position=${dataSpec.position} length=${dataSpec.length}")
        // The whole song is already on disk: no lookup needed (instant, and works offline).
        if (YtAudioCache.isFullyCached(id, dataSpec.position)) {
            android.util.Log.d("YtStream", "YT open $id: from the disk cache")
            return dataSpec.buildUpon().setKey("yt-$id").build()
        }


        val url =
            try {
                resolve(id)
            } catch (e: Exception) {
                throw IOException("YouTube: could not open $id (${e.message})", e)
            }

        // Look the media server up now (and log how long it takes): the player needs it right after.
        val host = Uri.parse(url).host
        if (host != null) {
            val dnsStarted = SystemClock.elapsedRealtime()
            try {
                java.net.InetAddress.getByName(host)
            } catch (e: Exception) {
                // The player will report it.
            }
            android.util.Log.d(
                "YtStream",
                "YT dns $host: ${SystemClock.elapsedRealtime() - dnsStarted} ms",
            )
        }
        return dataSpec.buildUpon().setUri(Uri.parse(url)).setKey("yt-$id").build()

    }

    companion object {
        private const val URL_TTL_MS = 20 * 60 * 1000L
        private val source = NewPipeYtSource()
        private val urls = ConcurrentHashMap<String, Pair<String, Long>>()
        private val inflight = ConcurrentHashMap<String, CompletableFuture<String>>()

        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        private fun idOf(uri: Uri) = uri.toString().removePrefix("yt://")

        private fun fresh(id: String) =
            urls[id]?.takeIf { it.second > SystemClock.elapsedRealtime() }?.first

        /** The real stream address of a song, from memory when it was resolved recently. */
        fun resolve(id: String): String {
            fresh(id)?.let {
                return it
            }
            // One lookup per song at a time: the tap, the player and the cache filler often ask
            // together, and each extra lookup only slows the others down.
            val mine = CompletableFuture<String>()
            val other = inflight.putIfAbsent(id, mine)
            if (other != null) {
                try {
                    return other.get()
                } catch (e: ExecutionException) {
                    throw (e.cause as? Exception) ?: e
                }
            }
            val started = SystemClock.elapsedRealtime()
            val url =
                try {
                    source.resolveStream(id).url.also {
                        urls[id] = it to SystemClock.elapsedRealtime() + URL_TTL_MS
                        mine.complete(it)
                    }
                } catch (e: Exception) {
                    mine.completeExceptionally(e)
                    throw e
                } finally {
                    inflight.remove(id)
                }

            android.util.Log.d("YtStream", "YT stream $id resolved in ${SystemClock.elapsedRealtime() - started} ms")
            return url
        }

        /** Resolve a yt:// song in the background so that playing it later starts at once. */
        fun prefetch(uri: Uri, head: Boolean = false) {
            if (uri.scheme != "yt") return
            val id = idOf(uri)
            if (YtAudioCache.isFullyCached(id, 0)) return
            if (fresh(id) != null && !head) return
            scope.launch {
                try {
                    val url = resolve(id)
                    // The first seconds go to the disk cache too, so starting the song later needs
                    // no network round trip.
                    if (head) YtAudioCache.fillHead(id, url)
                } catch (e: Exception) {
                    // The player will try again, and report the problem, when it opens the song.
                }
            }
        }

        /** Forget a stream address that stopped working (expired). */
        fun invalidate(uri: Uri) {
            urls.remove(idOf(uri))
        }

    }
}

/** Timing of the player's own downloads (log only): how long connecting and the first bytes take. */
@OptIn(UnstableApi::class)
object YtNetLog : TransferListener {
    @Volatile private var initAt = 0L
    @Volatile private var firstDone = true

    override fun onTransferInitializing(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        initAt = SystemClock.elapsedRealtime()
        firstDone = false
    }

    override fun onTransferStart(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {
        if (!isNetwork) return
        Log.d("YtStream", "YT net: connected in ${SystemClock.elapsedRealtime() - initAt} ms")
    }

    override fun onBytesTransferred(
        source: DataSource,
        dataSpec: DataSpec,
        isNetwork: Boolean,
        bytesTransferred: Int,
    ) {
        if (!isNetwork || firstDone) return
        firstDone = true
        Log.d("YtStream", "YT net: first bytes after ${SystemClock.elapsedRealtime() - initAt} ms")
    }

    override fun onTransferEnd(source: DataSource, dataSpec: DataSpec, isNetwork: Boolean) {}
}

/** The disk cache of streamed audio: replaying a song costs no data. */
@OptIn(UnstableApi::class)
object YtAudioCache {
    private const val MAX_BYTES = 512L * 1024 * 1024
    @Volatile private var cache: SimpleCache? = null

    private val fillScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fillSlots = Semaphore(2)
    private val filling = ConcurrentHashMap<String, Job>()
    private val writers = ConcurrentHashMap<String, CacheWriter>()

    private const val HEAD_BYTES = 256L * 1024

    /** True when the whole song is on disk from [position] on, so nothing needs the network. */
    fun isFullyCached(id: String, position: Long): Boolean {
        val c = cache ?: return false
        val key = "yt-$id"
        val length = ContentMetadata.getContentLength(c.getContentMetadata(key))
        return length > 0 && position < length && c.isCached(key, position, length - position)
    }

    /** Saves the first seconds of a song into the cache, so starting it later needs no network. */
    fun fillHead(id: String, url: String) {
        val c = cache ?: return
        val key = "yt-$id"
        if (c.isCached(key, 0, HEAD_BYTES)) return
        val started = SystemClock.elapsedRealtime()
        val upstream =
            CacheDataSource.Factory()
                .setCache(c)
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        val spec =
            DataSpec.Builder()
                .setUri(Uri.parse(url))
                .setKey(key)
                .setPosition(0)
                .setLength(HEAD_BYTES)
                .build()
        try {
            CacheWriter(upstream, spec, null, null).cache()
            Log.d("YtStream", "YT head $id saved (${SystemClock.elapsedRealtime() - started} ms)")
        } catch (e: Exception) {
            Log.d("YtStream", "YT head $id not saved: ${e.message}")
        }
    }


    /**
     * Keep exactly these songs being saved in full, in the background; any other fill stops. With
     * the whole song on disk, losing the connection in the middle no longer cuts it off.
     */
    fun keepFilled(context: Context, uris: List<Uri>) {
        val wanted = uris.filter { it.scheme == "yt" }.map { it.toString().removePrefix("yt://") }
        for ((id, job) in filling) {
            if (id !in wanted) {
                job.cancel()
                writers[id]?.cancel()
                filling.remove(id)
            }
        }
        for (id in wanted) {
            if (filling.containsKey(id)) continue
            val job =
                fillScope.launch(start = CoroutineStart.LAZY) {
                    try {
                        fillSlots.withPermit { fillOne(context.applicationContext, id) }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.d("YtStream", "YT cache $id not completed: ${e.message}")
                    } finally {
                        filling.remove(id)
                    }
                }
            filling[id] = job
            job.start()
        }
    }

    /** Downloads what is missing of one song into the cache. Blocking. */
    private fun fillOne(context: Context, id: String) {
        val started = SystemClock.elapsedRealtime()
        val url = YtStreamResolver.resolve(id)
        val upstream =
            CacheDataSource.Factory()
                .setCache(get(context))
                .setUpstreamDataSourceFactory(DefaultHttpDataSource.Factory())
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
        val spec = DataSpec.Builder().setUri(Uri.parse(url)).setKey("yt-$id").build()
        val writer = CacheWriter(upstream, spec, null, null)
        writers[id] = writer
        try {
            writer.cache()
            Log.d("YtStream", "YT cache $id complete (${SystemClock.elapsedRealtime() - started} ms)")
        } finally {
            writers.remove(id)
        }
    }


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

