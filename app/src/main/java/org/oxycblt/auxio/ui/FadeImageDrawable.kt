/*
 * Copyright (c) 2026 Beyond Player contributors
 * FadeImageDrawable.kt is part of Beyond Player, a fork of Auxio.
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

package org.oxycblt.auxio.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.Shader
import android.graphics.drawable.Drawable
import kotlin.math.max

/**
 * Draws [bitmap] cropped to fill the bounds, strongest at the top and fading out towards the
 * bottom. [strength] (0..1) is the opacity at the very top.
 */
class FadeImageDrawable(private val bitmap: Bitmap, private val strength: Float) : Drawable() {
    private val imagePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val maskPaint = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) }
    private val src = Rect()
    private var maskHeight = -1

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return

        // Center-crop the bitmap into the bounds, anchored to the top.
        val scale = max(b.width().toFloat() / bitmap.width, b.height().toFloat() / bitmap.height)
        val srcW = (b.width() / scale).toInt().coerceAtMost(bitmap.width)
        val srcH = (b.height() / scale).toInt().coerceAtMost(bitmap.height)
        val srcLeft = ((bitmap.width - srcW) / 2).coerceAtLeast(0)
        src.set(srcLeft, 0, srcLeft + srcW, srcH)

        if (maskHeight != b.height()) {
            maskHeight = b.height()
            val top = (strength * 255).toInt().coerceIn(0, 255)
            val mid = (strength * 0.75f * 255).toInt().coerceIn(0, 255)
            val bottom = (strength * 0.4f * 255).toInt().coerceIn(0, 255)
            maskPaint.shader =
                LinearGradient(
                    0f,
                    b.top.toFloat(),
                    0f,
                    b.bottom.toFloat(),
                    intArrayOf(top shl 24, mid shl 24, bottom shl 24),
                    floatArrayOf(0f, 0.5f, 1f),
                    Shader.TileMode.CLAMP,
                )
        }

        val saved =
            canvas.saveLayer(
                b.left.toFloat(),
                b.top.toFloat(),
                b.right.toFloat(),
                b.bottom.toFloat(),
                null,
            )
        canvas.drawBitmap(bitmap, src, b, imagePaint)
        canvas.drawRect(b, maskPaint)
        canvas.restoreToCount(saved)
    }

    override fun setAlpha(alpha: Int) {
        imagePaint.alpha = alpha
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        imagePaint.colorFilter = colorFilter
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
