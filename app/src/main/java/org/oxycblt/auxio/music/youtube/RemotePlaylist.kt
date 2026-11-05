/*
 * Copyright (c) 2026 Auxio Project
 * RemotePlaylist.kt is part of Auxio.
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

/**
 * A single track of a [RemotePlaylist].
 *
 * @property videoId The YouTube video ID of the track, or null if unknown.
 * @property title The title as listed on YouTube Music.
 * @property artist The uploader/artist as listed on YouTube Music. May be blank.
 * @property durationSec The duration in seconds, or -1 if unknown.
 */
data class RemoteTrack(
    val videoId: String?,
    val title: String,
    val artist: String,
    val durationSec: Long,
) {
    /** Human readable description for the "missing songs" summary. */
    val display: String
        get() = if (artist.isBlank()) title else "$artist - $title"
}

/**
 * A playlist read from YouTube Music.
 *
 * @property id The YouTube playlist ID (the `list=` parameter).
 * @property title The title of the playlist.
 * @property tracks The tracks, in the order shown on YouTube Music.
 */
data class RemotePlaylist(val id: String, val title: String, val tracks: List<RemoteTrack>)

/**
 * Something that can read a public YouTube Music playlist from a link. It is kept behind an
 * interface so the data source can be swapped if YouTube changes something.
 */
interface RemotePlaylistSource {
    /**
     * Read the playlist behind [link]. Only runs when the user explicitly imports.
     *
     * @throws RemotePlaylistException if the link is invalid or the playlist can't be read.
     */
    suspend fun fetch(link: String): RemotePlaylist
}

class RemotePlaylistException(message: String, cause: Throwable? = null) :
    Exception(message, cause)

/** Extract the playlist ID from a YouTube / YouTube Music link, or null if there isn't one. */
fun parsePlaylistId(link: String): String? =
    PLAYLIST_ID_REGEX.find(link.trim())?.groupValues?.get(1)

private val PLAYLIST_ID_REGEX = Regex("""[?&]list=([A-Za-z0-9_-]+)""")
