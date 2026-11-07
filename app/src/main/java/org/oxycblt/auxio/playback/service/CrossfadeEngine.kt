/*
 * Copyright (c) 2026 Auxio Project
 * CrossfadeEngine.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.service

import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import timber.log.Timber as L

/**
 * A player that is already loaded with the next song, silent and paused, waiting to take over.
 *
 * @param player The player. It holds the whole queue and sits on the next song.
 * @param signature Describes the queue it was built from, to notice if the queue changed since.
 */
class Incoming(val player: ExoPlayer, val signature: Any)

/**
 * Mixes the end of a song with the start of the next one by handing the "main player" role from one
 * player to another.
 *
 * A few seconds before the song ends, a second player is built with the same queue and prepared on
 * the next song, silent and paused. When the fade starts, that player becomes the main player (the
 * holder switches to it, so the queue, the controls and the notification already follow the new
 * song) and starts playing. The old player is left alone, it just keeps playing the end of its song
 * with its volume going down, so nothing in the outgoing audio is cut or repeated. When the fade is
 * over the old player is released.
 *
 * All calls must be made from the main thread.
 *
 * @param main The current main player.
 * @param canCrossfade Extra conditions from the app, for example a sleep timer that wants to pause
 *   when the song ends.
 * @param buildIncoming Builds the [Incoming] player for the song after the current one, or null.
 * @param signatureOf The current signature of the queue, to compare with [Incoming.signature].
 * @param swap Makes the incoming player the main one. The outgoing player is handed over too, it
 *   must no longer be controlled by the app.
 * @param onMainFactor Receives the volume factor (0..1) the main player should have.
 */
