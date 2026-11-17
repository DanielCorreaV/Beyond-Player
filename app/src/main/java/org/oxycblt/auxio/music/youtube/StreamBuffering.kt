/*
 * Copyright (c) 2026 Auxio Project
 * StreamBuffering.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * True while a streaming song that should be playing is still waiting for its audio. The player
 * sets it, the mini player shows it as a moving line.
 */
object StreamBuffering {
    private val state = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = state

    fun set(value: Boolean) {
        state.value = value
    }
}
