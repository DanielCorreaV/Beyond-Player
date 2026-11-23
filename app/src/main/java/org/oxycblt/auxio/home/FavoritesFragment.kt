/*
 * Copyright (c) 2026 Beyond Player
 * FavoritesFragment.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.home

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectableListListener
import org.oxycblt.auxio.list.recycler.SongViewHolder
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.music.youtube.YtArtistLookup
import org.oxycblt.auxio.music.youtube.YtCovers
import org.oxycblt.auxio.music.youtube.YtFavoriteArtists
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.Playlist

/**
 * The favorites page of Home: the Favoritas list (the one the heart of the player fills) and the
 * artists marked with the heart on their YouTube Music profile. Built in code.
 */
@AndroidEntryPoint
class FavoritesFragment : Fragment() {
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val rows = FavoritesAdapter()
    private var favorites: Playlist? = null
    private val lookedUp = seen

    private sealed interface Row {
        data class Header(val text: String) : Row

        data class Song(val song: org.oxycblt.musikr.Song, val playlist: Playlist) : Row

        data class SeeAll(val playlist: Playlist) : Row

        data class Artist(val name: String, val photo: String?) : Row

        data class Empty(val text: String) : Row
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val d = resources.displayMetrics.density
        rows.onSeeAll = { playlist -> detailModel.showPlaylist(playlist) }
        rows.songListener =
            object : SelectableListListener<org.oxycblt.musikr.Song> {
                override fun onClick(item: org.oxycblt.musikr.Song, viewHolder: RecyclerView.ViewHolder) {
                    val playlist = favorites ?: return
                    playbackModel.play(item, PlaySong.FromPlaylist(playlist))
                }

                override fun onOpenMenu(item: org.oxycblt.musikr.Song) {
                    val playlist = favorites ?: return
                    listModel.openMenu(R.menu.song, item, PlaySong.FromPlaylist(playlist))
                }

