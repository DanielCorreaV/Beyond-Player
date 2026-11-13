/*
 * Copyright (c) 2026 Beyond Player
 * YtDownloadService.kt is part of Beyond Player, a fork of Auxio.
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

import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.util.newMainPendingIntent
import timber.log.Timber as L

/** A song that is downloading right now, with its progress from 0 to 1. */
data class YtActive(val title: String, val fraction: Float)

/** What the download queue is doing. Counts reset when a new batch starts on an idle queue. */
data class YtQueueState(
    val total: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    /** Songs that were already on the phone (downloaded earlier), so nothing was fetched. */
    val skipped: Int = 0,
    val active: Map<String, YtActive> = emptyMap(),
    val lastError: String? = null,
) {
    val finished: Int
        get() = done + failed + skipped

    val idle: Boolean
        get() = finished >= total
}

/** Lets this plain service reach the app-wide [MusicRepository] without being Hilt-injected. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface YtMusicEntryPoint {
    fun musicRepository(): MusicRepository
}

/**
 * Downloads YouTube songs in the background, up to [MAX_PARALLEL] at a time, with one progress
 * notification for the whole queue. Survives leaving the app; the library is refreshed while the
 * queue runs and once more when it empties.
 */
class YtDownloadService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val gate = Semaphore(MAX_PARALLEL)
    private val jobs = ConcurrentHashMap<String, Job>()
    private val lock = Any()
    private val source: YtSource = NewPipeYtSource()
    private lateinit var notifications: NotificationManagerCompat
    private var lastNotifyAt = 0L

    /** Videos fetched (or found already on the phone) in the current batch, to sync playlists. */
    private val batchIds = ConcurrentHashMap.newKeySet<String>()

    /** Text shown instead of the counters while the library and playlists are being updated. */
    @Volatile private var phase: String? = null
    @Volatile private var syncedLists = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        notifications = NotificationManagerCompat.from(this)
        notifications.createNotificationChannel(
            NotificationChannelCompat.Builder(CHANNEL_ID, NotificationManagerCompat.IMPORTANCE_LOW)
                .setName("Descargas de música")
                .setLightsEnabled(false)
                .setVibrationEnabled(false)
                .setShowBadge(false)
                .build()
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_ENQUEUE -> {
                // Must go foreground right away: the app promised the system it would.
                goForeground()
                val track = intent.toTrack()
                if (track == null) {
                    if (jobs.isEmpty()) finishQueue()
                } else enqueue(track)
            }
            ACTION_CANCEL -> cancelAll()
            else -> stopSelf()
        }
        return START_NOT_STICKY
    }

    /** Android 15 stops data-sync services after a while: give up cleanly instead of crashing. */
    override fun onTimeout(startId: Int, fgsType: Int) {
        L.w("YT download service timed out")
        cancelAll()
    }

    override fun onDestroy() {
        scope.coroutineContext.cancelChildren()
        super.onDestroy()
    }

    private fun enqueue(track: YtTrack) {
        synchronized(lock) {
            if (jobs.containsKey(track.videoId)) return
            if (jobs.isEmpty()) {
                _state.value = YtQueueState()
                syncedLists = 0
            }
            _state.update { it.copy(total = it.total + 1) }
            val job = scope.launch(start = CoroutineStart.LAZY) { runOne(track) }
            jobs[track.videoId] = job
            job.start()
        }
        notifyProgress(force = true)
    }

    private suspend fun runOne(track: YtTrack) {
        val job = currentJob()
        var error: String? = null
        var skipped = false
        var cancelled = false
        try {
            gate.withPermit {
                _state.update { it.copy(active = it.active + (track.videoId to YtActive(track.title, 0f))) }
                notifyProgress(force = true)
                val downloader = YtDownloader(applicationContext)
                // Already downloaded before (playlists overlap): no network, no duplicate.
                if (downloader.existing(track) != null) {
                    skipped = true
                    return@withPermit
                }
                val audio = source.resolveAudio(track.videoId)
                downloader.download(track, audio) { done, total ->
                    // The socket read is blocking: this is the only place to notice a cancel.
                    if (!job.isActive) throw CancellationException("cancelled")
                    if (total > 0) setFraction(track, done.toFloat() / total)
                }
                Unit
            }
        } catch (e: CancellationException) {
            cancelled = true
            throw e
        } catch (e: Exception) {
            L.e(e, "YT download failed: ${track.videoId}")
            error = e.message ?: e.javaClass.simpleName
        } finally {
            if (!cancelled) completeOne(track, error, skipped) else dropActive(track)
        }
    }

    private suspend fun currentJob(): Job = kotlin.coroutines.coroutineContext.job

    private fun setFraction(track: YtTrack, fraction: Float) {
        _state.update { s ->
            if (!s.active.containsKey(track.videoId)) s
            else s.copy(active = s.active + (track.videoId to YtActive(track.title, fraction)))
        }
        notifyProgress(force = false)
    }

    private fun dropActive(track: YtTrack) {
        _state.update { it.copy(active = it.active - track.videoId) }
        jobs.remove(track.videoId)
    }

    private fun completeOne(track: YtTrack, error: String?, skipped: Boolean) {
        _state.update {
            it.copy(
                active = it.active - track.videoId,
                done = it.done + if (error == null && !skipped) 1 else 0,
                skipped = it.skipped + if (error == null && skipped) 1 else 0,
                failed = it.failed + if (error == null) 0 else 1,
                lastError = error ?: it.lastError,
            )
        }
        if (error == null) batchIds.add(track.videoId)
        val empty =
            synchronized(lock) {
                jobs.remove(track.videoId)
                jobs.isEmpty()
            }
        if (empty) {
            finishQueue()
        } else {
            // Long lists: let songs appear in the library as they arrive, not only at the end.
            if (error == null && !skipped && _state.value.done % REFRESH_EVERY == 0) refreshLibrary()
            notifyProgress(force = true)
        }
    }

    private fun cancelAll() {
        scope.coroutineContext.cancelChildren()
        synchronized(lock) { jobs.clear() }
        _state.update { it.copy(active = emptyMap(), total = it.finished) }
        batchIds.clear()
        if (_state.value.done > 0) refreshLibrary()
        wrapUp()
    }

    /** The queue emptied: put the new songs in the library, then in the playlists they belong to. */
    private fun finishQueue() {
        val ids = batchIds.toSet()
        batchIds.clear()
        if (ids.isEmpty()) {
            wrapUp()
            return
        }
        scope.launch {
            try {
                updateLibraryAndLists(ids)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                L.e(e, "YT could not update library/playlists")
            } finally {
                phase = null
                wrapUp()
            }
        }
    }

    private fun wrapUp() {
        // A new batch may have been queued while we were updating lists: keep going for it.
        if (synchronized(lock) { jobs.isNotEmpty() }) return
        val s = _state.value
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        if (s.total > 0) postSummary(s)
        stopSelf()
    }

    private suspend fun updateLibraryAndLists(ids: Set<String>) {
        val repo =
            EntryPointAccessors.fromApplication(applicationContext, YtMusicEntryPoint::class.java)
                .musicRepository()
        phase = "Actualizando la biblioteca…"
        notifyProgress(force = true)
        awaitLibraryUpdate(repo) { repo.requestIndex(true) }

        // Playlists whose "missing" list contained any of these songs are the ones to refresh.
        val links = YouTubeLinkStore.from(applicationContext).all()
        val affected = links.filter { link -> link.missing.any { it.videoId in ids } }
        if (affected.isEmpty()) return
        phase = "Actualizando tus listas…"
        notifyProgress(force = true)
        val syncer =
            YouTubePlaylistSyncer(
                repo,
                YouTubeLinkStore.from(applicationContext),
                NewPipePlaylistSource(),
            )
        for (link in affected) {
            val result =
                runCatching {
                    syncer.syncOne("https://music.youtube.com/playlist?list=${link.ytId}")
                }
            val item = result.getOrNull()
            if (item is YouTubeImportItem.Done) syncedLists++
            else L.e(result.exceptionOrNull(), "YT could not sync playlist ${link.ytId}: $item")
        }
    }

    /**
     * Run [trigger] (a re-index) and wait until the repository publishes a complete library. Adding
     * a listener fires it once immediately with the old library, so it is ignored until armed.
     * Everything stays on the main thread, where the repository dispatches its updates.
     */
    private suspend fun awaitLibraryUpdate(repo: MusicRepository, trigger: () -> Unit) {
        withContext(Dispatchers.Main) {
            withTimeoutOrNull(LIBRARY_WAIT_MS) {
                val armed = AtomicBoolean(false)
                val updated = CompletableDeferred<Unit>()
                val listener =
                    object : MusicRepository.UpdateListener {
                        override fun onMusicChanges(changes: MusicRepository.Changes) {
                            if (armed.get() && changes.deviceLibrary && !changes.partial) {
                                updated.complete(Unit)
                            }
                        }
                    }
                repo.addUpdateListener(listener)
                try {
                    armed.set(true)
                    trigger()
                    updated.await()
                } finally {
                    repo.removeUpdateListener(listener)
                }
            }
        }
    }

    private fun refreshLibrary() {
        runCatching {
                EntryPointAccessors.fromApplication(applicationContext, YtMusicEntryPoint::class.java)
                    .musicRepository()
                    .requestIndex(true)
            }
            .onFailure { L.e(it, "YT could not refresh the library") }
    }

    // --- notifications ---

    private fun goForeground() {
        val notification = buildProgress(_state.value)
        val type = if (Build.VERSION.SDK_INT >= 29) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }

    @SuppressLint("MissingPermission")
    private fun notifyProgress(force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastNotifyAt < 700) return
        lastNotifyAt = now
        runCatching { notifications.notify(NOTIFICATION_ID, buildProgress(_state.value)) }
    }

    private fun buildProgress(s: YtQueueState): Notification {
        val total = s.total.coerceAtLeast(1)
        val partial = s.active.values.sumOf { it.fraction.toDouble() }
        val overall = ((s.finished + partial) / total).coerceIn(0.0, 1.0)
        val now = s.active.values.firstOrNull()?.title
        val text =
            buildString {
                append("${(s.finished + 1).coerceAtMost(total)} de ${s.total}")
                if (now != null) append(" · $now")
                if (s.failed > 0) append(" · ${s.failed} con error")
            }
        val cancel =
            PendingIntent.getService(
                this,
                1,
                Intent(this, YtDownloadService::class.java).setAction(ACTION_CANCEL),
                PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_download_24)
            .setContentTitle("Descargando música")
            .setContentText(phase ?: text)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setOngoing(true)
            .setShowWhen(false)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(newMainPendingIntent())
            .setProgress(1000, (overall * 1000).toInt(), s.active.isEmpty() || phase != null)
            .addAction(0, "Cancelar", cancel)
            .build()
    }

    @SuppressLint("MissingPermission")
    private fun postSummary(s: YtQueueState) {
        val parts = mutableListOf<String>()
        if (s.done > 0) parts.add(if (s.done == 1) "1 descargada" else "${s.done} descargadas")
        if (s.skipped > 0) parts.add(if (s.skipped == 1) "1 ya estaba" else "${s.skipped} ya estaban")
        if (syncedLists > 0) parts.add(if (syncedLists == 1) "1 lista actualizada" else "$syncedLists listas actualizadas")
        if (s.failed > 0) parts.add("${s.failed} con error")
        val text = parts.joinToString(" · ")
        val title =
            when {
                s.done > 0 -> "Descarga terminada"
                s.skipped > 0 && s.failed == 0 -> "Ya estaba todo descargado"
                else -> "No se pudo descargar"
            }
        val notification =
            NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_download_24)
                .setContentTitle(title)
                .setContentText(if (s.done == 0 && s.skipped == 0) (s.lastError ?: text) else text)
                .setCategory(NotificationCompat.CATEGORY_STATUS)
                .setSilent(true)
                .setAutoCancel(true)
                .setContentIntent(newMainPendingIntent())
                .build()
        runCatching { notifications.notify(SUMMARY_ID, notification) }
    }

    private fun Intent.toTrack(): YtTrack? {
        val id = getStringExtra(EXTRA_ID) ?: return null
        return YtTrack(
            videoId = id,
            title = getStringExtra(EXTRA_TITLE) ?: id,
            artist = getStringExtra(EXTRA_ARTIST) ?: "",
            durationSec = getLongExtra(EXTRA_DURATION, -1),
            thumbnailUrl = getStringExtra(EXTRA_THUMB),
        )
    }

    companion object {
        /** Songs downloading at the same time. */
        const val MAX_PARALLEL = 3
        private const val REFRESH_EVERY = 5
        private const val LIBRARY_WAIT_MS = 90_000L
        private const val CHANNEL_ID = "yt_downloads"
        private const val NOTIFICATION_ID = 0xA0A2
        private const val SUMMARY_ID = 0xA0A3
        private const val ACTION_ENQUEUE = "org.oxycblt.auxio.YT_ENQUEUE"
        private const val ACTION_CANCEL = "org.oxycblt.auxio.YT_CANCEL"
        private const val EXTRA_ID = "id"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ARTIST = "artist"
        private const val EXTRA_DURATION = "duration"
        private const val EXTRA_THUMB = "thumb"

        private val _state = MutableStateFlow(YtQueueState())

        /** Live state of the queue, for any screen that wants to show it. */
        val queueState: StateFlow<YtQueueState> = _state.asStateFlow()

        /** Add [track] to the queue. Songs already queued or downloading are ignored. */
        fun enqueue(context: Context, track: YtTrack) {
            val intent =
                Intent(context, YtDownloadService::class.java)
                    .setAction(ACTION_ENQUEUE)
                    .putExtra(EXTRA_ID, track.videoId)
                    .putExtra(EXTRA_TITLE, track.title)
                    .putExtra(EXTRA_ARTIST, track.artist)
                    .putExtra(EXTRA_DURATION, track.durationSec)
                    .putExtra(EXTRA_THUMB, track.thumbnailUrl)
            ContextCompat.startForegroundService(context.applicationContext, intent)
        }

        fun enqueueAll(context: Context, tracks: List<YtTrack>) =
            tracks.forEach { enqueue(context, it) }

        /** Stop everything that is queued or running. */
        fun cancelAll(context: Context) {
            context.applicationContext.startService(
                Intent(context, YtDownloadService::class.java).setAction(ACTION_CANCEL)
            )
        }
    }
}
