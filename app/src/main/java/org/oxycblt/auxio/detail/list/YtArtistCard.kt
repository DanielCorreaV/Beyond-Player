/*
 * Copyright (c) 2026 Beyond Player
 * YtArtistCard.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.detail.list

import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.color.MaterialColors
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.youtube.ExploreItem
import org.oxycblt.auxio.music.youtube.YtCovers
import org.oxycblt.auxio.music.youtube.YtFavoriteArtists

/** The YouTube Music profile of the artist shown: a card at the top of the artist's list. */
data class YtArtistCard(val item: ExploreItem, val artistName: String = item.title)

/** Displays a [YtArtistCard] (photo, name, subscribers); tapping it opens the full profile. */
class YtArtistViewHolder
private constructor(
    root: View,
    private val photo: ImageView,
    private val name: TextView,
    private val info: TextView,
    private val heart: ImageView,
) : RecyclerView.ViewHolder(root) {
    fun bind(card: YtArtistCard, onOpen: (YtArtistCard) -> Unit) {
        name.text = card.item.title
        info.text = card.item.subtitle
        info.visibility = if (card.item.subtitle.isBlank()) View.GONE else View.VISIBLE
        val url = card.item.thumbnail
        photo.tag = url
        photo.setImageDrawable(null)
        if (!url.isNullOrBlank()) {
            val context = photo.context
            val cached = YtCovers.cached(context, url)
            if (cached != null) {
                photo.setImageURI(Uri.fromFile(cached))
            } else {
                YtCovers.load(context, url) { file ->
                    if (photo.tag == url) photo.setImageURI(Uri.fromFile(file))
                }
            }
        }
        val context = itemView.context
        fun showHeart(on: Boolean) =
            heart.setImageResource(if (on) R.drawable.ic_heart_filled_24 else R.drawable.ic_heart_24)
        showHeart(YtFavoriteArtists.isFavorite(context, card.artistName))
        heart.setOnClickListener { showHeart(YtFavoriteArtists.toggle(context, card.artistName)) }
        itemView.setOnClickListener { onOpen(card) }
    }

    companion object {
        /** A unique ID for this [RecyclerView.ViewHolder] type. */
        const val VIEW_TYPE = 0xA010

        fun from(parent: ViewGroup): YtArtistViewHolder {
            val context = parent.context
            val d = context.resources.displayMetrics.density
            val onSurface =
                MaterialColors.getColor(parent, com.google.android.material.R.attr.colorOnSurface)
            val variant =
                MaterialColors.getColor(
                    parent,
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                )
            val surface =
                MaterialColors.getColor(
                    parent,
                    com.google.android.material.R.attr.colorSurfaceVariant,
                )
            val primary = MaterialColors.getColor(parent, androidx.appcompat.R.attr.colorPrimary)
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
            val caption =
                TextView(context).apply {
                    setText(R.string.lbl_yt_in_ytm)
                    setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_LabelSmall
                    )
                    setTextColor(primary)
                }
            val name =
                TextView(context).apply {
                    setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_TitleMedium
                    )
                    setTextColor(onSurface)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            val info =
                TextView(context).apply {
                    setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_BodySmall
                    )
                    setTextColor(variant)
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                }
            val texts =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(caption)
                    addView(name)
                    addView(info)
                }
            val action =
                TextView(context).apply {
                    setText(R.string.lbl_yt_view_profile)
                    setTextAppearance(
                        com.google.android.material.R.style.TextAppearance_Material3_LabelLarge
                    )
                    setTextColor(primary)
                }
            val heart =
                ImageView(context).apply {
                    setColorFilter(primary)
                    contentDescription = context.getString(R.string.lbl_yt_favorite)
                    val pad = (8 * d).toInt()
                    setPadding(pad, pad, pad, pad)
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
                            .apply {
                                setMargins(
                                    (16 * d).toInt(),
                                    (8 * d).toInt(),
                                    (16 * d).toInt(),
                                    (8 * d).toInt(),
                                )
                            }
                    val p = (12 * d).toInt()
                    setPadding(p, p, p, p)
                    background =
                        GradientDrawable().apply {
                            cornerRadius = 16 * d
                            setColor(surface)
                        }
                    isClickable = true
                    isFocusable = true
                    addView(photo, LinearLayout.LayoutParams((56 * d).toInt(), (56 * d).toInt()))
                    addView(
                        texts,
                        LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            .apply {
                                marginStart = (12 * d).toInt()
                                marginEnd = (8 * d).toInt()
                            },
                    )
                    addView(heart, LinearLayout.LayoutParams((40 * d).toInt(), (40 * d).toInt()))
                    addView(action)
                }
            return YtArtistViewHolder(root, photo, name, info, heart)
        }
    }
}