@OptIn(UnstableApi::class)
class CrossfadeEngine(
    private val main: () -> ExoPlayer,
    private val canCrossfade: () -> Boolean,
    private val buildIncoming: () -> Incoming?,
    private val signatureOf: () -> Any,
    private val swap: (Incoming, ExoPlayer) -> Unit,
    private val onMainFactor: (Float) -> Unit,
) {
    private enum class Phase {
        IDLE,
        /** The incoming player is ready, waiting for the fade to begin. */
        PREPARED,
        FADING,
    }

    private var phase = Phase.IDLE
    private var incoming: Incoming? = null
    private var outgoing: ExoPlayer? = null
    private var outgoingDurationMs = 0L
    private var fadeMs = 0
    private var indexAtPrepare = C.INDEX_UNSET
    private var uriAtPrepare: android.net.Uri? = null
    private var lastLogAt = 0L
    // A song the crossfade failed on, so we do not retry it over and over.
    private var blockedIndex = C.INDEX_UNSET
    private var blockedUri: android.net.Uri? = null

    /** Whether a fade is running right now, so the caller should tick often. */
    val isFading: Boolean
        get() = phase == Phase.FADING

    /** Look at the players and move the crossfade forward. Call every 100ms or so. */
    fun tick(requestedMs: Int) {
        try {
            when (phase) {
                Phase.IDLE -> tickIdle(requestedMs)
                Phase.PREPARED -> tickPrepared(requestedMs)
                Phase.FADING -> tickFading()
            }
        } catch (e: Exception) {
            L.e(e, "Crossfade failed, giving up on this song")
            log("exception: $e")
            if (phase != Phase.FADING) blockCurrentSong()
            stop("exception")
        }
    }

    /** The main player moved to another song. Cancels a pending fade the user did not ask for. */
    fun onTransition() {
        if (phase == Phase.PREPARED) stop("the song changed on its own")
    }

    /**
     * Drop everything. A running fade is cut short: the outgoing player is released and the main
     * player gets its full volume back.
     */
    fun stop(reason: String = "requested") {
        if (phase != Phase.IDLE) log("stop ($phase): $reason")
        val inc = incoming
        val out = outgoing
        incoming = null
        outgoing = null
        phase = Phase.IDLE
        releaseQuietly(inc?.player)
        releaseQuietly(out)
        onMainFactor(1f)
    }

    fun release() = stop("release")

    private fun releaseQuietly(player: ExoPlayer?) {
        try {
            player?.release()
        } catch (e: Exception) {
            L.e(e, "Could not release a crossfade player")
        }
    }

    private fun log(message: String) {
        android.util.Log.i("AuxioFade", message)
    }

    private fun tickIdle(requestedMs: Int) {
        if (!eligible(requestedMs)) return
        val player = main()
        val remaining = durationMs(player) - player.currentPosition
        if (remaining <= requestedMs + PREPARE_LEAD_MS && remaining > requestedMs) {
            val built = buildIncoming()
            if (built == null) {
                blockCurrentSong()
                return
            }
            incoming = built
            fadeMs = requestedMs
            indexAtPrepare = player.currentMediaItemIndex
            uriAtPrepare = player.currentMediaItem?.localConfiguration?.uri
            phase = Phase.PREPARED
            log(
                "prepared: fade=${requestedMs}ms duration=${durationMs(player)} position=${player.currentPosition}"
            )
        }
    }

    private fun tickPrepared(requestedMs: Int) {
        val player = main()
        val inc = incoming
        val remaining = durationMs(player) - player.currentPosition
        if (inc?.player?.playerError != null) {
            log("incoming error: ${inc.player.playerError}")
            blockCurrentSong()
            stop("incoming error")
            return
        }
        if (
            inc == null ||
                requestedMs != fadeMs ||
                player.currentMediaItemIndex != indexAtPrepare ||
                player.currentMediaItem?.localConfiguration?.uri != uriAtPrepare ||
                !eligible(requestedMs) ||
                // The user moved back in the song, the fade is no longer near.
                remaining > requestedMs + PREPARE_LEAD_MS + 2000
        ) {
            stop("conditions changed (remaining=$remaining)")
            return
        }
        if (remaining <= requestedMs) {
            if (inc.player.playbackState != Player.STATE_READY) {
                if (remaining < requestedMs / 2) stop("incoming not ready in time")
                return
            }
            if (signatureOf() != inc.signature) {
                // Songs were added, moved or removed since, the prepared player is out of date.
                stop("the queue changed")
                return
            }
            startFade(inc, player)
        }
    }

    private fun startFade(inc: Incoming, out: ExoPlayer) {
        log("starting: out[pos=${out.currentPosition}] incoming[state=${inc.player.playbackState}]")
        outgoingDurationMs = durationMs(out)
        // The swap makes the app talk to the incoming player, which reports events right away,
        // so the phase must already say FADING.
        phase = Phase.FADING
        incoming = null
        outgoing = out
        swap(inc, out)
        log("swapped: main index=${main().currentMediaItemIndex}")
    }

    private fun tickFading() {
        val out = outgoing
        val player = main()
        if (out == null || out.playerError != null) {
            stop("outgoing=${out != null} error=${out?.playerError}")
            return
        }
        // How much of the outgoing song is left decides the progress, so it ends exactly with it.
        val remaining = outgoingDurationMs - out.currentPosition
        val t = (1f - remaining / fadeMs.toFloat()).coerceIn(0f, 1f)
        if (remaining <= FINISH_MARGIN_MS || out.playbackState == Player.STATE_ENDED) {
            stop("finished (remaining=$remaining)")
            return
        }
        // The outgoing player follows play and pause of the main one.
        if (out.playWhenReady != player.playWhenReady) out.playWhenReady = player.playWhenReady
        // Equal power curves keep the loudness steady through the middle of the fade.
        val angle = t * (PI / 2).toFloat()
        out.volume = cos(angle)
        onMainFactor(sin(angle))
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastLogAt >= 500) {
            lastLogAt = now
            log(
                "fade t=%.2f out[vol=%.2f pos=%d playing=%b] main[pos=%d playing=%b vol=%.2f]"
                    .format(
                        t,
                        out.volume,
                        out.currentPosition,
                        out.isPlaying,
                        player.currentPosition,
                        player.isPlaying,
                        player.volume,
                    )
            )
        }
    }

    private fun blockCurrentSong() {
        val player = main()
        blockedIndex = player.currentMediaItemIndex
        blockedUri = player.currentMediaItem?.localConfiguration?.uri
    }

    private fun eligible(requestedMs: Int): Boolean {
        val player = main()
        val duration = durationMs(player)
        val blocked =
            blockedIndex == player.currentMediaItemIndex &&
                blockedUri == player.currentMediaItem?.localConfiguration?.uri
        return !blocked &&
            requestedMs > 0 &&
            player.playWhenReady &&
            player.hasNextMediaItem() &&
            player.repeatMode != Player.REPEAT_MODE_ONE &&
            duration >= requestedMs * 3L &&
            canCrossfade()
    }

    private fun durationMs(player: ExoPlayer): Long {
        val duration = player.duration
        if (duration != C.TIME_UNSET && duration > 0) return duration
        return player.currentMediaItem?.mediaMetadata?.extras?.getLong("durationMs") ?: 0L
    }

    private companion object {
        /** How long before the fade the next player starts loading. */
        const val PREPARE_LEAD_MS = 4000L
        /** The fade is over this close to the end, before the old player can move on by itself. */
        const val FINISH_MARGIN_MS = 120L
    }
}
