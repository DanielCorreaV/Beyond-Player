package org.oxycblt.auxio.home

import android.view.LayoutInflater
import com.google.android.material.color.MaterialColors
import com.google.android.material.card.MaterialCardView
import com.google.android.material.R as MR
import android.content.res.ColorStateList
import android.widget.ImageView
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import org.oxycblt.auxio.R
import org.oxycblt.auxio.image.CoverView
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song

/** Carousel of songs ("Seguir escuchando") of the Neon home screen. */
class NeonRecentAdapter(private val onClick: (Song) -> Unit) :
    RecyclerView.Adapter<NeonRecentAdapter.Holder>() {
    private var songs = listOf<Song>()

    fun submit(newSongs: List<Song>) {
        songs = newSongs
        notifyDataSetChanged()
    }

    override fun getItemCount() = songs.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_neon_recent, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val song = songs[position]
        val context = holder.itemView.context
        holder.cover.bind(song)
        holder.title.text = song.name.resolve(context)
        holder.subtitle.text = song.artists.resolveNames(context)
        holder.itemView.setOnClickListener { onClick(song) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val cover: CoverView = view.findViewById(R.id.neon_item_cover)
        val title: TextView = view.findViewById(R.id.neon_item_title)
        val subtitle: TextView = view.findViewById(R.id.neon_item_subtitle)
    }
}

/** Grid of playlists ("Tus listas") of the Neon home screen. */
class NeonPlaylistAdapter(
    private val favoritesName: String,
    private val gradients: Array<IntArray>,
    private val onClick: (Playlist) -> Unit,
) : RecyclerView.Adapter<NeonPlaylistAdapter.Holder>() {
    private var playlists = listOf<Playlist>()

    fun submit(newPlaylists: List<Playlist>) {
        playlists = newPlaylists
        notifyDataSetChanged()
    }

    override fun getItemCount() = playlists.size

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
        Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_neon_list, parent, false))

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val playlist = playlists[position]
        val context = holder.itemView.context
        val name = playlist.name.resolve(context)
        val liked = playlist.name.raw == favoritesName
        val colors = gradients[position % gradients.size]
        holder.tile.background =
            GradientDrawable(GradientDrawable.Orientation.TL_BR, colors).apply {
                cornerRadius = context.resources.displayMetrics.density * 14
            }
        holder.icon.setImageResource(if (liked) R.drawable.ic_favorite_24 else R.drawable.ic_playlist_24)
        if (gradients === POP_GRADIENTS) {
            // Pop: pastel tiles with dark icons and a thick border.
            holder.icon.imageTintList = ColorStateList.valueOf(0xFF17142B.toInt())
            (holder.itemView as? MaterialCardView)?.apply {
                strokeWidth = (context.resources.displayMetrics.density * 2).toInt()
                strokeColor = MaterialColors.getColor(this, MR.attr.colorOnSurface)
            }
        }
        holder.title.text = name
        val count = playlist.songs.size
        holder.subtitle.text = context.resources.getQuantityString(R.plurals.fmt_song_count, count, count)
        holder.itemView.setOnClickListener { onClick(playlist) }
    }

    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val tile: View = view.findViewById(R.id.neon_item_tile)
        val icon: ImageView = view.findViewById(R.id.neon_item_icon)
        val title: TextView = view.findViewById(R.id.neon_item_title)
        val subtitle: TextView = view.findViewById(R.id.neon_item_subtitle)
    }

}

/** Gradient pairs of the playlist tiles. */
val NEON_GRADIENTS =
    arrayOf(
        intArrayOf(0xFF8C5CFF.toInt(), 0xFFFF4FA3.toInt()),
        intArrayOf(0xFF22D3EE.toInt(), 0xFF3B82F6.toInt()),
        intArrayOf(0xFF4ADE80.toInt(), 0xFF14B8A6.toInt()),
        intArrayOf(0xFFFB923C.toInt(), 0xFFF43F5E.toInt()),
    )

val VINYL_GRADIENTS =
    arrayOf(
        intArrayOf(0xFFFF7A1A.toInt(), 0xFFFFB347.toInt()),
        intArrayOf(0xFFC2410C.toInt(), 0xFFF97316.toInt()),
        intArrayOf(0xFFB45309.toInt(), 0xFFFBBF24.toInt()),
        intArrayOf(0xFF9A3412.toInt(), 0xFFEA580C.toInt()),
    )

val POP_GRADIENTS =
    arrayOf(
        intArrayOf(0xFFCFF5E7.toInt(), 0xFFCFF5E7.toInt()),
        intArrayOf(0xFFFFD3E8.toInt(), 0xFFFFD3E8.toInt()),
        intArrayOf(0xFFD6E2FF.toInt(), 0xFFD6E2FF.toInt()),
        intArrayOf(0xFFFFE9A3.toInt(), 0xFFFFE9A3.toInt()),
    )
