/*
 * Copyright (c) 2023 Auxio Project
 * UIPreferenceFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings.categories

import android.net.Uri
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.preference.Preference
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.ui.AppStyle
import org.oxycblt.auxio.ui.BackgroundImage
import org.oxycblt.auxio.ui.UISettings
import org.oxycblt.auxio.util.isNight
import org.oxycblt.auxio.util.navigateSafe
import timber.log.Timber as L

/**
 * Display preferences.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class UIPreferenceFragment : BasePreferenceFragment(R.xml.preferences_ui) {
    @Inject lateinit var uiSettings: UISettings

    private val pickBackground =
        registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) importBackground(uri)
        }

    private fun importBackground(uri: Uri) {
        val context = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) { BackgroundImage.import(context, uri) }
            if (ok) {
                L.d("Background image imported, recreating")
                requireActivity().recreate()
            } else {
                Toast.makeText(context, R.string.err_bg_image, Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        if (preference.key == getString(R.string.set_key_accent)) {
            L.d("Navigating to accent dialog")
            findNavController().navigateSafe(UIPreferenceFragmentDirections.accentSettings())
        }
    }

    override fun onSetupPreference(preference: Preference) {
        when (preference.key) {
            getString(R.string.set_key_theme) -> {
                L.d("Configuring theme setting")
                preference.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, value ->
                        L.d("Theme changed, recreating")
                        requireActivity().recreate()
                        true
                    }
            }
            getString(R.string.set_key_style) -> {
                L.d("Configuring style setting")
                preference.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, _ ->
                        L.d("Style changed, recreating")
                        requireActivity().recreate()
                        true
                    }
            }
            getString(R.string.set_key_bg_image_pick) -> {
                L.d("Configuring background image picker")
                preference.summary =
                    getString(
                        if (BackgroundImage.exists(requireContext())) R.string.set_bg_image_set
                        else R.string.set_bg_image_none
                    )
                preference.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        pickBackground.launch("image/*")
                        true
                    }
            }
            getString(R.string.set_key_bg_image_clear) -> {
                L.d("Configuring background image removal")
                preference.isEnabled = BackgroundImage.exists(requireContext())
                preference.onPreferenceClickListener =
                    Preference.OnPreferenceClickListener {
                        BackgroundImage.clear(requireContext())
                        requireActivity().recreate()
                        true
                    }
            }
            getString(R.string.set_key_bg_fade) -> {
                L.d("Configuring background image intensity")
                preference.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, _ ->
                        requireActivity().recreate()
                        true
                    }
            }
            getString(R.string.set_key_accent) -> {
                L.d("Configuring accent setting")
                if (uiSettings.style == AppStyle.CLASSIC) {
                    preference.summary = getString(uiSettings.accent.name)
                } else {
                    // The other styles have their own colors.
                    preference.isEnabled = false
                    preference.summary = getString(R.string.set_style_uses_style_colors)
                }
            }
            getString(R.string.set_key_black_theme) -> {
                L.d("Configuring black theme setting")
                preference.onPreferenceChangeListener =
                    Preference.OnPreferenceChangeListener { _, _ ->
                        val activity = requireActivity()
                        if (activity.isNight) {
                            L.d("Black theme changed in night mode, recreating")
                            activity.recreate()
                        }

                        true
                    }
            }
        }
    }
}
