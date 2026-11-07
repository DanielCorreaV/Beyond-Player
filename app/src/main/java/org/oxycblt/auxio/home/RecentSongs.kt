/*
 * Copyright (c) 2026 Auxio Project
 * RecentSongs.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.musikr.Music

/** Remembers the last songs that were played, for "Seguir escuchando". */
@Singleton
class RecentSongs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("recent_songs", Context.MODE_PRIVATE)

    fun add(uid: Music.UID) {
        val id = uid.toString()
        val list = load().toMutableList()
        if (list.firstOrNull() == id) return
        list.remove(id)
        list.add(0, id)
        prefs.edit().putString(KEY, list.take(MAX).joinToString("\n")).apply()
    }

    fun load(): List<String> =
        prefs.getString(KEY, "")?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    private companion object {
        const val KEY = "uids"
        const val MAX = 20
    }
}
