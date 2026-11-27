/*
 * Copyright (c) 2026 Beyond Player
 * YtAutoContinue.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import android.content.Context
import androidx.preference.PreferenceManager
import org.oxycblt.auxio.music.resolve
import org.oxycblt.auxio.music.resolveNames
import org.oxycblt.musikr.Song
import timber.log.Timber as L

/** What plays when the queue ends: the radio of the last song, from YouTube Music. */
object YtAutoContinue {
    private const val KEY = "yt_continue_enabled"
    private val seen = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    /** The user's own switch, regardless of the YouTube search setting. */
    fun userEnabled(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, true)

    /** Flips the switch and returns the new state. */
    fun toggle(context: Context): Boolean {
        val on = !userEnabled(context)
        PreferenceManager.getDefaultSharedPreferences(context).edit().putBoolean(KEY, on).apply()
        return on
    }

    /** On unless the YouTube search is off in the settings or [KEY] was turned off. */
    fun enabled(context: Context): Boolean =
        YtLibrary.searchEnabled() &&
            PreferenceManager.getDefaultSharedPreferences(context).getBoolean(KEY, true)

    /**
     * The songs YouTube lines up after [song] that are not in [queued] (video ids) nor were added
     * before. Blocking: call from a background dispatcher. Empty when nothing could be found.
     */
    fun nextTracks(context: Context, song: Song, queued: Set<String>): List<YtTrack> {
        val id = seedId(context, song) ?: return emptyList()
        val detail =
            try {
                YtExplore.detail(
                    ExploreItem(ExploreKind.PLAYLIST, "", "", "", null, playlistId = "RDAMVM$id"),
                    null,
                )
            } catch (e: Exception) {
                L.w("YT continue: no radio for $id: ${e.message}")
                return emptyList()
            }
        if (seen.size > 1000) seen.clear()
        seen.add(id)
        return detail.tracks
            .filter { it.videoId !in queued && seen.add(it.videoId) }
            .take(20)
    }

    /** The video of [song] on YouTube Music: its own for a cloud song, else the first search hit. */
    private fun seedId(context: Context, song: Song): String? {
        if (song.uri.scheme == "yt") return song.uri.toString().removePrefix("yt://")
        val query = (song.artists.resolveNames(context) + " " + song.name.resolve(context)).trim()
        if (query.isEmpty()) return null
        return try {
            NewPipeYtSource().search(query).firstOrNull()?.videoId
        } catch (e: Exception) {
            L.w("YT continue: search failed: ${e.message}")
            null
        }
    }
}
