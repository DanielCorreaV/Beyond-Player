/*
 * Copyright (c) 2026 Auxio Project
 * YtFastResolve.kt is part of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import android.os.SystemClock
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.json.JSONArray
import org.json.JSONObject
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.services.youtube.InnertubeClientRequestInfo
import org.schabi.newpipe.extractor.services.youtube.YoutubeParsingHelper
import timber.log.Timber as L

/**
 * The quick way to get the stream address of a song for playing it: two small requests instead of
 * the ten that a full NewPipe lookup makes. It borrows the client description, user agent and
 * helper functions from NewPipe, so NewPipe updates keep it current. When it fails the caller falls
 * back to the full lookup.
 */
internal object YtFastResolve {
    private const val BASE = "https://youtubei.googleapis.com/youtubei/v1/"
    private const val VISITOR_TTL_MS = 6 * 60 * 60 * 1000L
    private const val PAUSE_MS = 10 * 60 * 1000L
    private const val MAX_FAILURES = 3

    @Volatile private var visitor: Pair<String, Long>? = null
    @Volatile private var failures = 0
    @Volatile private var pausedUntil = 0L

    /** The audio stream of the song, or null when the quick way did not work. Blocking. */
    fun tryResolve(videoId: String): YtAudio? {
        if (SystemClock.elapsedRealtime() < pausedUntil) return null
        val started = SystemClock.elapsedRealtime()
        return try {
            val audio = resolve(videoId)
            failures = 0
            android.util.Log.d(
                "YtStream",
                "YT fast $videoId: ${SystemClock.elapsedRealtime() - started} ms",
            )
            audio
        } catch (e: Exception) {
            L.w("YT fast lookup failed for $videoId: ${e.message}")
            // Several failures in a row means YouTube changed something: stop trying for a while.
            if (++failures >= MAX_FAILURES) {
                pausedUntil = SystemClock.elapsedRealtime() + PAUSE_MS
                failures = 0
            }
            null
        }
    }

    /** The visitor id YouTube wants in every request; remembered for hours. Blocking. */
    fun visitorData(): String {
        val now = SystemClock.elapsedRealtime()
        visitor?.takeIf { it.second > now }?.let {
            return it.first
        }
        val response = post(BASE + "visitor_id?prettyPrint=false", requestBody(null))
        val data = JSONObject(response).getJSONObject("responseContext").getString("visitorData")
        visitor = data to SystemClock.elapsedRealtime() + VISITOR_TTL_MS
        return data
    }

    private fun resolve(videoId: String): YtAudio {
        val cpn = YoutubeParsingHelper.generateContentPlaybackNonce()
        val body = requestBody(visitorData())
        body.put(
            "playerRequest",
            JSONObject()
                .put("videoId", videoId)
                .put("cpn", cpn)
                .put("contentCheckOk", true)
                .put("racyCheckOk", true),
        )
        body.put("disablePlayerResponse", false)
        val url =
            BASE +
                "reel/reel_item_watch?prettyPrint=false&t=" +
                YoutubeParsingHelper.generateTParameter() +
                "&id=" +
                videoId +
                "&\$fields=playerResponse"
        val player = JSONObject(post(url, body)).getJSONObject("playerResponse")
        val status = player.optJSONObject("playabilityStatus")?.optString("status")
        if (status != null && status.isNotEmpty() && !status.equals("ok", ignoreCase = true)) {
            throw YtException("Not playable ($status): $videoId")
        }
        val formats: JSONArray = player.getJSONObject("streamingData").getJSONArray("adaptiveFormats")
        val audio =
            (0 until formats.length())
                .map { formats.getJSONObject(it) }
                .filter {
                    it.optString("mimeType").startsWith("audio/") && it.optString("url").isNotEmpty()
                }
        val best =
            audio
                .filter { it.optString("mimeType").startsWith("audio/mp4") }
                .maxByOrNull { it.optInt("bitrate") }
                ?: audio.maxByOrNull { it.optInt("bitrate") }
                ?: throw YtException("No direct audio stream for $videoId")
        val streamUrl = best.getString("url")
        // Addresses that need the "n" parameter decoded are throttled: leave those to NewPipe.
        if (Regex("[?&]n=").containsMatchIn(streamUrl)) {
            throw YtException("Stream needs the throttling parameter decoded")
        }
        val mime = best.optString("mimeType").substringBefore(';')
        return YtAudio(
            url = "$streamUrl&cpn=$cpn",
            mimeType = mime,
            extension = if (mime.contains("webm")) "webm" else "m4a",
            bitrate = best.optInt("bitrate") / 1000,
        )
    }

    /** The "who is asking" part of the request, copied from what NewPipe sends as the Android app. */
    private fun requestBody(visitorData: String?): JSONObject {
        val info = InnertubeClientRequestInfo.ofAndroidClient()
        val client =
            JSONObject()
                .put("clientName", info.clientInfo.clientName)
                .put("clientVersion", info.clientInfo.clientVersion)
        info.clientInfo.clientScreen?.let { client.put("clientScreen", it) }
        info.deviceInfo.platform?.let { client.put("platform", it) }
        visitorData?.let { client.put("visitorData", it) }
        info.deviceInfo.osName?.let { client.put("osName", it) }
        info.deviceInfo.osVersion?.let { client.put("osVersion", it) }
        if (info.deviceInfo.androidSdkVersion > 0) {
            client.put("androidSdkVersion", info.deviceInfo.androidSdkVersion)
        }
        client
            .put("hl", Localization.DEFAULT.localizationCode)
            .put("gl", ContentCountry.DEFAULT.countryCode)
            .put("utcOffsetMinutes", 0)
        return JSONObject()
            .put(
                "context",
                JSONObject()
                    .put("client", client)
                    .put(
                        "request",
                        JSONObject().put("internalExperimentFlags", JSONArray()).put("useSsl", true),
                    )
                    .put("user", JSONObject().put("lockedSafetyMode", false)),
            )
    }

    private fun post(url: String, body: JSONObject): String {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.doOutput = true
            conn.setRequestProperty(
                "User-Agent",
                YoutubeParsingHelper.getAndroidUserAgent(Localization.DEFAULT),
            )
            conn.setRequestProperty("X-Goog-Api-Format-Version", "2")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept-Encoding", "gzip")
            conn.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = conn.responseCode
            if (code != 200) throw YtException("HTTP $code from $url")
            val stream = conn.inputStream
            val text =
                (if (conn.contentEncoding.equals("gzip", ignoreCase = true)) GZIPInputStream(stream)
                    else stream)
                    .bufferedReader()
                    .use { it.readText() }
            return text
        } catch (e: Exception) {
            // Only drop the connection on errors: after a complete read it goes back to the pool.
            conn.disconnect()
            throw e
        }
    }
}
