/*
 * Copyright (c) 2026 Auxio Project
 * WalkmanTouchLayout.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback

import android.content.Context
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import org.oxycblt.auxio.R

/**
 * Hosts the Walkman cassette and a transparent step overlay on top of it. Touches that start on the
 * cover pager go to the cassette (so the cover can still be swiped); every other touch goes to the
 * overlay (double tap on either side to skip back/forward).
 */
class WalkmanTouchLayout(context: Context, attrs: AttributeSet?) : FrameLayout(context, attrs) {
    private var overPager = false
    private val rect = Rect()

    // The cassette decides the size; the overlay is stretched to exactly that, never taller.
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val card: View? = findViewById(R.id.playback_walkman_card)
        val overlay: View? = findViewById(R.id.playback_walkman_seek)
        if (card == null || overlay == null) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec)
            return
        }
        val padW = paddingLeft + paddingRight
        val padH = paddingTop + paddingBottom
        card.measure(
            getChildMeasureSpec(widthMeasureSpec, padW, card.layoutParams.width),
            getChildMeasureSpec(heightMeasureSpec, padH, card.layoutParams.height),
        )
        overlay.measure(
            MeasureSpec.makeMeasureSpec(card.measuredWidth, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(card.measuredHeight, MeasureSpec.EXACTLY),
        )
        setMeasuredDimension(
            resolveSize(card.measuredWidth + padW, widthMeasureSpec),
            resolveSize(card.measuredHeight + padH, heightMeasureSpec),
        )
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val card: View? = findViewById(R.id.playback_walkman_card)
        val overlay: View? = findViewById(R.id.playback_walkman_seek)
        if (card == null || overlay == null) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            val pager: View? = findViewById(R.id.playback_pager)
            overPager =
                pager != null &&
                    run {
                        pager.getDrawingRect(rect)
                        offsetDescendantRectToMyCoords(pager, rect)
                        rect.contains(ev.x.toInt(), ev.y.toInt())
                    }
        }
        return (if (overPager) card else overlay).dispatchTouchEvent(ev)
    }
}
