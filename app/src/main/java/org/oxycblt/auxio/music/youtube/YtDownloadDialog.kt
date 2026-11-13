/*
 * Copyright (c) 2026 Beyond Player
 * YtDownloadDialog.kt is part of Beyond Player, a fork of Auxio.
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
 
package org.oxycblt.auxio.music.youtube

import android.content.Context
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber as L

/**
 * Temporary proof-of-concept screen: search YouTube Music, tap a result to download it as a real
 * file. Plain views on purpose; the real UI comes after the pipeline is proven.
 */
object YtDownloadDialog {
    // Downloads outlive the dialog, so they run on an app-wide scope.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val source: YtSource = NewPipeYtSource()

    fun show(context: Context) {
        val density = context.resources.displayMetrics.density
        val pad = (20 * density).toInt()
        val results = mutableListOf<YtTrack>()
        val adapter = ArrayAdapter<String>(context, android.R.layout.simple_list_item_1)
        val input =
            EditText(context).apply {
                hint = "Buscar canción"
                setSingleLine()
                imeOptions = EditorInfo.IME_ACTION_SEARCH
            }
        val status = TextView(context).apply { textSize = 13f; alpha = 0.7f }
        val list = ListView(context).apply { this.adapter = adapter }
        val column =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(pad, (8 * density).toInt(), pad, 0)
                addView(input)
                addView(status)
                addView(
                    list,
                    LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        (320 * density).toInt(),
                    ),
                )
            }

        fun search() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            status.text = "Buscando…"
            scope.launch {
                val found =
                    runCatching { withContext(Dispatchers.IO) { source.search(q) } }
                        .onFailure { L.e(it, "YT search failed") }
                results.clear()
                found.getOrNull()?.let { results.addAll(it) }
                adapter.clear()
                adapter.addAll(results.map { "${it.title}\n${it.artist} · ${fmt(it.durationSec)}" })
                status.text =
                    found.fold(
                        { if (it.isEmpty()) "Sin resultados" else "${it.size} resultados. Toca uno para descargar" },
                        { "Error: ${it.message}" },
                    )
            }
        }
        input.setOnEditorActionListener { _, action, _ ->
            if (action == EditorInfo.IME_ACTION_SEARCH) {
                search()
                true
            } else false
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val track = results.getOrNull(position) ?: return@setOnItemClickListener
            download(context.applicationContext, track, status)
        }

        MaterialAlertDialogBuilder(context)
            .setTitle("Buscar y descargar (prueba)")
            .setView(column)
            .setPositiveButton("Buscar") { _, _ -> }
            .setNegativeButton("Cerrar", null)
            .create()
            .apply {
                show()
                // Keep the dialog open when pressing Search.
                getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { search() }
            }
    }

    private fun download(context: Context, track: YtTrack, status: TextView) {
        status.text = "Resolviendo ${track.title}…"
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    val audio = source.resolveAudio(track.videoId)
                    withContext(Dispatchers.Main) {
                        status.text = "Descargando (${audio.extension} ${audio.bitrate}kbps)…"
                    }
                    var lastPct = -1
                    YtDownloader(context).download(track, audio) { done, total ->
                        val pct = if (total > 0) (done * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            status.post { status.text = "Descargando… $pct%" }
                        }
                    }
                }
            }
            result
                .onSuccess {
                    val msg =
                        if (it.existed) "Ya estaba: ${it.displayName}"
                        else
                            "Guardado: ${it.displayName} (${it.bytes / 1024} KB)" +
                                " · etiquetas ${if (it.tagged) "sí" else "no"}" +
                                " · portada ${if (it.hasCover) "sí" else "no"}"
                    status.text = msg
                    Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
                }
                .onFailure {
                    L.e(it, "YT download failed")
                    status.text = "Error: ${it.message}"
                    Toast.makeText(context, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                }
        }
    }

    private fun fmt(sec: Long) = if (sec < 0) "?" else "%d:%02d".format(sec / 60, sec % 60)
}
