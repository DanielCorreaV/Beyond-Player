/*
 * Copyright (c) 2026 Auxio Project
 * CustomIcon.kt is part of Auxio.
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

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.Intent
import android.graphics.Bitmap
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import org.oxycblt.auxio.MainActivity
import org.oxycblt.auxio.R

/**
 * Puts the user's own picture on the home screen as an icon.
 *
 * Android does not let an app swap its own launcher icon for an arbitrary image at runtime, the
 * icon is fixed when the app is built. What an app can do is add a shortcut to the home screen,
 * with any picture as its icon, that opens the app. That is what this does. The icon in the app
 * drawer stays the default one.
 */
object CustomIcon {
    private const val ID = "beyond_custom_icon"
    // The launcher entry of the app, see the manifest. The package name of debug builds has a
    // suffix, the class names do not.
    private const val ORIGINAL_ICON = "org.oxycblt.auxio.IconDefault"

    enum class Result {
        /** The shortcut is already on the home screen, its picture was replaced. */
        UPDATED,
        /** The launcher was asked to add it, it will show its own confirmation. */
        REQUESTED,
        UNSUPPORTED,
    }

    /** Whether the shortcut with the user's picture is on the home screen. */
    fun isPinned(context: Context): Boolean =
        try {
            ShortcutManagerCompat.getShortcuts(context, ShortcutManagerCompat.FLAG_MATCH_PINNED)
                .any { it.id == ID }
        } catch (e: Exception) {
            false
        }

    /** Whether the original icon is hidden from the app drawer. */
    fun isOriginalHidden(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(
            ComponentName(context.packageName, ORIGINAL_ICON)
        ) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    /**
     * Hide or show the original icon in the app drawer, so the user's shortcut is not next to a
     * second icon of the same app. The app stays installed and the shortcut keeps working.
     */
    fun setOriginalHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            ComponentName(context.packageName, ORIGINAL_ICON),
            if (hidden) {
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            } else {
                PackageManager.COMPONENT_ENABLED_STATE_ENABLED
            },
            PackageManager.DONT_KILL_APP,
        )
    }

    /**
     * @param bitmap The picture, as the full layer of an adaptive icon (108dp, with the part that
     *   is always visible in the middle 72dp).
     */
    fun pin(context: Context, bitmap: Bitmap): Result {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) return Result.UNSUPPORTED
        val info =
            ShortcutInfoCompat.Builder(context, ID)
                .setShortLabel(context.getString(R.string.info_app_name))
                .setIcon(IconCompat.createWithAdaptiveBitmap(bitmap))
                .setIntent(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN))
                .build()
        return if (isPinned(context)) {
            ShortcutManagerCompat.updateShortcuts(context, listOf(info))
            Result.UPDATED
        } else if (ShortcutManagerCompat.requestPinShortcut(context, info, null)) {
            Result.REQUESTED
        } else {
            Result.UNSUPPORTED
        }
    }
}
