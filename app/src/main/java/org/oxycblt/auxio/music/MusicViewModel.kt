/*
 * Copyright (c) 2021 Auxio Project
 * MusicViewModel.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.list.ListSettings
import org.oxycblt.auxio.music.youtube.NewPipePlaylistSource
import org.oxycblt.auxio.music.youtube.RemotePlaylistException
import org.oxycblt.auxio.music.youtube.RemotePlaylistSource
import org.oxycblt.auxio.music.youtube.SongMatcher
import org.oxycblt.auxio.music.youtube.YouTubeImportItem
import org.oxycblt.auxio.music.youtube.YouTubeImportResult
import org.oxycblt.auxio.music.youtube.YouTubeLink
import org.oxycblt.auxio.music.youtube.YouTubeLinkStore
import org.oxycblt.auxio.music.youtube.parsePlaylistId
import org.oxycblt.auxio.util.Event
import org.oxycblt.auxio.util.MutableEvent
import org.oxycblt.musikr.Album
import org.oxycblt.musikr.Artist
import org.oxycblt.musikr.Genre
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song
import org.oxycblt.musikr.playlist.ExportConfig
import org.oxycblt.musikr.playlist.ExternalPlaylistManager
import timber.log.Timber as L

/**
 * A [ViewModel] providing data specific to the music loading process.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@HiltViewModel
class MusicViewModel
@Inject
constructor(
    @ApplicationContext context: Context,
    private val listSettings: ListSettings,
    private val musicRepository: MusicRepository,
) : ViewModel(), MusicRepository.UpdateListener, MusicRepository.IndexingListener {
    private val externalPlaylistManager = ExternalPlaylistManager.from(context)
    private val youTubeLinks = YouTubeLinkStore.from(context)
    // Swappable: anything implementing RemotePlaylistSource can replace NewPipe.
    private val youTubeSource: RemotePlaylistSource = NewPipePlaylistSource()
    private var youTubeImportRunning = false

    private val favoritesName = context.getString(R.string.lbl_favorites_playlist)

    /** The [Music.UID]s of the songs in the default "liked songs" playlist. */
    val favoriteUids: StateFlow<Set<Music.UID>>
        field = MutableStateFlow<Set<Music.UID>>(emptySet())

    private val _indexingState = MutableStateFlow<IndexingState?>(null)

    /** The current music loading state, or null if no loading is going on. */
    val indexingState: StateFlow<IndexingState?> = _indexingState

    /** [Statistics] about the last completed music load. */
    val statistics: StateFlow<Statistics?>
        field = MutableStateFlow<Statistics?>(null)

    /**
     * A [PlaylistDecision] command that is awaiting a view capable of responding to it. Null if
     * none currently.
     */
    val playlistDecision: Event<PlaylistDecision>
        field = MutableEvent<PlaylistDecision>()

    val playlistMessage: Event<PlaylistMessage>
        field = MutableEvent<PlaylistMessage>()

    /** Summary of the last YouTube Music import, awaiting a view to show it. */
    val youTubeImportResult: Event<YouTubeImportResult>
        field = MutableEvent<YouTubeImportResult>()

    /** Which link is being read right now (current, total), for the progress dialog. */
    val youTubeProgress: StateFlow<Pair<Int, Int>>
        field = MutableStateFlow(0 to 0)

    /** True while a YouTube Music import is reading from the network. */
    val youTubeImporting: StateFlow<Boolean>
        field = MutableStateFlow(false)

    init {
        musicRepository.addUpdateListener(this)
        musicRepository.addIndexingListener(this)
    }

    override fun onCleared() {
        musicRepository.removeUpdateListener(this)
        musicRepository.removeIndexingListener(this)
    }

    /** Finds the songs with these saved UIDs (see RecentSongs), skipping those that are gone. */
    fun findSongs(uids: List<String>): List<Song> {
        val library = musicRepository.library ?: return emptyList()
        return uids.mapNotNull { Music.UID.fromString(it) }.mapNotNull { library.findSong(it) }
    }

    override fun onMusicChanges(changes: MusicRepository.Changes) {
        musicRepository.library?.let { library ->
            favoriteUids.value =
                library.playlists
                    .firstOrNull { it.name.raw == favoritesName }
                    ?.songs
                    ?.map { it.uid }
                    ?.toSet() ?: emptySet()
        }
        if (!changes.deviceLibrary) return
        val library = musicRepository.library ?: return
        statistics.value =
            Statistics(
                library.songs.size,
                library.albums.size,
                library.artists.size,
                library.genres.size,
                library.songs.sumOf { it.durationMs },
                library.songs.sumOf { it.size },
            )
        L.d("Updated statistics: ${statistics.value}")
    }

    override fun onIndexingStateChanged() {
        _indexingState.value = musicRepository.indexingState
    }

    /** Requests that the music library should be re-loaded while leveraging the cache. */
    fun refresh() {
        L.d("Refreshing library")
        musicRepository.requestIndex(true)
    }

    /** Requests that the music library be re-loaded without the cache. */
    fun rescan() {
        L.d("Rescanning library")
        musicRepository.requestIndex(false)
    }

    /**
     * Create a new generic [Playlist].
     *
     * @param name The name of the new [Playlist]. If null, the user will be prompted for one.
     * @param songs The [Song]s to be contained in the new playlist.
     * @param reason The reason why a new playlist is being created. For all intensive purposes, you
     *   do not need to specify this.
     */
    fun createPlaylist(
        name: String? = null,
        songs: List<Song> = listOf(),
        reason: PlaylistDecision.New.Reason = PlaylistDecision.New.Reason.NEW,
    ) {
        if (name != null) {
            L.d("Creating $name with ${songs.size} songs]")
            viewModelScope.launch(Dispatchers.IO) {
                musicRepository.createPlaylist(name, songs)
                val message =
                    when (reason) {
                        PlaylistDecision.New.Reason.NEW -> PlaylistMessage.NewPlaylistSuccess
                        PlaylistDecision.New.Reason.ADD -> PlaylistMessage.AddSuccess
                        PlaylistDecision.New.Reason.IMPORT -> PlaylistMessage.ImportSuccess
                    }
                playlistMessage.put(message)
            }
        } else {
            L.d("Launching creation dialog for ${songs.size} songs")
            playlistDecision.put(PlaylistDecision.New(songs, null, reason))
        }
    }

    /**
     * Import a playlist from a file [Uri]. Errors pushed to [playlistMessage].
     *
     * @param uri The [Uri] of the file to import. If null, the user will be prompted with a file
     *   picker.
     * @param target The [Playlist] to import to. If null, a new playlist will be created. Note the
     *   [Playlist] will not be renamed to the name of the imported playlist.
     * @see ExternalPlaylistManager
     */
    fun importPlaylist(uri: Uri? = null, target: Playlist? = null) {
        if (uri != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val importedPlaylist = externalPlaylistManager.import(uri)
                if (importedPlaylist == null) {
                    L.e("Could not import playlist")
                    playlistMessage.put(PlaylistMessage.ImportFailed)
                    return@launch
                }

                val library = musicRepository.library ?: return@launch
                val songs =
                    importedPlaylist.paths.mapNotNull {
                        it.firstNotNullOfOrNull(library::findSongByPath)
                    }

                if (songs.isEmpty()) {
                    L.e("No songs found")
                    playlistMessage.put(PlaylistMessage.ImportFailed)
                    return@launch
                }

                if (target !== null) {
                    if (importedPlaylist.name != null && importedPlaylist.name != target.name.raw) {
                        playlistDecision.put(
                            PlaylistDecision.Rename(
                                target,
                                importedPlaylist.name,
                                songs,
                                PlaylistDecision.Rename.Reason.IMPORT,
                            )
                        )
                    } else {
                        musicRepository.rewritePlaylist(target, songs)
                        playlistMessage.put(PlaylistMessage.ImportSuccess)
                    }
                } else {
                    playlistDecision.put(
                        PlaylistDecision.New(
                            songs,
                            importedPlaylist.name,
                            PlaylistDecision.New.Reason.IMPORT,
                        )
                    )
                }
            }
        } else {
            L.d("Launching import picker")
            playlistDecision.put(PlaylistDecision.Import(target))
        }
    }

    /**
     * Import one or more YouTube Music playlists, matching their tracks against the songs already
     * in the library. Re-importing a link updates the playlist created by the first import instead
     * of creating another one. This is the only code that touches the network, and it only runs
     * when the user asks for it.
     *
     * @param links Text containing one playlist link per line.
     */
    fun importFromYouTube(links: String) {
        runYouTubeImport(links.lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct())
    }

    /** Whether [playlist] was created by a YouTube Music import and can be refreshed. */
    fun isYouTubePlaylist(playlist: Playlist) =
        youTubeLinks.getByPlaylist(playlist.uid.toString()) != null

    /** Re-read the YouTube Music playlist [playlist] came from, without pasting the link again. */
    fun refreshYouTubePlaylist(playlist: Playlist) {
        val link = youTubeLinks.getByPlaylist(playlist.uid.toString())
        if (link == null) {
            youTubeImportResult.put(
                YouTubeImportResult(
                    listOf(YouTubeImportItem.Failed(playlist.name.raw, "Not imported from YouTube"))
                )
            )
            return
        }
        runYouTubeImport(listOf(youTubeUrl(link.ytId)))
    }

    /** Re-read every playlist that was imported from YouTube Music. */
    fun refreshAllYouTubePlaylists() {
        val links = youTubeLinks.all()
        if (links.isEmpty()) {
            youTubeImportResult.put(YouTubeImportResult(emptyList()))
            return
        }
        runYouTubeImport(links.map { youTubeUrl(it.ytId) })
    }

    private fun youTubeUrl(ytId: String) = "https://music.youtube.com/playlist?list=$ytId"

    private fun runYouTubeImport(lines: List<String>) {
        if (lines.isEmpty() || youTubeImportRunning) return
        youTubeImportRunning = true
        youTubeImporting.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val items = lines.mapIndexed { index, line ->
                    youTubeProgress.value = (index + 1) to lines.size
                    importOneFromYouTube(line)
                }
                youTubeImportResult.put(YouTubeImportResult(items))
            } finally {
                youTubeImportRunning = false
                youTubeImporting.value = false
            }
        }
    }

    private suspend fun importOneFromYouTube(link: String): YouTubeImportItem {
        val id =
            parsePlaylistId(link)
                ?: return YouTubeImportItem.Failed(link, "No playlist ID (list=...) in the link")
        val remote =
            try {
                youTubeSource.fetch(link)
            } catch (e: RemotePlaylistException) {
                L.e(e, "Could not read YouTube playlist")
                return YouTubeImportItem.Failed(link, e.message ?: "Could not read the playlist")
            }
        val library =
            musicRepository.library?.takeIf { !musicRepository.isLibraryPartial }
                ?: return YouTubeImportItem.Failed(link, "The music library is not loaded yet")

        L.d("YT import: ${remote.tracks.size} remote tracks, ${library.songs.size} local songs")
        remote.tracks.take(5).forEach {
            L.d("YT remote: '${it.title}' | '${it.artist}' | ${it.durationSec}s")
        }
        library.songs.take(5).forEach {
            L.d(
                "YT local: '${it.name.raw}' | ${it.artists.joinToString { a -> (a.name as? org.oxycblt.musikr.tag.Name.Known)?.raw ?: "?" }} | ${it.durationMs / 1000}s"
            )
        }
        val match = SongMatcher.match(remote.tracks, library.songs)
        L.d("YT match: ${match.matchedCount} matched, ${match.missing.size} missing")
        match.pairs.forEach { (t, song) ->
            L.d(
                "YT pair: '${t.title}' | '${t.artist}' | ${t.durationSec}s => '${song.name.raw}' | " +
                    "${song.artists.joinToString { a -> (a.name as? org.oxycblt.musikr.tag.Name.Known)?.raw ?: "?" }} | " +
                    "${song.durationMs / 1000}s"
            )
        }
        match.weakPairs.forEach { (t, song) ->
            L.d("YT weak: '${t.title}' | '${t.artist}' | ${t.durationSec}s => '${song.name.raw}'")
        }
        match.missing.forEach {
            L.d("YT missing: '${it.title}' | '${it.artist}' | ${it.durationSec}s")
        }
        match.unmatched.forEach {
            L.d(
                "YT unmatched local: '${it.name.raw}' | " +
                    "${it.artists.joinToString { a -> (a.name as? org.oxycblt.musikr.tag.Name.Known)?.raw ?: "?" }} | " +
                    "${it.durationMs / 1000}s"
            )
        }
        val stored = youTubeLinks.get(id)
        val existing = stored?.let {
            Music.UID.fromString(it.playlistUid)?.let(library::findPlaylist)
        }

        val playlist: Playlist?
        val created: Boolean
        var kept = 0
        if (existing != null) {
            // Songs the user added by hand are the ones the last sync did not put there. They
            // stay, after the synced songs. Links from before this was tracked have nothing to
            // compare with, so they start clean.
            val lastSynced = stored?.syncedUids?.toSet()
            val additions =
                if (lastSynced == null) emptyList()
                else
                    existing.songs.filter { it.uid.toString() !in lastSynced && it !in match.songs }
            kept = additions.size
            // Never wipe a list because nothing matched (e.g. the library is still scanning).
            if (match.songs.isNotEmpty()) {
                musicRepository.rewritePlaylist(existing, match.songs + additions)
            }
            playlist = existing
            created = false
        } else {
            playlist = musicRepository.createPlaylist(remote.title, match.songs)
            created = true
        }
        if (playlist == null) {
            return YouTubeImportItem.Failed(link, "Could not create the playlist")
        }

        youTubeLinks.put(
            YouTubeLink(
                ytId = id,
                playlistUid = playlist.uid.toString(),
                title = remote.title,
                missing = match.missing,
                syncedUids = match.songs.map { it.uid.toString() },
            )
        )
        return YouTubeImportItem.Done(
            link = link,
            title = playlist.name.raw,
            total = remote.tracks.size,
            matched = match.matchedCount,
            missing = match.missing,
            created = created,
            kept = kept,
        )
    }

    /**
     * Export a [Playlist] to a file [Uri]. Errors pushed to [playlistMessage].
     *
     * @param playlist The [Playlist] to export.
     * @param uri The [Uri] to export to. If null, the user will be prompted for one.
     */
    fun exportPlaylist(playlist: Playlist, uri: Uri? = null, config: ExportConfig? = null) {
        if (uri != null && config != null) {
            L.d("Exporting playlist to $uri")
            viewModelScope.launch(Dispatchers.IO) {
                if (externalPlaylistManager.export(playlist, uri, config)) {
                    playlistMessage.put(PlaylistMessage.ExportSuccess)
                } else {
                    playlistMessage.put(PlaylistMessage.ExportFailed)
                }
            }
        } else {
            L.d("Launching export dialog")
            playlistDecision.put(PlaylistDecision.Export(playlist))
        }
    }

    /**
     * Rename the given playlist.
     *
     * @param playlist The [Playlist] to rename,
     * @param name The new name of the [Playlist]. If null, the user will be prompted for a name.
     * @param applySongs The songs to apply to the playlist after renaming. If empty, no songs will
     *   be applied. This argument is internal and does not need to be specified in normal use.
     * @param reason The reason why the playlist is being renamed. This argument is internal and
     *   does not need to be specified in normal use.
     */
    fun renamePlaylist(
        playlist: Playlist,
        name: String? = null,
        applySongs: List<Song> = listOf(),
        reason: PlaylistDecision.Rename.Reason = PlaylistDecision.Rename.Reason.ACTION,
    ) {
        if (name != null) {
            L.d("Renaming $playlist to $name")
            viewModelScope.launch(Dispatchers.IO) {
                musicRepository.renamePlaylist(playlist, name)
                if (applySongs.isNotEmpty()) {
                    musicRepository.rewritePlaylist(playlist, applySongs)
                }
                val message =
                    when (reason) {
                        PlaylistDecision.Rename.Reason.ACTION -> PlaylistMessage.RenameSuccess
                        PlaylistDecision.Rename.Reason.IMPORT -> PlaylistMessage.ImportSuccess
                    }
                playlistMessage.put(message)
            }
        } else {
            L.d("Launching rename dialog for $playlist")
            playlistDecision.put(PlaylistDecision.Rename(playlist, null, applySongs, reason))
        }
    }

    /**
     * Delete a [Playlist].
     *
     * @param playlist The playlist to delete.
     * @param rude Whether to immediately delete the playlist or prompt the user first. This should
     *   be false at almost all times. This argument is internal and does not need to be specified
     *   in normal use.
     */
    fun deletePlaylist(playlist: Playlist, rude: Boolean = false) {
        if (rude) {
            L.d("Deleting $playlist")
            viewModelScope.launch(Dispatchers.IO) {
                musicRepository.deletePlaylist(playlist)
                playlistMessage.put(PlaylistMessage.DeleteSuccess)
            }
        } else {
            L.d("Launching deletion dialog for $playlist")
            playlistDecision.put(PlaylistDecision.Delete(playlist))
        }
    }

    /**
     * Add a [Song] to a [Playlist].
     *
     * @param song The [Song] to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(song: Song, playlist: Playlist? = null) {
        L.d("Adding $song to playlist")
        addToPlaylist(listOf(song), playlist)
    }

    /**
     * Add an [Album] to a [Playlist].
     *
     * @param album The [Album] to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(album: Album, playlist: Playlist? = null) {
        L.d("Adding $album to playlist")
        addToPlaylist(listSettings.albumSongSort.songs(album.songs), playlist)
    }

    /**
     * Add an [Artist] to a [Playlist].
     *
     * @param artist The [Artist] to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(artist: Artist, playlist: Playlist? = null) {
        L.d("Adding $artist to playlist")
        addToPlaylist(listSettings.artistSongSort.songs(artist.songs), playlist)
    }

    /**
     * Add a [Genre] to a [Playlist].
     *
     * @param genre The [Genre] to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(genre: Genre, playlist: Playlist? = null) {
        L.d("Adding $genre to playlist")
        addToPlaylist(listSettings.genreSongSort.songs(genre.songs), playlist)
    }

    /**
     * Add or remove a [Song] from the default "liked songs" playlist, creating it when needed.
     *
     * @param song The [Song] to like or un-like.
     */
    fun toggleFavorite(song: Song) {
        viewModelScope.launch(Dispatchers.IO) {
            val library = musicRepository.library ?: return@launch
            val favorites = library.playlists.firstOrNull { it.name.raw == favoritesName }
            when {
                favorites == null -> {
                    L.d("Creating the liked songs playlist")
                    musicRepository.createPlaylist(favoritesName, listOf(song))
                }
                favorites.songs.any { it.uid == song.uid } -> {
                    L.d("Removing $song from liked songs")
                    musicRepository.rewritePlaylist(
                        favorites,
                        favorites.songs.filter { it.uid != song.uid },
                    )
                }
                else -> {
                    L.d("Adding $song to liked songs")
                    musicRepository.addToPlaylist(listOf(song), favorites)
                }
            }
        }
    }

    /**
     * Add [Song]s to a [Playlist].
     *
     * @param songs The [Song]s to add to the [Playlist].
     * @param playlist The [Playlist] to add to. If null, the user will be prompted for one.
     */
    fun addToPlaylist(songs: List<Song>, playlist: Playlist? = null) {
        if (playlist != null) {
            L.d("Adding ${songs.size} songs to $playlist")
            viewModelScope.launch(Dispatchers.IO) {
                musicRepository.addToPlaylist(songs, playlist)
                playlistMessage.put(PlaylistMessage.AddSuccess)
            }
        } else {
            L.d("Launching addition dialog for songs=${songs.size}")
            playlistDecision.put(PlaylistDecision.Add(songs))
        }
    }

    /**
     * Non-manipulated statistics bound the last successful music load.
     *
     * @param songs The amount of [Song]s that were loaded.
     * @param albums The amount of [Album]s that were created.
     * @param artists The amount of [Artist]s that were created.
     * @param genres The amount of [Genre]s that were created.
     * @param durationMs The total duration of all songs in the library, in milliseconds.
     */
    data class Statistics(
        val songs: Int,
        val albums: Int,
        val artists: Int,
        val genres: Int,
        val durationMs: Long,
        val totalSizeBytes: Long,
    )
}

