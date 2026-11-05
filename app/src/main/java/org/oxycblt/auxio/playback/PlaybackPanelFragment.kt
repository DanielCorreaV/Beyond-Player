/*
 * Copyright (c) 2021 Auxio Project
 * PlaybackPanelFragment.kt is part of Auxio.
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

import android.annotation.SuppressLint
import com.google.android.material.button.MaterialButton
import androidx.appcompat.view.ContextThemeWrapper
import androidx.core.content.ContextCompat
import com.google.android.material.shape.RelativeCornerSize
import com.google.android.material.shape.ShapeAppearanceModel
import android.view.ViewGroup
import android.content.res.ColorStateList
import org.oxycblt.musikr.Music
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.ui.AppStyle
import org.oxycblt.auxio.playback.ui.DiscSpinner
import org.oxycblt.auxio.music.MusicViewModel
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import androidx.core.view.doOnLayout
import androidx.core.view.children
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import android.view.View
import androidx.appcompat.widget.Toolbar
import androidx.core.view.updatePadding
import androidx.dynamicanimation.animation.SpringForce
import androidx.fragment.app.activityViewModels
import androidx.fragment.app.viewModels
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.abs
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentPlaybackPanelBinding
import org.oxycblt.auxio.detail.DetailViewModel
import androidx.viewpager2.widget.ViewPager2
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.adapter.UpdateInstructions
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.equalizer.EqualizerController
import org.oxycblt.auxio.playback.equalizer.EqualizerDialog
import org.oxycblt.auxio.playback.equalizer.SongFade
import org.oxycblt.auxio.playback.equalizer.SoundStabilizer
import org.oxycblt.auxio.playback.queue.QueueViewModel
import org.oxycblt.auxio.playback.timer.SleepTimer
import org.oxycblt.auxio.playback.timer.SleepTimerDialog
import org.oxycblt.auxio.playback.timer.TimerState
import org.oxycblt.auxio.playback.state.RepeatMode
import org.oxycblt.auxio.playback.ui.StyledSeekBar
import org.oxycblt.auxio.playback.ui.stepper.Direction
import org.oxycblt.auxio.playback.ui.stepper.StepperOverlay
import org.oxycblt.auxio.playback.ui.swiper.CarouselTransformer
import org.oxycblt.auxio.playback.ui.swiper.CoverPagerAdapter
import org.oxycblt.auxio.playback.ui.swiper.UserAwarePagerCallback
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.dampen
import org.oxycblt.auxio.util.recycler
import org.oxycblt.auxio.util.showToast
import org.oxycblt.auxio.util.smoothScrollByPageTo
import org.oxycblt.auxio.util.systemBarInsetsCompat
import org.oxycblt.musikr.MusicParent
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * A [ViewBindingFragment] more information about the currently playing song, alongside all
 * available controls.
 *
 * @author Alexander Capehart (OxygenCobalt)
 *
 * TODO: Improve flickering situation on play button
 */
