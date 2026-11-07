/*
 * Copyright (c) 2021 Auxio Project
 * AboutFragment.kt is part of Auxio.
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
 
package org.oxycblt.auxio.settings

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import androidx.core.net.toUri
import androidx.core.view.updatePadding
import androidx.fragment.app.activityViewModels
import androidx.navigation.fragment.findNavController
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.transition.MaterialFadeThrough
import dagger.hilt.android.AndroidEntryPoint
import org.oxycblt.auxio.BuildConfig
import org.oxycblt.auxio.R
import org.oxycblt.auxio.databinding.FragmentAboutBinding
import org.oxycblt.auxio.music.MusicViewModel
import org.oxycblt.auxio.playback.formatDurationMs
import org.oxycblt.auxio.ui.ViewBindingFragment
import org.oxycblt.auxio.util.collectImmediately
import org.oxycblt.auxio.util.openInBrowser
import org.oxycblt.auxio.util.startIntent
import org.oxycblt.auxio.util.systemBarInsetsCompat

/**
 * A [ViewBindingFragment] that displays information about the app and the current music library.
 *
 * @author Alexander Capehart (OxygenCobalt)
 */
@AndroidEntryPoint
class AboutFragment : ViewBindingFragment<FragmentAboutBinding>() {
    private val musicModel: MusicViewModel by activityViewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enterTransition = MaterialFadeThrough()
        exitTransition = MaterialFadeThrough()
    }

    override fun onCreateBinding(inflater: LayoutInflater) = FragmentAboutBinding.inflate(inflater)

    override fun onBindingCreated(binding: FragmentAboutBinding, savedInstanceState: Bundle?) {
        super.onBindingCreated(binding, savedInstanceState)

        // --- UI SETUP ---
        binding.aboutToolbar.setNavigationOnClickListener { findNavController().navigateUp() }
        binding.aboutContents.setOnApplyWindowInsetsListener { view, insets ->
            view.updatePadding(bottom = insets.systemBarInsetsCompat.bottom)
            insets
        }
        binding.aboutVersion.text = BuildConfig.VERSION_NAME
        // Where the source of THIS version lives. The GPL requires offering it when distributing,
        // so fill in about_fork_source_url in strings_about.xml before sharing the app.
        val sourceUrl = getString(R.string.about_fork_source_url)
        binding.aboutCode.apply {
            visibility = if (sourceUrl.isBlank()) View.GONE else View.VISIBLE
            setOnClickListener { requireContext().openInBrowser(sourceUrl) }
        }
        binding.aboutLicenses.setOnClickListener { showLegalNotices() }
        binding.aboutProfile.setOnClickListener { requireContext().openInBrowser(LINK_SOURCE) }

        val siteUrl = getString(R.string.about_fork_site_url)
        val email = getString(R.string.about_fork_email)
        binding.aboutFeedbackCard.visibility =
            if (siteUrl.isBlank() && email.isBlank()) View.GONE else View.VISIBLE
        binding.aboutFeedbackGithub.apply {
            visibility = if (siteUrl.isBlank()) View.GONE else View.VISIBLE
            setOnClickListener { requireContext().openInBrowser(siteUrl) }
        }
        binding.aboutFeedbackEmail.apply {
            visibility = if (email.isBlank()) View.GONE else View.VISIBLE
            setOnClickListener { requireContext().sendEmail(email) }
        }

        // VIEWMODEL SETUP
        collectImmediately(musicModel.statistics, ::updateStatistics)
    }

    private fun updateStatistics(statistics: MusicViewModel.Statistics?) {
        val binding = requireBinding()
        binding.aboutSongCount.text = getString(R.string.fmt_lib_song_count, statistics?.songs ?: 0)
        requireBinding().aboutAlbumCount.text =
            getString(R.string.fmt_lib_album_count, statistics?.albums ?: 0)
        requireBinding().aboutArtistCount.text =
            getString(R.string.fmt_lib_artist_count, statistics?.artists ?: 0)
        requireBinding().aboutGenreCount.text =
            getString(R.string.fmt_lib_genre_count, statistics?.genres ?: 0)
        binding.aboutTotalDuration.text =
            getString(
                R.string.fmt_lib_total_duration,
                (statistics?.durationMs ?: 0).formatDurationMs(false),
            )

        binding.aboutTotalSize.text =
            getString(
                R.string.fmt_lib_total_size,
                Formatter.formatFileSize(context, statistics?.totalSizeBytes ?: 0L),
            )
    }

    /** The legal notices the GPL asks interactive programs to show. */
    private fun showLegalNotices() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.lbl_licenses)
            .setMessage(
                getString(
                    R.string.about_legal_text,
                    getString(R.string.about_fork_name),
                    getString(R.string.about_fork_author),
                )
            )
            .setPositiveButton(R.string.about_legal_gpl) { _, _ ->
                requireContext().openInBrowser(LINK_GPL)
            }
            .setNeutralButton(R.string.about_legal_libs) { _, _ ->
                requireContext().openInBrowser(LINK_LICENSES)
            }
            .setNegativeButton(android.R.string.ok, null)
            .show()
    }

    private fun Context.sendEmail(recipient: String) {
        val intent = Intent(Intent.ACTION_SENDTO).apply { data = "mailto:$recipient".toUri() }
        startIntent(intent)
    }

    private companion object {
        const val LINK_SOURCE = "https://github.com/OxygenCobalt/Auxio"
        const val LINK_LICENSES = "$LINK_SOURCE/wiki/Licenses"
        const val LINK_GPL = "https://www.gnu.org/licenses/gpl-3.0.html"
    }
}
