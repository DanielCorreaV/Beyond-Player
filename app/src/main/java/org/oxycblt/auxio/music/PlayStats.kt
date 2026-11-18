/*
 * Copyright (c) 2026 Auxio Project
 * PlayStats.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music

import android.content.Context
import java.io.File
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * How many times each song was played (a play = 30 s in a row), kept in files/play_stats.json.
 * Songs are identified by their uid text, which stays the same when the library is rebuilt.
 */
object PlayStats {
    private class Entry(var count: Int, var lastMs: Long)

    private val lock = Any()
    private val entries = HashMap<String, Entry>()
    private var file: File? = null
    private val writer = Executors.newSingleThreadExecutor()

    fun install(context: Context) {
        val f = File(context.applicationContext.filesDir, "play_stats.json")
        synchronized(lock) {
            file = f
            entries.clear()
            runCatching {
                if (f.isFile) {
                    val json = JSONObject(f.readText())
                    for (key in json.keys()) {
                        val o = json.getJSONObject(key)
                        entries[key] = Entry(o.optInt("c", 0), o.optLong("t", 0L))
                    }
                }
            }
        }
    }

    /** One more play of the song with this uid text. */
    fun record(uid: String) {
        val snapshot: String
        synchronized(lock) {
            val entry = entries.getOrPut(uid) { Entry(0, 0L) }
            entry.count++
            entry.lastMs = System.currentTimeMillis()
            val json = JSONObject()
            for ((key, e) in entries) {
                json.put(key, JSONObject().put("c", e.count).put("t", e.lastMs))
            }
            snapshot = json.toString()
        }
        val f = file ?: return
        writer.execute { runCatching { f.writeText(snapshot) } }
    }

    /** How many plays this song has (0 if none). */
    fun count(uid: String): Int = synchronized(lock) { entries[uid]?.count ?: 0 }

    /** The uid texts of the most played songs, most played first (ties: the latest played first). */

    fun top(limit: Int): List<String> =
        synchronized(lock) {
            entries.entries
                .filter { it.value.count > 0 }
                .sortedWith(
                    compareByDescending<Map.Entry<String, Entry>> { it.value.count }
                        .thenByDescending { it.value.lastMs }
                )
                .take(limit)
                .map { it.key }
        }
}
