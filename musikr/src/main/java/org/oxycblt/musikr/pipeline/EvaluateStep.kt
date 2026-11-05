/*
 * Copyright (c) 2024 Auxio Project
 * EvaluateStep.kt is part of Auxio.
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
 
package org.oxycblt.musikr.pipeline

import android.content.Context
import kotlinx.coroutines.channels.Channel
import org.oxycblt.musikr.BuildConfig
import org.oxycblt.musikr.Config
import org.oxycblt.musikr.Interpretation
import org.oxycblt.musikr.MutableLibrary
import org.oxycblt.musikr.graph.MusicGraph
import org.oxycblt.musikr.model.LibraryFactory
import org.oxycblt.musikr.playlist.db.StoredPlaylists
import org.oxycblt.musikr.playlist.interpret.PlaylistInterpreter
import org.oxycblt.musikr.tag.interpret.TagInterpreter

internal interface EvaluateStep {
    suspend fun evaluate(
        extractedMusic: Channel<Extracted>,
        onPartial: (suspend (MutableLibrary) -> Unit)? = null,
    ): MutableLibrary

    companion object {
        fun new(context: Context, config: Config, interpretation: Interpretation): EvaluateStep =
            EvaluateStepImpl(
                context,
                TagInterpreter.new(interpretation),
                PlaylistInterpreter.new(interpretation),
                config.storage.storedPlaylists,
                LibraryFactory.new(),
            )
    }
}

/** Flip to true only when you need to inspect the music graph on a debug build. */
private const val RENDER_GRAPH = false

// The first partial library is published as soon as this many songs are read, so something shows
// up quickly. After that, one is published at most every PARTIAL_INTERVAL_MS, and only if at
// least MIN_NEW_PARTIAL_SONGS songs arrived since the last one.
private const val FIRST_PARTIAL_SONGS = 12
private const val MIN_NEW_PARTIAL_SONGS = 15
private const val PARTIAL_INTERVAL_MS = 250L
// Past this size rebuilding snapshots costs more than it helps, so only the final one is shown.
private const val MAX_PARTIAL_SONGS = 2000

private class EvaluateStepImpl(
    private val context: Context,
    private val tagInterpreter: TagInterpreter,
    private val playlistInterpreter: PlaylistInterpreter,
    private val storedPlaylists: StoredPlaylists,
    private val libraryFactory: LibraryFactory,
) : EvaluateStep {
    override suspend fun evaluate(
        extractedMusic: Channel<Extracted>,
        onPartial: (suspend (MutableLibrary) -> Unit)?,
    ): MutableLibrary {
        // Snapshots of the songs read so far, so the UI can show music before the scan ends.
        val preSongs = mutableListOf<org.oxycblt.musikr.tag.interpret.PreSong>()
        var lastPartialSize = 0
        var nextPartialAt = 0L
        val e0 = android.os.SystemClock.elapsedRealtime()
        var interpretMs = 0L
        val builder = MusicGraph.builder()
        for (extracted in extractedMusic) {
            when (extracted) {
                is RawSong -> {
                    val i0 = android.os.SystemClock.elapsedRealtime()
                    val pre = tagInterpreter.interpret(extracted)
                    val i1 = android.os.SystemClock.elapsedRealtime()
                    builder.add(pre)
                    interpretMs += i1 - i0
                    if (onPartial != null) {
                        preSongs.add(pre)
                        val now = android.os.SystemClock.elapsedRealtime()
                        val due =
                            if (lastPartialSize == 0) {
                                preSongs.size >= FIRST_PARTIAL_SONGS
                            } else {
                                now >= nextPartialAt &&
                                    preSongs.size - lastPartialSize >= MIN_NEW_PARTIAL_SONGS
                            }
                        if (due && preSongs.size <= MAX_PARTIAL_SONGS) {
                            // The real graph is mutated when built, so snapshots use their own.
                            val snapshot = MusicGraph.builder()
                            preSongs.forEach { snapshot.add(it) }
                            onPartial(
                                libraryFactory.create(
                                    snapshot.build(),
                                    storedPlaylists,
                                    playlistInterpreter,
                                )
                            )
                            lastPartialSize = preSongs.size
                            // Back off if snapshots are getting expensive on big libraries.
                            val spent = android.os.SystemClock.elapsedRealtime() - now
                            nextPartialAt =
                                android.os.SystemClock.elapsedRealtime() +
                                    maxOf(PARTIAL_INTERVAL_MS, spent * 3)
                        }
                    }
                }
                is RawPlaylist -> builder.add(playlistInterpreter.interpret(extracted.file))
                is NotAudio -> {}
                is InvalidSong -> {}
            }
        }
        val e1 = android.os.SystemClock.elapsedRealtime()
        val graph = builder.build()
        val e2 = android.os.SystemClock.elapsedRealtime()

        // Graphviz rendering is very slow on large libraries, so it is opt-in.
        if (BuildConfig.DEBUG && RENDER_GRAPH) {
            val fileName = "music_graph_debug.dot"
            graph.renderToGraphviz(context, fileName)
        }

        val e3 = android.os.SystemClock.elapsedRealtime()
        val library = libraryFactory.create(graph, storedPlaylists, playlistInterpreter)
        android.util.Log.i(
            "AuxioPerf",
            "evaluate: receive+interpret=${e1 - e0}ms (interpret=${interpretMs}ms), graph build=${e2 - e1}ms, library create=${android.os.SystemClock.elapsedRealtime() - e3}ms",
        )
        return library
    }
}
