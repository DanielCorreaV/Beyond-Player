/*
 * Copyright (c) 2026 Auxio Project
 * BackgroundImage.kt is part of Auxio.
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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import java.io.File
import kotlin.math.max

/**
 * The optional custom background image of the app. The picked photo is copied (and downscaled) into
 * the app's private storage, so no storage permission has to be kept around.
 */
object BackgroundImage {
    private const val FILE_NAME = "style_background.jpg"
    private const val MAX_SIZE = 1600

    fun file(context: Context) = File(context.filesDir, FILE_NAME)

    fun exists(context: Context) = file(context).isFile

    fun clear(context: Context) {
        file(context).delete()
    }

    /** Copy [uri] into private storage. Must be called off the main thread. */
    fun import(context: Context, uri: Uri): Boolean {
        return try {
            val resolver = context.contentResolver
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return false

            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_SIZE) {
                sample *= 2
            }
            val options = BitmapFactory.Options().apply { inSampleSize = sample }
            var bitmap =
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
                    ?: return false

            val rotation =
                resolver.openInputStream(uri)?.use {
                    when (
                        ExifInterface(it)
                            .getAttributeInt(
                                ExifInterface.TAG_ORIENTATION,
                                ExifInterface.ORIENTATION_NORMAL,
                            )
                    ) {
                        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                        else -> 0f
                    }
                } ?: 0f
            if (rotation != 0f) {
                val rotated =
                    Bitmap.createBitmap(
                        bitmap,
                        0,
                        0,
                        bitmap.width,
                        bitmap.height,
                        Matrix().apply { postRotate(rotation) },
                        true,
                    )
                if (rotated !== bitmap) bitmap.recycle()
                bitmap = rotated
            }

            file(context).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            bitmap.recycle()
            true
        } catch (e: Exception) {
            false
        }
    }
}
