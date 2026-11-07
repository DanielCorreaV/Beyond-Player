/*
 * Copyright (c) 2026 Auxio Project
 * YouTubeImportDialogs.kt is part of Auxio.
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
 
package org.oxycblt.auxio.music.youtube

import android.content.Context
import android.text.InputType
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.CircularProgressIndicator
import org.oxycblt.auxio.R

/** Plain dialogs for the YouTube Music import: paste the link(s), then read the summary. */
object YouTubeImportDialogs {
    /** Ask for one or more links (one per line) and pass the raw text to [onImport]. */
    fun showLinkDialog(context: Context, onImport: (String) -> Unit) {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val input =
            EditText(context).apply {
                setHint(R.string.lbl_import_youtube_hint)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                minLines = 2
                maxLines = 8
            }
        val container =
            FrameLayout(context).apply {
                setPadding(pad, (8 * density).toInt(), pad, 0)
                addView(input)
            }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_import_youtube)
            .setView(container)
            .setPositiveButton(R.string.lbl_import) { _, _ -> onImport(input.text.toString()) }
            .setNegativeButton(R.string.lbl_cancel, null)
            .show()
    }

    /** A progress dialog that stays on screen while a playlist is being read. */
    class Progress(
        private val context: Context,
        private val dialog: AlertDialog,
        private val text: TextView,
    ) {
        /** Show which link is being read, [current] of [total]. */
        fun update(current: Int, total: Int) {
            text.text =
                if (total > 1) context.getString(R.string.fmt_youtube_importing_n, current, total)
                else context.getString(R.string.lng_youtube_importing)
        }

        fun dismiss() {
            if (dialog.isShowing) dialog.dismiss()
        }
    }

    /** Show a spinner with a short explanation while the import reads from the network. */
    fun showProgressDialog(context: Context): Progress {
        val density = context.resources.displayMetrics.density
        val pad = (24 * density).toInt()
        val message =
            TextView(context).apply {
                text = context.getString(R.string.lng_youtube_importing)
                textSize = 16f
            }
        val note =
            TextView(context).apply {
                text = context.getString(R.string.lng_youtube_wait)
                textSize = 13f
                alpha = 0.7f
            }
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((16 * density).toInt(), 0, 0, 0)
                addView(message)
                addView(note)
            }
        val row =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(pad, pad, pad, (12 * density).toInt())
                gravity = android.view.Gravity.CENTER_VERTICAL
                addView(CircularProgressIndicator(context).apply { isIndeterminate = true })
                addView(column)
            }
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.lbl_youtube_summary)
                .setView(row)
                .setCancelable(false)
                .create()
        dialog.show()
        return Progress(context, dialog, message)
    }

    /** Show how many songs matched per link and which ones are missing from the phone. */
    fun showResultDialog(context: Context, result: YouTubeImportResult) {
        val text = buildString {
            if (result.items.isEmpty()) append(context.getString(R.string.lng_youtube_none))
            result.items.forEachIndexed { index, item ->
                if (index > 0) append("\n\n")
                when (item) {
                    is YouTubeImportItem.Done -> {
                        val res =
                            if (item.created) R.string.fmt_youtube_created
                            else R.string.fmt_youtube_updated
                        append(context.getString(res, item.title, item.matched, item.total))
                        if (item.kept > 0) {
                            append("\n")
                            append(context.getString(R.string.fmt_youtube_kept, item.kept))
                        }
                        if (item.missing.isNotEmpty()) {
                            append("\n")
                            append(context.getString(R.string.lbl_youtube_missing))
                            item.missing.forEach { append("\n• ").append(it.display) }
                        }
                    }
                    is YouTubeImportItem.Failed ->
                        append(
                            context.getString(R.string.fmt_youtube_failed, item.link, item.reason)
                        )
                }
            }
        }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.lbl_youtube_summary)
            .setMessage(text)
            .setPositiveButton(R.string.lbl_ok, null)
            .show()
    }
}
