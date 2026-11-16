/*
 * Copyright (c) 2026 Auxio Project
 * StreamLoss.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * The countdown shown when the connection drops while streaming: the player starts it, the screen
 * shows [countdown] with a Cancel button. Everything runs on the main thread.
 */
object StreamLoss {
    private val state = MutableStateFlow<Int?>(null)

    /** Seconds left before moving to the next song, or null when nothing is pending. */
    val countdown: StateFlow<Int?> = state

    private var job: Job? = null
    private var onCancel: (() -> Unit)? = null

    fun start(scope: CoroutineScope, seconds: Int, onTimeout: () -> Unit, onCancel: () -> Unit) {
        job?.cancel()
        this.onCancel = onCancel
        job =
            scope.launch {
                for (left in seconds downTo 1) {
                    state.value = left
                    delay(1000)
                }
                state.value = null
                this@StreamLoss.onCancel = null
                onTimeout()
            }
    }

    /** The user chose to stay on this song. */
    fun cancel() {
        val callback = onCancel
        clear()
        callback?.invoke()
    }

    /** Forget a pending countdown without doing anything (the user moved on by themselves). */
    fun clear() {
        job?.cancel()
        job = null
        onCancel = null
        state.value = null
    }
}
