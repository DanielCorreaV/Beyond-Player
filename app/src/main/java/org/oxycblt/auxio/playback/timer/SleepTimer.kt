/*
 * Copyright (c) 2026 Auxio Project
 * SleepTimer.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.timer

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** What the timer does when its time is up. Only used by the time-based timer. */
enum class TimerEnd {
    /** Fade out and pause right away. */
    CUT,
    /** Let the song that is playing finish, then pause. */
    FINISH_SONG,
}

/** How the timer counts. */
enum class TimerMode {
    TIME,
    SONGS,
}

/**
 * A running timer.
 *
 * @param mode How the timer counts.
 * @param end What happens when it ends (always [TimerEnd.FINISH_SONG] for [TimerMode.SONGS]).
 * @param endsAtMs For [TimerMode.TIME], the [SystemClock.elapsedRealtime] when time is up.
 * @param songsLeft For [TimerMode.SONGS], how many songs are left, counting the current one.
 * @param finishingSong Whether the timer already ran out and is waiting for the current song to
 *   end.
 */
data class TimerState(
    val mode: TimerMode,
    val end: TimerEnd,
    val endsAtMs: Long,
    val songsLeft: Int,
    val finishingSong: Boolean,
    /** Length of the whole time-based timer, for the progress ring. */
    val totalMs: Long = 0L,
    /** Songs the timer started with, for the progress ring. */
    val initialSongs: Int = 0,
)

/**
 * A sleep timer that pauses playback after some time or after some songs. It only keeps the timer
 * state, the player itself is driven through the attached [Host]. Everything must be called from
 * the main thread.
 */
@Singleton
class SleepTimer @Inject constructor() {
    /** The part of the player the timer controls. */
    interface Host {
        val isPlayingNow: Boolean

        /** Fade the volume out, pause, and restore the volume. */
        fun fadeOutAndPause()

        /** Abort a fade started by [fadeOutAndPause] and restore the volume. */
        fun cancelFade()

        /** Re-apply [pauseAtEndRequested] to the player. */
        fun refreshPauseAtEnd()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val _state = MutableStateFlow<TimerState?>(null)
    private var host: Host? = null

    /** The running timer, or null if there is none. */
    val state: StateFlow<TimerState?> = _state

    /** Whether the player should pause when the current song ends because of the timer. */
    @Volatile
    var pauseAtEndRequested = false
        private set

    private val timeUp = Runnable {
        val current = _state.value ?: return@Runnable
        if (host?.isPlayingNow != true) {
            // Nothing is playing, there is nothing to stop.
            clear()
            return@Runnable
        }
        when (current.end) {
            TimerEnd.CUT -> {
                clear()
                host?.fadeOutAndPause()
            }
            TimerEnd.FINISH_SONG -> {
                _state.value = current.copy(finishingSong = true)
                setPauseAtEnd(true)
            }
        }
    }

    fun attachHost(host: Host) {
        this.host = host
    }

    fun detachHost(host: Host) {
        if (this.host === host) {
            cancel()
            this.host = null
        }
    }

    /** Start a timer that runs for [minutes]. */
    fun startTime(minutes: Int, end: TimerEnd) {
        cancel()
        val durationMs = minutes * 60_000L
        _state.value =
            TimerState(
                TimerMode.TIME,
                end,
                SystemClock.elapsedRealtime() + durationMs,
                songsLeft = 0,
                finishingSong = false,
                totalMs = durationMs,
            )
        handler.postDelayed(timeUp, durationMs)
    }

    /** Start a timer that pauses at the end of the [count]th song, counting the current one. */
    fun startSongs(count: Int) {
        cancel()
        _state.value =
            TimerState(
                TimerMode.SONGS,
                TimerEnd.FINISH_SONG,
                endsAtMs = 0,
                songsLeft = count,
                finishingSong = false,
                initialSongs = count,
            )
        armIfLastSong()
    }

        /** Add [minutes] to a running time-based timer that has not run out yet. */
    fun extend(minutes: Int) {
        val current = _state.value ?: return
        if (current.mode != TimerMode.TIME || current.finishingSong) return
        handler.removeCallbacks(timeUp)
        val extra = minutes * 60_000L
        val newEnd = current.endsAtMs + extra
        _state.value = current.copy(endsAtMs = newEnd, totalMs = current.totalMs + extra)
        handler.postDelayed(timeUp, (newEnd - SystemClock.elapsedRealtime()).coerceAtLeast(0L))
    }

    /** Stop the timer without touching playback. */
    fun cancel() {
        handler.removeCallbacks(timeUp)
        host?.cancelFade()
        clear()
    }

    /** The player moved on to another song on its own, or because the user skipped. */
    fun onSongStarted() {
        val current = _state.value ?: return
        if (current.mode != TimerMode.SONGS || current.finishingSong) return
        _state.value = current.copy(songsLeft = current.songsLeft - 1)
        armIfLastSong()
    }

    /** The player paused at the end of a song (or the queue ended). */
    fun onPausedAtSongEnd() {
        val current = _state.value ?: return
        if (current.finishingSong) clear()
    }

    private fun armIfLastSong() {
        val current = _state.value ?: return
        if (current.mode == TimerMode.SONGS && current.songsLeft <= 1) {
            _state.value = current.copy(finishingSong = true)
            setPauseAtEnd(true)
        }
    }

    private fun clear() {
        _state.value = null
        if (pauseAtEndRequested) setPauseAtEnd(false)
    }

    private fun setPauseAtEnd(requested: Boolean) {
        pauseAtEndRequested = requested
        host?.refreshPauseAtEnd()
    }
}
