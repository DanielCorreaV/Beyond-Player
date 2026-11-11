/*
 * Copyright (c) 2026 Auxio Project
 * BandSliderView.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.equalizer

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import com.google.android.material.color.MaterialColors
import kotlin.math.roundToInt

/**
 * A vertical slider for one equalizer band, like on a mixing desk: drag up to boost, down to cut.
 * The centre is 0 dB and the bar is filled from there to the handle.
 */
class BandSliderView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density
    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color =
                MaterialColors.getColor(
                    context,
                    com.google.android.material.R.attr.colorOutlineVariant,
                    0xFFBBBBBB.toInt(),
                )
            strokeWidth = 4 * density
            strokeCap = Paint.Cap.ROUND
        }
    private val activePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color =
                MaterialColors.getColor(
                    context,
                    android.R.attr.colorPrimary,
                    0xFF6750A4.toInt(),
                )
            strokeWidth = 6 * density
            strokeCap = Paint.Cap.ROUND
        }
    private val thumbRadius = 11 * density
    private val tickPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = trackPaint.color
            strokeWidth = 2 * density
        }

    /** Lowest level in millibels. */
    var minLevel = -1500

    /** Highest level in millibels. */
    var maxLevel = 1500

    /** Current level in millibels. */
    var level = 0
        set(value) {
            field = value.coerceIn(minLevel, maxLevel)
            invalidate()
        }

    /** Called with the new level in millibels while the user drags. */
    var onLevelChanged: ((Int) -> Unit)? = null

    private fun top() = thumbRadius

    private fun bottom() = height - thumbRadius

    private fun yFor(level: Int): Float {
        val fraction = (level - minLevel).toFloat() / (maxLevel - minLevel).coerceAtLeast(1)
        return bottom() - fraction * (bottom() - top())
    }

    override fun onDraw(canvas: Canvas) {
        val x = width / 2f
        val zeroY = yFor(0)
        val levelY = yFor(level)
        canvas.drawLine(x, top(), x, bottom(), trackPaint)
        canvas.drawLine(x - 8 * density, zeroY, x + 8 * density, zeroY, tickPaint)
        canvas.drawLine(x, zeroY, x, levelY, activePaint)
        canvas.drawCircle(x, levelY, thumbRadius, activePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Disabled (equalizer off): do not take the touch, so the dialog can scroll past.
        if (!isEnabled) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                // Keep the scroll view from stealing the drag.
                parent?.requestDisallowInterceptTouchEvent(true)
                val fraction = ((bottom() - event.y) / (bottom() - top())).coerceIn(0f, 1f)
                var next = (minLevel + fraction * (maxLevel - minLevel)).roundToInt()
                // Snap to 0 dB when close, so a flat band is easy to get back to.
                if (kotlin.math.abs(next) < (maxLevel - minLevel) / 60) next = 0
                if (next != level) {
                    level = next
                    onLevelChanged?.invoke(next)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}
