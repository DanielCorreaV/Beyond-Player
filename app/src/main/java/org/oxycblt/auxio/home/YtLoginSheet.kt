/*
 * Copyright (c) 2026 Beyond Player
 * YtLoginSheet.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.home

import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import com.google.android.material.color.MaterialColors
import org.json.JSONObject
import org.json.JSONTokener
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.youtube.YtSession
import timber.log.Timber as L

/**
 * Full-screen Google sign-in for YouTube Music, in a web view. As soon as the music.youtube.com
 * page loads with a signed-in session, the session is kept (see [YtSession]) and this closes.
 */
class YtLoginSheet : AppCompatDialogFragment() {
    private var web: WebView? = null
    private var finishing = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val d = resources.displayMetrics.density
        val title =
            TextView(context).apply {
                setText(R.string.lbl_yt_login_title)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium)
                setTextColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0))
            }
        val close =
            TextView(context).apply {
                setText(R.string.lbl_yt_close)
                setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge)
                setTextColor(MaterialColors.getColor(context, androidx.appcompat.R.attr.colorPrimary, 0))
                setPadding((12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt(), (12 * d).toInt())
                setOnClickListener { dismissAllowingStateLoss() }
            }
        val bar =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding((16 * d).toInt(), (24 * d).toInt(), (8 * d).toInt(), 0)
                addView(title, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(close)
            }
        val view =
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
                webViewClient =
                    object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView,
                            request: WebResourceRequest,
                        ): Boolean {
                            val scheme = request.url.scheme
                            return !(scheme == "http" || scheme == "https")
                        }

                        override fun onPageFinished(view: WebView, url: String?) {
                            if (url != null && Uri.parse(url).host == "music.youtube.com") {
                                tryFinish(view, 0)
                            }
                        }
                    }
                loadUrl("https://accounts.google.com/ServiceLogin?continue=https%3A%2F%2Fmusic.youtube.com")
            }
        web = view
        return LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(MaterialColors.getColor(context, com.google.android.material.R.attr.colorSurface, 0))
            addView(bar)
            addView(view, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        web?.destroy()
        web = null
    }

    /** Called when music.youtube.com finished loading: is there a signed-in session yet? */
    private fun tryFinish(view: WebView, attempt: Int) {
        if (finishing) return
        val cookie = CookieManager.getInstance().getCookie("https://music.youtube.com").orEmpty()
        // Not signed in (yet): keep waiting, the sign-in pages are still showing.
        if (!cookie.split(";").any { it.trim().startsWith("SAPISID=") }) return
        view.evaluateJavascript(
            "(function(){try{var c=window.yt.config_;" +
                "return JSON.stringify({v:c.VISITOR_DATA||'',i:String(c.SESSION_INDEX||0)})}" +
                "catch(e){return null}})()"
        ) { raw ->
            var visitor: String? = null
            var authUser = "0"
            runCatching {
                val inner = JSONTokener(raw).nextValue() as? String
                if (inner != null) {
                    val json = JSONObject(inner)
                    visitor = json.optString("v").ifEmpty { null }
                    authUser = json.optString("i").filter(Char::isDigit).ifEmpty { "0" }
                }
            }
            if (visitor == null && attempt < 6) {
                // The page is still starting up: ask again in a moment.
                view.postDelayed({ tryFinish(view, attempt + 1) }, 700)
            } else if (!finishing) {
                finishing = true
                L.d("YT login done (visitor=${visitor != null})")
                YtSession.save(requireContext().applicationContext, cookie, visitor, authUser)
                dismissAllowingStateLoss()
            }
        }
    }
}
