/*
 * Copyright (c) 2026 Beyond Player
 * YtSource.kt is part of Beyond Player, a fork of Auxio.
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

/** A song found on YouTube Music. */
data class YtTrack(
    val videoId: String,
    val title: String,
    val artist: String,
    val durationSec: Long,
    val thumbnailUrl: String?,
)

/** A direct audio stream for a [YtTrack]. [extension] is "m4a" or "webm". */
data class YtAudio(
    val url: String,
    val mimeType: String,
    val extension: String,
    val bitrate: Int,
    /** Album and year when YouTube knows them (only for auto-generated "Topic" uploads). */
    val album: String? = null,
    val year: Int? = null,
    /** Biggest thumbnail of the video, used for the cover when the search gave none. */
    val thumbnailUrl: String? = null,
)

class YtException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Where YouTube data comes from. Everything else in the app talks to this interface so the
 * extractor (NewPipe today) can be replaced without touching downloads or UI.
 */
interface YtSource {
    /** Search songs. Blocking: call from a background dispatcher. */
    fun search(query: String): List<YtTrack>

    /** Do the one-time network setup now so the first real request is not slower. Blocking. */
    fun warmUp() {}

    /** Pick the best direct audio stream, preferring m4a. Blocking. */
    fun resolveAudio(videoId: String): YtAudio
}

/** Reads the album and year out of the description YouTube generates for official uploads. */
object YtDescription {
    private val YEAR = Regex("""(?:Released on|℗)[^\d]{0,12}((?:19|20)\d{2})""")

    /**
     * Auto-generated descriptions look like:
     * ```
     * Provided to YouTube by Some Label
     *
     * Song · Artist 1 · Artist 2
     *
     * Album Name
     *
     * ℗ 2019 Some Label
     *
     * Released on: 2019-05-10
     * ```
     * Anything that does not match gives (null, null): a missing album must never fail a download.
     */
    fun parse(text: String?): Pair<String?, Int?> {
        if (text.isNullOrBlank()) return null to null
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val start = lines.indexOfFirst { it.startsWith("Provided to YouTube by") }
        val year = YEAR.find(text)?.groupValues?.get(1)?.toIntOrNull()
        if (start < 0) return null to year
        val songLine = lines.getOrNull(start + 1)
        if (songLine == null || !songLine.contains(" · ")) return null to year
        val album =
            lines.getOrNull(start + 2)?.takeIf {
                !it.startsWith("℗") && !it.startsWith("Released on") && !it.contains(": ")
            }
        return album to year
    }

    /** "Artist - Topic" is how YouTube names the auto-generated channel; the artist is the rest. */
    fun cleanArtist(name: String) = name.removeSuffix(" - Topic").trim()
}
