/*
 * Copyright (c) 2026 Beyond Player
 * ExploreFragment.kt is part of Beyond Player, a fork of Auxio.
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
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.core.view.updatePadding
import org.oxycblt.auxio.util.systemBarInsetsCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
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
import org.oxycblt.auxio.music.youtube.ExploreGroup
import org.oxycblt.auxio.music.youtube.ExploreItem
import org.oxycblt.auxio.music.youtube.ExploreKind
import org.oxycblt.auxio.music.youtube.ExploreSection
import org.oxycblt.auxio.music.youtube.YtCovers
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.util.showToast
import timber.log.Timber as L

/**
 * The "Explorar" page of Home: new releases, moods and genres, mixes and charts from YouTube Music,
 * built in code (no layout files). Loads when the tab is first shown. Tapping a song plays it by
 * streaming and queues the songs that follow it in the same shelf.
 */
@AndroidEntryPoint
class ExploreFragment : Fragment() {
    private val musicModel: MusicViewModel by activityViewModels()
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private var rows: ExploreRowAdapter? = null
    private var started = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val d = resources.displayMetrics.density
        val adapter = ExploreRowAdapter(::onItemClick, ::load)
        rows = adapter
        return RecyclerView(context).apply {
            id = R.id.home_explore_recycler
            layoutManager = LinearLayoutManager(context)
            clipToPadding = false
            // The same bottom space as the other lists, so the mini player never covers the end.
            val space = (88 * d).toInt()
            setPadding(0, (8 * d).toInt(), 0, space)
            setOnApplyWindowInsetsListener { v, insets ->
                v.updatePadding(bottom = space + insets.systemBarInsetsCompat.bottom)
                insets
            }
            itemAnimator = null
            this.adapter = adapter
        }
    }

    override fun onResume() {
        super.onResume()
        // Only the tab on screen is resumed, so nothing is requested until the user opens it.
        if (!started) {
            started = true
            load()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        started = false
        rows = null
    }

    private fun load() {
        val groups = ExploreGroup.values()
        val slots = arrayOfNulls<List<ExploreSection>>(groups.size)
        var pending = groups.size
        rows?.submitList(listOf(ExploreRow.Loading))
        val dump = File(requireContext().cacheDir, "yt_explore")
        groups.forEachIndexed { index, group ->
            viewLifecycleOwner.lifecycleScope.launch {
                slots[index] =
                    withContext(Dispatchers.IO) {
                        try {
                            org.oxycblt.auxio.music.youtube.YtExplore.load(group, dump)
                        } catch (e: Exception) {
                            if (e is CancellationException) throw e
                            L.w("Explore ${group.name} failed: ${e.message}")
                            emptyList()
                        }
                    }
                pending--
                val shelves =
                    groups.indices.flatMap { i ->
                        slots[i].orEmpty().mapIndexed { n, s -> ExploreRow.Shelf("$i:$n:${s.title}", s) }
                    }
                rows?.submitList(
                    when {
                        pending > 0 -> shelves + ExploreRow.Loading
                        shelves.isEmpty() -> listOf(ExploreRow.Failed)
                        else -> shelves
                    }
                )
            }
        }
    }

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

internal sealed interface ExploreRow {
    data class Shelf(val key: String, val section: ExploreSection) : ExploreRow

    object Loading : ExploreRow

    object Failed : ExploreRow
}

internal class ExploreRowAdapter(
    private val onItem: (ExploreItem, List<ExploreItem>) -> Unit,
    private val onRetry: () -> Unit,
) : ListAdapter<ExploreRow, RecyclerView.ViewHolder>(DIFF) {
    private val pool = RecyclerView.RecycledViewPool()

    override fun getItemViewType(position: Int) =
        when (getItem(position)) {
            is ExploreRow.Shelf -> 0
            ExploreRow.Loading -> 1
            ExploreRow.Failed -> 2
        }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val context = parent.context
        val d = context.resources.displayMetrics.density
        return when (viewType) {
            0 -> {
                val title =
                    TextView(context).apply {
                        setTextAppearance(
                            com.google.android.material.R.style.TextAppearance_Material3_TitleLarge
                        )
                        setPadding((16 * d).toInt(), (16 * d).toInt(), (16 * d).toInt(), (8 * d).toInt())
                    }
                val inner =
                    RecyclerView(context).apply {
                        clipToPadding = false
                        setPadding((12 * d).toInt(), 0, (12 * d).toInt(), 0)
                        setRecycledViewPool(pool)
                        isNestedScrollingEnabled = false
                    }
                val root =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams =
                            RecyclerView.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                        addView(
                            title,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                        addView(
                            inner,
                            LinearLayout.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            ),
                        )
                    }
                ShelfHolder(root, title, inner, ExploreCardAdapter(onItem))
            }
            1 -> {
                val bar =
                    ProgressBar(context).apply { isIndeterminate = true }
                val root =
                    LinearLayout(context).apply {
                        gravity = Gravity.CENTER
                        layoutParams =
                            RecyclerView.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                (96 * d).toInt(),
                            )
                        addView(bar, LinearLayout.LayoutParams((32 * d).toInt(), (32 * d).toInt()))
                    }
                object : RecyclerView.ViewHolder(root) {}
            }
            else -> {
                val label =
                    TextView(context).apply {
                        setText(R.string.lbl_yt_explore_failed)
                        gravity = Gravity.CENTER
                    }
                val retry =
                    MaterialButton(context).apply {
                        setText(R.string.lbl_yt_retry)
                        setOnClickListener { onRetry() }
                    }
                val root =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER
                        val p = (24 * d).toInt()
                        setPadding(p, p * 2, p, p)
                        layoutParams =
                            RecyclerView.LayoutParams(
                                ViewGroup.LayoutParams.MATCH_PARENT,
                                ViewGroup.LayoutParams.WRAP_CONTENT,
                            )
                        addView(label)
                        addView(retry)
                    }
                object : RecyclerView.ViewHolder(root) {}
            }
        }
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        val row = getItem(position)
        if (holder is ShelfHolder && row is ExploreRow.Shelf) holder.bind(row.section)
    }

    private class ShelfHolder(
        root: View,
        private val title: TextView,
        private val inner: RecyclerView,
        private val cards: ExploreCardAdapter,
    ) : RecyclerView.ViewHolder(root) {
        init {
            inner.adapter = cards
        }

        fun bind(section: ExploreSection) {
            val d = itemView.resources.displayMetrics.density
            title.text = section.title
            title.visibility = if (section.title.isBlank()) View.GONE else View.VISIBLE
            val params = inner.layoutParams
            if (section.chips) {
                inner.layoutManager =
                    GridLayoutManager(itemView.context, 2, RecyclerView.HORIZONTAL, false)
                params.height = (2 * 56 * d).toInt()
            } else {
                inner.layoutManager =
                    LinearLayoutManager(itemView.context, RecyclerView.HORIZONTAL, false)
                params.height = ViewGroup.LayoutParams.WRAP_CONTENT
            }
            inner.layoutParams = params
            cards.items = section.items
            cards.notifyDataSetChanged()
        }
    }

    private companion object {
        val DIFF =
            object : DiffUtil.ItemCallback<ExploreRow>() {
                override fun areItemsTheSame(a: ExploreRow, b: ExploreRow) =
                    if (a is ExploreRow.Shelf && b is ExploreRow.Shelf) a.key == b.key else a == b

                override fun areContentsTheSame(a: ExploreRow, b: ExploreRow) = a == b
            }
    }
}

