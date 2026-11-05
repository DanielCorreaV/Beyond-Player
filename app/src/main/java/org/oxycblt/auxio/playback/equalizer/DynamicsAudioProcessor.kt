/*
 * Copyright (c) 2026 Auxio Project
 * DynamicsAudioProcessor.kt is part of Auxio.
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

package org.oxycblt.auxio.playback.equalizer

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import javax.inject.Inject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * An [AudioProcessor] that keeps the volume steady from song to song and tames peaks inside a song,
 * so the phone volume can be set once. In three stages:
 * 1. A slow leveler measures how loud the output has been over the last seconds and raises or lowers
 *    the gain towards the target loudness (up to +12 dB / -12 dB). It holds on near-silence.
 * 2. A gentle compressor lowers peaks that go well above the target in proportion to how far they
 *    go over. Since the leveler measures what comes out, it makes up for the lost level.
 * 3. A limiter makes sure nothing clips.
 *
 * It does nothing while [StabilizerMode.OFF] is selected. Only 16-bit PCM is handled, like the
 * ReplayGain processor.
 */
@OptIn(markerClass = [UnstableApi::class])
class DynamicsAudioProcessor @Inject constructor(private val stabilizer: SoundStabilizer) :
    BaseAudioProcessor() {
    private var channels = 2
    private var sampleRate = 44100
    private var frame = FloatArray(2)

    // Leveler
    private var meanSquare = 0.0
    private var levelGain = 1.0
    private var levelCounter = 0

    // Compressor
    private var envelope = 0.0
    private var compGain = 1.0
    private var compCounter = 0

    // Limiter
    private var limiterGain = 1.0

    // Coefficients derived from the sample rate.
    private var powerCoef = 0.0
    private var attackCoef = 0.0
    private var releaseCoef = 0.0
    private var limiterReleaseCoef = 0.0
    private var levelAttackStep = 0.0
    private var levelReleaseStep = 0.0

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        channels = inputAudioFormat.channelCount.coerceAtLeast(1)
        sampleRate = inputAudioFormat.sampleRate.coerceAtLeast(8000)
        frame = FloatArray(channels)
        val fs = sampleRate.toDouble()
        powerCoef = 1.0 - exp(-1.0 / (POWER_TAU_SEC * fs))
        attackCoef = exp(-1.0 / (COMP_ATTACK_SEC * fs))
        releaseCoef = exp(-1.0 / (COMP_RELEASE_SEC * fs))
        limiterReleaseCoef = 1.0 - exp(-1.0 / (LIMITER_RELEASE_SEC * fs))
        levelAttackStep = 1.0 - exp(-LEVEL_STEP / (LEVEL_ATTACK_SEC * fs))
        levelReleaseStep = 1.0 - exp(-LEVEL_STEP / (LEVEL_RELEASE_SEC * fs))
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val start = inputBuffer.position()
        val end = inputBuffer.limit()
        val output = replaceOutputBuffer(end - start)
        val mode = stabilizer.mode.value

        if (mode == StabilizerMode.OFF) {
            output.put(inputBuffer.slice())
            inputBuffer.position(end)
            output.flip()
            return
        }

        val targetDb = mode.targetDb
        val compress = mode.ratio > 1f
        val threshold = targetDb + mode.allowanceDb
        val slope = 1f - 1f / mode.ratio
        val bytesPerFrame = 2 * channels
        var index = start
        while (index + bytesPerFrame <= end) {
            var peak = 0f
            var power = 0f
            for (c in 0 until channels) {
                val at = index + c * 2
                val lo = inputBuffer.get(at).toInt() and 0xFF
                val hi = inputBuffer.get(at + 1).toInt()
                val x = ((hi shl 8) or lo) / 32768f
                frame[c] = x
                peak = max(peak, abs(x))
                power += x * x
            }
            power /= channels

            // Stage 2: compressor, watching the peaks after the leveler's gain.
            val leveled = peak * levelGain
            val a = if (leveled > envelope) attackCoef else releaseCoef
            envelope = a * envelope + (1.0 - a) * leveled
            if (++compCounter >= COMP_STEP) {
                compCounter = 0
                compGain =
                    if (compress) {
                        val over = 20.0 * log10(envelope + 1e-9) - threshold
                        if (over > 0) 10.0.pow(-over * slope / 20.0) else 1.0
                    } else {
                        1.0
                    }
            }
            val gain = levelGain * compGain

            // Stage 1: slow leveler, steering the loudness of the output towards the target.
            meanSquare += (power * gain * gain - meanSquare) * powerCoef
            if (++levelCounter >= LEVEL_STEP) {
                levelCounter = 0
                val rmsDb = 20.0 * log10(sqrt(meanSquare) + 1e-9)
                if (rmsDb > SILENCE_DB) {
                    val error = targetDb - rmsDb
                    val factor = 10.0.pow(error / 20.0)
                    val step = if (error < 0) levelAttackStep else levelReleaseStep
                    levelGain = (levelGain * (1.0 + (factor - 1.0) * step)).coerceIn(MIN_GAIN, MAX_GAIN)
                }
            }

            // Stage 3: limiter, instant attack and slow release.
            val boosted = peak * gain
            val needed = if (boosted > LIMIT) LIMIT / boosted else 1.0
            limiterGain = minOf(needed, limiterGain + (1.0 - limiterGain) * limiterReleaseCoef)
            val total = (gain * limiterGain).toFloat()

            for (c in 0 until channels) {
                val v = (frame[c] * total * 32768f).roundToInt().coerceIn(-32768, 32767)
                output.put(v.toByte())
                output.put((v shr 8).toByte())
            }
            index += bytesPerFrame
        }
        // Any trailing partial frame is passed through untouched.
        while (index < end) {
            output.put(inputBuffer.get(index))
            index++
        }
        inputBuffer.position(end)
        output.flip()
    }

    private companion object {
        /** Window the loudness is measured over. */
        const val POWER_TAU_SEC = 1.5

        /** Frames between leveler gain updates. */
        const val LEVEL_STEP = 128

        /** Frames between compressor gain updates. */
        const val COMP_STEP = 32

        const val LEVEL_ATTACK_SEC = 0.3
        const val LEVEL_RELEASE_SEC = 2.0
        const val COMP_ATTACK_SEC = 0.01
        const val COMP_RELEASE_SEC = 0.15
        const val LIMITER_RELEASE_SEC = 0.1

        /** Gain limits of the leveler, +12 dB and -12 dB. */
        val MAX_GAIN = 10.0.pow(12.0 / 20.0)
        val MIN_GAIN = 10.0.pow(-12.0 / 20.0)

        /** Below this loudness the leveler holds its gain instead of boosting silence. */
        const val SILENCE_DB = -55.0

        /** Highest output level, about -1 dBFS. */
        const val LIMIT = 0.89
    }
}
