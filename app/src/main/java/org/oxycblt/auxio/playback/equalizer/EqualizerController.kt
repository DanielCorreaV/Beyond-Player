/*
 * Copyright (c) 2026 Auxio Project
 * EqualizerController.kt is part of Auxio.
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

import android.content.Context
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.media.audiofx.BassBoost
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.ln
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.R
import timber.log.Timber as L

/** One band of the equalizer. [level] is in millibels (1/100 dB). */
data class EqualizerBand(val centerHz: Int, val level: Int)

/**
 * What the equalizer dialog needs to draw itself.
 *
 * @property mode Index in [SoundMode.ALL] of the active sound style, or -1 for custom bands.
 * @property bass Bass boost strength, 0..1000. Only meaningful if [bassSupported].
 * @property spatial Spatial widening strength, 0..1000. Only meaningful if [spatialSupported].
 */
data class EqualizerState(
    val enabled: Boolean,
    val minLevel: Int,
    val maxLevel: Int,
    val bands: List<EqualizerBand>,
    val mode: Int,
    val bassSupported: Boolean,
    val bass: Int,
    val spatialSupported: Boolean,
    val spatial: Int,
)

/**
 * A sound style: a target curve in dB at some frequencies. It is applied to however many bands the
 * device has by interpolating between the points, so it works on any equalizer.
 */
class SoundMode(@StringRes val nameRes: Int, private val points: List<Pair<Int, Float>>) {
    /** The gain in dB this style asks for at [hz]. */
    fun gainAt(hz: Int): Float {
        if (points.isEmpty()) return 0f
        if (hz <= points.first().first) return points.first().second
        if (hz >= points.last().first) return points.last().second
        for (i in 1 until points.size) {
            val (upperHz, upperGain) = points[i]
            if (hz <= upperHz) {
                val (lowerHz, lowerGain) = points[i - 1]
                val t =
                    (ln(hz.toFloat()) - ln(lowerHz.toFloat())) /
                        (ln(upperHz.toFloat()) - ln(lowerHz.toFloat()))
                return lowerGain + (upperGain - lowerGain) * t
            }
        }
        return 0f
    }

    companion object {
        val ALL =
            listOf(
                SoundMode(R.string.lbl_mode_flat, emptyList()),
                SoundMode(
                    R.string.lbl_mode_bass,
                    listOf(60 to 6f, 150 to 4f, 400 to 1f, 1000 to 0f),
                ),
                SoundMode(
                    R.string.lbl_mode_treble,
                    listOf(1000 to 0f, 4000 to 3f, 10000 to 6f),
                ),
                SoundMode(
                    R.string.lbl_mode_vocal,
                    listOf(60 to -3f, 250 to -1f, 1000 to 3f, 3000 to 4f, 8000 to 0f, 12000 to -1f),
                ),
                SoundMode(
                    R.string.lbl_mode_rock,
                    listOf(60 to 4f, 250 to 2f, 1000 to -1f, 3000 to 2f, 8000 to 4f),
                ),
                SoundMode(
                    R.string.lbl_mode_pop,
                    listOf(60 to -1f, 500 to 2f, 2000 to 3f, 8000 to 1f),
                ),
                SoundMode(
                    R.string.lbl_mode_electronic,
                    listOf(60 to 5f, 200 to 2f, 1000 to -1f, 4000 to 2f, 12000 to 5f),
                ),
                SoundMode(
                    R.string.lbl_mode_classical,
                    listOf(60 to 2f, 250 to 0f, 1000 to 0f, 4000 to 1f, 12000 to 3f),
                ),
                SoundMode(
                    R.string.lbl_mode_cinema,
                    listOf(60 to 4f, 250 to 0f, 1000 to -1f, 4000 to 2f, 10000 to 3f),
                ),
            )
    }
}

/**
 * A built-in equalizer, so Auxio does not depend on a system equalizer app. It drives the Android
 * [Equalizer], [BassBoost] and [Virtualizer] effects attached to the player's audio session and
 * remembers their settings.
 */
@Singleton
class EqualizerController @Inject constructor(@ApplicationContext context: Context) {
    /** Where the sound goes. Each output can keep its own settings. */
    enum class Output(@StringRes val nameRes: Int, val key: String) {
        SPEAKER(R.string.lbl_output_speaker, "speaker"),
        WIRED(R.string.lbl_output_wired, "wired"),
        BLUETOOTH(R.string.lbl_output_bluetooth, "bluetooth"),
    }

    private val prefs = context.getSharedPreferences("equalizer", Context.MODE_PRIVATE)
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private var deviceCallbackRegistered = false
    private var profile = Output.SPEAKER