@AndroidEntryPoint
class PlaybackPanelFragment :
    ViewBindingFragment<FragmentPlaybackPanelBinding>(),
    Toolbar.OnMenuItemClickListener,
    StyledSeekBar.Listener,
    StepperOverlay.Listener {
    private val coverPagerAdapter = CoverPagerAdapter(this)
    private val playbackModel: PlaybackViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    private val listModel: ListViewModel by activityViewModels()
    private val musicModel: MusicViewModel by activityViewModels()
    @Inject lateinit var uiSettings: UISettings
    private var discSpinner: DiscSpinner? = null
    private var reelSpinner: DiscSpinner? = null
    private var walkman: WalkmanViews? = null
    private val queueModel: QueueViewModel by viewModels()
    @Inject lateinit var equalizer: EqualizerController
    @Inject lateinit var stabilizer: SoundStabilizer
    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var songFade: SongFade
    private var userAwarePagerCallback: UserAwarePagerCallback? = null

    override fun onCreateBinding(inflater: LayoutInflater): FragmentPlaybackPanelBinding {
        // Pop style on tall phones: the Walkman screen, with its own dark palette.
        if (uiSettings.style != AppStyle.POP || resources.configuration.screenHeightDp < 520) {
            return FragmentPlaybackPanelBinding.inflate(inflater)
        }
        val themed =
            inflater.cloneInContext(
                ContextThemeWrapper(inflater.context, R.style.ThemeOverlay_Auxio_Walkman)
            )
        return FragmentPlaybackPanelBinding.bind(
            themed.inflate(R.layout.fragment_playback_walkman, null, false)
        )
    }

    override fun onBindingCreated(
        binding: FragmentPlaybackPanelBinding,
        savedInstanceState: Bundle?,
    ) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.systemBarInsetsCompat
            view.updatePadding(bottom = bars.bottom)
            insets
        }

        binding.playbackToolbar.apply {
            setNavigationOnClickListener { playbackModel.openMain() }
            setOnMenuItemClickListener(this@PlaybackPanelFragment)
        }

        binding.playbackPager?.apply {
            // intentional LTR override since thepager is a chronological element
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            adapter = coverPagerAdapter
            userAwarePagerCallback =
                UserAwarePagerCallback(this) {
                        // Posting the queue goto command prevents the seekbar pos from desyncing
                        // from the song's duration, which creates a visual flicker in the seekbar.
                        post { queueModel.goto(it) }
                    }
                    .also { it.attach() }
            setPageTransformer(CarouselTransformer())
            recycler().apply {
                // Make it possible to collapse the bottom sheet from the ViewPager's touch area.
                isNestedScrollingEnabled = false
                // Visual effect consistency
                // TODO: Custom overscroll?
                overScrollMode = View.OVER_SCROLL_NEVER
            }
            // Make it easier to collapse the bottom sheet
            dampen()
            offscreenPageLimit = 1
        }

        // Set up fast seek overlay
        binding.playbackSong.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentSong() }
        }
        binding.playbackArtist.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentArtist() }
        }
        binding.playbackAlbum?.apply {
            isSelected = true
            setOnClickListener { navigateToCurrentAlbum() }
        }

        binding.playbackFavorite?.setOnClickListener { toggleFavorite() }
        setupVinyl(binding)
        setupWalkman(binding)
        setupActionTiles(binding)

        binding.playbackSeekBar?.listener = this

        // Set up actions
        // TODO: Add better playback button accessibility
        binding.playbackRepeat.setOnClickListener { playbackModel.toggleRepeatMode() }
        binding.playbackSkipPrev.setOnClickListener { playbackModel.prev() }
        binding.playbackPlayPause.apply {
            @SuppressLint("RestrictedApi")
            setCornerSpringForce(
                SpringForce().apply {
                    stiffness = 700f
                    dampingRatio = 0.9f
                }
            )
            setOnClickListener { playbackModel.togglePlaying() }
        }
        binding.playbackSkipNext.setOnClickListener { playbackModel.next() }
        binding.playbackShuffle.setOnClickListener { playbackModel.toggleShuffled() }
        binding.playbackMore?.setOnClickListener {
            playbackModel.song.value?.let {
                listModel.openMenu(R.menu.playback_song, it, PlaySong.ByItself)
            }
        }

        // --- VIEWMODEL SETUP --
        collectImmediately(playbackModel.song, ::updateSong)
        collectImmediately(playbackModel.isPlaying, ::updateDisc)
        collectImmediately(playbackModel.song, musicModel.favoriteUids, ::updateFavorite)
        collectImmediately(sleepTimer.state, ::updateTimerIcon)
        collectImmediately(playbackModel.parent, ::updateParent)
        collectImmediately(playbackModel.positionDs, ::updatePosition)
        collectImmediately(playbackModel.repeatMode, ::updateRepeat)
        collectImmediately(playbackModel.isPlaying, ::updatePlaying)
        collectImmediately(playbackModel.isShuffled, ::updateShuffled)
        collectImmediately(playbackModel.pagerQueue, ::updatePager)
    }

    // FIXME: Old code!! Maybe not necessary anymore?
    //    override fun onStart() {
    //        super.onStart()
    //        playbackModel.song.value?.let { requireBinding().playbackCover.bind(it) }
    //        requireBinding().root.viewTreeObserver.addOnGlobalLayoutListener(this)
    //    }

    //    override fun onStop() {
    //        super.onStop()
    //        requireBinding().root.viewTreeObserver.removeOnGlobalLayoutListener(this)
    //    }

    //    override fun onGlobalLayout() {
    //        if (binding == null || lastCoverWidth < 0) {
    //            return
    //        }
    // Hacky workaround for cover radius not being preserved in between sizing changes
    // (i.e split screen or landscape mode)
    // For some reason ConstraintLayout does several passes on 1:1 elements that causes their
    // size to radically change, so we wait until it stabilizes and then force an image
    // reload if needed. Optimistically this is a no-op from coil caching, but when the cover
    // did accidentally load the wrong image (with weird corner radius intended for bigger
    // covers) we can force it to reload.
    // If this breaks, it's fine since we also started a load as we normally did w/state
    // updates, so the cover will not break.
    //        val binding = requireBinding()
    //        val coverWidth = binding.playbackCover.width
    //        if (lastCoverWidth != coverWidth) {
    //            lastCoverWidth = coverWidth
    //        } else {
    //            playbackModel.song.value?.let { binding.playbackCover.bind(it) }
    //            lastCoverWidth = -1
    //        }
    //    }

    override fun onDestroyBinding(binding: FragmentPlaybackPanelBinding) {
        discSpinner?.release()
        reelSpinner?.release()
        reelSpinner = null
        walkman = null
        discSpinner = null
        binding.playbackRepeat.clearPendingIcon()
        binding.playbackSong.isSelected = false
        binding.playbackArtist.isSelected = false
        binding.playbackAlbum?.isSelected = false
        binding.playbackToolbar.setOnMenuItemClickListener(null)
        userAwarePagerCallback?.release()
        binding.playbackPager?.adapter = null
    }

    private fun setupActionTiles(binding: FragmentPlaybackPanelBinding) {
        if (uiSettings.style != AppStyle.NEON) return
        // Bigger cover: the side margins shrink.
        binding.playbackPager?.let { pager ->
            (pager.layoutParams as? ViewGroup.MarginLayoutParams)?.let {
                val m = (resources.displayMetrics.density * 6).toInt()
                it.setMargins(m, it.topMargin, m, it.bottomMargin)
                pager.layoutParams = it
            }
        }
        // Round play button.
        binding.playbackPlayPause.setBaseShape(
            ShapeAppearanceModel.builder().setAllCornerSizes(RelativeCornerSize(0.5f)).build()
        )
        binding.playbackToolbar.title = getString(R.string.lbl_neon_playing_from)
        binding.playbackFavorite?.apply {
            backgroundTintList = ColorStateList.valueOf(0x33FF4FA3)
            iconTint = ColorStateList.valueOf(0xFFFF6FB5.toInt())
        }
    }

    private fun tintToolbarButtons(view: View, color: Int) {
        if (view is MaterialButton) view.iconTint = ColorStateList.valueOf(color)
        if (view is ViewGroup) view.children.forEach { tintToolbarButtons(it, color) }
    }

    private fun setupWalkman(binding: FragmentPlaybackPanelBinding) {
        walkman =
            if (binding.root.findViewById<View>(R.id.playback_walkman_card) != null) {
                WalkmanViews(binding.root)
            } else {
                null
            }
        val views = walkman ?: return
        coverPagerAdapter.stepperEnabled = false
        binding.root.findViewById<StepperOverlay>(R.id.playback_walkman_seek)?.listener = this
        binding.playbackToolbar.title = getString(R.string.lbl_walkman_name)
        tintToolbarButtons(binding.playbackToolbar, 0xFFE8EDF7.toInt())
        views.labelTitle?.isSelected = true
        val spinner = DiscSpinner(REEL_REVOLUTION_MS).also { reelSpinner = it }
        listOf(views.reelLeft, views.reelRight).forEach { reel -> reel?.let(spinner::attach) }
        spinner.setPlaying(playbackModel.isPlaying.value)
    }

    private fun setupVinyl(binding: FragmentPlaybackPanelBinding) {
        if (uiSettings.style != AppStyle.VINYL) return
        // The Vinyl style spins the cover like a record and swings a tonearm onto it.
        val spinner = DiscSpinner().also { discSpinner = it }
        binding.playbackPager?.recycler()?.let { recycler ->
            recycler.addOnChildAttachStateChangeListener(
                object : RecyclerView.OnChildAttachStateChangeListener {
                    override fun onChildViewAttachedToWindow(view: View) = spinner.attachPage(view)

                    override fun onChildViewDetachedFromWindow(view: View) = spinner.detachPage(view)
                }
            )
            recycler.children.forEach(spinner::attachPage)
        }
        binding.playbackTonearm?.apply {
            isVisible = true
            doOnLayout {
                pivotX = it.width * TONEARM_PIVOT_X
                pivotY = it.height * TONEARM_PIVOT_Y
                rotation = if (playbackModel.isPlaying.value) 0f else TONEARM_REST_ANGLE
            }
        }
    }

    private fun updateDisc(isPlaying: Boolean) {
        discSpinner?.setPlaying(isPlaying)
        reelSpinner?.setPlaying(isPlaying)
        walkman?.state?.setText(
            if (isPlaying) R.string.lbl_walkman_playing else R.string.lbl_walkman_paused
        )
        requireBinding()
            .playbackTonearm
            ?.animate()
            ?.rotation(if (isPlaying) 0f else TONEARM_REST_ANGLE)
            ?.setDuration(TONEARM_MOVE_MS)
            ?.start()
    }

    private fun updateFavorite(song: Song?, favorites: Set<Music.UID>) {
        requireBinding().playbackFavorite?.isChecked = song != null && song.uid in favorites
    }

    private fun toggleFavorite() {
        val song = playbackModel.song.value ?: return
        val liked = song.uid in musicModel.favoriteUids.value
        musicModel.toggleFavorite(song)
        requireContext()
            .showToast(if (liked) R.string.msg_favorite_removed else R.string.msg_favorite_added)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (item.itemId == R.id.action_open_equalizer) {
            // Auxio has its own equalizer, so no system equalizer app is needed.
            L.d("Opening equalizer")
            EqualizerDialog.show(requireContext(), equalizer, stabilizer, songFade)
            return true
        }

        if (item.itemId == R.id.action_sleep_timer) {
            L.d("Opening sleep timer")
            SleepTimerDialog.show(requireContext(), sleepTimer)
            return true
        }

        return false
    }

    override fun onSeekConfirmed(positionDs: Long) {
        playbackModel.seekTo(positionDs)
    }

    private fun updateTimerIcon(state: TimerState?) {
        val item = requireBinding().playbackToolbar.menu.findItem(R.id.action_sleep_timer) ?: return
        // A dot on the icon shows that a timer is running.
        item.setIcon(
            if (state != null) R.drawable.ic_timer_active_24 else R.drawable.ic_timer_24
        )
    }

    private fun updateSong(song: Song?) {
        if (song == null) {
            // Nothing to do.
            return
        }

        val binding = requireBinding()
        val context = requireContext()
        L.d("Updating song display: $song")
        binding.playbackSong.text = song.name.resolve(context)
        binding.playbackArtist.text = song.artists.resolveNames(context)
        binding.playbackAlbum?.text = song.album.name.resolve(context)
        walkman?.let {
            it.labelTitle?.text = song.name.resolve(context)
            it.labelArtist?.text = song.artists.resolveNames(context)
            it.footerAlbum?.text = song.album.name.resolve(context)
            it.footerArtist?.text = song.artists.resolveNames(context)
        }
        binding.playbackDeckTitle?.text = song.name.resolve(context)
        binding.playbackDeckArtist?.text = song.artists.resolveNames(context)
        binding.playbackSeekBar?.durationDs = song.durationMs.msToDs()
    }

    private fun updateParent(parent: MusicParent?) {
        val binding = requireBinding()
        val context = requireContext()
        binding.playbackToolbar.subtitle =
            parent?.run { name.resolve(context) } ?: context.getString(R.string.lbl_all_songs)
    }

    private fun updatePosition(positionDs: Long) {
        requireBinding().playbackSeekBar?.positionDs = positionDs
        walkman?.counter?.text = "%03d".format((positionDs / 10).toInt() % 1000)
    }

    private fun updateRepeat(repeatMode: RepeatMode) {
        val repeatButton = requireBinding().playbackRepeat
        repeatButton.isChecked = repeatMode != RepeatMode.NONE
        repeatButton.setIconResource(repeatMode.icon)
    }

    private fun updatePlaying(isPlaying: Boolean) {
        requireBinding().playbackPlayPause.isChecked = isPlaying
        requireBinding().playbackSeekBar?.setWaveEnabled(isPlaying)
    }

    private fun updateShuffled(isShuffled: Boolean) {
        requireBinding().playbackShuffle.isChecked = isShuffled
    }

    private fun updatePager(queue: PagerQueue) {
        // Right now there's easily 140ms of frame skipping when going next/prev. This is primarily
        // the fault of specifically the nested bottom sheet UI setup, which is intractable to
        // optimize. If I don't do multiple remeasures/relayouts on every slightest state
        // instability
        // I will suddenly encounter insane issues where the sheet fails to measure, appears below
        // the sidebar, flies away, not changing with ui scale, etc, often only on third-party OEM
        // ROMs that randomly mangle  SDK APIs and the SystemUI chrome for no reason.
        //
        // Historically this was not an issue, as I did not animate next/prev. Now I do, and it's
        // highly noticeable. So at least for plain next/prev I have to hack around it, do not
        // execute any transition until the state has fully adjudicated and laid out the UI. It's
        // not effective for swiping but there's nothing I can do there.
        //
        // Eventually one day Claude Fable 6.7 will probably be able to figure out that you need to
        // reflect into System.FoobaCrumbo::beegieConnector(GoolaUtils.PlubBud) and call it
        // specifically with 0x189B31FA alongside disabling the AndroidX Helpo SuperCharge by
        // manually clobbering `BottomSheetM2InternalBoogieCompat::scrimbloManager` to null for it
        // to not actually randomly mangle the sheets and do it in 1 clean layout, but for now I
        // must do this to keep my sanity.
        //
        // Actual snippet here was codex, just cleaned & adapted it / cognitive ownership
        requireBinding().playbackPager.apply {
            if (!isAttachedToWindow) {
                post { updatePagerImpl(queue) }
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isHardwareAccelerated) {
                // New version using post-Q frame hooks
                viewTreeObserver.registerFrameCommitCallback {
                    post { postOnAnimation { updatePagerImpl(queue) } }
                }
                postInvalidateOnAnimation()
            } else {
                // Let current layout happen, then wait for the next to conclude
                postOnAnimation { postOnAnimation { updatePagerImpl(queue) } }
            }
        }
    }

    private fun updatePagerImpl(captured: PagerQueue) {
        // Android insanity means this may be executed after view destruction
        // but only on some devices.
        val binding = binding ?: return

        // The state can move on between the moment this was scheduled and now, and the command
        // slot only remembers the last command. Always work from the newest queue.
        val queue = playbackModel.pagerQueue.value
        val command = playbackModel.pagerCommand.consume()
        val oldSize = coverPagerAdapter.currentList.size

        // Several queue changes in a row (for example removing a duplicate and then adding a song)
        // collapse into one update with only the last command, so the incremental instruction no
        // longer describes the real change. A list updated that way keeps stale covers around, so
        // when the sizes do not add up, or the list no longer lines up with the current song,
        // just rebuild it.
        val update = command?.update
        val incrementalMismatch =
            when (update) {
                is UpdateInstructions.Add -> queue.queue.size != oldSize + update.size
                is UpdateInstructions.Remove -> queue.queue.size != oldSize - update.size
                is UpdateInstructions.Move -> queue.queue.size != oldSize
                else -> false
            }
        val misaligned =
            update == null &&
                (oldSize != queue.queue.size ||
                    coverPagerAdapter.currentList.getOrNull(queue.index)?.uid !=
                        queue.queue.getOrNull(queue.index)?.uid)
        if (incrementalMismatch || misaligned) {
            android.util.Log.w(
                "AuxioPager",
                "pager out of sync (command=$command old=$oldSize new=${queue.queue.size}), rebuilding",
            )
            coverPagerAdapter.update(queue.queue, UpdateInstructions.Replace(0))
            binding.playbackPager.setCurrentItem(queue.index, false)
            return
        }

        if (command == null) {
            // Another update already took care of the command, only make sure the UI state is
            // aligned. Don't want broken UI.
            val pager = binding.playbackPager
            if (
                pager.scrollState == ViewPager2.SCROLL_STATE_IDLE && pager.currentItem != queue.index
            ) {
                pager.setCurrentItem(queue.index, false)
            }
            return
        }

        if (command.update != null) {
            // queue needs to be updated.
            coverPagerAdapter.update(queue.queue, command.update)
        }

        if (command.scroll != null) {
            // we need to scroll, however the smooth scroll only really looks best
            // when we are only doing next/prev due to various factors. better to
            // just not animate on outright gotos or queue updates
            val delta = binding.playbackPager.currentItem - command.scroll
            if (delta == 0) {
                // user scroll, carry on
                return
            }
            if (command.update == null && abs(delta) == 1) {
                binding.playbackPager.smoothScrollByPageTo(command.scroll)
            } else {
                binding.playbackPager.setCurrentItem(command.scroll, false)
            }
        }
    }

    private fun navigateToCurrentSong() {
        playbackModel.song.value?.let(detailModel::showAlbum)
    }

    private fun navigateToCurrentArtist() {
        playbackModel.song.value?.let(detailModel::showArtist)
    }

    private fun navigateToCurrentAlbum() {
        playbackModel.song.value?.let { detailModel.showAlbum(it.album) }
    }

    override fun hold(active: Boolean) {
        playbackModel.boost(active)
    }

    override fun seek(direction: Direction) {
        when (direction) {
            Direction.FORWARDS -> playbackModel.stepForward()
            Direction.BACKWARDS -> playbackModel.stepBackwards()
        }
    }

    private companion object {
        /** Where the tonearm pivots, as a fraction of its size. */
        const val TONEARM_PIVOT_X = 100f / 120f
        const val TONEARM_PIVOT_Y = 17f / 170f
        /** Angle of the tonearm when it rests away from the record. */
        const val TONEARM_REST_ANGLE = -30f
        const val TONEARM_MOVE_MS = 600L
    }
}

private const val REEL_REVOLUTION_MS = 2600L
