/*
 * Copyright (c) 2026 Auxio Project
 * SoundStabilizer.kt is part of Auxio.
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
import androidx.annotation.StringRes
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.oxycblt.auxio.R

/**
 * How strongly the stable-volume mode works.
 *
 * @property targetDb The loudness (RMS, in dBFS) every song is steered towards.
 * @property allowanceDb How far above [targetDb] a peak may go before it is compressed, in dB.
 * @property ratio Compression ratio above the threshold. 1 means no compression.
 */
enum class StabilizerMode(
    @StringRes val nameRes: Int,
    val targetDb: Float,
    val allowanceDb: Float,
    val ratio: Float,
) {
    OFF(R.string.lbl_stabilizer_off, 0f, 0f, 1f),
    LIGHT(R.string.lbl_stabilizer_light, -18f, 0f, 1f),
    BALANCED(R.string.lbl_stabilizer_balanced, -17f, 8f, 2f),
    STRONG(R.string.lbl_stabilizer_strong, -16f, 5f, 3.5f),
}

/** The chosen [StabilizerMode], remembered between sessions and read by the audio processor. */
@Singleton
class SoundStabilizer @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("sound_stabilizer", Context.MODE_PRIVATE)
    private val _mode =
        MutableStateFlow(
            StabilizerMode.entries.firstOrNull { it.name == prefs.getString(KEY_MODE, null) }
                ?: StabilizerMode.OFF
        )

    val mode: StateFlow<StabilizerMode> = _mode

    fun setMode(mode: StabilizerMode) {
        _mode.value = mode
        prefs.edit().putString(KEY_MODE, mode.name).apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
    }
}
