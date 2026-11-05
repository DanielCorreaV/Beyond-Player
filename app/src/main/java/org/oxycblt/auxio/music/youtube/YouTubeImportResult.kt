/*
 * Copyright (c) 2026 Auxio Project
 * YouTubeImportResult.kt is part of Auxio.
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

/** Outcome of importing one link. */
sealed interface YouTubeImportItem {
    /** The link that was imported, as typed by the user. */
    val link: String

    /**
     * The playlist was read and a local playlist was created or updated.
     *
     * @property title Name of the local playlist.
     * @property total Amount of tracks in the YouTube Music playlist.
     * @property matched Amount of tracks found on the phone.
     * @property missing Tracks not found on the phone.
     * @property kept Amount of songs added by the user that were kept in the playlist.
     * @property created True if a new local playlist was created, false if an existing one was
     *   updated.
     */
    data class Done(
        override val link: String,
        val title: String,
        val total: Int,
        val matched: Int,
        val missing: List<RemoteTrack>,
        val created: Boolean,
        val kept: Int = 0,
    ) : YouTubeImportItem

    /** The link could not be imported. */
    data class Failed(override val link: String, val reason: String) : YouTubeImportItem
}

/** Summary shown after an import finished, one entry per link. */
data class YouTubeImportResult(val items: List<YouTubeImportItem>)
