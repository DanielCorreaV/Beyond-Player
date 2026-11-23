/*
 * Copyright (c) 2025 Auxio Project
 * NamedTabStrategy.kt is part of Auxio.
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
 
package org.oxycblt.auxio.home.tabs

import com.google.android.material.tabs.TabLayout
import com.google.android.material.tabs.TabLayoutMediator.TabConfigurationStrategy
import org.oxycblt.auxio.music.MusicType

class NamedTabStrategy(
    private val homeTabs: List<MusicType>,
    private val extraTitle: String? = null,
    private val accountTitle: String? = null,
    private val favoritesTitle: String? = null,
) : TabConfigurationStrategy {
    override fun onConfigureTab(tab: TabLayout.Tab, position: Int) {
        if (position == 0) {
            // The Explore tab: just a compass.
            tab.setIcon(org.oxycblt.auxio.R.drawable.ic_explore_24)
            tab.contentDescription = extraTitle
        } else if (position == 1) {
            // The account tab: just a person.
            tab.setIcon(org.oxycblt.auxio.R.drawable.ic_account_24)
            tab.contentDescription = accountTitle
        } else if (position == 2) {
            // The favorites tab: just a heart.
            tab.setIcon(org.oxycblt.auxio.R.drawable.ic_heart_24)
            tab.contentDescription = favoritesTitle
        } else {
            tab.setText(homeTabs[position - 3].nameRes)
        }
    }
}
