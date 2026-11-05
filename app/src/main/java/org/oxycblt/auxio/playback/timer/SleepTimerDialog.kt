/*
 * Copyright (c) 2026 Auxio Project
 * SleepTimerDialog.kt is part of Auxio.
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
 
package org.oxycblt.auxio.playback.timer

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import org.oxycblt.auxio.R

/**
 * The sleep timer screen: pick what to count (time or songs), how much, and what to do when time is
 * up. While a timer runs it also shows what is left and lets the user cancel it.
 */
object SleepTimerDialog {
    private val PRESETS = intArrayOf(5, 10, 15)
    private const val MAX_MINUTES = 720
    private const val MAX_SONGS = 500

    fun show(context: Context, timer: SleepTimer): AlertDialog {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val prefs = context.getSharedPreferences("sleep_timer", Context.MODE_PRIVATE)

        var mode =
            if (prefs.getString("mode", null) == TimerMode.SONGS.name) TimerMode.SONGS
            else TimerMode.TIME
        var end =
            if (prefs.getString("end", null) == TimerEnd.FINISH_SONG.name) TimerEnd.FINISH_SONG
            else TimerEnd.CUT
        // Index into PRESETS, or PRESETS.size for the custom value.
        var preset = prefs.getInt("preset", 1).coerceIn(0, PRESETS.size)
        var customValue = prefs.getString("custom", "") ?: ""

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(4), dp(20), dp(8))
            }
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.lbl_timer)
                .setView(ScrollView(context).apply { addView(root) })
                .setPositiveButton(R.string.lbl_timer_start, null)
                .setNeutralButton(R.string.lbl_timer_cancel) { _, _ -> timer.cancel() }
                .setNegativeButton(R.string.lbl_timer_close, null)
                .create()

        fun title(text: Int): TextView =
            TextView(context)
                .apply {
                    setText(text)
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(16), 0, dp(4))
                }
                .also { root.addView(it) }

        fun chip(label: String, checked: Boolean, onPick: () -> Unit) =
            Chip(context).apply {
                setChipDrawable(
                    ChipDrawable.createFromAttributes(
                        context,
                        null,
                        0,
                        com.google.android.material.R.style.Widget_Material3_Chip_Filter,
                    )
                )
                text = label
                isCheckable = true
                isChecked = checked
                setOnClickListener { onPick() }
            }

        fun chipGroup() =
            ChipGroup(context)
                .apply {
                    isSingleSelection = true
                    isSelectionRequired = true
                }
                .also { root.addView(it) }

        // Status of the running timer, if any.
        val status =
            TextView(context).apply {
                textSize = 14f
                setPadding(dp(12), dp(10), dp(12), dp(10))
                visibility = View.GONE
            }
        root.addView(status)

        title(R.string.lbl_timer_mode)
        val modeGroup = chipGroup()
        title(R.string.lbl_timer_amount)
        val amountGroup = chipGroup()
        val input =
            EditText(context).apply {
                inputType = InputType.TYPE_CLASS_NUMBER
                setSingleLine()
                visibility = View.GONE
                setText(customValue)
            }
        root.addView(input)
        val endTitle = title(R.string.lbl_timer_end_title)
        val endGroup = chipGroup()
        val hint =
            TextView(context).apply {
                setText(R.string.lbl_timer_songs_hint)
                textSize = 13f
                alpha = 0.7f
                setPadding(0, dp(12), 0, 0)
            }
        root.addView(hint)

        fun amountLabel(value: Int) =
            context.getString(
                if (mode == TimerMode.TIME) R.string.lbl_timer_minutes
                else R.string.lbl_timer_songs,
                value,
            )

        fun refreshAmount() {
            amountGroup.removeAllViews()
            PRESETS.forEachIndexed { index, value ->
                amountGroup.addView(
                    chip(amountLabel(value), index == preset) {
                        preset = index
                        input.visibility = View.GONE
                    }
                )
            }
            amountGroup.addView(
                chip(context.getString(R.string.lbl_timer_custom), preset == PRESETS.size) {
                    preset = PRESETS.size
                    input.visibility = View.VISIBLE
                    input.requestFocus()
                }
            )
            input.hint =
                context.getString(
                    if (mode == TimerMode.TIME) R.string.lbl_timer_custom_minutes
                    else R.string.lbl_timer_custom_songs
                )
            input.visibility = if (preset == PRESETS.size) View.VISIBLE else View.GONE
        }

        fun refreshEnd() {
            val timeMode = mode == TimerMode.TIME
            endTitle.visibility = if (timeMode) View.VISIBLE else View.GONE
            endGroup.visibility = if (timeMode) View.VISIBLE else View.GONE
            hint.visibility = if (timeMode) View.GONE else View.VISIBLE
        }

        fun refreshMode() {
            refreshAmount()
            refreshEnd()
        }

        modeGroup.addView(
            chip(context.getString(R.string.lbl_timer_by_time), mode == TimerMode.TIME) {
                mode = TimerMode.TIME
                refreshMode()
            }
        )
        modeGroup.addView(
            chip(context.getString(R.string.lbl_timer_by_songs), mode == TimerMode.SONGS) {
                mode = TimerMode.SONGS
                refreshMode()
            }
        )
        endGroup.addView(
            chip(context.getString(R.string.lbl_timer_end_cut), end == TimerEnd.CUT) {
                end = TimerEnd.CUT
            }
        )
        endGroup.addView(
            chip(
                context.getString(R.string.lbl_timer_end_finish),
                end == TimerEnd.FINISH_SONG,
            ) {
                end = TimerEnd.FINISH_SONG
            }
        )
        refreshMode()

        fun clock(ms: Long): String {
            val total = (ms / 1000 + if (ms % 1000 > 0) 1 else 0).coerceAtLeast(0)
            val hours = total / 3600
            val minutes = total % 3600 / 60
            val seconds = total % 60
            return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds)
            else "%d:%02d".format(minutes, seconds)
        }

        fun statusText(state: TimerState): String =
            when {
                state.finishingSong -> context.getString(R.string.lbl_timer_status_finishing)
                state.mode == TimerMode.SONGS ->
                    context.getString(R.string.lbl_timer_status_songs, state.songsLeft)
                else -> {
                    val left = clock(state.endsAtMs - SystemClock.elapsedRealtime())
                    context.getString(
                        if (state.end == TimerEnd.FINISH_SONG) R.string.lbl_timer_status_time_finish
                        else R.string.lbl_timer_status_time,
                        left,
                    )
                }
            }

        val handler = Handler(Looper.getMainLooper())
        lateinit var tick: Runnable
        fun refreshStatus() {
            val state = timer.state.value
            if (state == null) {
                status.visibility = View.GONE
            } else {
                status.visibility = View.VISIBLE
                status.text = statusText(state)
            }
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL)?.visibility =
                if (state == null) View.GONE else View.VISIBLE
        }
        tick = Runnable {
            refreshStatus()
            handler.postDelayed(tick, 1000)
        }

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val value =
                    if (preset < PRESETS.size) PRESETS[preset]
                    else input.text.toString().toIntOrNull() ?: 0
                val max = if (mode == TimerMode.TIME) MAX_MINUTES else MAX_SONGS
                if (value !in 1..max) {
                    input.visibility = View.VISIBLE
                    input.error = context.getString(R.string.lbl_timer_invalid, max)
                    return@setOnClickListener
                }
                when (mode) {
                    TimerMode.TIME -> timer.startTime(value, end)
                    TimerMode.SONGS -> timer.startSongs(value)
                }
                customValue = input.text.toString()
                prefs
                    .edit()
                    .putString("mode", mode.name)
                    .putString("end", end.name)
                    .putInt("preset", preset)
                    .putString("custom", customValue)
                    .apply()
                Toast.makeText(
                        context,
                        context.getString(R.string.lbl_timer_started, amountLabel(value)),
                        Toast.LENGTH_SHORT,
                    )
                    .show()
                dialog.dismiss()
            }
            tick.run()
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
        return dialog
    }
}
