/*
 * Copyright (c) 2026 Auxio Project
 * EqualizerDialog.kt is part of Auxio.
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
import android.transition.TransitionManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipDrawable
import com.google.android.material.chip.ChipGroup
import com.google.android.material.color.MaterialColors
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import org.oxycblt.auxio.R

/**
 * The built-in sound screen. The two switches stay on top; everything else lives in collapsible
 * sections (sound style, band sliders, stable volume, fade between songs, effects) so the dialog is
 * short and a vertical swipe never lands on a control. The band sliders ignore touches while the
 * equalizer is off.
 */
object EqualizerDialog {
    /** Sections the user left open, remembered while the app is running. */
    private val openSections = mutableSetOf<String>()

    fun show(
        context: Context,
        controller: EqualizerController,
        stabilizer: SoundStabilizer,
        songFade: SongFade,
    ): AlertDialog {
        val density = context.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(20), dp(4), dp(20), dp(8))
            }
        val dialog =
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.lbl_sound)
                .setView(ScrollView(context).apply { addView(root) })
                .setPositiveButton(R.string.lbl_ok, null)
                .create()

        fun hintText(text: Int) =
            TextView(context).apply {
                setText(text)
                textSize = 13f
                alpha = 0.7f
                setPadding(0, 0, 0, dp(6))
            }

        fun divider() {
            root.addView(
                View(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1)
                    setBackgroundColor(
                        MaterialColors.getColor(
                            context,
                            com.google.android.material.R.attr.colorOutlineVariant,
                            0x33888888,
                        )
                    )
                }
            )
        }

        /** A header that opens and closes its content. [summary] shows while it is closed. */
        fun section(
            key: String,
            titleRes: Int,
            summary: String? = null,
            hint: Int? = null,
            content: (LinearLayout) -> Unit,
        ) {
            val isOpen = key in openSections
            val body =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    visibility = if (isOpen) View.VISIBLE else View.GONE
                }
            if (hint != null) body.addView(hintText(hint))
            content(body)

            val titleView =
                TextView(context).apply {
                    setText(titleRes)
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
            val summaryView =
                TextView(context).apply {
                    text = summary.orEmpty()
                    textSize = 13f
                    alpha = 0.7f
                    setPadding(0, 0, dp(8), 0)
                    visibility = if (summary == null || isOpen) View.GONE else View.VISIBLE
                }
            val arrow =
                TextView(context).apply {
                    text = "▾"
                    textSize = 18f
                    rotation = if (isOpen) 0f else -90f
                }
            val header =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(0, dp(14), 0, dp(14))
                    isClickable = true
                    isFocusable = true
                    val attrs =
                        context.obtainStyledAttributes(
                            intArrayOf(android.R.attr.selectableItemBackground)
                        )
                    background = attrs.getDrawable(0)
                    attrs.recycle()
                    addView(titleView)
                    addView(summaryView)
                    addView(arrow)
                    setOnClickListener {
                        val open = body.visibility != View.VISIBLE
                        TransitionManager.beginDelayedTransition(root)
                        body.visibility = if (open) View.VISIBLE else View.GONE
                        summaryView.visibility =
                            if (open || summary == null) View.GONE else View.VISIBLE
                        arrow.animate().rotation(if (open) 0f else -90f).setDuration(150).start()
                        if (open) openSections.add(key) else openSections.remove(key)
                    }
                }
            divider()
            root.addView(header)
            root.addView(body)
        }

        fun chipRow(
            names: List<String>,
            selected: Int,
            onPick: (Int) -> Unit,
        ): HorizontalScrollView {
            val group =
                ChipGroup(context).apply {
                    isSingleLine = true
                    isSingleSelection = true
                    isSelectionRequired = true
                }
            names.forEachIndexed { index, name ->
                group.addView(
                    Chip(context).apply {
                        setChipDrawable(
                            ChipDrawable.createFromAttributes(
                                context,
                                null,
                                0,
                                com.google.android.material.R.style.Widget_Material3_Chip_Filter,
                            )
                        )
                        text = name
                        isCheckable = true
                        isChecked = index == selected
                        setOnClickListener { onPick(index) }
                    }
                )
            }
            return HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                addView(group)
            }
        }

        fun levelSlider(parent: LinearLayout, label: Int, current: Int, onChange: (Int) -> Unit) {
            parent.addView(
                TextView(context).apply {
                    setText(label)
                    setPadding(0, dp(8), 0, 0)
                }
            )
            parent.addView(
                Slider(context).apply {
                    valueFrom = 0f
                    valueTo = 100f
                    this.value = (current / 10f).coerceIn(0f, 100f)
                    addOnChangeListener { _, newValue, fromUser ->
                        if (fromUser) onChange((newValue * 10).toInt())
                    }
                }
            )
        }

        lateinit var render: () -> Unit
        render = {
            root.removeAllViews()
            val state = controller.state.value

            // --- Equalizer ---
            if (state == null) {
                root.addView(
                    TextView(context).apply {
                        setText(R.string.lbl_equalizer)
                        textSize = 16f
                        setTypeface(typeface, android.graphics.Typeface.BOLD)
                        setPadding(0, dp(12), 0, dp(2))
                    }
                )
                root.addView(
                    TextView(context).apply { setText(R.string.lng_equalizer_unavailable) }
                )
            } else {
                val bandsArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val styleArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val bandSliders = mutableListOf<BandSliderView>()
                lateinit var master: MaterialSwitch

                fun look(enabled: Boolean) {
                    val alpha = if (enabled) 1f else 0.45f
                    bandsArea.alpha = alpha
                    styleArea.alpha = alpha
                    // While the equalizer is off the bars ignore touches, so a swipe to scroll the
                    // dialog passes through them instead of changing a band.
                    bandSliders.forEach { it.isEnabled = enabled }
                }

                // Picking a sound style while the equalizer is off turns it on.
                fun ensureEnabled() {
                    if (controller.state.value?.enabled == false) {
                        controller.setEnabled(true)
                        master.setOnCheckedChangeListener(null)
                        master.isChecked = true
                        master.setOnCheckedChangeListener { _, checked ->
                            controller.setEnabled(checked)
                            look(checked)
                        }
                        look(true)
                    }
                }

                master =
                    MaterialSwitch(context).apply {
                        setText(R.string.lbl_equalizer_enable)
                        isChecked = state.enabled
                        setOnCheckedChangeListener { _, checked ->
                            controller.setEnabled(checked)
                            look(checked)
                        }
                    }
                root.addView(master)
                root.addView(
                    MaterialSwitch(context).apply {
                        setText(R.string.lbl_equalizer_per_output)
                        isChecked = controller.perOutput
                        setOnCheckedChangeListener { _, checked ->
                            controller.setPerOutput(checked)
                            render()
                        }
                    }
                )
                root.addView(
                    TextView(context).apply {
                        text =
                            context.getString(
                                R.string.lng_equalizer_per_output_hint,
                                context.getString(controller.output.nameRes),
                            )
                        textSize = 13f
                        alpha = 0.7f
                        setPadding(0, 0, 0, dp(6))
                    }
                )

                section(
                    "style",
                    R.string.lbl_sound_style,
                    SoundMode.ALL.getOrNull(state.mode)?.let { context.getString(it.nameRes) },
                ) { body ->
                    styleArea.addView(
                        chipRow(
                            SoundMode.ALL.map { context.getString(it.nameRes) },
                            state.mode,
                        ) { index ->
                            ensureEnabled()
                            controller.useMode(index)
                            render()
                        }
                    )
                    body.addView(styleArea)
                }

                section("bands", R.string.lbl_sound_bands, hint = R.string.lng_sound_bands_hint) {
                    body ->
                    val row =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            weightSum = state.bands.size.toFloat()
                        }
                    state.bands.forEachIndexed { index, band ->
                        val column =
                            LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                gravity = Gravity.CENTER_HORIZONTAL
                                layoutParams =
                                    LinearLayout.LayoutParams(
                                        0,
                                        ViewGroup.LayoutParams.WRAP_CONTENT,
                                        1f,
                                    )
                            }
                        val dbLabel =
                            TextView(context).apply {
                                textSize = 12f
                                gravity = Gravity.CENTER
                            }
                        fun showDb(level: Int) {
                            dbLabel.text =
                                if (level == 0) "0" else String.format("%+.0f", level / 100f)
                        }
                        showDb(band.level)
                        val slider =
                            BandSliderView(context).apply {
                                minLevel = state.minLevel
                                maxLevel = state.maxLevel
                                level = band.level
                                layoutParams = LinearLayout.LayoutParams(dp(48), dp(190))
                                onLevelChanged = { newLevel ->
                                    controller.setBandLevel(index, newLevel)
                                    showDb(newLevel)
                                }
                            }
                        bandSliders.add(slider)
                        val hz =
                            TextView(context).apply {
                                textSize = 12f
                                gravity = Gravity.CENTER
                                text =
                                    if (band.centerHz >= 1000) {
                                        String.format("%.0fk", band.centerHz / 1000f)
                                    } else {
                                        band.centerHz.toString()
                                    }
                            }
                        column.addView(dbLabel)
                        column.addView(slider)
                        column.addView(hz)
                        row.addView(column)
                    }
                    bandsArea.addView(row)
                    body.addView(bandsArea)
                }
                look(state.enabled)
            }

            // --- Stable volume ---
            val modes = StabilizerMode.entries
            section(
                "stabilizer",
                R.string.lbl_stabilizer,
                context.getString(stabilizer.mode.value.nameRes),
                R.string.lng_stabilizer_hint,
            ) { body ->
                body.addView(
                    chipRow(
                        modes.map { context.getString(it.nameRes) },
                        modes.indexOf(stabilizer.mode.value),
                    ) { index ->
                        stabilizer.setMode(modes[index])
                    }
                )
            }

            // --- Fade between songs ---
            val fadeLabels =
                SongFade.OPTIONS.map {
                    if (it == 0) context.getString(R.string.lbl_fade_off)
                    else context.getString(R.string.lbl_fade_seconds, it / 1000)
                }
            val fadeIndex = SongFade.OPTIONS.indexOf(songFade.ms.value).coerceAtLeast(0)
            section("fade", R.string.lbl_fade, fadeLabels[fadeIndex], R.string.lng_fade_hint) { body
                ->
                body.addView(
                    chipRow(fadeLabels, fadeIndex) { index -> songFade.set(SongFade.OPTIONS[index]) }
                )
            }

            // --- Effects ---
            if (state != null && (state.bassSupported || state.spatialSupported)) {
                section("effects", R.string.lbl_sound_effects) { body ->
                    if (state.bassSupported) {
                        levelSlider(body, R.string.lbl_effect_bass, state.bass) {
                            controller.setBass(it)
                        }
                    }
                    if (state.spatialSupported) {
                        levelSlider(body, R.string.lbl_effect_spatial, state.spatial) {
                            controller.setSpatial(it)
                        }
                    }
                }
            }

            if (state != null) {
                divider()
                root.addView(
                    MaterialButton(
                            context,
                            null,
                            com.google.android.material.R.attr.materialButtonOutlinedStyle,
                        )
                        .apply {
                            setText(R.string.lbl_equalizer_reset)
                            setOnClickListener {
                                controller.reset()
                                render()
                            }
                        }
                )
            }
        }
        render()
        dialog.show()
        return dialog
    }
}
