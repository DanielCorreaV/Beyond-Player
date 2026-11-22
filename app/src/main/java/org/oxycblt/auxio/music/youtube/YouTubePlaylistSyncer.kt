/*
 * Copyright (c) 2026 Beyond Player
 * YouTubePlaylistSyncer.kt is part of Beyond Player, a fork of Auxio.
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
 

package org.oxycblt.auxio.music.youtube

import org.oxycblt.auxio.music.MusicRepository
import org.oxycblt.musikr.Music
import org.oxycblt.musikr.Playlist
import org.oxycblt.musikr.Song

import timber.log.Timber as L

/**
 * Reads a YouTube Music playlist and makes its local playlist match, keeping songs the user added by
 * hand. Moved here unchanged from MusicViewModel so the same code runs from the import dialog and
 * from the download service, which refreshes playlists on its own once their missing songs arrive.
 */
class YouTubePlaylistSyncer(
    private val musicRepository: MusicRepository,
    private val youTubeLinks: YouTubeLinkStore,
    private val youTubeSource: RemotePlaylistSource,
) {
    /**
     * Adds the tracks that are not on the phone to the streaming list and refreshes the library from its
     * cache, so they exist as cloud songs. False when nothing was added or the refresh was not possible.
     */
    private suspend fun addCloudSongs(missing: List<RemoteTrack>): Boolean {
        val tracks =
            missing.mapNotNull { t ->
                val id = t.videoId ?: return@mapNotNull null
                YtTrack(id, t.title, t.artist, t.durationSec, t.thumbnailUrl)
            }
        if (tracks.isEmpty()) return false
        // Nothing new in the streaming list (the songs were there already, or they were downloaded): the
        // library of the last scan has them all. Reloading from the cache here could drop files that were
        // just downloaded, so it only happens when cloud songs were really added.
        val added = YtLibrary.addAll(tracks)
        // Covers of the songs of saved lists only (never of what is just streamed from a search). They
        // are downloaded before the library reloads so the cloud songs come out with them.
        val covers = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            YtLibrary.ensureCovers()
        }
        if (added == 0 && !covers) return false
        return musicRepository.refreshFromCache()
    }

    /** Read the YouTube playlist behind [link] and make the local playlist match it. */
    suspend fun syncOne(link: String): YouTubeImportItem {
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
        var library =
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
        // Streamed (cloud) songs never count as already being on the phone.
        var match = SongMatcher.match(remote.tracks, library.songs.filter { it.uri.scheme != "yt" })
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
        // What is not on the phone joins the library as cloud songs (played by streaming), so the playlist
        // is playable at once. Downloading one later replaces it with the real file.
        if (addCloudSongs(match.missing)) {
            musicRepository.library?.let { library = it }
            match = SongMatcher.match(remote.tracks, library.songs.filter { it.uri.scheme != "yt" })
        }
        val cloudSongs =
            library.songs
                .filter { it.uri.scheme == "yt" }
                .associateBy { it.uri.toString().removePrefix("yt://") }
        val byTrack = java.util.IdentityHashMap<RemoteTrack, Song>()
        match.pairs.forEach { (track, song) -> byTrack[track] = song }
        // The playlist in the order of the YouTube list: the file when there is one, else the cloud song.
        val ordered =
            remote.tracks
                .mapNotNull { track -> byTrack[track] ?: track.videoId?.let { cloudSongs[it] } }
                .distinct()
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
                    existing.songs.filter { it.uid.toString() !in lastSynced && it !in ordered }
            kept = additions.size
            // Never wipe a list because nothing matched (e.g. the library is still scanning).
            if (ordered.isNotEmpty()) {
                musicRepository.rewritePlaylist(existing, ordered + additions)
            }
            playlist = existing
            created = false
        } else {
            playlist = musicRepository.createPlaylist(remote.title, ordered)
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
                syncedUids = ordered.map { it.uid.toString() },
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
}
