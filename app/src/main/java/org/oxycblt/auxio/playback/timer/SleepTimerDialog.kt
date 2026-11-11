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
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.color.MaterialColors
import androidx.core.widget.doAfterTextChanged
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import org.oxycblt.auxio.R

/**
 * A big round dial. While setting up a timer you drag around the ring to choose the amount; while a
 * timer runs the ring empties as time passes and the middle shows what is left.
 */
private class TimerDialView(context: Context) : View(context) {
    private val density = context.resources.displayMetrics.density

    private fun themeColor(attr: Int, fallback: Int) = MaterialColors.getColor(context, attr, fallback)

    private val trackPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 16 * density
            strokeCap = Paint.Cap.ROUND
            color =
                themeColor(com.google.android.material.R.attr.colorOutlineVariant, 0x33888888)
        }
    private val arcPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 16 * density
            strokeCap = Paint.Cap.ROUND
            color = themeColor(android.R.attr.colorPrimary, 0xFF6750A4.toInt())
        }
    private val knobPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = themeColor(android.R.attr.colorPrimary, 0xFF6750A4.toInt())
        }
    private val bigPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            color = themeColor(com.google.android.material.R.attr.colorOnSurface, 0xFF000000.toInt())
        }
    private val unitPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            textAlign = Paint.Align.CENTER
            textSize = 16 * density
            color =
                themeColor(
                    com.google.android.material.R.attr.colorOnSurfaceVariant,
                    0xFF555555.toInt(),
                )
        }
    private val oval = RectF()
    private var lastFraction = 0f

    var minValue = 1
    var maxValue = 180
    var step = 5

    var value = 15
        set(v) {
            field = v.coerceAtLeast(minValue)
            invalidate()
        }

    var unit = ""
        set(v) {
            field = v
            invalidate()
        }

    var interactive = true
        set(v) {
            field = v
            invalidate()
        }

    /** While a timer runs: how much of the ring is still full (0..1). Null while setting up. */
    var runningFraction: Float? = null
        set(v) {
            field = v
            invalidate()
        }

    /** While a timer runs: the text in the middle. Null while setting up. */
    var runningText: String? = null
        set(v) {
            field = v
            invalidate()
        }

    var onValueChanged: ((Int) -> Unit)? = null

    /** Whether the number is drawn here; off when a text field sits on top of it. */
    var showValue = true

    private fun fraction(): Float =
        runningFraction ?: (value.toFloat() / maxValue.coerceAtLeast(1)).coerceIn(0f, 1f)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val size = min(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec))
        setMeasuredDimension(size, size)
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val pad = 8 * density + trackPaint.strokeWidth / 2 + 8 * density
        oval.set(pad, pad, width - pad, height - pad)
        canvas.drawArc(oval, 0f, 360f, false, trackPaint)
        val f = fraction()
        if (f > 0f) canvas.drawArc(oval, -90f, 360f * f, false, arcPaint)
        if (interactive) {
            val radius = oval.width() / 2f
            val angle = Math.toRadians(-90.0 + 360.0 * f)
            canvas.drawCircle(
                cx + radius * cos(angle).toFloat(),
                cy + radius * sin(angle).toFloat(),
                14 * density,
                knobPaint,
            )
        }
        val big = runningText ?: value.toString()
        bigPaint.textSize = (if (big.length > 5) 44 else 68) * density
        val baseline = cy - (bigPaint.ascent() + bigPaint.descent()) / 2f
        val lift = if (unit.isNotEmpty()) 14 * density else 0f
        if (runningText != null || showValue) canvas.drawText(big, cx, baseline - lift, bigPaint)
        if (unit.isNotEmpty()) {
            canvas.drawText(unit, cx, baseline - lift + 30 * density, unitPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!interactive) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                if (event.actionMasked == MotionEvent.ACTION_DOWN) lastFraction = fraction()
                val dx = event.x - width / 2f
                val dy = event.y - height / 2f
                var angle = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
                if (angle < 0f) angle += 360f
                var f = angle / 360f
                // Do not let the dial wrap around from the end back to the start.
                if (lastFraction > 0.8f && f < 0.2f) f = 1f
                else if (lastFraction < 0.2f && f > 0.8f) f = 0f
                lastFraction = f
                val next =
                    (((f * maxValue) / step).roundToInt() * step).coerceIn(minValue, maxValue)
                if (next != value) {
                    value = next
                    performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                    onValueChanged?.invoke(next)
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return false
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }
}