/**
 * Navigation command for when a [Playlist] must have some operation performed on it by the user.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
sealed interface PlaylistDecision {
    /**
     * Navigate to a dialog that allows a user to pick a name for a new [Playlist].
     *
     * @param songs The [Song]s to contain in the new [Playlist].
     * @param template An existing playlist name that should be editable in the opened dialog. If
     *   null, a placeholder should be created and shown as a hint instead.
     * @param reason The reason why a new playlist is being created. For all intensive purposes, you
     *   do not need to specify this.
     */
    data class New(val songs: List<Song>, val template: String?, val reason: Reason) :
        PlaylistDecision {
        enum class Reason {
            NEW,
            ADD,
            IMPORT,
        }
    }

    /**
     * Navigate to a file picker to import a playlist from.
     *
     * @param target The [Playlist] to import to. If null, then the file imported will create a new
     *   playlist.
     */
    data class Import(val target: Playlist?) : PlaylistDecision

    /**
     * Navigate to a dialog that allows a user to rename an existing [Playlist].
     *
     * @param playlist The playlist to act on.
     */
    data class Rename(
        val playlist: Playlist,
        val template: String?,
        val applySongs: List<Song>,
        val reason: Reason,
    ) : PlaylistDecision {
        enum class Reason {
            ACTION,
            IMPORT,
        }
    }

    /**
     * Navigate to a dialog that allows the user to export a [Playlist].
     *
     * @param playlist The [Playlist] to export.
     */
    data class Export(val playlist: Playlist) : PlaylistDecision

    /**
     * Navigate to a dialog that confirms the deletion of an existing [Playlist].
     *
     * @param playlist The playlist to act on.
     */
    data class Delete(val playlist: Playlist) : PlaylistDecision

    /**
     * Navigate to a dialog that allows the user to add [Song]s to a [Playlist].
     *
     * @param songs The [Song]s to add to the chosen [Playlist].
     */
    data class Add(val songs: List<Song>) : PlaylistDecision
}

sealed interface PlaylistMessage {
    val stringRes: Int

    data object NewPlaylistSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_created
    }

    data object ImportSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_imported
    }

    data object ImportFailed : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.err_import_failed
    }

    data object RenameSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_renamed
    }

    data object DeleteSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_deleted
    }

    data object AddSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_added
    }

    data object ExportSuccess : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.lng_playlist_exported
    }

    data object ExportFailed : PlaylistMessage {
        override val stringRes: Int
            get() = R.string.err_export_failed
    }
}
