/*
 * Copyright (c) 2021 Auxio Project
 * HomeFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home

import android.annotation.SuppressLint
import androidx.recyclerview.widget.GridLayoutManager
import android.graphics.drawable.ColorDrawable
import org.oxycblt.auxio.playback.ui.DiscSpinner
import com.google.android.material.color.MaterialColors
import com.google.android.material.R as MR
import androidx.core.view.updatePadding
import androidx.core.graphics.ColorUtils
import android.os.Bundle
import android.view.LayoutInflater
import android.view.MenuItem
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.view.MenuCompat
import androidx.core.view.isInvisible
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentManager
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.LifecycleOwner
import androidx.navigation.fragment.findNavController
import androidx.viewpager2.adapter.FragmentStateAdapter
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.appbar.AppBarLayout
import com.google.android.material.tabs.TabLayoutMediator
import com.google.android.material.transition.MaterialSharedAxis
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentHomeBinding
import org.oxycblt.auxio.detail.DetailViewModel
import org.oxycblt.auxio.detail.Show
import org.oxycblt.auxio.home.list.AlbumListFragment
import org.oxycblt.auxio.home.list.ArtistListFragment
import org.oxycblt.auxio.home.list.GenreListFragment
import org.oxycblt.auxio.home.list.PlaylistListFragment
import org.oxycblt.auxio.home.list.SongListFragment
import org.oxycblt.auxio.home.tabs.NamedTabStrategy
import org.oxycblt.auxio.home.tabs.Tab
import org.oxycblt.auxio.list.ListViewModel
import org.oxycblt.auxio.list.SelectionFragment
import org.oxycblt.auxio.list.menu.Menu
import org.oxycblt.auxio.music.IndexingState
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.music.PlaylistDecision
import org.oxycblt.auxio.music.PlaylistMessage
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.auxio.playback.PlaybackDecision
import org.oxycblt.auxio.playback.PlaybackViewModel
import org.oxycblt.auxio.ui.AppStyle
import org.oxycblt.auxio.ui.FadingToolbarOffsetListener
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.collect
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.dampen
import org.oxycblt.auxio.util.navigateSafe
import org.oxycblt.auxio.util.showToast
import org.oxycblt.musikr.IndexingProgress
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.playlist.m3u.M3U
import timber.log.Timber as L

/**
 * The starting [SelectionFragment] of Auxio. Shows the user's music library and enables navigation
 * to other views.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class HomeFragment : SelectionFragment<FragmentHomeBinding>() {
    override val listModel: ListViewModel by activityViewModels()
    override val musicModel: MusicViewModel by activityViewModels()
    override val playbackModel: PlaybackViewModel by activityViewModels()
    private val homeModel: HomeViewModel by activityViewModels()
    private val detailModel: DetailViewModel by activityViewModels()
    @Inject lateinit var uiSettings: UISettings
    @Inject lateinit var recentSongs: RecentSongs
    private var discSpinner: DiscSpinner? = null
    private var storagePermissionLauncher: ActivityResultLauncher<String>? = null
    private var getContentLauncher: ActivityResultLauncher<String>? = null
    private var pendingImportTarget: Playlist? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        returnTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
        exitTransition = MaterialSharedAxis(MaterialSharedAxis.Z, true)
        reenterTransition = MaterialSharedAxis(MaterialSharedAxis.Z, false)
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentHomeBinding.inflate(inflater)

    override fun getSelectionToolbar(binding: FragmentHomeBinding) = binding.homeSelectionToolbar

    @SuppressLint("ClickableViewAccessibility")
    override fun onBindingCreated(binding: FragmentHomeBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // Have to set up the permission launcher before the view is shown
        storagePermissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestPermission()) {
                musicModel.refresh()
            }

        getContentLauncher =
            registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
                if (uri == null) {
                    L.w("No URI returned from file picker")
                    return@registerForActivityResult
                }

                L.d("Received playlist URI $uri")
                musicModel.importPlaylist(uri, pendingImportTarget)
            }

        // --- UI SETUP ---

        if (uiSettings.style == AppStyle.CLASSIC) {
            binding.homeAppbar.addOnOffsetChangedListener(
                FadingToolbarOffsetListener(binding.homeToolbar, binding.homeContent)
            )
        } else {
            setupStickyHeader(binding)
            if (uiSettings.style == AppStyle.NEON || uiSettings.style == AppStyle.POP) setupNeonTitle(binding)
        }
        binding.homeNormalToolbar.apply {
            setOnMenuItemClickListener(this@HomeFragment)
            MenuCompat.setGroupDividerEnabled(menu, true)
        }

        binding.homePager.apply {
            // Update HomeViewModel whenever the user swipes through the ViewPager.
            // This would be implemented in HomeFragment itself, but OnPageChangeCallback
            // is an object for some reason.
            registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        homeModel.synchronizeTabPosition(position)
                    }
                }
            )

            // ViewPager2 will nominally consume window insets, which will then break the window
            // insets applied to the indexing view before API 30. Fix this by overriding the
            // listener with a non-consuming listener.
            setOnApplyWindowInsetsListener { _, insets -> insets }

            // We know that there will only be a fixed amount of tabs, so we manually set this
            // limit to the maximum amount possible. This will prevent the tab ripple from
            // bugging out due to dynamically inflating each fragment, at the cost of slower
            // debug UI performance.
            offscreenPageLimit = Tab.MAX_SEQUENCE_IDX + 1

            dampen()
        }

        binding.homeNowPlay.setOnClickListener { playbackModel.togglePlaying() }
        binding.homeNowContinue.setOnClickListener { playbackModel.togglePlaying() }

        // Further initialization must be done in the function that also handles
        // re-creating the ViewPager.
        setupPager(binding)

        // --- VIEWMODEL SETUP ---
        collect(homeModel.recreateTabs.flow, ::handleRecreate)
        collect(homeModel.chooseMusicLocations.flow, ::handleChooseFolders)
        collectImmediately(homeModel.currentTabType, ::updateCurrentTab)
        collect(detailModel.toShow.flow, ::handleShow)
        collect(listModel.menu.flow, ::handleMenu)
        collectImmediately(listModel.selected, ::updateSelection)
        collectImmediately(musicModel.indexingState, ::updateIndexerState)
        collect(musicModel.playlistDecision.flow, ::handlePlaylistDecision)
        collectImmediately(musicModel.playlistMessage.flow, ::handlePlaylistMessage)
        collect(playbackModel.playbackDecision.flow, ::handlePlaybackDecision)
        collectImmediately(playbackModel.song, ::updateNowCard)
        collectImmediately(playbackModel.isPlaying, ::updateNowCardPlaying)
        setupNeonDashboard(binding)
    }

    private fun setupNeonTitle(binding: FragmentHomeBinding) {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        binding.homeNormalToolbar.title =
            getString(if (uiSettings.style == AppStyle.POP) R.string.lbl_pop_title else R.string.lbl_neon_title)
        binding.homeNormalToolbar.subtitle =
            getString(
                when {
                    hour < 12 -> R.string.lbl_greet_morning
                    hour < 19 -> R.string.lbl_greet_afternoon
                    else -> R.string.lbl_greet_night
                }
            )
    }

    private fun setupStickyHeader(binding: FragmentHomeBinding) {
        // The now playing card scrolls away; the toolbar (app title) and tabs stay pinned below it,
        // on a translucent plate so the title stands out over the background image.
        val surface = MaterialColors.getColor(binding.root, MR.attr.colorSurface)
        binding.homeToolbar.setBackgroundColor(surface)
        // Also cover the status bar area, so the card never shows behind it.
        binding.homeAppbar.statusBarForeground = ColorDrawable(surface)
        // Cancel the header's scroll for the toolbar so it stays at the top of the screen.
        binding.homeAppbar.addOnOffsetChangedListener { _, offset ->
            binding.homeToolbar.translationY = -offset.toFloat()
        }
    }

    private fun setupPopHero(binding: FragmentHomeBinding) {
        binding.homePopHero.isVisible = true
        val context = requireContext()
        var mode = 0
        var discover: Song? = null
        // Modes: 0 = resume, 1 = discover, 2 = shuffle. Resume/discover are skipped when empty.
        fun resumeSong(): Song? = musicModel.findSongs(recentSongs.load()).firstOrNull()
        fun discoverSong(): Song? {
            if (discover == null) discover = homeModel.songList.value.randomOrNull()
            return discover
        }
        fun available(m: Int) = when (m) {
            0 -> resumeSong() != null
            1 -> discoverSong() != null
            else -> true
        }
        lateinit var action: () -> Unit
        fun render() {
            if (!available(mode)) mode = 2
            val card = binding.homePopHeroCard
            val withCover = mode != 2
            binding.homePopBlobA.isVisible = !withCover
            binding.homePopBlobB.isVisible = !withCover
            binding.homePopHeroCover.isVisible = withCover
            binding.homePopHeroSub.isVisible = withCover
            val song =
                when (mode) {
                    0 -> resumeSong()
                    1 -> discoverSong()
                    else -> null
                }
            when (mode) {
                0 -> {
                    card.setCardBackgroundColor(0xFF3DD5F3.toInt())
                    binding.homePopHeroLabel.setText(R.string.lbl_pop_resume_label)
                    binding.homePopPlay.setText(R.string.lbl_pop_resume_play)
                }
                1 -> {
                    card.setCardBackgroundColor(0xFFFF8FB8.toInt())
                    binding.homePopHeroLabel.setText(R.string.lbl_pop_discover_label)
                    binding.homePopPlay.setText(R.string.lbl_pop_discover_play)
                }
                else -> {
                    card.setCardBackgroundColor(0xFFFFD84D.toInt())
                    binding.homePopHeroLabel.setText(R.string.lbl_pop_hero_label)
                    binding.homePopHeroTitle.setText(R.string.lbl_pop_hero_title)
                    binding.homePopPlay.setText(R.string.lbl_pop_hero_play)
                }
            }
            if (song != null) {
                binding.homePopHeroTitle.text = song.name.resolve(context)
                binding.homePopHeroSub.text = song.artists.resolveNames(context)
                binding.homePopHeroCover.bind(song)
            }
            binding.homePopHeroDots.text =
                (0..2).joinToString(" ") { if (it == mode) "\u25CF" else "\u25CB" }
            action = {
                when (mode) {
                    0, 1 -> song?.let { playbackModel.play(it, homeModel.playWith) }
                    else -> playbackModel.shuffleAll()
                }
            }
        }
        fun step(delta: Int) {
            var next = mode
            repeat(3) {
                next = (next + delta + 3) % 3
                if (available(next)) {
                    mode = next
                    render()
                    return
                }
            }
        }
        val detector =
            android.view.GestureDetector(
                context,
                object : android.view.GestureDetector.SimpleOnGestureListener() {
                    override fun onDown(e: android.view.MotionEvent) = true

                    override fun onSingleTapUp(e: android.view.MotionEvent): Boolean {
                        action()
                        return true
                    }

                    override fun onFling(
                        e1: android.view.MotionEvent?,
                        e2: android.view.MotionEvent,
                        velocityX: Float,
                        velocityY: Float,
                    ): Boolean {
                        if (kotlin.math.abs(velocityX) < kotlin.math.abs(velocityY)) return false
                        step(if (velocityX < 0) 1 else -1)
                        return true
                    }
                },
            )
        binding.homePopHeroCard.setOnTouchListener { v, ev ->
            if (ev.actionMasked == android.view.MotionEvent.ACTION_DOWN) {
                v.parent?.requestDisallowInterceptTouchEvent(true)
            }
            detector.onTouchEvent(ev)
        }
        binding.homePopPlay.setOnClickListener { action() }
        // Start on "resume" when there is something to resume, otherwise on the shuffle card.
        mode = if (resumeSong() != null) 0 else 1
        render()
    }

    private fun setupNeonDashboard(binding: FragmentHomeBinding) {
        if (uiSettings.style == AppStyle.CLASSIC) return
        binding.homeNeonDash.isVisible = true
        if (uiSettings.style == AppStyle.POP) {
            setupPopHero(binding)
        }
        binding.homeSearchPill.setOnClickListener {
            findNavController().navigateSafe(HomeFragmentDirections.search())
        }
        binding.homeRecents.adapter =
            NeonRecentAdapter { song -> playbackModel.play(song, homeModel.playWith) }
        binding.homeLists.layoutManager =
            GridLayoutManager(requireContext(), 2, GridLayoutManager.HORIZONTAL, false)
        binding.homeLists.adapter =
            NeonPlaylistAdapter(
                getString(R.string.lbl_favorites_playlist),
                when (uiSettings.style) {
                    AppStyle.VINYL -> VINYL_GRADIENTS
                    AppStyle.POP -> POP_GRADIENTS
                    else -> NEON_GRADIENTS
                },
            ) { playlist ->
                detailModel.showPlaylist(playlist)
            }
        collectImmediately(homeModel.playlistList, ::updateNeonLists)
        collectImmediately(playbackModel.song, ::updateNeonRecents)
    }

    private fun updateNeonLists(playlists: List<Playlist>) {
        val binding = requireBinding()
        (binding.homeLists.layoutManager as? GridLayoutManager)?.spanCount =
            playlists.size.coerceIn(1, 2)
        (binding.homeLists.adapter as? NeonPlaylistAdapter)?.submit(playlists)
        binding.homeListsSection.isVisible = playlists.isNotEmpty()
        refreshNeonRecents()
    }

    private fun updateNeonRecents(song: Song?) {
        if (song != null) recentSongs.add(song.uid)
        refreshNeonRecents()
    }

    private fun refreshNeonRecents() {
        val binding = requireBinding()
        val songs = musicModel.findSongs(recentSongs.load())
        (binding.homeRecents.adapter as? NeonRecentAdapter)?.submit(songs)
        binding.homeRecentsSection.isVisible = songs.isNotEmpty()
    }

    private fun updateNowCard(song: Song?) {
        val binding = requireBinding()
        if (song == null || uiSettings.style == AppStyle.CLASSIC ||
            uiSettings.style == AppStyle.NEON || uiSettings.style == AppStyle.POP) {
            binding.homeNowCard.isVisible = false
            return
        }
        val context = requireContext()
        val hero = uiSettings.style == AppStyle.VINYL
        binding.homeNowCard.isVisible = true
        binding.homeNowHero.isVisible = hero
        binding.homeNowCompact.isVisible = !hero
        binding.homeNowHeroSong.text = song.name.resolve(context)
        binding.homeNowHeroArtist.text = song.artists.resolveNames(context)
        binding.homeNowCover.bind(song)
        binding.homeNowDisc.bind(song)
        if (hero && discSpinner == null) {
            discSpinner =
                DiscSpinner().also {
                    it.attach(binding.homeNowDisc)
                    it.attach(binding.homeNowDiscDecor)
                }
        }
        binding.homeNowSong.text = song.name.resolve(context)
        binding.homeNowArtist.text = song.artists.resolveNames(context)
    }

    private fun updateNowCardPlaying(isPlaying: Boolean) {
        val binding = requireBinding()
        binding.homeNowPlay.isChecked = isPlaying
        discSpinner?.setPlaying(isPlaying)
        binding.homeNowContinue.setText(
            if (isPlaying) R.string.lbl_hero_pause else R.string.lbl_hero_continue
        )
    }

    override fun onDestroyBinding(binding: FragmentHomeBinding) {
        discSpinner?.release()
        discSpinner = null
        super.onDestroyBinding(binding)
        storagePermissionLauncher = null
        binding.homeNormalToolbar.setOnMenuItemClickListener(null)
    }

    override fun onMenuItemClick(item: MenuItem): Boolean {
        if (super.onMenuItemClick(item)) {
            return true
        }

        return when (item.itemId) {
            // Handle main actions (Search, Settings, About)
            R.id.action_search -> {
                L.d("Navigating to search")
                findNavController().navigateSafe(HomeFragmentDirections.search())
                true
            }
            R.id.action_settings -> {
                L.d("Navigating to preferences")
                homeModel.showSettings()
                true
            }
            R.id.action_about -> {
                L.d("Navigating to about")
                homeModel.showAbout()
                true
            }

            // Handle sort menu
            R.id.action_sort -> {
                // Junk click event when opening the menu
                val directions =
                    when (homeModel.currentTabType.value) {
                        MusicType.SONGS -> HomeFragmentDirections.sortSongs()
                        MusicType.ALBUMS -> HomeFragmentDirections.sortAlbums()
                        MusicType.ARTISTS -> HomeFragmentDirections.sortArtists()
                        MusicType.GENRES -> HomeFragmentDirections.sortGenres()
                        MusicType.PLAYLISTS -> HomeFragmentDirections.sortPlaylists()
                    }
                findNavController().navigateSafe(directions)
                true
            }
            else -> {
                L.w("Unexpected menu item selected")
                false
            }
        }
    }

    private fun setupPager(binding: FragmentHomeBinding) {
        binding.homePager.adapter =
            HomePagerAdapter(homeModel.currentTabTypes, childFragmentManager, viewLifecycleOwner)

        val toolbarParams = binding.homeHeader.layoutParams as AppBarLayout.LayoutParams
        if (homeModel.currentTabTypes.size == 1) {
            // A single tab makes the tab layout redundant, hide it and disable the collapsing
            // behavior.
            L.d("Single tab shown, disabling TabLayout")
            binding.homeTabs.isVisible = false
            binding.homeAppbar.setExpanded(true, false)
            toolbarParams.scrollFlags = 0
        } else {
            binding.homeTabs.isVisible = true
            toolbarParams.scrollFlags =
                if (uiSettings.style == AppStyle.CLASSIC) {
                    AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                        AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS
                } else {
                    // Sticky toolbar: only the now playing card scrolls away.
                    AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL or
                        (if (uiSettings.style == AppStyle.NEON || uiSettings.style == AppStyle.VINYL || uiSettings.style == AppStyle.POP) 0
                        else AppBarLayout.LayoutParams.SCROLL_FLAG_ENTER_ALWAYS) or
                        AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED
                }
        }

        // Set up the mapping between the ViewPager and TabLayout.
        TabLayoutMediator(
                binding.homeTabs,
                binding.homePager,
                NamedTabStrategy(homeModel.currentTabTypes),
            )
            .attach()
    }

    private fun updateCurrentTab(tabType: MusicType) {
        val binding = requireBinding()

        // Update the scrolling view in AppBarLayout to align with the current tab's
        // scrolling state. This prevents the lift state from being confused as one
        // goes between different tabs.
        binding.homeAppbar.liftOnScrollTargetViewId =
            when (tabType) {
                MusicType.SONGS -> R.id.home_song_recycler
                MusicType.ALBUMS -> R.id.home_album_recycler
                MusicType.ARTISTS -> R.id.home_artist_recycler
                MusicType.GENRES -> R.id.home_genre_recycler
                MusicType.PLAYLISTS -> R.id.home_playlist_recycler
            }
    }

    private fun handleRecreate(recreate: Unit?) {
        if (recreate == null) return
        val binding = requireBinding()
        L.d("Recreating ViewPager")
        // Move back to position zero, as there must be a tab there.
        binding.homePager.currentItem = 0
        // Make sure tabs are set up to also follow the new ViewPager configuration.
        setupPager(binding)
        homeModel.recreateTabs.consume()
    }

    private fun handleChooseFolders(unit: Unit?) {
        if (unit == null) {
            return
        }
        findNavController().navigateSafe(HomeFragmentDirections.chooseLocations())
        homeModel.chooseMusicLocations.consume()
    }

    private fun updateIndexerState(state: IndexingState?) {
        val binding = requireBinding()
        when (state) {
            is IndexingState.Completed -> {
                binding.homeIndexingContainer.isInvisible = state.error == null
                binding.homeIndexingProgress.isInvisible = state.error != null
                binding.homeIndexingError.isInvisible = state.error == null
                if (state.error != null) {
                    binding.homeIndexingContainer.setOnClickListener {
                        findNavController()
                            .navigateSafe(HomeFragmentDirections.reportError(state.error))
                    }
                } else {
                    binding.homeIndexingContainer.setOnClickListener(null)
                }
            }
            is IndexingState.Indexing -> {
                binding.homeIndexingContainer.isInvisible = false
                binding.homeIndexingProgress.apply {
                    isInvisible = false
                    when (state.progress) {
                        is IndexingProgress.Songs -> {
                            isIndeterminate = false
                            progress = state.progress.loaded
                            max = state.progress.explored
                        }
                        is IndexingProgress.Indeterminate -> {
                            isIndeterminate = true
                        }
                    }
                }
                binding.homeIndexingError.isInvisible = true
            }
            null -> {
                binding.homeIndexingContainer.isInvisible = true
            }
        }
    }

    private fun handlePlaylistDecision(decision: PlaylistDecision?) {
        if (decision == null) return
        val directions =
            when (decision) {
                is PlaylistDecision.New -> {
                    L.d("Creating new playlist")
                    HomeFragmentDirections.newPlaylist(
                        decision.songs.map { it.uid }.toTypedArray(),
                        decision.template,
                        decision.reason,
                    )
                }
                is PlaylistDecision.Import -> {
                    L.d("Importing playlist")
                    pendingImportTarget = decision.target
                    requireNotNull(getContentLauncher) {
                            "Content picker launcher was not available"
                        }
                        .launch(M3U.MIME_TYPE)
                    musicModel.playlistDecision.consume()
                    return
                }
                is PlaylistDecision.Rename -> {
                    L.d("Renaming ${decision.playlist}")
                    HomeFragmentDirections.renamePlaylist(
                        decision.playlist.uid,
                        decision.template,
                        decision.applySongs.map { it.uid }.toTypedArray(),
                        decision.reason,
                    )
                }
                is PlaylistDecision.Export -> {
                    L.d("Exporting ${decision.playlist}")
                    HomeFragmentDirections.exportPlaylist(decision.playlist.uid)
                }
                is PlaylistDecision.Delete -> {
                    L.d("Deleting ${decision.playlist}")
                    HomeFragmentDirections.deletePlaylist(decision.playlist.uid)
                }
                is PlaylistDecision.Add -> {
                    L.d("Adding ${decision.songs.size} to a playlist")
                    HomeFragmentDirections.addToPlaylist(
                        decision.songs.map { it.uid }.toTypedArray()
                    )
                }
            }
        findNavController().navigateSafe(directions)
    }

    private fun handlePlaylistMessage(message: PlaylistMessage?) {
        if (message == null) return
        requireContext().showToast(message.stringRes)
        musicModel.playlistMessage.consume()
    }

    private fun handlePlaybackDecision(decision: PlaybackDecision?) {
        when (decision) {
            is PlaybackDecision.PlayFromArtist -> {
                findNavController()
                    .navigateSafe(HomeFragmentDirections.playFromArtist(decision.song.uid))
            }
            is PlaybackDecision.PlayFromGenre -> {
                findNavController()
                    .navigateSafe(HomeFragmentDirections.playFromGenre(decision.song.uid))
            }
            null -> {}
        }
    }

    private fun handleShow(show: Show?) {
        when (show) {
            is Show.SongDetails -> {
                L.d("Navigating to ${show.song}")
                findNavController().navigateSafe(HomeFragmentDirections.showSong(show.song.uid))
            }
            is Show.SongAlbumDetails -> {
                L.d("Navigating to the album of ${show.song}")
                findNavController()
                    .navigateSafe(HomeFragmentDirections.showAlbum(show.song.album.uid))
            }
            is Show.AlbumDetails -> {
                L.d("Navigating to ${show.album}")
                findNavController().navigateSafe(HomeFragmentDirections.showAlbum(show.album.uid))
            }
            is Show.ArtistDetails -> {
                L.d("Navigating to ${show.artist}")
                findNavController().navigateSafe(HomeFragmentDirections.showArtist(show.artist.uid))
            }
            is Show.SongArtistDecision -> {
                L.d("Navigating to artist choices for ${show.song}")
                findNavController()
                    .navigateSafe(HomeFragmentDirections.showArtistChoices(show.song.uid))
            }
            is Show.AlbumArtistDecision -> {
                L.d("Navigating to artist choices for ${show.album}")
                findNavController()
                    .navigateSafe(HomeFragmentDirections.showArtistChoices(show.album.uid))
            }
            is Show.GenreDetails -> {
                L.d("Navigating to ${show.genre}")
                findNavController().navigateSafe(HomeFragmentDirections.showGenre(show.genre.uid))
            }
            is Show.PlaylistDetails -> {
                L.d("Navigating to ${show.playlist}")
                findNavController()
                    .navigateSafe(HomeFragmentDirections.showPlaylist(show.playlist.uid))
            }
            null -> {}
        }
    }

    private fun handleMenu(menu: Menu?) {
        if (menu == null) return
        val directions =
            when (menu) {
                is Menu.ForSong -> HomeFragmentDirections.openSongMenu(menu.parcel)
                is Menu.ForAlbum -> HomeFragmentDirections.openAlbumMenu(menu.parcel)
                is Menu.ForArtist -> HomeFragmentDirections.openArtistMenu(menu.parcel)
                is Menu.ForGenre -> HomeFragmentDirections.openGenreMenu(menu.parcel)
                is Menu.ForPlaylist -> HomeFragmentDirections.openPlaylistMenu(menu.parcel)
                is Menu.ForSelection -> HomeFragmentDirections.openSelectionMenu(menu.parcel)
            }
        findNavController().navigateSafe(directions)
    }

    private fun updateSelection(selected: List<Music>) {
        val binding = requireBinding()
        if (selected.isNotEmpty()) {
            binding.homeSelectionToolbar.title = getString(R.string.fmt_selected, selected.size)
            if (binding.homeToolbar.setVisible(R.id.home_selection_toolbar)) {
                // New selection started, show the AppBarLayout to indicate the new state.
                L.d("Significant selection occurred, expanding AppBar")
                binding.homeAppbar.expandWithScrollingRecycler()
            }
        } else {
            binding.homeToolbar.setVisible(R.id.home_normal_toolbar)
        }
    }

    /**
     * [FragmentStateAdapter] implementation for the [HomeFragment]'s [ViewPager2] instance.
     *
     * @param tabs The current tab configuration. This will define the [Fragment]s created.
     * @param fragmentManager The [FragmentManager] required by [FragmentStateAdapter].
     * @param lifecycleOwner The [LifecycleOwner], whose Lifecycle is required by
     *   [FragmentStateAdapter].
     */
    private class HomePagerAdapter(
        private val tabs: List<MusicType>,
        fragmentManager: FragmentManager,
        lifecycleOwner: LifecycleOwner,
    ) : FragmentStateAdapter(fragmentManager, lifecycleOwner.lifecycle) {
        override fun getItemCount() = tabs.size

        override fun createFragment(position: Int): Fragment =
            when (tabs[position]) {
                MusicType.SONGS -> SongListFragment()
                MusicType.ALBUMS -> AlbumListFragment()
                MusicType.ARTISTS -> ArtistListFragment()
                MusicType.GENRES -> GenreListFragment()
                MusicType.PLAYLISTS -> PlaylistListFragment()
            }
    }
}
