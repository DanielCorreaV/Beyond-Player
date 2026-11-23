/*
 * Copyright (c) 2026 Beyond Player
 * YtFavoriteArtists.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** The artists the user marked with the heart, by name as written. Kept in the app's preferences. */
object YtFavoriteArtists {
    private const val PREFS = "beyond_fav_artists"
    private const val KEY = "names"

    private val _changes = MutableStateFlow(0)
    /** Goes up every time the favorites change, so lists can rebuild themselves. */
    val changes: StateFlow<Int> = _changes

    private fun key(name: String) = name.trim().lowercase()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun load(context: Context): Set<String> =
        prefs(context).getStringSet(KEY, emptySet())?.toSet() ?: emptySet()

    fun isFavorite(context: Context, name: String): Boolean =
        load(context).any { key(it) == key(name) }

    /** The favorites as written, in alphabetical order. */
    fun list(context: Context): List<String> =
        load(context).sortedBy { it.lowercase() }

    /** The favorites in lower case, for matching (empty until the library has started). */
    fun names(): Set<String> =
        YtLibrary.context()?.let { c -> load(c).map { key(it) }.toSet() } ?: emptySet()

    /** Flips the heart of [name]. Returns true when it is a favorite now. */
    fun toggle(context: Context, name: String): Boolean {
        val k = key(name)
        val now = load(context).toMutableSet()
        val had = now.removeAll { key(it) == k }
        if (!had) now.add(name.trim())
        prefs(context).edit().putStringSet(KEY, now).apply()
        _changes.value = _changes.value + 1
        return !had
    }
}
