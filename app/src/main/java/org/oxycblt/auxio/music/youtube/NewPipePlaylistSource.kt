/*
 * Copyright (c) 2026 Auxio Project
 * NewPipePlaylistSource.kt is part of Auxio.
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

import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import timber.log.Timber as L

/**
 * [RemotePlaylistSource] backed by NewPipeExtractor. Nothing here touches the network until
 * [fetch] is called, which only happens when the user taps import.
 */
class NewPipePlaylistSource : RemotePlaylistSource {
    override suspend fun fetch(link: String): RemotePlaylist =
        withContext(Dispatchers.IO) {
            val id =
                parsePlaylistId(link)
                    ?: throw RemotePlaylistException("Link has no playlist ID: $link")
            ensureInitialized()
            // YouTube Music playlists are regular YouTube playlists under the hood.
            val url = "https://www.youtube.com/playlist?list=$id"
            try {
                val service = ServiceList.YouTube
                val info = PlaylistInfo.getInfo(service, url)
                val items = info.relatedItems.toMutableList()
                var page = info.nextPage
                var pages = 0
                while (page != null && pages++ < MAX_PAGES) {
                    val more = PlaylistInfo.getMoreItems(service, url, page)
                    items.addAll(more.items)
                    page = more.nextPage
                }
                L.d(
                    "YT NewPipe: url=$url name='${info.name}' first=${info.relatedItems.size} " +
                        "total=${items.size} errors=${info.errors.size}"
                )
                info.errors.take(3).forEach { L.e(it, "YT NewPipe: extractor error") }
                if (items.isEmpty()) {
                    throw RemotePlaylistException(
                        "YouTube returned no tracks for $id (name='${info.name}', " +
                            "errors=${info.errors.size}). It may be private, a radio/mix, " +
                            "or YouTube changed something."
                    )
                }
                RemotePlaylist(
                    id = id,
                    title = info.name?.takeIf { it.isNotBlank() } ?: "YouTube Music $id",
                    tracks =
                        items.map {
                            RemoteTrack(
                                videoId = VIDEO_ID_REGEX.find(it.url ?: "")?.groupValues?.get(1),
                                title = it.name ?: "",
                                artist = it.uploaderName ?: "",
                                durationSec = it.duration,
                            )
                        },
                )
            } catch (e: RemotePlaylistException) {
                throw e
            } catch (e: Exception) {
                L.e(e, "Failed to read YouTube playlist $id")
                throw RemotePlaylistException("Could not read playlist $id", e)
            }
        }

    private fun ensureInitialized() {
        synchronized(NewPipePlaylistSource) {
            if (!initialized) {
                NewPipe.init(HttpDownloader)
                initialized = true
            }
        }
    }

    private companion object {
        const val MAX_PAGES = 100
        val VIDEO_ID_REGEX = Regex("""[?&]v=([A-Za-z0-9_-]{11})""")
        var initialized = false
    }
}

/** Minimal [Downloader] on top of [HttpURLConnection]. */
private object HttpDownloader : Downloader() {
    private const val USER_AGENT =
        "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

    override fun execute(request: Request): Response {
        val conn = URL(request.url()).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = request.httpMethod()
            conn.connectTimeout = 15_000
            conn.readTimeout = 30_000
            conn.instanceFollowRedirects = true
            conn.setRequestProperty("User-Agent", USER_AGENT)
            for ((name, values) in request.headers()) {
                conn.setRequestProperty(name, values.joinToString(", "))
            }
            request.dataToSend()?.let {
                conn.doOutput = true
                conn.outputStream.use { out -> out.write(it) }
            }

            val code = conn.responseCode
            if (code == 429) {
                throw ReCaptchaException("Rate limited / captcha requested", request.url())
            }
            val stream: InputStream? = if (code >= 400) conn.errorStream else conn.inputStream
            val body =
                stream
                    ?.let {
                        if (conn.contentEncoding.equals("gzip", ignoreCase = true)) {
                            GZIPInputStream(it)
                        } else it
                    }
                    ?.bufferedReader()
                    ?.use { it.readText() } ?: ""
            val headers = buildMap {
                for ((k, v) in conn.headerFields) if (k != null) put(k, v)
            }
            return Response(code, conn.responseMessage ?: "", headers, body, conn.url.toString())
        } finally {
            conn.disconnect()
        }
    }
}
