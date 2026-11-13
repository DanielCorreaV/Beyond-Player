/*
 * Copyright (c) 2026 Beyond Player
 * YtDownloadIndex.kt is part of Beyond Player, a fork of Auxio.
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

import android.content.Context

/**
 * Remembers which YouTube video became which file in Music/BeyondPlayer, so a song that shows up
 * in several playlists (or under a slightly different name) is downloaded once. The file itself is
 * always checked before trusting this: if the user deleted it, the song downloads again.
 */
object YtDownloadIndex {
    private const val PREFS = "yt_downloaded"

    fun get(context: Context, videoId: String): String? =
        runCatching { prefs(context).getString(videoId, null) }.getOrNull()

    fun record(context: Context, videoId: String, displayName: String) {
        runCatching { prefs(context).edit().putString(videoId, displayName).apply() }
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
