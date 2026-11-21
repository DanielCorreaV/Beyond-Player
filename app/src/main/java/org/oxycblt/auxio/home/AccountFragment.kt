/*
 * Copyright (c) 2026 Beyond Player
 * AccountFragment.kt is part of Beyond Player, a fork of Auxio.
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
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.updatePadding
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ConcatAdapter
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import dagger.hilt.android.AndroidEntryPoint
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.youtube.ExploreItem
import org.oxycblt.auxio.music.youtube.ExploreKind
import org.oxycblt.auxio.music.youtube.YtCovers
import org.oxycblt.auxio.music.youtube.YtExplore
import org.oxycblt.auxio.music.youtube.YtSession
import org.oxycblt.auxio.music.youtube.YtTrack
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.util.systemBarInsetsCompat
import timber.log.Timber as L

/**
 * The account page of Home: sign in, and once signed in your own YouTube Music: "Para ti" (your
 * home: mixes, listen again, recommendations), "Me gusta", "Listas" (what you saved) and
 * "Historial". Built in code. Loads when the tab is first shown.
 */
@AndroidEntryPoint
class AccountFragment : Fragment() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val shelves = ExploreRowAdapter(::onItemClick, ::reload)
    private var concat: ConcatAdapter? = null
    private var header: AccountHeaderAdapter? = null
    private var content: RecyclerView.Adapter<*>? = null
    private var tracks: List<YtTrack> = emptyList()
    private var selected = 0
    private var started = false
    private var job: Job? = null

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val d = resources.displayMetrics.density
        val head =
            AccountHeaderAdapter(
                onChip = { index ->
                    selected = index
                    reload()
                },
                onAccount = ::onAccountTap,
                onPlay = { playList(tracks, 0) },
                onDownload = ::downloadLiked,
            )
        header = head
        val all = ConcatAdapter(head)
        concat = all
        return RecyclerView(context).apply {
            id = R.id.home_account_recycler
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            val space = (88 * d).toInt()
            setPadding(0, (8 * d).toInt(), 0, space)
            setOnApplyWindowInsetsListener { v, insets ->
                v.updatePadding(bottom = space + insets.systemBarInsetsCompat.bottom)
                insets
            }
            itemAnimator = null
            adapter = all
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Signing in or out changes everything on this page.
        viewLifecycleOwner.lifecycleScope.launch {
            var first = true
            YtSession.loggedIn.collect {
                if (first) {
                    first = false
                } else if (started) {
                    YtExplore.clearCache()
                    selected = 0
                    reload()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Only the tab on screen is resumed, so nothing is requested until the user opens it.
        if (!started) {
            started = true
            YtSession.restore(requireContext())
            reload()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        job?.cancel()
        started = false
        concat = null
        header = null
        content = null
    }

    private fun setContent(next: RecyclerView.Adapter<*>?) {
        val all = concat ?: return
        content?.let { all.removeAdapter(it) }
        content = next
        next?.let { all.addAdapter(it) }
    }

    private fun reload() {
        job?.cancel()
        val loggedIn = YtSession.isLoggedIn
        tracks = emptyList()
        header?.update(loggedIn, selected, false)
        if (!loggedIn) {
            setContent(null)
            return
        }
        val dump = File(requireContext().cacheDir, "yt_explore")
        when (selected) {
            0 -> loadShelves(dump)
            1 -> loadLiked(dump)
            2 -> loadLists(dump)
            else -> loadHistory(dump)
        }
    }

    private fun loadShelves(dump: File) {
        setContent(shelves)
        shelves.submitList(listOf(ExploreRow.Loading))
        job =
            viewLifecycleOwner.lifecycleScope.launch {
                val sections =
                    withContext(Dispatchers.IO) {
                        try {
                            YtExplore.page("FEmusic_home", dump, 40)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            L.w("Account home failed: ${e.message}")
                            null
                        }
                    }
                when {
                    sections == null -> shelves.submitList(listOf(ExploreRow.Failed))
                    sections.isEmpty() -> setContent(MessageAdapter(getString(R.string.lbl_yt_empty)))
                    else ->
                        shelves.submitList(
                            sections.take(12).mapIndexed { n, s ->
                                ExploreRow.Shelf("home:$n:${s.title}", s)
                            }
                        )
                }
            }
    }

    private fun loadHistory(dump: File) {
        setContent(shelves)
        shelves.submitList(listOf(ExploreRow.Loading))
        job =
            viewLifecycleOwner.lifecycleScope.launch {
                val list =
                    withContext(Dispatchers.IO) {
                        try {
                            YtExplore.page("FEmusic_history", dump, 300)
                                .flatMap { it.items }
                                .filter { it.kind == ExploreKind.SONG }
                                .mapNotNull { it.toTrack() }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            L.w("Account history failed: ${e.message}")
                            null
                        }
                    }
                when {
                    list == null -> shelves.submitList(listOf(ExploreRow.Failed))
                    list.isEmpty() -> setContent(MessageAdapter(getString(R.string.lbl_yt_empty)))
                    else -> setContent(TrackAdapter(list, null) { index -> playList(list, index) })
                }
            }
    }

    private fun loadLiked(dump: File) {
        setContent(shelves)
        shelves.submitList(listOf(ExploreRow.Loading))
        job =
            viewLifecycleOwner.lifecycleScope.launch {
                val liked =
                    ExploreItem(ExploreKind.PLAYLIST, "Me gusta", "", "", null, browseId = "VLLM")
                val detail =
                    withContext(Dispatchers.IO) {
                        try {
                            YtExplore.detail(liked, dump)
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            L.w("Account liked failed: ${e.message}")
                            null
                        }
                    }
                if (detail == null) {
                    shelves.submitList(listOf(ExploreRow.Failed))
                    return@launch
                }
                tracks = detail.tracks
                header?.update(true, selected, tracks.isNotEmpty())
                if (tracks.isEmpty()) {
                    setContent(MessageAdapter(getString(R.string.lbl_yt_empty)))
                } else {
                    val list = tracks
                    setContent(TrackAdapter(list, null) { index -> playList(list, index) })
                }
            }
    }

    private fun loadLists(dump: File) {
        setContent(shelves)
        shelves.submitList(listOf(ExploreRow.Loading))
        job =
            viewLifecycleOwner.lifecycleScope.launch {
                val items =
                    withContext(Dispatchers.IO) {
                        try {
                            val saved = YtExplore.page("FEmusic_liked_playlists", dump, 300)
                            // Saved albums are a separate page; not having it is not an error.
                            val albums =
                                try {
                                    YtExplore.page("FEmusic_liked_albums", dump, 300)
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    emptyList()
                                }
                            (saved + albums).flatMap { it.items }.distinctBy {
                                it.browseId ?: it.playlistId ?: it.title
                            }
                        } catch (e: CancellationException) {
                            throw e
                        } catch (e: Exception) {
                            L.w("Account lists failed: ${e.message}")
                            null
                        }
                    }
                when {
                    items == null -> shelves.submitList(listOf(ExploreRow.Failed))
                    items.isEmpty() -> setContent(MessageAdapter(getString(R.string.lbl_yt_empty)))
                    else ->
                        setContent(
                            ItemListAdapter(items) { tapped ->
                                YtCollectionSheet.open(childFragmentManager, tapped)
                            }
                        )
                }
            }
    }

    private fun onAccountTap() {
        if (YtSession.isLoggedIn) {
            YtSession.logout(requireContext())
        } else {
            YtLoginSheet().show(childFragmentManager, "yt_login")
        }
    }

    private fun downloadLiked() {
        val list = tracks
        if (list.isEmpty()) return
        val context = requireContext().applicationContext
        musicModel.downloadMissing(list) { have, queued ->
            val message =
                if (queued == 0) context.getString(R.string.lbl_yt_have_all)
                else context.getString(R.string.fmt_yt_missing_result, have, queued)
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

    /** Plays [list] from [index]: that song at once, the next 49 in the queue. */
    private fun playList(list: List<YtTrack>, index: Int) {
        val first = list.getOrNull(index) ?: return
        val following = list.drop(index + 1).take(49)
        val play = playbackModel
        musicModel.addYouTubeSong(first) { song ->
            play.play(song, PlaySong.ByItself)
            if (following.isNotEmpty()) {
                musicModel.queueYouTubeSongs(following) { next -> play.addToQueue(next) }
            }
        }
    }

    /** A card of one of the shelves. */
    private fun onItemClick(item: ExploreItem, siblings: List<ExploreItem>) {
        val track = item.toTrack()
        if (item.kind != ExploreKind.SONG || track == null) {
            YtCollectionSheet.open(childFragmentManager, item)
            return
        }
        val following =
            siblings.dropWhile { it !== item }.drop(1)
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
    }
}

/** The top of the account page: the account card, the section chips and the liked-songs buttons. */
private class AccountHeaderAdapter(
    private val onChip: (Int) -> Unit,
    private val onAccount: () -> Unit,
    private val onPlay: () -> Unit,
    private val onDownload: () -> Unit,
) : RecyclerView.Adapter<AccountHeaderAdapter.Holder>() {
    private var loggedIn = false
    private var selected = 0
    private var hasTracks = false

    fun update(loggedIn: Boolean, selected: Int, hasTracks: Boolean) {
        this.loggedIn = loggedIn
        this.selected = selected
        this.hasTracks = hasTracks
        notifyItemChanged(0)
    }

    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val d = context.resources.displayMetrics.density
        val onSurface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface)
        val variant = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val surface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurfaceVariant)
        val title =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleLarge)
                setTextColor(onSurface)
            }
        val text =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(variant)
            }
        val button = MaterialButton(context).apply { setOnClickListener { onAccount() } }
        val card =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val p = (16 * d).toInt()
                setPadding(p, p, p, p)
                background =
                    GradientDrawable().apply {
                        cornerRadius = 20 * d
                        setColor(surface)
                    }
                addView(title)
                addView(
                    text,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = (4 * d).toInt() },
                )
                addView(
                    button,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = (12 * d).toInt() },
                )
            }
        val labels =
            listOf(
                R.string.lbl_yt_chip_foryou,
                R.string.lbl_yt_chip_liked,
                R.string.lbl_yt_chip_lists,
                R.string.lbl_yt_chip_history,
            )
        val group =
            ChipGroup(context).apply {
                isSingleLine = true
                isSingleSelection = true
                isSelectionRequired = true
            }
        val chips =
            labels.mapIndexed { index, label ->
                Chip(context).apply {
                    setText(label)
                    isCheckable = true
                    setOnClickListener { onChip(index) }
                    group.addView(this)
                }
            }
        val scroll =
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(group)
            }
        val play =
            MaterialButton(context).apply {
                setText(R.string.lbl_yt_play)
                setOnClickListener { onPlay() }
            }
        val download =
            MaterialButton(
                    context,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                )
                .apply {
                    setText(R.string.lbl_yt_download_missing)
                    setOnClickListener { onDownload() }
                }
        val actions =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
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
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
                val p = (16 * d).toInt()
                setPadding(p, (8 * d).toInt(), p, (8 * d).toInt())
                addView(card)
                addView(
                    scroll,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = (12 * d).toInt() },
                )
                addView(
                    actions,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = (8 * d).toInt() },
                )
            }
        return Holder(root, title, text, button, scroll, chips, actions)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        holder.title.setText(if (loggedIn) R.string.lbl_yt_acc_title_in else R.string.lbl_yt_acc_title_out)
        holder.text.setText(if (loggedIn) R.string.lbl_yt_acc_text_in else R.string.lbl_yt_acc_text_out)
        holder.button.setText(if (loggedIn) R.string.lbl_yt_logout else R.string.lbl_yt_login)
        holder.chipsScroll.visibility = if (loggedIn) View.VISIBLE else View.GONE
        holder.chips.forEachIndexed { index, chip -> chip.isChecked = index == selected }
        holder.actions.visibility =
            if (loggedIn && selected == 1 && hasTracks) View.VISIBLE else View.GONE
    }

    class Holder(
        root: View,
        val title: TextView,
        val text: TextView,
        val button: MaterialButton,
        val chipsScroll: HorizontalScrollView,
        val chips: List<Chip>,
        val actions: LinearLayout,
    ) : RecyclerView.ViewHolder(root)
}

