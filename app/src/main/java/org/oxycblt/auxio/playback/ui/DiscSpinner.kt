/*
 * Copyright (c) 2026 Auxio Project
 * DiscSpinner.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.ui

import android.animation.ValueAnimator
import android.view.View
import android.view.animation.LinearInterpolator
import org.oxycblt.auxio.R

/**
 * Spins record-shaped cover views (Vinyl style). A single animation drives every attached view so
 * neighbouring covers stay in sync, and pausing keeps the angle so playback resumes smoothly.
 */
class DiscSpinner(private val revolutionMs: Long = 9000L) {
    private val views = mutableSetOf<View>()
    private var angle = 0f
    private val animator =
        ValueAnimator.ofFloat(0f, 360f).apply {
            duration = revolutionMs
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = it.animatedValue as Float
                views.forEach { view -> view.rotation = angle }
            }
        }

    /** Spin the record parts of a cover page, leaving its touch zones (double tap) alone. */
    fun attachPage(page: View) {
        page.findViewById<View>(R.id.cover)?.let(::attach)
        page.findViewById<View>(R.id.cover_decor)?.let(::attach)
    }

    fun detachPage(page: View) {
        page.findViewById<View>(R.id.cover)?.let(::detach)
        page.findViewById<View>(R.id.cover_decor)?.let(::detach)
    }

    fun attach(view: View) {
        views.add(view)
        view.rotation = angle
    }

    fun detach(view: View) {
        views.remove(view)
        view.rotation = 0f
    }

    fun setPlaying(isPlaying: Boolean) {
        if (isPlaying) {
            if (!animator.isStarted) {
                animator.start()
            } else if (animator.isPaused) {
                animator.resume()
            }
        } else if (animator.isRunning) {
            animator.pause()
        }
    }

    fun release() {
        animator.cancel()
        views.forEach { it.rotation = 0f }
        views.clear()
    }

    private companion object {
        const val REVOLUTION_MS = 9000L
    }
}
