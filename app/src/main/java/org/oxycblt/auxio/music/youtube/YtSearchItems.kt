/*
 * Copyright (c) 2026 Beyond Player
 * YtSearchItems.kt is part of Beyond Player, a fork of Auxio.
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
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.content.res.AppCompatResources
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.ItemSongBinding
import org.oxycblt.auxio.list.BasicHeader
import org.oxycblt.auxio.list.PlainDivider

/** Marks a row of a streamed song (not on the phone) with the small cloud before the text. */
fun android.widget.TextView.showStreamingCloud(song: org.oxycblt.musikr.Song) {
    if (song.uri.scheme != "yt") {
        setCompoundDrawablesRelative(null, null, null, null)
        return
    }
    AppCompatResources.getDrawable(context, R.drawable.ic_yt_cloud_24)?.mutate()?.let {
        val size = (14 * resources.displayMetrics.density).toInt()
        it.setBounds(0, 0, size, size)
        setCompoundDrawablesRelative(it, null, null, null)
        compoundDrawablePadding = size / 2
    }
}

/** The header that opens the YouTube Music part of the search results. */
val YT_HEADER = BasicHeader(R.string.lbl_youtube_music)

/** A YouTube Music song in the search results. Not in the library: tapping it queues a download. */
data class YtResultItem(val track: YtTrack)

/** The line under the YouTube header while its results are loading (or when the search failed). */
data class YtStatusItem(val failed: Boolean)

/** True for everything that belongs to the YouTube part of the results (header, divider, rows). */
fun isYtSearchItem(item: Any): Boolean =
    item is YtResultItem || item is YtStatusItem || item == YT_HEADER || (item is PlainDivider && item.anchor == YT_HEADER)

/**
 * A row for a [YtResultItem]. It reuses the song row layout so it follows every style (Classic,
 * Neon, Vinyl, Pop), and a small cloud before the artist marks it as streaming/not on the phone.
 */
class YtResultViewHolder private constructor(private val binding: ItemSongBinding) :
    RecyclerView.ViewHolder(binding.root) {
    fun bind(item: YtResultItem, onClick: (YtTrack) -> Unit) {
        val context = binding.root.context
        val track = item.track
        binding.songName.text = track.title
        binding.songInfo.text =
            if (track.artist.isBlank()) formatDuration(track.durationSec)
            else "${track.artist} · ${formatDuration(track.durationSec)}"

        AppCompatResources.getDrawable(context, R.drawable.ic_yt_cloud_24)?.mutate()?.let {
            val size = (14 * context.resources.displayMetrics.density).toInt()
            it.setBounds(0, 0, size, size)
            binding.songInfo.setCompoundDrawablesRelative(it, null, null, null)
            binding.songInfo.compoundDrawablePadding = size / 2
        }

        // Tap plays it by streaming; the "more" button holds the download.
        binding.songMenu.visibility = View.VISIBLE
        binding.songMenu.setOnClickListener { anchor ->
            android.widget.PopupMenu(context, anchor).apply {
                menu.add(0, 1, 0, context.getString(R.string.lbl_yt_download))
                setOnMenuItemClickListener {
                    YtDownloadDialog.askNotificationPermission(context)
                    YtDownloadService.enqueue(context, track)
                    android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.fmt_yt_queued, track.title),
                            android.widget.Toast.LENGTH_SHORT,
                        )
                        .show()
                    true
                }
                show()
            }
        }

        binding.root.setOnClickListener { onClick(track) }

        val cover = binding.songAlbumCover
        val url = track.thumbnailUrl
        cover.tag = url
        val file = url?.let { YtCovers.cached(context, it) }
        cover.bindRemote(file, track.title)
        if (file == null && url != null) {
            YtCovers.load(context, url) { loaded ->
                // The row may have been recycled for another song while this was downloading.
                if (cover.tag == url) cover.bindRemote(loaded, track.title)
            }
        }
    }

    private fun formatDuration(sec: Long) = if (sec < 0) "" else "%d:%02d".format(sec / 60, sec % 60)

    companion object {
        const val VIEW_TYPE = 0xA0F0

        fun from(parent: ViewGroup) =
            YtResultViewHolder(
                ItemSongBinding.inflate(LayoutInflater.from(parent.context), parent, false)
            )
    }
}


