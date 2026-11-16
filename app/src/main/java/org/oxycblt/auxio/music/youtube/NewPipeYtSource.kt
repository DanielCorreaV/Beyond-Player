/*
 * Copyright (c) 2026 Beyond Player
 * NewPipeYtSource.kt is part of Beyond Player, a fork of Auxio.
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

import org.schabi.newpipe.extractor.MediaFormat
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import timber.log.Timber as L

/** [YtSource] backed by NewPipeExtractor. Nothing touches the network until a call is made. */
class NewPipeYtSource : YtSource {
    override fun search(query: String): List<YtTrack> {
        ensureInitialized()
        try {
            val service = ServiceList.YouTube
            val handler =
                service.searchQHFactory.fromQuery(
                    query,
                    listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS),
                    "",
                )
            val info = SearchInfo.getInfo(service, handler)
            info.errors.take(3).forEach { L.e(it, "YT search: extractor error") }
            return info.relatedItems.filterIsInstance<StreamInfoItem>().mapNotNull {
                val id = VIDEO_ID_REGEX.find(it.url ?: "")?.groupValues?.get(1)
                if (id == null) null
                else
                    YtTrack(
                        videoId = id,
                        title = it.name ?: "",
                        artist = YtDescription.cleanArtist(it.uploaderName ?: ""),
                        durationSec = it.duration,
                        thumbnailUrl = it.thumbnails.maxByOrNull { img -> img.height }?.url,
                    )
            }
        } catch (e: Exception) {
            throw YtException("Search failed: ${e.message}", e)
        }
    }

    override fun warmUp() {
        ensureInitialized()
        // Looks up (and remembers) the YouTube Music version that the first search needs.
        runCatching { YoutubeParsingHelper.getYoutubeMusicClientVersion() }
            .onFailure { L.w(it, "YT warm-up failed") }
        runCatching { YtFastResolve.visitorData() }

    }

    override fun resolveStream(videoId: String): YtAudio =
        YtFastResolve.tryResolve(videoId) ?: resolveAudio(videoId)

    override fun resolveAudio(videoId: String): YtAudio {

        ensureInitialized()
        try {
            val info = StreamInfo.getInfo(ServiceList.YouTube, "https://www.youtube.com/watch?v=$videoId")
            val usable =
                info.audioStreams.filter {
                    it.isUrl && it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP
                }
            L.d(
                "YT resolve $videoId: ${info.audioStreams.size} audio streams, " +
                    "${usable.size} direct: " +
                    usable.joinToString { "${it.format?.suffix}@${it.averageBitrate}" }
            )
            // m4a (AAC) first: TagLib and every Android decoder handle it. WebM/Opus is the
            // fallback so a song is never unavailable just because of its container.
            val best =
                usable.filter { it.format == MediaFormat.M4A }.maxByOrNull { it.averageBitrate }
                    ?: usable.maxByOrNull { it.averageBitrate }
                    ?: throw YtException("No direct audio stream for $videoId")
            val format = best.format
            val (album, year) = YtDescription.parse(info.description?.content)
            L.d("YT resolve $videoId: album=$album year=$year")
            return YtAudio(
                url = best.content,
                mimeType = format?.mimeType ?: "audio/mp4",
                extension = format?.suffix ?: "m4a",
                bitrate = best.averageBitrate,
                album = album,
                year = year,
                thumbnailUrl = info.thumbnails.maxByOrNull { it.height }?.url,
            )
        } catch (e: YtException) {
            throw e
        } catch (e: Exception) {
            throw YtException("Could not resolve $videoId: ${e.message}", e)
        }
    }

    private fun ensureInitialized() {
        synchronized(NewPipeYtSource) {
            if (NewPipe.getDownloader() == null) {
                NewPipe.init(HttpDownloader)
            }
        }
    }

    private companion object {
        val VIDEO_ID_REGEX = Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
    }
}