                override fun onSelect(item: org.oxycblt.musikr.Song) {}
            }
        rows.onArtist = ::openArtist
        rows.onArtistHold = ::askRemove
        val grid = GridLayoutManager(context, 3)
        grid.spanSizeLookup =
            object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (rows.isArtist(position)) 1 else 3
            }
        val recycler =
            RecyclerView(context).apply {
                id = R.id.home_favorites_recycler
                layoutManager = grid
                clipToPadding = false
                val space = (88 * d).toInt()
                setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), space)
                setOnApplyWindowInsetsListener { v, insets ->
                    v.updatePadding(bottom = space + insets.systemBarInsetsCompat.bottom)
                    insets
                }
                itemAnimator = null
                adapter = rows
            }
        viewLifecycleOwner.lifecycleScope.launch {
            homeModel.playlistList.collect { lists ->
                val name = getString(R.string.lbl_favorites_playlist)
                favorites = lists.firstOrNull { it.name.raw == name }
                rebuild()
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            YtFavoriteArtists.changes.collect { rebuild() }
        }
        return recycler
    }

    private fun rebuild() {
        val context = context ?: return
        val artists = YtFavoriteArtists.entries(context)
        val list = mutableListOf<Row>()
        list += Row.Header(getString(R.string.lbl_yt_fav_songs))
        val playlist = favorites
        if (playlist == null || playlist.songs.isEmpty()) {
            list += Row.Empty(getString(R.string.lbl_yt_no_fav_songs))
        } else {
            // A sample to play right away (the latest added); the whole list is one tap away.
            playlist.songs.takeLast(5).reversed().forEach { list += Row.Song(it, playlist) }
            list += Row.SeeAll(playlist)
        }
        list += Row.Header(getString(R.string.lbl_yt_favorites))
        if (artists.isEmpty()) list += Row.Empty(getString(R.string.lbl_yt_no_fav_artists))
        else artists.forEach { list += Row.Artist(it.name, it.photo) }
        rows.submit(list)
        // Artists marked before the photo was kept: look it up once.
        artists
            .filter { it.photo == null && lookedUp.add(it.name) }
            .forEach { fav ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val found =
                        withContext(Dispatchers.IO) {
                            try {
                                YtArtistLookup.find(fav.name)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                null
                            }
                        }
                    val url = found?.thumbnail ?: return@launch
                    YtFavoriteArtists.setPhoto(requireContext(), fav.name, url)
                    rebuild()
                }
            }
    }

    private fun openArtist(name: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            val found =
                withContext(Dispatchers.IO) {
                    try {
                        YtArtistLookup.find(name)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        null
                    }
                }
            if (found == null) {
                Toast.makeText(requireContext(), R.string.lbl_yt_artist_not_found, Toast.LENGTH_SHORT)
                    .show()
                return@launch
            }
            YtCollectionSheet.open(childFragmentManager, found)
        }
    }

    private fun askRemove(name: String) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(name)
            .setPositiveButton(R.string.lbl_yt_unfavorite) { _, _ ->
                YtFavoriteArtists.toggle(requireContext(), name)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private inner class FavoritesAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        private var items: List<Row> = emptyList()
        var onSeeAll: (Playlist) -> Unit = {}
        var songListener: SelectableListListener<org.oxycblt.musikr.Song>? = null
        var onArtist: (String) -> Unit = {}
        var onArtistHold: (String) -> Unit = {}

        fun submit(list: List<Row>) {
            items = list
            notifyDataSetChanged()
        }

        fun isArtist(position: Int) = items.getOrNull(position) is Row.Artist

        override fun getItemCount() = items.size

        override fun getItemViewType(position: Int) =
            when (items[position]) {
                is Row.Header -> 0
                is Row.Song -> 1
                is Row.Artist -> 2
                is Row.Empty -> 3
                is Row.SeeAll -> 4
            }

        private fun color(parent: View, attr: Int) = MaterialColors.getColor(parent, attr)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            val d = context.resources.displayMetrics.density
            val onSurface = color(parent, com.google.android.material.R.attr.colorOnSurface)
            val variant = color(parent, com.google.android.material.R.attr.colorOnSurfaceVariant)
            val surface = color(parent, com.google.android.material.R.attr.colorSurfaceVariant)
            val primary = color(parent, androidx.appcompat.R.attr.colorPrimary)
            fun matchWidth() =
                RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                )
            return when (viewType) {
                0 ->
                    object :
                        RecyclerView.ViewHolder(
                            TextView(context).apply {
                                layoutParams = matchWidth()
                                setTextAppearance(
                                    com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                                )
                                setTextColor(onSurface)
                                setPadding((8 * d).toInt(), (16 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                            }
                        ) {}
                1 -> SongViewHolder.from(parent)
                4 ->
                    object :
                        RecyclerView.ViewHolder(
                            MaterialButton(
                                    context,
                                    null,
                                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                                )
                                .apply {
                                    layoutParams =
                                        matchWidth().apply {
                                            setMargins((16 * d).toInt(), (4 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
                                        }
                                    setText(R.string.lbl_yt_see_full_list)
                                }
                        ) {}
                2 -> {
                    val photo =
                        ImageView(context).apply {
                            scaleType = ImageView.ScaleType.CENTER_CROP
                            setBackgroundColor(android.graphics.Color.argb(40, 128, 128, 128))
                            clipToOutline = true
                            outlineProvider =
                                object : ViewOutlineProvider() {
                                    override fun getOutline(view: View, outline: Outline) {
                                        outline.setOval(0, 0, view.width, view.height)
                                    }
                                }
                        }
                    val name =
                        TextView(context).apply {
                            setTextAppearance(
                                com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                            )
                            setTextColor(onSurface)
                            gravity = Gravity.CENTER
                            maxLines = 2
                            ellipsize = TextUtils.TruncateAt.END
                        }
                    val cell =
                        LinearLayout(context).apply {
                            layoutParams = matchWidth()
                            orientation = LinearLayout.VERTICAL
                            gravity = Gravity.CENTER_HORIZONTAL
                            val p = (8 * d).toInt()
                            setPadding(p, p, p, p)
                            isClickable = true
                            isFocusable = true
                            addView(photo, LinearLayout.LayoutParams((88 * d).toInt(), (88 * d).toInt()))
                            addView(
                                name,
                                LinearLayout.LayoutParams(
                                        ViewGroup.LayoutParams.MATCH_PARENT,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                    )
                                    .apply { topMargin = (6 * d).toInt() },
                            )
                        }
                    ArtistHolder(cell, photo, name)
                }
                else ->
                    object :
                        RecyclerView.ViewHolder(
                            TextView(context).apply {
                                layoutParams = matchWidth()
                                setTextAppearance(
                                    com.google.android.material.R.style.TextAppearance_Material3_BodyMedium
                                )
                                setTextColor(variant)
                                setPadding((8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt(), (8 * d).toInt())
                            }
                        ) {}
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val row = items[position]) {
                is Row.Header -> (holder.itemView as TextView).text = row.text
                is Row.Empty -> (holder.itemView as TextView).text = row.text
                is Row.Song -> songListener?.let { (holder as SongViewHolder).bind(row.song, it) }
                is Row.SeeAll -> holder.itemView.setOnClickListener { onSeeAll(row.playlist) }
                is Row.Artist -> {
                    val h = holder as ArtistHolder
                    h.name.text = row.name
                    val url = row.photo
                    h.photo.tag = url
                    h.photo.setImageDrawable(null)
                    if (!url.isNullOrBlank()) {
                        val context = h.photo.context
                        val cached = YtCovers.cached(context, url)
                        if (cached != null) {
                            h.photo.setImageURI(Uri.fromFile(cached))
                        } else {
                            YtCovers.load(context, url) { file ->
                                if (h.photo.tag == url) h.photo.setImageURI(Uri.fromFile(file))
                            }
                        }
                    }
                    h.itemView.setOnClickListener { onArtist(row.name) }
                    h.itemView.setOnLongClickListener {
                        onArtistHold(row.name)
                        true
                    }
                }
            }
        }
    }

    private class ArtistHolder(root: View, val photo: ImageView, val name: TextView) :
        RecyclerView.ViewHolder(root)

    private companion object {
        /** Artists already looked up in this run (the page is rebuilt every time Home comes back). */
        val seen = mutableSetOf<String>()
    }
}
