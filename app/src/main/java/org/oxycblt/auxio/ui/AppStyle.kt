/*
 * Copyright (c) 2026 Auxio Project
 * AppStyle.kt is part of Auxio.
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

import androidx.annotation.StringRes
import androidx.annotation.StyleRes
import org.oxycblt.auxio.R

/**
 * The look of the app. [CLASSIC] is the original one, whose colors come from the accent the user
 * picks. The others bring a complete color scheme of their own, each with a light and a dark
 * version (the day/night setting chooses which).
 *
 * @param value The number saved in the settings.
 * @param theme The theme of the style, or 0 to use the accent themes.
 */
enum class AppStyle(val value: Int, @StyleRes val theme: Int, @StringRes val nameRes: Int) {
    CLASSIC(0, 0, R.string.style_classic),
    NEON(1, R.style.Theme_Auxio_Neon, R.string.style_neon),
    VINYL(2, R.style.Theme_Auxio_Vinyl, R.string.style_vinyl),
    POP(3, R.style.Theme_Auxio_Pop, R.string.style_pop);

    companion object {
        fun from(value: Int) = values().firstOrNull { it.value == value } ?: CLASSIC
    }
}