    private val deviceCallback =
        object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) =
                onOutputsChanged()

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) =
                onOutputsChanged()
        }
    private var equalizer: Equalizer? = null
    private var bassBoost: BassBoost? = null
    private var virtualizer: Virtualizer? = null
    private val _state = MutableStateFlow<EqualizerState?>(null)

    /** The current equalizer, or null if there is no audio session to attach to. */
    val state: StateFlow<EqualizerState?> = _state

    /** The output the sound is going to right now. */
    val output: Output
        @Synchronized get() = profile

    /** Whether every [Output] keeps its own settings. If not, one setting is used for all. */
    val perOutput: Boolean
        get() = prefs.getBoolean(KEY_PER_OUTPUT, true)

    @Synchronized
    fun setPerOutput(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_PER_OUTPUT, enabled).apply()
        applySaved()
    }

    /** Attach to the player's [sessionId] and apply the saved settings. */
    @Synchronized
    fun attach(sessionId: Int) {
        release()
        if (sessionId <= 0) return
        profile = detectOutput()
        if (!deviceCallbackRegistered) {
            deviceCallbackRegistered = true
            audioManager.registerAudioDeviceCallback(
                deviceCallback,
                Handler(Looper.getMainLooper()),
            )
        }
        try {
            val eq = Equalizer(0, sessionId)
            equalizer = eq
            restore(eq)
        } catch (e: Exception) {
            L.e(e, "Could not create the equalizer")
            equalizer = null
            _state.value = null
            return
        }
        // These two are optional, plenty of devices lack one of them.
        try {
            val effect = BassBoost(0, sessionId)
            if (effect.strengthSupported) {
                val strength = getInt(KEY_BASS, 0)
                effect.setStrength(strength.toShort())
                effect.setEnabled(strength > 0)
                bassBoost = effect
            } else {
                effect.release()
            }
        } catch (e: Exception) {
            L.e(e, "Could not create the bass boost")
            bassBoost = null
        }
        try {
            val effect = Virtualizer(0, sessionId)
            if (effect.strengthSupported) {
                val strength = getInt(KEY_SPATIAL, 0)
                effect.setStrength(strength.toShort())
                effect.setEnabled(strength > 0)
                virtualizer = effect
            } else {
                effect.release()
            }
        } catch (e: Exception) {
            L.e(e, "Could not create the virtualizer")
            virtualizer = null
        }
        publish()
    }

    @Synchronized
    fun release() {
        try {
            equalizer?.release()
            bassBoost?.release()
            virtualizer?.release()
        } catch (e: Exception) {
            L.e(e, "Could not release the equalizer")
        }
        equalizer = null
        bassBoost = null
        virtualizer = null
        _state.value = null
    }

    @Synchronized
    fun setEnabled(enabled: Boolean) {
        val eq = equalizer ?: return
        eq.setEnabled(enabled)
        prefs.edit().putBoolean(key(KEY_ENABLED), enabled).apply()
        publish()
    }

    /** Change one band. The dialog already shows the slider position, so nothing is published. */
    @Synchronized
    fun setBandLevel(band: Int, level: Int) {
        val eq = equalizer ?: return
        val range = eq.bandLevelRange
        eq.setBandLevel(band.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
        save(eq, mode = -1)
    }

    /** Apply the [SoundMode.ALL] style at [index] to the bands. */
    @Synchronized
    fun useMode(index: Int) {
        val eq = equalizer ?: return
        val mode = SoundMode.ALL.getOrNull(index) ?: return
        val range = eq.bandLevelRange
        for (band in 0 until eq.numberOfBands) {
            val hz = eq.getCenterFreq(band.toShort()) / 1000
            val level = (mode.gainAt(hz) * 100).roundToInt()
            eq.setBandLevel(band.toShort(), level.coerceIn(range[0].toInt(), range[1].toInt()).toShort())
        }
        save(eq, mode = index)
        publish()
    }

    /** Bass boost strength, 0..1000. */
    @Synchronized
    fun setBass(strength: Int) {
        val effect = bassBoost ?: return
        effect.setStrength(strength.coerceIn(0, 1000).toShort())
        effect.setEnabled(strength > 0)
        prefs.edit().putInt(key(KEY_BASS), strength.coerceIn(0, 1000)).apply()
    }

    /** Spatial widening strength, 0..1000. */
    @Synchronized
    fun setSpatial(strength: Int) {
        val effect = virtualizer ?: return
        effect.setStrength(strength.coerceIn(0, 1000).toShort())
        effect.setEnabled(strength > 0)
        prefs.edit().putInt(key(KEY_SPATIAL), strength.coerceIn(0, 1000)).apply()
    }

    /** Everything back to flat: bands at 0 dB, no bass boost, no widening. */
    @Synchronized
    fun reset() {
        val eq = equalizer ?: return
        for (band in 0 until eq.numberOfBands) eq.setBandLevel(band.toShort(), 0)
        save(eq, mode = 0)
        setBass(0)
        setSpatial(0)
        publish()
    }

    private fun restore(eq: Equalizer) {
        val range = eq.bandLevelRange
        val saved =
            (prefs.getString(key(KEY_LEVELS), null) ?: prefs.getString(KEY_LEVELS, null))
                ?.split(',')
                ?.mapNotNull { it.toIntOrNull() }
        for (band in 0 until eq.numberOfBands.toInt()) {
            // Nothing saved for this output means flat, not whatever the last output had.
            val level = saved?.takeIf { it.size == eq.numberOfBands.toInt() }?.get(band) ?: 0
            eq.setBandLevel(
                band.toShort(),
                level.coerceIn(range[0].toInt(), range[1].toInt()).toShort(),
            )
        }
        eq.setEnabled(
            prefs.getBoolean(key(KEY_ENABLED), prefs.getBoolean(KEY_ENABLED, false))
        )
    }

    private fun save(eq: Equalizer, mode: Int) {
        val levels =
            (0 until eq.numberOfBands).joinToString(",") { eq.getBandLevel(it.toShort()).toString() }
        prefs.edit().putString(key(KEY_LEVELS), levels).putInt(key(KEY_MODE), mode).apply()
    }

    private fun publish() {
        val eq = equalizer ?: return
        val range = eq.bandLevelRange
        val bands =
            (0 until eq.numberOfBands).map {
                EqualizerBand(
                    centerHz = eq.getCenterFreq(it.toShort()) / 1000,
                    level = eq.getBandLevel(it.toShort()).toInt(),
                )
            }
        _state.value =
            EqualizerState(
                enabled = eq.enabled,
                minLevel = range[0].toInt(),
                maxLevel = range[1].toInt(),
                bands = bands,
                mode = getInt(KEY_MODE, -1).takeIf { it in SoundMode.ALL.indices } ?: -1,
                bassSupported = bassBoost != null,
                bass = getInt(KEY_BASS, 0),
                spatialSupported = virtualizer != null,
                spatial = getInt(KEY_SPATIAL, 0),
            )
    }

    /** The preference key for [name] under the current output, if outputs are kept apart. */
    private fun key(name: String) = if (perOutput) "${profile.key}.$name" else name

    /** Reads the setting of the current output, falling back to the old shared one. */
    private fun getInt(name: String, default: Int) =
        prefs.getInt(key(name), prefs.getInt(name, default))

    /** Re-read every saved setting for the current output and apply it. */
    private fun applySaved() {
        equalizer?.let { restore(it) }
        bassBoost?.let {
            val strength = getInt(KEY_BASS, 0)
            it.setStrength(strength.toShort())
            it.setEnabled(strength > 0)
        }
        virtualizer?.let {
            val strength = getInt(KEY_SPATIAL, 0)
            it.setStrength(strength.toShort())
            it.setEnabled(strength > 0)
        }
        publish()
    }

    @Synchronized
    private fun onOutputsChanged() {
        val detected = detectOutput()
        if (detected == profile) return
        L.d("Sound output changed to $detected")
        profile = detected
        if (perOutput) applySaved()
    }

    private fun detectOutput(): Output {
        val types = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).map { it.type }
        return when {
            types.any { it in BLUETOOTH_TYPES } -> Output.BLUETOOTH
            types.any { it in WIRED_TYPES } -> Output.WIRED
            else -> Output.SPEAKER
        }
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_LEVELS = "levels"
        const val KEY_MODE = "mode"
        const val KEY_BASS = "bass"
        const val KEY_SPATIAL = "spatial"
        const val KEY_PER_OUTPUT = "per_output"
        val BLUETOOTH_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
                AudioDeviceInfo.TYPE_BLUETOOTH_SCO,
                AudioDeviceInfo.TYPE_HEARING_AID,
                // Bluetooth LE Audio, added in later Android versions.
                26,
                27,
                30,
            )
        val WIRED_TYPES =
            setOf(
                AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
                AudioDeviceInfo.TYPE_WIRED_HEADSET,
                AudioDeviceInfo.TYPE_USB_HEADSET,
                AudioDeviceInfo.TYPE_USB_DEVICE,
                AudioDeviceInfo.TYPE_LINE_ANALOG,
                AudioDeviceInfo.TYPE_LINE_DIGITAL,
            )
    }
}
