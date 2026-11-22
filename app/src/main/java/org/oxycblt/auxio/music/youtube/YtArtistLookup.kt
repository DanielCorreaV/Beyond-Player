/*
 * Copyright (c) 2026 Beyond Player
 * YtArtistLookup.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import java.text.Normalizer
import java.util.concurrent.ConcurrentHashMap

/**
 * Finds the YouTube Music artist that matches an artist of the phone, by name. Only a result whose
 * name is the same (ignoring case, accents and punctuation) counts: no profile is better than the
 * wrong one. Answers are remembered, so each artist is searched once.
 */
object YtArtistLookup {
    private class Entry(val at: Long, val item: ExploreItem?)

    private val cache = ConcurrentHashMap<String, Entry>()
    private const val HIT_TTL_MS = 12 * 60 * 60 * 1000L
    private const val MISS_TTL_MS = 60 * 60 * 1000L

    /** Blocking. Null when there is no artist with that name. Throws when the request fails. */
    fun find(name: String): ExploreItem? {
        val key = normalize(name)
        if (key.isEmpty()) return null
        val now = System.currentTimeMillis()
        cache[key]?.let { entry ->
            val ttl = if (entry.item != null) HIT_TTL_MS else MISS_TTL_MS
            if (now - entry.at < ttl) return entry.item
        }
        val found =
            YtExplore.searchArtists(name)
                .flatMap { it.items }
                .firstOrNull { it.kind == ExploreKind.ARTIST && normalize(it.title) == key }
        cache[key] = Entry(now, found)
        return found
    }

    private fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^\\p{L}\\p{N}]+"), "")
}
