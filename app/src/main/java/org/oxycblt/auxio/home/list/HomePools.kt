/*
 * Copyright (c) 2026 Beyond Player
 * HomePools.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.home.list

import android.app.Activity
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import java.lang.ref.WeakReference

/**
 * Pools of already built rows for the lists of Home, kept while the app is open so that coming back
 * to Home does not inflate every row again. There is one pool per list (their rows are not the same
 * kind), and all of them are dropped when the activity is recreated.
 */
object HomePools {
    private var owner: WeakReference<Activity>? = null
    private val pools = HashMap<String, RecyclerView.RecycledViewPool>()
    private val keepers = ArrayList<RecyclerView>()

    /** A pool empties itself when no list uses it; this adapter with no rows keeps one "in use". */
    private object Empty : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount() = 0

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder =
            throw UnsupportedOperationException()

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {}
    }

    @Synchronized
    fun of(activity: Activity, key: String): RecyclerView.RecycledViewPool {
        if (owner?.get() !== activity) {
            pools.clear()
            keepers.clear()
            owner = WeakReference(activity)
        }
        return pools.getOrPut(key) {
            val pool = RecyclerView.RecycledViewPool()
            pool.setMaxRecycledViews(0, 24)
            keepers +=
                RecyclerView(activity.applicationContext).apply {
                    adapter = Empty
                    setRecycledViewPool(pool)
                }
            pool
        }
    }
}
