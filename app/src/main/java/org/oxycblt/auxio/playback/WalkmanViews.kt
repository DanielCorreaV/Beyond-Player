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