/**
 * The sleep timer screen, a bottom sheet around a big dial. Setting up: choose time or songs, drag
 * the dial (or use the quick buttons and +/-), choose what happens at the end, press start. While a
 * timer runs the dial turns into a live countdown with +5 min and cancel.
 */
object SleepTimerDialog {
    private const val MAX_MINUTES = 720
    private const val MAX_SONGS = 500
    private val TIME_PRESETS = intArrayOf(5, 10, 15, 30, 60)
    private val SONG_PRESETS = intArrayOf(1, 2, 3, 5, 10)

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
        var timeValue = prefs.getInt("time_value", 15).coerceIn(1, MAX_MINUTES)
        var songValue = prefs.getInt("song_value", 3).coerceIn(1, MAX_SONGS)

        val dialog = MaterialAlertDialogBuilder(context).create()
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(24), dp(20), dp(24), dp(28))
            }
        dialog.setView(ScrollView(context).apply { addView(root) })

        fun amountLabel(value: Int) =
            context.getString(
                if (mode == TimerMode.TIME) R.string.lbl_timer_minutes else R.string.lbl_timer_songs,
                value,
            )

        /** The label without its number, for under the big number. */
        fun unitOf(label: String, value: Int) = label.replace(value.toString(), "").trim()

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

        fun outlinedButton(label: String) =
            MaterialButton(
                    context,
                    null,
                    com.google.android.material.R.attr.materialButtonOutlinedStyle,
                )
                .apply {
                    text = label
                    isAllCaps = false
                }

        fun toggleButton(label: Int) =
            outlinedButton(context.getString(label)).apply {
                isCheckable = true
                id = View.generateViewId()
            }

        // --- Title and mode ---
        root.addView(
            TextView(context).apply {
                setText(R.string.lbl_timer)
                textSize = 22f
                setTypeface(typeface, Typeface.BOLD)
                gravity = Gravity.CENTER
            }
        )
        val timeButton = toggleButton(R.string.lbl_timer_by_time)
        val songsButton = toggleButton(R.string.lbl_timer_by_songs)
        val modeToggle =
            MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
                addView(timeButton)
                addView(songsButton)
                check(if (mode == TimerMode.TIME) timeButton.id else songsButton.id)
                layoutParams =
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = dp(16) }
            }
        root.addView(modeToggle)

        // --- The dial ---
        val dial =
            TimerDialView(context).apply {
                layoutParams =
                    LinearLayout.LayoutParams(dp(268), dp(268)).apply {
                        topMargin = dp(12)
                        bottomMargin = dp(4)
                    }
            }
        val dialFrame = FrameLayout(context)
        val dialParams = dial.layoutParams
        dial.layoutParams = FrameLayout.LayoutParams(dp(268), dp(268))
        dial.showValue = false
        dialFrame.addView(dial)
        // The number in the middle is a field, so a custom value can be typed.
        val valueInput =
            EditText(context).apply {
                background = null
                setPadding(0, 0, 0, 0)
                includeFontPadding = false
                gravity = Gravity.CENTER
                setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, 68 * density)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(
                    MaterialColors.getColor(
                        context,
                        com.google.android.material.R.attr.colorOnSurface,
                        0xFF000000.toInt(),
                    )
                )
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                filters = arrayOf<android.text.InputFilter>(android.text.InputFilter.LengthFilter(3))
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setSelectAllOnFocus(true)
                translationY = -14 * density
            }
        dialFrame.addView(
            valueInput,
            FrameLayout.LayoutParams(dp(150), ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER),
        )
        root.addView(dialFrame, dialParams)
        var updatingInput = false

        fun syncInput(value: Int) {
            val text = value.toString()
            if (valueInput.text.toString() != text) {
                updatingInput = true
                valueInput.setText(text)
                valueInput.setSelection(text.length)
                updatingInput = false
            }
        }

        valueInput.doAfterTextChanged { text ->
            if (updatingInput) return@doAfterTextChanged
            val typed = text?.toString()?.toIntOrNull() ?: return@doAfterTextChanged
            if (typed < 1) return@doAfterTextChanged
            val limit = if (mode == TimerMode.TIME) MAX_MINUTES else MAX_SONGS
            val clamped = typed.coerceAtMost(limit)
            if (mode == TimerMode.TIME) timeValue = clamped else songValue = clamped
            dial.value = clamped
            dial.unit = unitOf(amountLabel(clamped), clamped)
            if (clamped != typed) syncInput(clamped)
        }
        fun finishTyping() {
            syncInput(if (mode == TimerMode.TIME) timeValue else songValue)
            valueInput.clearFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE)
                    as android.view.inputmethod.InputMethodManager)
                .hideSoftInputFromWindow(valueInput.windowToken, 0)
        }
        valueInput.setOnEditorActionListener { _, _, _ ->
            finishTyping()
            true
        }
        valueInput.setOnFocusChangeListener { _, focused ->
            if (!focused) syncInput(if (mode == TimerMode.TIME) timeValue else songValue)
        }

        // --- Setup controls ---
        val setupGroup =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
            }
        val minusButton = outlinedButton("−").apply { textSize = 20f }
        val plusButton = outlinedButton("+").apply { textSize = 20f }
        setupGroup.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(minusButton)
                addView(
                    plusButton,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { marginStart = dp(16) },
                )
            }
        )
        val presetRow = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        setupGroup.addView(
            HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(presetRow)
                layoutParams =
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = dp(8) }
            }
        )
        val cutButton = toggleButton(R.string.lbl_timer_end_cut)
        val finishButton = toggleButton(R.string.lbl_timer_end_finish)
        val endToggle =
            MaterialButtonToggleGroup(context).apply {
                isSingleSelection = true
                isSelectionRequired = true
                addView(cutButton)
                addView(finishButton)
                check(if (end == TimerEnd.CUT) cutButton.id else finishButton.id)
                layoutParams =
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { topMargin = dp(16) }
            }
        setupGroup.addView(endToggle)
        val songsHint =
            TextView(context).apply {
                setText(R.string.lbl_timer_songs_hint)
                textSize = 13f
                alpha = 0.7f
                gravity = Gravity.CENTER
                setPadding(0, dp(16), 0, 0)
            }
        setupGroup.addView(songsHint)
        val startButton =
            MaterialButton(context).apply {
                setText(R.string.lbl_timer_start)
                textSize = 16f
                layoutParams =
                    LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply {
                        topMargin = dp(20)
                    }
            }
        setupGroup.addView(startButton)
        root.addView(setupGroup)

        // --- Running controls ---
        val runningGroup =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                visibility = View.GONE
            }
        val statusView =
            TextView(context).apply {
                textSize = 15f
                gravity = Gravity.CENTER
                alpha = 0.8f
                setPadding(0, dp(4), 0, dp(12))
            }
        runningGroup.addView(statusView)
        val extendButton =
            outlinedButton("+" + context.getString(R.string.lbl_timer_minutes, 5))
        val cancelButton = outlinedButton(context.getString(R.string.lbl_timer_cancel))
        runningGroup.addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(extendButton)
                addView(
                    cancelButton,
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { marginStart = dp(16) },
                )
            }
        )
        root.addView(runningGroup)

        // --- Behaviour ---
        fun currentValue() = if (mode == TimerMode.TIME) timeValue else songValue

        fun applyValue(value: Int) {
            val limit = if (mode == TimerMode.TIME) MAX_MINUTES else MAX_SONGS
            val clamped = value.coerceIn(1, limit)
            if (mode == TimerMode.TIME) timeValue = clamped else songValue = clamped
            dial.value = clamped
            syncInput(clamped)
            dial.unit = unitOf(amountLabel(clamped), clamped)
        }

        fun refreshSetup() {
            dial.interactive = true
            valueInput.visibility = View.VISIBLE
            dial.runningFraction = null
            dial.runningText = null
            dial.minValue = 1
            dial.maxValue = if (mode == TimerMode.TIME) 180 else 30
            dial.step = if (mode == TimerMode.TIME) 5 else 1
            applyValue(currentValue())
            presetRow.removeAllViews()
            (if (mode == TimerMode.TIME) TIME_PRESETS else SONG_PRESETS).forEachIndexed { i, p ->
                presetRow.addView(
                    outlinedButton(amountLabel(p)).apply { setOnClickListener { applyValue(p) } },
                    LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { if (i > 0) marginStart = dp(8) },
                )
            }
            endToggle.visibility = if (mode == TimerMode.TIME) View.VISIBLE else View.GONE
            songsHint.visibility = if (mode == TimerMode.SONGS) View.VISIBLE else View.GONE
        }

        fun refreshRunning(state: TimerState) {
            dial.interactive = false
            valueInput.visibility = View.GONE
            when {
                state.finishingSong -> {
                    dial.runningFraction = 0f
                    dial.runningText = "—"
                    dial.unit = ""
                }
                state.mode == TimerMode.SONGS -> {
                    dial.runningFraction =
                        if (state.initialSongs > 0) {
                            (state.songsLeft.toFloat() / state.initialSongs).coerceIn(0f, 1f)
                        } else {
                            1f
                        }
                    dial.runningText = state.songsLeft.toString()
                    dial.unit =
                        unitOf(
                            context.getString(R.string.lbl_timer_songs, state.songsLeft),
                            state.songsLeft,
                        )
                }
                else -> {
                    val left = state.endsAtMs - SystemClock.elapsedRealtime()
                    dial.runningFraction =
                        if (state.totalMs > 0) (left.toFloat() / state.totalMs).coerceIn(0f, 1f)
                        else 1f
                    dial.runningText = clock(left)
                    dial.unit = ""
                }
            }
            statusView.text = statusText(state)
            extendButton.visibility =
                if (state.mode == TimerMode.TIME && !state.finishingSong) View.VISIBLE
                else View.GONE
        }

        var showingRunning: Boolean? = null
        fun refresh() {
            val state = timer.state.value
            val running = state != null
            if (running) {
                refreshRunning(state!!)
            }
            if (showingRunning != running) {
                showingRunning = running
                setupGroup.visibility = if (running) View.GONE else View.VISIBLE
                modeToggle.visibility = if (running) View.GONE else View.VISIBLE
                runningGroup.visibility = if (running) View.VISIBLE else View.GONE
                if (!running) refreshSetup()
            }
        }

        dial.onValueChanged = { value ->
            if (mode == TimerMode.TIME) timeValue = value else songValue = value
            dial.unit = unitOf(amountLabel(value), value)
            syncInput(value)
        }
        minusButton.setOnClickListener { applyValue(currentValue() - 1) }
        plusButton.setOnClickListener { applyValue(currentValue() + 1) }
        modeToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) {
                mode = if (checkedId == timeButton.id) TimerMode.TIME else TimerMode.SONGS
                refreshSetup()
            }
        }
        endToggle.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (isChecked) end = if (checkedId == cutButton.id) TimerEnd.CUT else TimerEnd.FINISH_SONG
        }
        startButton.setOnClickListener {
            val value = currentValue()
            when (mode) {
                TimerMode.TIME -> timer.startTime(value, end)
                TimerMode.SONGS -> timer.startSongs(value)
            }
            prefs
                .edit()
                .putString("mode", mode.name)
                .putString("end", end.name)
                .putInt("time_value", timeValue)
                .putInt("song_value", songValue)
                .apply()
            refresh()
        }
        extendButton.setOnClickListener {
            timer.extend(5)
            refresh()
        }
        cancelButton.setOnClickListener {
            timer.cancel()
            refresh()
        }

        val handler = Handler(Looper.getMainLooper())
        lateinit var tick: Runnable
        tick = Runnable {
            refresh()
            handler.postDelayed(tick, 250)
        }
        dialog.window?.setSoftInputMode(
            android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        )
        dialog.setOnShowListener {
            
            
            tick.run()
        }
        dialog.setOnDismissListener { handler.removeCallbacks(tick) }
        dialog.show()
        return dialog
    }
}
