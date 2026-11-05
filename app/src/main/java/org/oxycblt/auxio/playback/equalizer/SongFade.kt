/*
 * Copyright (c) 2026 Auxio Project
 * SongFade.kt is part of Auxio.
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * How long, in milliseconds, the end of a song overlaps with the start of the next one. 0 means no
 * crossfade. The choice is remembered between sessions.
 */
@Singleton
class SongFade @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("song_fade", Context.MODE_PRIVATE)
    private val _ms = MutableStateFlow(prefs.getInt(KEY_MS, 0).takeIf { it in OPTIONS } ?: 0)

    val ms: StateFlow<Int> = _ms

    fun set(ms: Int) {
        _ms.value = ms
        prefs.edit().putInt(KEY_MS, ms).apply()
    }

    companion object {
        val OPTIONS = listOf(0, 2000, 4000, 6000, 8000)
        private const val KEY_MS = "ms"
    }
}
