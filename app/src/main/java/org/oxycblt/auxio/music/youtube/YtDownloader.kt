/*
 * Copyright (c) 2026 Beyond Player
 * YtDownloader.kt is part of Beyond Player, a fork of Auxio.
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

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import timber.log.Timber as L

/**
 * Downloads a resolved [YtAudio] as a real audio file in Music/BeyondPlayer, where the normal
 * library scan picks it up like any other file on the phone.
 *
 * The song is first fetched into the app's cache, tagged there (title, artist, album, year, cover
 * and our YouTube id, see [Mp4Tagger]) and only then moved into the public folder, so the library
 * never sees an untagged or half-written file.
 *
 * Android 10+: goes through MediaStore (no storage permission needed for our own files). Android 9
 * and below: plain file in the public Music folder, then handed to the media scanner.
 */
class YtDownloader(context: Context) {
    private val context = context.applicationContext

    /** Result of a download: where the file is, and whether it already existed. */
    data class Saved(
        val uri: Uri,
        val displayName: String,
        val bytes: Long,
        val existed: Boolean,
        val tagged: Boolean,
        val hasCover: Boolean,
    )

    /** Blocking. [onProgress] gets (bytesDone, bytesTotal or -1). */
    fun download(
        track: YtTrack,
        audio: YtAudio,
        onProgress: (Long, Long) -> Unit = { _, _ -> },
    ): Saved {
        val name = fileName(track, audio.extension)
        existing(track, audio.extension)?.let { return it }

        val cacheDir = File(context.cacheDir, "yt").apply { mkdirs() }
        val temp = File(cacheDir, "${track.videoId}.${audio.extension}")
        try {
            temp.outputStream().use { fetch(audio.url, it, onProgress) }

            var tagged = false
            var hasCover = false
            if (audio.extension.equals("m4a", ignoreCase = true)) {
                val cover = (track.thumbnailUrl ?: audio.thumbnailUrl)?.let { runCatching { fetchCover(it) }.getOrNull() }
                hasCover = cover != null
                try {
                    Mp4Tagger.write(
                        temp,
                        Mp4Tagger.Meta(
                            title = track.title,
                            artist = track.artist,
                            album = audio.album,
                            year = audio.year,
                            videoId = track.videoId,
                            cover = cover,
                        ),
                    )
                    tagged = true
                } catch (e: Exception) {
                    // A song without tags is still a song: keep it and say so in the log.
                    L.e(e, "YT tagging failed for ${track.videoId}")
                    hasCover = false
                }
            } else {
                L.w("YT ${track.videoId}: ${audio.extension} cannot be tagged yet")
            }

            val saved =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveViaMediaStore(name, audio, temp)
                else saveLegacy(name, audio, temp)
            YtDownloadIndex.record(context, track.videoId, name)
            return saved.copy(tagged = tagged, hasCover = hasCover)
        } finally {
            temp.delete()
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun audioCollection(): Uri =
        MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    /**
     * The file this song was already saved as, if it is still there. Checks the id we remember first
     * (a playlist and a search can name the same song differently), then the plain file name.
     */
    fun existing(track: YtTrack, extension: String = "m4a"): Saved? {
        YtDownloadIndex.get(context, track.videoId)?.let { remembered ->
            findExisting(remembered)?.let { return it }
        }
        val name = fileName(track, extension)
        return findExisting(name)?.also { YtDownloadIndex.record(context, track.videoId, name) }
    }

    private fun findExisting(name: String): Saved? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val collection = audioCollection()
            context.contentResolver
                .query(
                    collection,
                    arrayOf(MediaStore.Audio.Media._ID, MediaStore.Audio.Media.SIZE),
                    "${MediaStore.Audio.Media.DISPLAY_NAME}=? AND " +
                        "${MediaStore.Audio.Media.RELATIVE_PATH} LIKE ?",
                    arrayOf(name, "$RELATIVE_DIR%"),
                    null,
                )
                ?.use {
                    if (it.moveToFirst()) {
                        val uri = ContentUris.withAppendedId(collection, it.getLong(0))
                        return Saved(uri, name, it.getLong(1), true, false, false)
                    }
                }
            return null
        }
        val target = File(legacyDir(), name)
        return if (target.exists() && target.length() > 0)
            Saved(Uri.fromFile(target), name, target.length(), true, false, false)
        else null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun saveViaMediaStore(name: String, audio: YtAudio, temp: File): Saved {
        val resolver = context.contentResolver
        val values =
            ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, audio.mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, RELATIVE_DIR)
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
        val uri =
            resolver.insert(audioCollection(), values) ?: throw YtException("MediaStore refused $name")
        try {
            resolver.openOutputStream(uri, "w")?.use { out -> temp.inputStream().use { it.copyTo(out) } }
                ?: throw YtException("Could not open $name for writing")
            resolver.update(
                uri,
                ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) },
                null,
                null,
            )
            L.d("YT saved $name (${temp.length()} bytes) -> $uri")
            return Saved(uri, name, temp.length(), false, false, false)
        } catch (e: Throwable) {
            // Never leave a half-written file behind.
            runCatching { resolver.delete(uri, null, null) }
            throw e
        }
    }

    @Suppress("DEPRECATION")
    private fun legacyDir() =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), SUB_DIR)

    private fun saveLegacy(name: String, audio: YtAudio, temp: File): Saved {
        val dir = legacyDir()
        if (!dir.isDirectory && !dir.mkdirs()) throw YtException("Cannot create $dir")
        val target = File(dir, name)
        val part = File(dir, "$name.part")
        try {
            temp.copyTo(part, overwrite = true)
            if (!part.renameTo(target)) throw YtException("Cannot rename $part")
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(audio.mimeType),
                null,
            )
            L.d("YT saved $name (${target.length()} bytes) -> $target")
            return Saved(Uri.fromFile(target), name, target.length(), false, false, false)
        } catch (e: Throwable) {
            part.delete()
            throw e
        }
    }

    /**
     * Download the cover as a square JPEG. YouTube Music covers come from googleusercontent and
     * accept a size in the URL, so ask for a big one; anything else is used as served and
     * center-cropped to a square.
     */
    private fun fetchCover(rawUrl: String): ByteArray? {
        val url = SIZE_SUFFIX.replace(rawUrl, "=w$COVER_PX-h$COVER_PX-l90-rj")
        val conn = URL(url).openConnection() as HttpURLConnection
        val bytes =
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 15_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                if (conn.responseCode != 200) return null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        val side = minOf(bitmap.width, bitmap.height)
        val square =
            if (bitmap.width == bitmap.height) bitmap
            else
                Bitmap.createBitmap(
                    bitmap,
                    (bitmap.width - side) / 2,
                    (bitmap.height - side) / 2,
                    side,
                    side,
                )
        val out = ByteArrayOutputStream()
        square.compress(Bitmap.CompressFormat.JPEG, 92, out)
        return out.toByteArray().takeIf { it.isNotEmpty() }
    }

    /**
     * Fetch [url] in ranged chunks. YouTube throttles or resets long single requests, but serves
     * 1 MiB ranges at full speed.
     */
    private fun fetch(url: String, out: OutputStream, onProgress: (Long, Long) -> Unit): Long {
        var offset = 0L
        var total = -1L
        val buffer = ByteArray(64 * 1024)
        while (total < 0 || offset < total) {
            val conn = URL(url).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", USER_AGENT)
                conn.setRequestProperty("Range", "bytes=$offset-${offset + CHUNK - 1}")
                val code = conn.responseCode
                if (code != 206 && code != 200) throw YtException("Download failed: HTTP $code")
                if (total < 0) {
                    total =
                        conn.getHeaderField("Content-Range")?.substringAfter('/')?.toLongOrNull()
                            ?: conn.contentLengthLong
                }
                var got = 0L
                conn.inputStream.use { input ->
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        got += n
                        onProgress(offset + got, total)
                    }
                }
                offset += got
                // Server ignored the range and sent everything, or sent nothing: stop either way.
                if (code == 200) break
                if (got == 0L) throw YtException("Download stalled at $offset of $total")
            } finally {
                conn.disconnect()
            }
        }
        return offset
    }

    private fun fileName(track: YtTrack, ext: String): String {
        val raw = if (track.artist.isBlank()) track.title else "${track.artist} - ${track.title}"
        val clean = raw.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "_").trim().take(120)
        return "${clean.ifBlank { track.videoId }}.$ext"
    }

    private companion object {
        const val SUB_DIR = "BeyondPlayer"
        const val RELATIVE_DIR = "Music/BeyondPlayer/"
        const val CHUNK = 1L * 1024 * 1024
        const val COVER_PX = 600
        val SIZE_SUFFIX = Regex("""=w\d+-h\d+[^/?]*$""")
        const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"
    }
}
