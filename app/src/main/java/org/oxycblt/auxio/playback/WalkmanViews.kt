/*
 * Copyright (c) 2026 Auxio Project
 * WalkmanViews.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback

import android.view.View
import android.widget.ImageView
import android.widget.TextView
import org.oxycblt.auxio.R

/** The views that only exist in the Walkman player of the Pop style (fragment_playback_walkman). */
class WalkmanViews(root: View) {
    val reelLeft: ImageView? = root.findViewById(R.id.playback_reel_left)
    val reelRight: ImageView? = root.findViewById(R.id.playback_reel_right)
    val counter: TextView? = root.findViewById(R.id.playback_walkman_counter)
    val state: TextView? = root.findViewById(R.id.playback_walkman_state)
    val labelTitle: TextView? = root.findViewById(R.id.playback_label_title)
    val labelArtist: TextView? = root.findViewById(R.id.playback_label_artist)
    val footerAlbum: TextView? = root.findViewById(R.id.playback_footer_album)
    val footerArtist: TextView? = root.findViewById(R.id.playback_footer_artist)
}
