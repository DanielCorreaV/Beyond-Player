/*
 * Copyright (c) 2021 Auxio Project
 * SearchViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import org.oxycblt.auxio.R
import org.oxycblt.auxio.list.BasicHeader
import org.oxycblt.auxio.list.Item
import org.oxycblt.auxio.list.PlainDivider
import org.oxycblt.auxio.list.sort.Sort
import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.auxio.music.MusicType
import org.oxycblt.auxio.playback.PlaySong
import org.oxycblt.auxio.playback.PlaybackSettings
import org.oxycblt.auxio.music.youtube.NewPipeYtSource
import org.oxycblt.auxio.music.youtube.RemoteTrack
import org.oxycblt.auxio.music.youtube.SongMatcher
import org.oxycblt.auxio.music.youtube.YT_HEADER
import org.oxycblt.auxio.music.youtube.YtResultItem
import org.oxycblt.auxio.music.youtube.YtSource
import org.oxycblt.auxio.music.youtube.YtTrack
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.oxycblt.musikr.Library
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/**
 * An [ViewModel] that keeps performs search operations and tracks their results.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class SearchViewModel
@Inject
constructor(
    private val musicRepository: MusicRepository,
    private val searchEngine: SearchEngine,
    private val searchSettings: SearchSettings,
    private val playbackSettings: PlaybackSettings,
) : ViewModel(), MusicRepository.UpdateListener {
    private var lastQuery: String? = null
    private var currentSearchJob: Job? = null
    private var currentYtJob: Job? = null
    private val ytSource: YtSource = NewPipeYtSource()
    private var ytCache: Pair<String, List<YtTrack>>? = null
    private var lastLocalSongs: List<Song> = emptyList()

    /** The results of the last [search] call, if any. */
    val searchResults: StateFlow<List<Item>>
        field = MutableStateFlow(listOf<Item>())

    /** The [PlaySong] instructions to use when playing a [Song]. */
    val playWith
        get() = playbackSettings.playInListWith

    init {
        musicRepository.addUpdateListener(this)
    }

    override fun onCleared() {
        musicRepository.removeUpdateListener(this)
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        if (changes.deviceLibrary || changes.userLibrary) {
            L.d("Music changed, re-searching library")
            search(lastQuery)
        }
    }

    /**
     * Asynchronously search the music library. Results will be pushed to [searchResults]. Will
     * cancel any previous search operations started prior.
     *
     * @param query The query to search the music library for.
     */
    fun search(query: String?) {
        // Cancel the previous background search.
        currentSearchJob?.cancel()
        currentYtJob?.cancel()
        lastQuery = query

        val library = musicRepository.library
        if (query.isNullOrEmpty() || library == null) {
            L.d("Cannot search for the current query, aborting")
            searchResults.value = listOf()
            return
        }

        // Searching is time-consuming, so do it in the background.
        L.d("Searching music library for $query")
        currentSearchJob =
            viewModelScope.launch(Dispatchers.Default) {
                val local = searchImpl(library, query).also { yield() }
                val localSongs = lastLocalSongs
                searchResults.value = local
                // Local first; YouTube is searched after, in parallel with the user reading.
                searchYouTube(query, local, localSongs)
            }
    }

    private suspend fun searchImpl(library: Library, query: String): List<Item> {
        val filters = searchSettings.filters

        val items =
            if (!filters.isEmpty()) {
                SearchEngine.Items(
                    songs = if (MusicType.SONGS in filters) library.songs else null,
                    albums = if (MusicType.ALBUMS in filters) library.albums else null,
                    artists = if (MusicType.ARTISTS in filters) library.artists else null,
                    genres = if (MusicType.GENRES in filters) library.genres else null,
                    playlists = if (MusicType.PLAYLISTS in filters) library.playlists else null,
                )
            } else {
                SearchEngine.Items(
                    songs = library.songs,
                    albums = library.albums,
                    artists = library.artists,
                    genres = library.genres,
                    playlists = library.playlists,
                )
            }

        val results = searchEngine.search(items, query)
        lastLocalSongs = results.songs.orEmpty().toList()

        return buildList {
            results.artists?.let {
                L.d("Adding ${it.size} artists to search results")
                val header = BasicHeader(R.string.lbl_artists)
                add(header)
                addAll(SORT.artists(it))
            }
            results.albums?.let {
                L.d("Adding ${it.size} albums to search results")
                val header = BasicHeader(R.string.lbl_albums)
                if (isNotEmpty()) {
                    add(PlainDivider(header))
                }

                add(header)
                addAll(SORT.albums(it))
            }
            results.playlists?.let {
                L.d("Adding ${it.size} playlists to search results")
                val header = BasicHeader(R.string.lbl_playlists)
                if (isNotEmpty()) {
                    add(PlainDivider(header))
                }

                add(header)
                addAll(SORT.playlists(it))
            }
            results.genres?.let {
                L.d("Adding ${it.size} genres to search results")
                val header = BasicHeader(R.string.lbl_genres)
                if (isNotEmpty()) {
                    add(PlainDivider(header))
                }

                add(header)
                addAll(SORT.genres(it))
            }
            results.songs?.let {
                L.d("Adding ${it.size} songs to search results")
                val header = BasicHeader(R.string.lbl_songs)
                if (isNotEmpty()) {
                    add(PlainDivider(header))
                }

                add(header)
                addAll(SORT.songs(it))
            }
        }
    }

    private fun searchYouTube(query: String, local: List<Item>, localSongs: List<Song>) {
        val filters = searchSettings.filters
        if (query.length < YT_MIN_CHARS || !(filters.isEmpty() || MusicType.SONGS in filters)) {
            return
        }
        currentYtJob =
            viewModelScope.launch(Dispatchers.Default) {
                val cached = ytCache?.takeIf { it.first == query }?.second
                val tracks =
                    cached
                        ?: run {
                            // Wait for the typing to settle: every keystroke cancels this job.
                            delay(YT_DEBOUNCE_MS)
                            val found =
                                runCatching { withContext(Dispatchers.IO) { ytSource.search(query) } }
                                    .onFailure {
                                        if (it !is CancellationException) L.e(it, "YT search failed")
                                    }
                                    .getOrNull() ?: return@launch
                            ytCache = query to found
                            found
                        }
                val remote = withoutLocal(tracks, localSongs)
                if (remote.isEmpty()) return@launch
                yield()
                searchResults.value = buildList {
                    addAll(local)
                    if (local.isNotEmpty()) add(PlainDivider(YT_HEADER))
                    add(YT_HEADER)
                    remote.forEach { add(YtResultItem(it)) }
                }
            }
    }

    /** Drop the YouTube results the library already has: they are in the local section. */
    private fun withoutLocal(tracks: List<YtTrack>, localSongs: List<Song>): List<YtTrack> {
        if (localSongs.isEmpty()) return tracks
        val match =
            SongMatcher.match(
                tracks.map { RemoteTrack(it.videoId, it.title, it.artist, it.durationSec) },
                localSongs,
            )
        val have = match.pairs.mapNotNull { it.first.videoId }.toSet()
        return tracks.filter { it.videoId !in have }
    }

    /** The current filters used for search. */
    val filters: Set<MusicType>
        get() = searchSettings.filters

    /**
     * Update the filters used by search. Will trigger a research.
     *
     * @param filters The new filters to use.
     */
    fun updateFilters(filters: Set<MusicType>) {
        searchSettings.filters = filters
        search(lastQuery)
    }

    private companion object {
        val SORT = Sort(Sort.Mode.ByName, Sort.Direction.ASCENDING)
        const val YT_MIN_CHARS = 3
        const val YT_DEBOUNCE_MS = 450L
    }
}
