/*
 * Copyright (c) 2026 Auxio Project
 * CustomIconView.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings.icon

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.max
import kotlin.math.min

/**
 * Shows a picture under a rounded-square window, which the user can drag and pinch to choose what
 * ends up in an icon. [render] makes the final bitmap.
 */
class CustomIconView @JvmOverloads constructor(context: Context, attrs: AttributeSet? = null) :
    View(context, attrs) {
    private var bitmap: Bitmap? = null
    private val matrix = Matrix()
    private val values = FloatArray(9)
    private val crop = RectF()
    private val dimPath = Path()
    private val maskPath = Path()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val dimPaint = Paint().apply { color = 0xB3000000.toInt() }
    private val outlinePaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 2f * resources.displayMetrics.density
        }

    private val dragDetector =
        GestureDetector(
            context,
            object : GestureDetector.SimpleOnGestureListener() {
                override fun onDown(e: MotionEvent) = true

                override fun onScroll(
                    e1: MotionEvent?,
                    e2: MotionEvent,
                    distanceX: Float,
                    distanceY: Float,
                ): Boolean {
                    matrix.postTranslate(-distanceX, -distanceY)
                    clamp()
                    invalidate()
                    return true
                }
            },
        )

    private val scaleDetector =
        ScaleGestureDetector(
            context,
            object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
                override fun onScale(detector: ScaleGestureDetector): Boolean {
                    matrix.postScale(
                        detector.scaleFactor,
                        detector.scaleFactor,
                        detector.focusX,
                        detector.focusY,
                    )
                    clamp()
                    invalidate()
                    return true
                }
            },
        )

    /** Show a new picture, centered. */
    fun setBitmap(bitmap: Bitmap) {
        this.bitmap = bitmap
        reset()
        invalidate()
    }

    /**
     * Make the picture the layers of an adaptive icon: 108dp wide at 4x, with the window the user
     * framed in the middle 72dp, the part launchers always show.
     */
    fun render(size: Int = 432): Bitmap? {
        val bmp = bitmap ?: return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        // Around the window the launcher may show a bit more, or the picture may not reach: fill
        // with its average color.
        canvas.drawColor(Bitmap.createScaledBitmap(bmp, 1, 1, true).getPixel(0, 0) or 0xFF000000.toInt())
        val visible = size * 72f / 108f
        val m = Matrix(matrix)
        m.postTranslate(-crop.left, -crop.top)
        m.postScale(visible / crop.width(), visible / crop.width())
        m.postTranslate((size - visible) / 2f, (size - visible) / 2f)
        canvas.drawBitmap(bmp, m, paint)
        return out
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val side = min(w, h) * 0.78f
        crop.set((w - side) / 2f, (h - side) / 2f, (w + side) / 2f, (h + side) / 2f)
        val radius = side * 0.3f
        maskPath.reset()
        maskPath.addRoundRect(crop, radius, radius, Path.Direction.CW)
        dimPath.reset()
        dimPath.fillType = Path.FillType.EVEN_ODD
        dimPath.addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
        dimPath.addRoundRect(crop, radius, radius, Path.Direction.CW)
        reset()
    }

    override fun onDraw(canvas: Canvas) {
        val bmp = bitmap ?: return
        canvas.drawBitmap(bmp, matrix, paint)
        canvas.drawPath(dimPath, dimPaint)
        canvas.drawPath(maskPath, outlinePaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            // Inside a dialog or a scroll view, keep the gesture for ourselves.
            parent?.requestDisallowInterceptTouchEvent(true)
        }
        scaleDetector.onTouchEvent(event)
        if (!scaleDetector.isInProgress) dragDetector.onTouchEvent(event)
        return true
    }

    private fun reset() {
        val bmp = bitmap ?: return
        if (crop.isEmpty) return
        val scale = max(crop.width() / bmp.width, crop.height() / bmp.height)
        matrix.reset()
        matrix.postScale(scale, scale)
        matrix.postTranslate(
            crop.centerX() - bmp.width * scale / 2f,
            crop.centerY() - bmp.height * scale / 2f,
        )
    }

    /** Keep the picture covering the window, and the zoom within reason. */
    private fun clamp() {
        val bmp = bitmap ?: return
        val minScale = max(crop.width() / bmp.width, crop.height() / bmp.height)
        matrix.getValues(values)
        val scale = values[Matrix.MSCALE_X]
        val fixed = scale.coerceIn(minScale, minScale * MAX_ZOOM)
        if (fixed != scale) {
            val factor = fixed / scale
            matrix.postScale(factor, factor, crop.centerX(), crop.centerY())
        }
        val bounds = RectF(0f, 0f, bmp.width.toFloat(), bmp.height.toFloat())
        matrix.mapRect(bounds)
        var dx = 0f
        var dy = 0f
        if (bounds.left > crop.left) dx = crop.left - bounds.left
        else if (bounds.right < crop.right) dx = crop.right - bounds.right
        if (bounds.top > crop.top) dy = crop.top - bounds.top
        else if (bounds.bottom < crop.bottom) dy = crop.bottom - bounds.bottom
        matrix.postTranslate(dx, dy)
    }

    private companion object {
        const val MAX_ZOOM = 8f
    }
}