internal class ExploreCardAdapter(private val onItem: (ExploreItem, List<ExploreItem>) -> Unit) :
    RecyclerView.Adapter<ExploreCardAdapter.Holder>() {
    var items: List<ExploreItem> = emptyList()

    override fun getItemCount() = items.size

    override fun getItemViewType(position: Int) = if (items[position].kind == ExploreKind.MOOD) 1 else 0

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val context = parent.context
        val d = context.resources.displayMetrics.density
        val onSurface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface)
        val variant = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurfaceVariant)
        val surface = MaterialColors.getColor(parent, com.google.android.material.R.attr.colorSurfaceVariant)
        if (viewType == 1) {
            val chip =
                TextView(context).apply {
                    setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
                    )
                    setTextColor(onSurface)
                    gravity = Gravity.CENTER_VERTICAL
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    setPadding((16 * d).toInt(), 0, (16 * d).toInt(), 0)
                    layoutParams =
                        RecyclerView.LayoutParams((160 * d).toInt(), (48 * d).toInt()).apply {
                            setMargins((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
                        }
                }
            return Holder(chip, null, chip, null, surface, d)
        }
        val image =
            ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                setBackgroundColor(surface)
                clipToOutline = true
            }
        val title =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium)
                setTextColor(onSurface)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        val subtitle =
            TextView(context).apply {
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_BodySmall)
                setTextColor(variant)
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
            }
        val size = (140 * d).toInt()
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams =
                    RecyclerView.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        setMargins((4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt(), (4 * d).toInt())
                    }
                addView(image, LinearLayout.LayoutParams(size, size))
                addView(
                    title,
                    LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = (6 * d).toInt()
                    },
                )
                addView(subtitle, LinearLayout.LayoutParams(size, ViewGroup.LayoutParams.WRAP_CONTENT))
                val ripple = android.util.TypedValue()
                if (context.theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                    foreground = androidx.appcompat.content.res.AppCompatResources.getDrawable(context, ripple.resourceId)
                }
            }
        return Holder(root, image, title, subtitle, surface, d)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = items[position]
        holder.bind(item)
        holder.itemView.setOnClickListener { onItem(item, items) }
    }

    class Holder(
        root: View,
        private val image: ImageView?,
        private val title: TextView,
        private val subtitle: TextView?,
        private val surface: Int,
        private val d: Float,
    ) : RecyclerView.ViewHolder(root) {
        private var round = false

        init {
            image?.outlineProvider =
                object : ViewOutlineProvider() {
                    override fun getOutline(view: View, outline: Outline) {
                        val r = if (round) view.width / 2f else 12 * d
                        outline.setRoundRect(0, 0, view.width, view.height, r)
                    }
                }
        }

        fun bind(item: ExploreItem) {
            title.text = item.title
            if (image == null) {
                // A mood or genre chip, tinted with its own color when it has one.
                val tint = if (item.color != 0) ColorUtils.setAlphaComponent(item.color, 70) else surface
                title.background =
                    android.graphics.drawable.GradientDrawable().apply {
                        cornerRadius = 12 * d
                        setColor(tint)
                    }
                return
            }
            subtitle?.text = item.subtitle
            round = item.kind == ExploreKind.ARTIST
            image.invalidateOutline()
            val url = item.thumbnail
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
    }
}
