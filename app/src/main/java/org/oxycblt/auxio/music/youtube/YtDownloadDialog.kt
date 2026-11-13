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

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.os.Build
import android.view.inputmethod.EditorInfo
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber as L

/**
 * Temporary proof-of-concept screen: search YouTube Music, tap results to queue them. Downloads
 * run in [YtDownloadService], so closing this dialog or the app does not stop them.
 */
object YtDownloadDialog {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val source: YtSource = NewPipeYtSource()

    fun show(context: Context) {
        askNotificationPermission(context)
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

        // Status line = last search message, plus the live state of the queue when it has work.
        var searchMessage = ""
        var queue = YtDownloadService.queueState.value
        fun render() {
            status.text =
                if (queue.total > 0 && !queue.idle)
                    "Cola: ${queue.finished}/${queue.total}" +
                        (if (queue.failed > 0) " · ${queue.failed} con error" else "") +
                        " · puedes cerrar esto, sigue en segundo plano"
                else if (queue.total > 0 && queue.done + queue.failed > 0)
                    "Terminado: ${queue.done} descargadas" +
                        (if (queue.failed > 0) " · ${queue.failed} con error" else "")
                else searchMessage
        }

        fun search() {
            val q = input.text.toString().trim()
            if (q.isEmpty()) return
            searchMessage = "Buscando…"
            render()
            scope.launch {
                val found =
                    runCatching { withContext(Dispatchers.IO) { source.search(q) } }
                        .onFailure { L.e(it, "YT search failed") }
                results.clear()
                found.getOrNull()?.let { results.addAll(it) }
                adapter.clear()
                adapter.addAll(results.map { "${it.title}\n${it.artist} · ${fmt(it.durationSec)}" })
                searchMessage =
                    found.fold(
                        {
                            if (it.isEmpty()) "Sin resultados"
                            else "${it.size} resultados. Toca uno para añadirlo a la cola"
                        },
                        { "Error: ${it.message}" },
                    )
                render()
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
            YtDownloadService.enqueue(context, track)
            searchMessage = "Añadida: ${track.title}"
            render()
        }

        var watcher: Job? = null
        MaterialAlertDialogBuilder(context)
            .setTitle("Buscar y descargar (prueba)")
            .setView(column)
            .setPositiveButton("Buscar") { _, _ -> }
            .setNeutralButton("Cancelar descargas") { _, _ -> YtDownloadService.cancelAll(context) }
            .setNegativeButton("Cerrar", null)
            .setOnDismissListener { watcher?.cancel() }
            .create()
            .apply {
                show()
                // Keep the dialog open when pressing Search.
                getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener { search() }
                watcher =
                    scope.launch {
                        YtDownloadService.queueState.collect {
                            queue = it
                            render()
                        }
                    }
            }
    }

    /** Android 13+ hides the progress notification until this is granted. Fire and forget. */
    private fun askNotificationPermission(context: Context) {
        if (Build.VERSION.SDK_INT < 33) return
        if (
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
            return
        var c: Context? = context
        while (c is ContextWrapper) {
            if (c is Activity) {
                ActivityCompat.requestPermissions(
                    c,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE,
                )
                return
            }
            c = c.baseContext
        }
    }

    private const val NOTIFICATION_PERMISSION_CODE = 0xA0D0

    private fun fmt(sec: Long) = if (sec < 0) "?" else "%d:%02d".format(sec / 60, sec % 60)
}
