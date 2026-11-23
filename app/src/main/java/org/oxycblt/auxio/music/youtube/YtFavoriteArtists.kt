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

/** The artists the user marked with the heart, by name. Kept in the app's preferences. */
object YtFavoriteArtists {
    private const val PREFS = "beyond_fav_artists"
    private const val KEY = "names"

    private val _changes = MutableStateFlow(0)
    /** Goes up every time the favorites change, so lists can rebuild themselves. */
    val changes: StateFlow<Int> = _changes

    private fun key(name: String) = name.trim().lowercase()

    private fun load(context: Context): Set<String> =
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY, emptySet())
            ?.toSet() ?: emptySet()

    fun isFavorite(context: Context, name: String): Boolean = key(name) in load(context)

    /** The favorites of the app (empty until the library has started). */
    fun names(): Set<String> = YtLibrary.context()?.let { load(it) } ?: emptySet()

    /** Flips the heart of [name]. Returns true when it is a favorite now. */
    fun toggle(context: Context, name: String): Boolean {
        val k = key(name)
        val now = load(context).toMutableSet()
        val on = now.add(k)
        if (!on) now.remove(k)
        context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putStringSet(KEY, now)
            .apply()
        _changes.value = _changes.value + 1
        return on
    }
}