/** One line of text, for an empty section. */
private class MessageAdapter(private val message: String) :
    RecyclerView.Adapter<MessageAdapter.Holder>() {
    override fun getItemCount() = 1

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val d = context.resources.displayMetrics.density
        val view =
            TextView(context).apply {
                gravity = Gravity.CENTER
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyLarge)
                setTextColor(MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant))
                val p = (32 * d).toInt()
                setPadding(p, p, p, p)
                layoutParams =
                    RecyclerView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    )
            }
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        (holder.itemView as TextView).text = message
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view)
}

/** What you saved (playlists, albums, artists), one per row with its cover. */
private class ItemListAdapter(
    private val items: List<ExploreItem>,
    private val onTap: (ExploreItem) -> Unit,
) : RecyclerView.Adapter<ItemListAdapter.Holder>() {
    override fun getItemCount() = items.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val d = context.resources.displayMetrics.density
        val onSurface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface)
        val variant = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val surface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurfaceVariant)
        val holderRound = BooleanArray(1)
        val image =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
                clipToOutline = true
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            val r = if (holderRound[0]) view.width / 2f else 8 * d
                            outline.setRoundRect(0, 0, view.width, view.height, r)
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
        val subtitle =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(variant)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        val texts =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(title)
                addView(subtitle)
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
                addView(image, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
                addView(
                    texts,
                    LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginStart = (12 * d).toInt()
                    },
                )
            }
        return Holder(root, image, title, subtitle, holderRound)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.title.text = item.title
        holder.subtitle.text = item.subtitle
        holder.round[0] = item.kind == ExploreKind.ARTIST
        holder.image.invalidateOutline()
        val url = item.thumbnail
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
        holder.itemView.setOnClickListener { onTap(item) }
    }

    class Holder(
        root: View,
        val image: ImageView,
        val title: TextView,
        val subtitle: TextView,
        val round: BooleanArray,
    ) : RecyclerView.ViewHolder(root)
}
