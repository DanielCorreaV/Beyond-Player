/*
 * Copyright (c) 2026 Auxio Project
 * RemoteSongs.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.musikr

import android.net.Uri
import org.oxycblt.musikr.fs.AddedMs
import org.oxycblt.musikr.fs.Components
import org.oxycblt.musikr.fs.File
import org.oxycblt.musikr.fs.Path
import org.oxycblt.musikr.fs.Volume
import org.oxycblt.musikr.metadata.Properties
import org.oxycblt.musikr.pipeline.RawSong
import org.oxycblt.musikr.tag.parse.ParsedTags

/** A song that is not a file on the phone (YouTube Music): only metadata, played by streaming. */
data class RemoteSong(
    val id: String,
    val title: String,
    val artist: String,
    val album: String?,
    val durationMs: Long,
    val addedMs: Long,
) {
    /** The address the player resolves when it opens this song. */
    val uri: Uri
        get() = Uri.parse("yt://$id")
}

/** Where the library builder asks for the remote songs. Set once by the app at startup. */
object RemoteSongs {
    @Volatile var provider: (() -> List<RemoteSong>)? = null
}

private val REMOTE_VOLUME = Volume.ThirdParty(Uri.parse("yt://youtube"))

/** Dresses a [RemoteSong] up as a scanned file so it goes through the normal tag interpreter. */
internal fun RemoteSong.toRawSong(): RawSong {
    val added = addedMs
    val file =
        File(
            uri = uri,
            path = Path(REMOTE_VOLUME, Components.parseUnix("YouTube Music/$id.m4a")),
            addedMs =
                object : AddedMs {
                    override suspend fun resolve(): Long? = added
                },
            modifiedMs = added,
            mimeType = "audio/mp4",
            size = 0L,
            parent = null,
        )
    return RawSong(
        file = file,
        properties =
            Properties(
                mimeType = "audio/mp4",
                durationMs = durationMs,
                bitrateKbps = 128,
                sampleRateHz = 44100,
            ),
        tags =
            ParsedTags(
                durationMs = durationMs,
                name = title,
                albumName = album ?: "YouTube Music",
                artistNames = if (artist.isBlank()) emptyList() else listOf(artist),
            ),
        cover = null,
        addedMs = added,
    )
}