/** The row for a [YtStatusItem]: a small spinner and a line of text, built in code. */
class YtStatusViewHolder private constructor(
    root: android.widget.LinearLayout,
    private val spinner: com.google.android.material.progressindicator.CircularProgressIndicator,
    private val text: android.widget.TextView,
) : RecyclerView.ViewHolder(root) {
    fun bind(item: YtStatusItem) {
        spinner.visibility = if (item.failed) View.GONE else View.VISIBLE
        text.setText(
            if (item.failed) R.string.lbl_yt_search_failed else R.string.lbl_yt_searching
        )
    }

    companion object {
        const val VIEW_TYPE = 0xA0F1

        fun from(parent: ViewGroup): YtStatusViewHolder {
            val context = parent.context
            val d = context.resources.displayMetrics.density
            val spinner =
                com.google.android.material.progressindicator.CircularProgressIndicator(context)
                    .apply {
                        isIndeterminate = true
                        indicatorSize = (20 * d).toInt()
                        trackThickness = (2 * d).toInt()
                    }
            val text =
                android.widget.TextView(context).apply {
                    setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                    )
                    setTextColor(
                        com.google.android.material.color.MaterialColors.getColor(
                            this,
                            com.google.android.material.R.attr.colorOnSurfaceVariant,
                        )
                    )
                }
            val root =
                android.widget.LinearLayout(context).apply {
                    orientation = android.widget.LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    val h = (16 * d).toInt()
                    val v = (14 * d).toInt()
                    setPadding(h, v, h, v)
                    layoutParams =
                        RecyclerView.LayoutParams(
                            RecyclerView.LayoutParams.MATCH_PARENT,
                            RecyclerView.LayoutParams.WRAP_CONTENT,
                        )
                    addView(spinner)
                    addView(
                        text,
                        android.widget.LinearLayout.LayoutParams(
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                            )
                            .apply { marginStart = (12 * d).toInt() },
                    )
                }
            return YtStatusViewHolder(root, spinner, text)
        }
    }
}

/**
 * Small disk cache for the covers of YouTube results. Coil here has no network support, so the
 * image is downloaded to a file and the cover view is given the file.
 */
object YtCovers {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val main = Handler(Looper.getMainLooper())
    private val loading = ConcurrentHashMap<String, Boolean>()
    private val SIZE_SUFFIX = Regex("""=w\d+-h\d+[^/?]*$""")

    /** Ask for a list-sized image when the URL takes a size (YouTube Music covers do). */
    private fun sized(url: String) = SIZE_SUFFIX.replace(url, "=w256-h256-l90-rj")

    private fun fileFor(context: Context, url: String): File {
        val dir = File(context.applicationContext.cacheDir, "yt_covers").apply { mkdirs() }
        val hash =
            MessageDigest.getInstance("SHA-1").digest(sized(url).toByteArray()).joinToString("") {
                "%02x".format(it)
            }
        return File(dir, "$hash.img")
    }

    fun cached(context: Context, url: String): File? =
        fileFor(context, url).takeIf { it.length() > 0 }

    /** Download [url] if needed and call [onReady] on the main thread with the file. */
    fun load(context: Context, url: String, onReady: (File) -> Unit) {
        val target = fileFor(context, url)
        if (loading.putIfAbsent(target.path, true) != null) return
        scope.launch {
            try {
                val conn = URL(sized(url)).openConnection() as HttpURLConnection
                try {
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    if (conn.responseCode != 200) return@launch
                    val tmp = File(target.path + ".part")
                    conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                    if (!tmp.renameTo(target)) {
                        tmp.delete()
                        return@launch
                    }
                } finally {
                    conn.disconnect()
                }
                main.post { onReady(target) }
            } catch (e: Exception) {
                // No cover is not an error worth more than the placeholder.
            } finally {
                loading.remove(target.path)
            }
        }
    }
}
