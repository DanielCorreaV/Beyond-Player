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
import android.view.Gravity
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.slider.Slider
import org.oxycblt.auxio.R

/**
 * The built-in sound screen. From top to bottom: the equalizer switch, ready-made sound styles, the
 * band sliders, the stable-volume mode and the extra effects.
 */
object EqualizerDialog {
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

        fun title(text: Int, hint: Int? = null) {
            root.addView(
                TextView(context).apply {
                    setText(text)
                    textSize = 16f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setPadding(0, dp(20), 0, dp(2))
                }
            )
            if (hint != null) {
                root.addView(
                    TextView(context).apply {
                        setText(hint)
                        textSize = 13f
                        alpha = 0.7f
                        setPadding(0, 0, 0, dp(6))
                    }
                )
            }
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

        fun levelSlider(label: Int, current: Int, onChange: (Int) -> Unit) {
            root.addView(
                TextView(context).apply {
                    setText(label)
                    setPadding(0, dp(8), 0, 0)
                }
            )
            root.addView(
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
                title(R.string.lbl_equalizer)
                root.addView(TextView(context).apply { setText(R.string.lng_equalizer_unavailable) })
            } else {
                val bandsArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                val styleArea = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
                lateinit var master: MaterialSwitch

                fun look(enabled: Boolean) {
                    val alpha = if (enabled) 1f else 0.45f
                    bandsArea.alpha = alpha
                    styleArea.alpha = alpha
                }

                // Touching any control while the equalizer is off turns it on.
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

                title(R.string.lbl_sound_style)
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
                root.addView(styleArea)

                title(R.string.lbl_sound_bands, R.string.lng_sound_bands_hint)
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
                            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                        }
                    val dbLabel =
                        TextView(context).apply {
                            textSize = 12f
                            gravity = Gravity.CENTER
                        }
                    fun showDb(level: Int) {
                        dbLabel.text = if (level == 0) "0" else String.format("%+.0f", level / 100f)
                    }
                    showDb(band.level)
                    val slider =
                        BandSliderView(context).apply {
                            minLevel = state.minLevel
                            maxLevel = state.maxLevel
                            level = band.level
                            layoutParams = LinearLayout.LayoutParams(dp(48), dp(190))
                            onLevelChanged = { newLevel ->
                                ensureEnabled()
                                controller.setBandLevel(index, newLevel)
                                showDb(newLevel)
                            }
                        }
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
                root.addView(bandsArea)
                look(state.enabled)
            }

            // --- Stable volume ---
            title(R.string.lbl_stabilizer, R.string.lng_stabilizer_hint)
            val modes = StabilizerMode.entries
            root.addView(
                chipRow(
                    modes.map { context.getString(it.nameRes) },
                    modes.indexOf(stabilizer.mode.value),
                ) { index ->
                    stabilizer.setMode(modes[index])
                }
            )

            // --- Fade between songs ---
            title(R.string.lbl_fade, R.string.lng_fade_hint)
            root.addView(
                chipRow(
                    SongFade.OPTIONS.map {
                        if (it == 0) context.getString(R.string.lbl_fade_off)
                        else context.getString(R.string.lbl_fade_seconds, it / 1000)
                    },
                    SongFade.OPTIONS.indexOf(songFade.ms.value).coerceAtLeast(0),
                ) { index ->
                    songFade.set(SongFade.OPTIONS[index])
                }
            )

            // --- Effects ---
            if (state != null && (state.bassSupported || state.spatialSupported)) {
                title(R.string.lbl_sound_effects)
                if (state.bassSupported) {
                    levelSlider(R.string.lbl_effect_bass, state.bass) { controller.setBass(it) }
                }
                if (state.spatialSupported) {
                    levelSlider(R.string.lbl_effect_spatial, state.spatial) {
                        controller.setSpatial(it)
                    }
                }
            }

            if (state != null) {
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
