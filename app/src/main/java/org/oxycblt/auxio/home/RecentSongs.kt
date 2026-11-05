package org.oxycblt.auxio.home

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import org.oxycblt.musikr.Music

/** Remembers the last songs that were played, for "Seguir escuchando". */
@Singleton
class RecentSongs @Inject constructor(@ApplicationContext context: Context) {
    private val prefs = context.getSharedPreferences("recent_songs", Context.MODE_PRIVATE)

    fun add(uid: Music.UID) {
        val id = uid.toString()
        val list = load().toMutableList()
        if (list.firstOrNull() == id) return
        list.remove(id)
        list.add(0, id)
        prefs.edit().putString(KEY, list.take(MAX).joinToString("\n")).apply()
    }

    fun load(): List<String> =
        prefs.getString(KEY, "")?.split("\n")?.filter { it.isNotEmpty() } ?: emptyList()

    private companion object {
        const val KEY = "uids"
        const val MAX = 20
    }
}
