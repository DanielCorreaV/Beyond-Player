/*
 * Copyright (c) 2026 Beyond Player
 * YtCollectionSheet.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.home

import android.graphics.Outline
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BackportBottomSheetBehavior
import com.google.android.material.bottomsheet.BackportBottomSheetDialog
import com.google.android.material.bottomsheet.BackportBottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.youtube.ExploreItem
import org.oxycblt.auxio.music.youtube.ExploreKind
import org.oxycblt.auxio.music.youtube.YtCovers
import org.oxycblt.auxio.music.youtube.YtDownloadService
import org.oxycblt.auxio.music.youtube.YtArtistLookup
import org.oxycblt.auxio.music.youtube.YtExplore
import org.oxycblt.auxio.music.youtube.YtFavoriteArtists

import org.oxycblt.auxio.music.youtube.YtTrack
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackViewModel
import timber.log.Timber as L

/**
 * What opens when a card of Explore that is not a song is tapped: an album, playlist, mix, artist
 * or mood. Shows its songs (or its shelves) with "Reproducir" and "Descargar todo". Built in code.
 */
@AndroidEntryPoint
class YtCollectionSheet : BackportBottomSheetDialogFragment() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val rows = ExploreRowAdapter(::onItem, ::load)
    private lateinit var item: ExploreItem
    private var detail: YtExplore.ExploreDetail? = null
    private var list: RecyclerView? = null
    private var cover: ImageView? = null
    private var titleView: TextView? = null
    private var subtitleView: TextView? = null
    private var playButton: MaterialButton? = null
    private var downloadButton: MaterialButton? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val a = requireArguments()
        item =
            ExploreItem(
                kind = ExploreKind.valueOf(a.getString("kind") ?: "PLAYLIST"),
                title = a.getString("title").orEmpty(),
                subtitle = a.getString("subtitle").orEmpty(),
                artist = a.getString("artist").orEmpty(),
                thumbnail = a.getString("thumb"),
                browseId = a.getString("browse"),
                params = a.getString("params"),
                playlistId = a.getString("playlist"),
            )
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val d = resources.displayMetrics.density
        val onSurface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)
        val variant = MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurfaceVariant, 0)
        val surface = MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurfaceVariant, 0)

        val image =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
                clipToOutline = true
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            val r = if (item.kind == ExploreKind.ARTIST) view.width / 2f else 12 * d
                            outline.setRoundRect(0, 0, view.width, view.height, r)
                        }
                    }
            }
        val title =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                setTextColor(onSurface)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = item.title
            }
        val subtitle =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(variant)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
                text = item.artist.ifBlank { item.subtitle }
            }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_VERTICAL
                addView(title)
                addView(subtitle)
            }
        val primary = MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0)
        fun roundButton(icon: Int, label: Int) =
            ImageView(context).apply {
                setImageResource(icon)
                setColorFilter(primary)
                contentDescription = context.getString(label)
                val pad = (10 * d).toInt()
                setPadding(pad, pad, pad, pad)
                background =
                    android.graphics.drawable.GradientDrawable().apply {
                        shape = android.graphics.drawable.GradientDrawable.OVAL
                        setStroke((2 * d).toInt(), primary)
                        setColor(android.graphics.Color.TRANSPARENT)
                    }
                isClickable = true
                isFocusable = true
            }
        val heartButton = roundButton(R.drawable.ic_heart_24, R.string.lbl_yt_favorite)
        fun paintHeart(on: Boolean) =
            heartButton.setImageResource(
                if (on) R.drawable.ic_heart_filled_24 else R.drawable.ic_heart_24
            )
        paintHeart(YtFavoriteArtists.isFavorite(context, item.title))
        heartButton.setOnClickListener {
            paintHeart(YtFavoriteArtists.toggle(context, item.title, item.thumbnail))
        }
        // The favorites have their own section of Home: the profile only has the heart.

        val header =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val p = (16 * d).toInt()
                setPadding(p, p, p, (8 * d).toInt())
                addView(image, LinearLayout.LayoutParams((88 * d).toInt(), (88 * d).toInt()))
                addView(
                    texts,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (16 * d).toInt()
                    },
                )
                if (item.kind == ExploreKind.ARTIST) {
                    val size = (44 * d).toInt()
                    addView(
                        heartButton,
                        LinearLayout.LayoutParams(size, size).apply { marginStart = (8 * d).toInt() },
                    )
                    // (no second button)

                }
            }
        val play =
            MaterialButton(context).apply {
                setText(R.string.lbl_yt_play)
                visibility = View.GONE
                setOnClickListener { playFrom(0) }
            }
        val download =
            MaterialButton(
                    context,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                )
                .apply {
                    setText(R.string.lbl_yt_download_all)
                    visibility = View.GONE
                    setOnClickListener { downloadAll() }
                }
        val buttons =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                val p = (16 * d).toInt()
                setPadding(p, 0, p, (8 * d).toInt())
                addView(play)
                addView(
                    download,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { marginStart = (8 * d).toInt() },
                )
            }
        val recycler =
            RecyclerView(context).apply {
                layoutManager = LinearLayoutManager(context)
                clipToPadding = false
                setPadding(0, 0, 0, (24 * d).toInt())
                itemAnimator = null
                adapter = rows
            }
        cover = image
        titleView = title
        subtitleView = subtitle
        playButton = play
        downloadButton = download
        list = recycler
        bindImage(image, item.thumbnail)
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams =
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    (resources.displayMetrics.heightPixels * 0.85).toInt(),
                )
            addView(header)
            addView(buttons)
            addView(recycler, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        load()
    }

    override fun onStart() {
        super.onStart()
        (dialog as? BackportBottomSheetDialog)?.behavior?.apply {
            state = BackportBottomSheetBehavior.STATE_EXPANDED
            skipCollapsed = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        list = null
        cover = null
        titleView = null
        subtitleView = null
        playButton = null
        downloadButton = null
    }

    private fun load() {
        rows.submitList(listOf(ExploreRow.Loading))
        list?.adapter = rows
        val dump = File(requireContext().cacheDir, "yt_explore")
        viewLifecycleOwner.lifecycleScope.launch {
            val result =
                withContext(Dispatchers.IO) {
                    try {
                        YtExplore.detail(item, dump)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        L.w("Explore detail failed: ${e.message}")
                        null
                    }
                }
            if (result == null) rows.submitList(listOf(ExploreRow.Failed)) else show(result)
        }
    }

    private fun show(d: YtExplore.ExploreDetail) {
        detail = d
        titleView?.text = d.title.ifBlank { item.title }
        subtitleView?.text = d.artist.ifBlank { item.subtitle }
        if (item.thumbnail == null) cover?.let { bindImage(it, d.thumbnail) }
        val has = d.tracks.isNotEmpty()
        playButton?.visibility = if (has) View.VISIBLE else View.GONE
        downloadButton?.visibility = if (has) View.VISIBLE else View.GONE
        downloadButton?.setText(
            if (importableListId() != null) R.string.lbl_yt_save_list
            else R.string.lbl_yt_download_missing
        )
        if (d.sections.isNotEmpty()) {
            rows.submitList(
                d.sections.mapIndexed { n, s -> ExploreRow.Shelf("$n:${s.title}", s) }
            )
        } else {
            list?.adapter = TrackAdapter(d.tracks, d.thumbnail ?: item.thumbnail, ::playFrom)
        }
    }

    // (the favorites are listed in their own section of Home)


    private fun playFrom(index: Int) {
        val tracks = detail?.tracks ?: return
        val first = tracks.getOrNull(index) ?: return
        // The first song starts at once; the next 49 go to the queue.
        val following = tracks.drop(index + 1).take(49)
        val play = playbackModel
        musicModel.addYouTubeSong(first) { song ->
            play.play(song, PlaySong.ByItself)
            if (following.isNotEmpty()) {
                musicModel.queueYouTubeSongs(following) { next -> play.addToQueue(next) }
            }
        }
        dismissAllowingStateLoss()
    }

    /** The YouTube id of this list when it can be imported like a pasted link, else null. */
    private fun importableListId(): String? {
        if (item.kind != ExploreKind.PLAYLIST) return null
        val raw = item.browseId ?: item.playlistId ?: return null
        val id = if (raw.startsWith("VL")) raw.substring(2) else raw
        // Automatic mixes and radios are not real lists, they cannot be read as one.
        if (id.startsWith("FE") || (id.startsWith("RD") && !id.startsWith("RDCLAK"))) return null
        return id
    }

    private fun downloadAll() {
        val tracks = detail?.tracks ?: return
        val context = requireContext().applicationContext
        val listId = importableListId()
        if (listId != null) {
            // Same as importing the link: builds the list from the songs already on the phone and
            // the summary offers to download only the missing ones.
            musicModel.importFromYouTube("https://music.youtube.com/playlist?list=$listId")
            dismissAllowingStateLoss()
            return
        }
        musicModel.downloadMissing(tracks) { have, queued ->
            val message =
                if (queued == 0) context.getString(R.string.lbl_yt_have_all)
                else context.getString(R.string.fmt_yt_missing_result, have, queued)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /** A card of one of the shelves (artist and mood pages). */
    private fun onItem(tapped: ExploreItem, siblings: List<ExploreItem>) {
        val track = tapped.toTrack()
        if (tapped.kind != ExploreKind.SONG || track == null) {
            open(childFragmentManager, tapped)
            return
        }
        val following =
            siblings.dropWhile { it !== tapped }.drop(1)
                .filter { it.kind == ExploreKind.SONG }
                .mapNotNull { it.toTrack() }
                .take(9)
        val play = playbackModel
        musicModel.addYouTubeSong(track) { song ->
            play.play(song, PlaySong.ByItself)
            if (following.isNotEmpty()) {
                musicModel.queueYouTubeSongs(following) { next -> play.addToQueue(next) }
            }
        }
        dismissAllowingStateLoss()
    }

    private fun bindImage(image: ImageView, url: String?) {
        image.tag = url
        image.setImageDrawable(null)
        if (url.isNullOrBlank()) return
        val context = image.context
        val cached = YtCovers.cached(context, url)
        if (cached != null) {
            image.setImageURI(Uri.fromFile(cached))
        } else {
            YtCovers.load(context, url) { file ->
                if (image.tag == url) image.setImageURI(Uri.fromFile(file))
            }
        }
    }

    companion object {
        fun open(fm: FragmentManager, item: ExploreItem) {
            val sheet =
                YtCollectionSheet().apply {
                    arguments =
                        Bundle().apply {
                            putString("kind", item.kind.name)
                            putString("title", item.title)
                            putString("subtitle", item.subtitle)
                            putString("artist", item.artist)
                            putString("thumb", item.thumbnail)
                            putString("browse", item.browseId)
                            putString("params", item.params)
                            putString("playlist", item.playlistId)
                        }
                }
            sheet.show(fm, "yt_collection_" + (item.browseId ?: item.playlistId ?: item.title))
        }
    }
}

/** The song list of an album, playlist or mix: cover, title, artist and length per row. */
internal class TrackAdapter(
    private val tracks: List<YtTrack>,
    private val fallbackCover: String?,
    private val onTap: (Int) -> Unit,
) : RecyclerView.Adapter<TrackAdapter.Holder>() {
    override fun getItemCount() = tracks.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val d = context.resources.displayMetrics.density
        val onSurface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface)
        val variant = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val surface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurfaceVariant)
        val image =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
                clipToOutline = true
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setRoundRect(0, 0, view.width, view.height, 8 * d)
                        }
                    }
            }
        val title =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(onSurface)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        val artist =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(variant)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        val time =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(variant)
            }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(artist)
            }
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams =
                    RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                val h = (16 * d).toInt()
                val v = (8 * d).toInt()
                setPadding(h, v, h, v)
                val ripple = android.util.TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                    setBackgroundResource(ripple.resourceId)
                }
                addView(image, LinearLayout.LayoutParams((48 * d).toInt(), (48 * d).toInt()))
                addView(
                    texts,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (12 * d).toInt()
                        marginEnd = (12 * d).toInt()
                    },
                )
                addView(time)
            }
        return Holder(root, image, title, artist, time)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val t = tracks[position]
        holder.title.text = t.title
        holder.artist.text = t.artist
        holder.time.text = if (t.durationSec > 0) "%d:%02d".format(t.durationSec / 60, t.durationSec % 60) else ""
        val url = t.thumbnailUrl ?: fallbackCover
        holder.image.tag = url
        holder.image.setImageDrawable(null)
        if (!url.isNullOrBlank()) {
            val context = holder.image.context
            val cached = YtCovers.cached(context, url)
            if (cached != null) {
                holder.image.setImageURI(Uri.fromFile(cached))
            } else {
                YtCovers.load(context, url) { file ->
                    if (holder.image.tag == url) holder.image.setImageURI(Uri.fromFile(file))
                }
            }
        }
        holder.itemView.setOnClickListener { onTap(holder.bindingAdapterPosition) }
    }

    class Holder(
        root: View,
        val image: ImageView,
        val title: TextView,
        val artist: TextView,
        val time: TextView,
    ) : RecyclerView.ViewHolder(root)
}
