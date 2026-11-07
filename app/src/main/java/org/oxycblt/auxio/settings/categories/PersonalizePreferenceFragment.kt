/*
 * Copyright (c) 2023 Auxio Project
 * PersonalizePreferenceFragment.kt is part of Auxio.
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

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.oxycblt.auxio.R
import org.oxycblt.auxio.settings.BasePreferenceFragment
import org.oxycblt.auxio.settings.icon.CustomIcon
import org.oxycblt.auxio.settings.icon.CustomIconView
import org.oxycblt.auxio.settings.ui.WrappedDialogPreference
import org.oxycblt.auxio.util.navigateSafe
import timber.log.Timber as L

/**
 * Personalization settings interface.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
class PersonalizePreferenceFragment : BasePreferenceFragment(R.xml.preferences_personalize) {
    private val pickImage =
        registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            if (uri != null) openIconEditor(uri)
        }

    override fun onOpenDialogPreference(preference: WrappedDialogPreference) {
        when (preference.key) {
            getString(R.string.set_key_home_tabs) -> {
                L.d("Navigating to home tab dialog")
                findNavController()
                    .navigateSafe(PersonalizePreferenceFragmentDirections.tabSettings())
            }
            getString(R.string.set_key_app_icon) -> showIconInfo()
        }
    }

    /**
     * Explains what a custom icon is, since Android cannot change the icon of the app itself, and
     * lets the user hide the original one.
     */
    private fun showIconInfo() {
        val context = requireContext()
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val message =
            TextView(context).apply {
                setText(R.string.icon_explain)
                textSize = 14f
                setPadding(dp(24), dp(8), dp(24), dp(8))
            }
        val pinned = CustomIcon.isPinned(context)
        val hidden = CustomIcon.isOriginalHidden(context)
        val checkBox =
            MaterialCheckBox(context).apply {
                setText(R.string.icon_hide_original)
                isChecked = hidden
                // Hiding the original while there is no shortcut would leave no icon to open the
                // app from. Showing it again is always possible.
                isEnabled = pinned || hidden
                setPadding(dp(16), 0, dp(16), 0)
                setOnCheckedChangeListener { _, checked ->
                    CustomIcon.setOriginalHidden(context, checked)
                    Toast.makeText(
                            context,
                            if (checked) R.string.icon_original_hidden
                            else R.string.icon_original_shown,
                            Toast.LENGTH_SHORT,
                        )
                        .show()
                }
            }
        val needsShortcut =
            TextView(context).apply {
                setText(R.string.icon_hide_needs_shortcut)
                textSize = 12f
                setPadding(dp(24), 0, dp(24), dp(8))
                visibility = if (isEnabledFor(pinned, hidden)) View.GONE else View.VISIBLE
            }
        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(message)
                addView(checkBox)
                addView(needsShortcut)
            }
        MaterialAlertDialogBuilder(context)
            .setTitle(R.string.set_app_icon)
            .setView(root)
            .setPositiveButton(R.string.icon_pick) { _, _ ->
                pickImage.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                )
            }
            .setNegativeButton(R.string.icon_close, null)
            .show()
    }

    private fun isEnabledFor(pinned: Boolean, hidden: Boolean) = pinned || hidden

    private fun openIconEditor(uri: Uri) {
        viewLifecycleOwner.lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) { decode(uri) }
            val context = context ?: return@launch
            if (bitmap == null) {
                Toast.makeText(context, R.string.icon_load_failed, Toast.LENGTH_SHORT).show()
                return@launch
            }
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val editor =
                CustomIconView(context).apply {
                    layoutParams =
                        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(320))
                    setBitmap(bitmap)
                }
            val hint =
                TextView(context).apply {
                    setText(R.string.icon_edit_hint)
                    textSize = 13f
                    gravity = Gravity.CENTER
                    setPadding(dp(24), dp(8), dp(24), 0)
                }
            val root =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(editor)
                    addView(hint)
                }
            MaterialAlertDialogBuilder(context)
                .setTitle(R.string.set_app_icon)
                .setView(root)
                .setPositiveButton(R.string.icon_create) { _, _ ->
                    val icon = editor.render() ?: return@setPositiveButton
                    when (CustomIcon.pin(context, icon)) {
                        CustomIcon.Result.UPDATED ->
                            Toast.makeText(context, R.string.icon_updated, Toast.LENGTH_SHORT)
                                .show()
                        CustomIcon.Result.UNSUPPORTED ->
                            Toast.makeText(context, R.string.icon_unsupported, Toast.LENGTH_LONG)
                                .show()
                        CustomIcon.Result.REQUESTED -> {
                            // The launcher shows its own confirmation.
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }
    }

    /** Read the picture, scaled down so a huge photo does not use all the memory. */
    private fun decode(uri: Uri): Bitmap? {
        val context = context ?: return null
        val resolver = context.contentResolver
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Also applies the rotation the camera stored with the photo.
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) {
                    decoder,
                    info,
                    _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val longest = max(info.size.width, info.size.height)
                    if (longest > MAX_SIDE) {
                        val factor = MAX_SIDE.toFloat() / longest
                        decoder.setTargetSize(
                            (info.size.width * factor).toInt().coerceAtLeast(1),
                            (info.size.height * factor).toInt().coerceAtLeast(1),
                        )
                    }
                }
            } else {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / sample > MAX_SIDE) sample *= 2
                val options = BitmapFactory.Options().apply { inSampleSize = sample }
                resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
            }
        } catch (e: Exception) {
            L.e(e, "Could not read the picture")
            null
        }
    }

    private companion object {
        const val MAX_SIDE = 2048
    }
}
