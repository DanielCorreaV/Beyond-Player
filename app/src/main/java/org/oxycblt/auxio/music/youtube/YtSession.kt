/*
 * Copyright (c) 2026 Beyond Player
 * YtSession.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.music.youtube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.webkit.CookieManager
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject
import timber.log.Timber as L

/**
 * The YouTube Music account of the person. Signing in happens once in a web view (see
 * [YtLoginSheet]); what is kept is the cookies of that session, which are as powerful as a password,
 * so they are stored encrypted with a key that lives in the phone's keystore and never leaves it.
 * The password itself is never seen or stored by the app.
 */
object YtSession {
    private const val PREFS = "yt_session"
    private const val KEY = "blob"
    private const val ALIAS = "beyond_yt_session"
    private const val ORIGIN = "https://music.youtube.com"

    @Volatile private var appContext: Context? = null
    @Volatile private var cookie: String? = null
    @Volatile private var visitor: String? = null
    @Volatile private var authUser: String = "0"
    @Volatile private var restored = false

    val loggedIn: StateFlow<Boolean>
        field = MutableStateFlow(false)

    val isLoggedIn: Boolean
        get() = cookie != null

    /** Load the saved session (once). Safe to call again. */
    fun restore(context: Context) {
        appContext = context.applicationContext
        if (restored) return
        restored = true
        try {
            val blob =
                context.applicationContext
                    .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .getString(KEY, null) ?: return
            val json = JSONObject(decrypt(blob))
            cookie = json.optString("cookie").ifEmpty { null }
            visitor = json.optString("visitor").ifEmpty { null }
            authUser = json.optString("authUser").ifEmpty { "0" }
            loggedIn.value = cookie != null
        } catch (e: Exception) {
            L.w("YT session could not be restored: ${e.message}")
            wipe(context)
        }
    }

    /** Keep a new session (right after the web view login). */
    fun save(context: Context, cookie: String, visitor: String?, authUser: String) {
        appContext = context.applicationContext
        restored = true
        this.cookie = cookie
        this.visitor = visitor?.ifEmpty { null }
        this.authUser = authUser.ifEmpty { "0" }
        try {
            val json =
                JSONObject()
                    .put("cookie", cookie)
                    .put("visitor", visitor.orEmpty())
                    .put("authUser", this.authUser)
            context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY, encrypt(json.toString()))
                .apply()
        } catch (e: Exception) {
            // Still signed in until the app closes.
            L.w("YT session could not be saved: ${e.message}")
        }
        YtExplore.clearCache()
        loggedIn.value = true
    }

    /** Sign out: forget the session here and in the web view. */
    fun logout(context: Context) {
        wipe(context)
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    /** YouTube refused the session (it expired or was closed from another place). */
    fun onAuthRejected() {
        val context = appContext ?: return
        if (cookie == null) return
        L.w("YT session rejected, signing out")
        wipe(context)
    }

    private fun wipe(context: Context) {
        cookie = null
        visitor = null
        authUser = "0"
        runCatching {
            context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        }
        YtExplore.clearCache()
        loggedIn.value = false
    }

    fun visitorData(): String? = visitor

    /** The headers that make a request come from the signed-in account, or null when signed out. */
    fun authHeaders(): Map<String, String>? {
        val c = cookie ?: return null
        val sapisid = cookieValue(c, "SAPISID") ?: cookieValue(c, "__Secure-3PAPISID") ?: return null
        val now = System.currentTimeMillis() / 1000
        val hash = sha1("$now $sapisid $ORIGIN")
        val headers =
            mutableMapOf(
                "Cookie" to c,
                "Authorization" to "SAPISIDHASH ${now}_$hash",
                "X-Goog-AuthUser" to authUser,
                "X-Origin" to ORIGIN,
            )
        visitor?.let { headers["X-Goog-Visitor-Id"] = it }
        return headers
    }

    private fun cookieValue(cookies: String, name: String): String? =
        cookies.split(";").map { it.trim() }.firstOrNull { it.startsWith("$name=") }
            ?.substringAfter('=')?.ifEmpty { null }

    private fun sha1(text: String): String =
        MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") {
            "%02x".format(it)
        }

    // ---- storage: AES-GCM with a key that never leaves the keystore ----

    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(ALIAS, null) as? SecretKey)?.let {
            return it
        }
        val generator =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    private fun encrypt(text: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val data = cipher.doFinal(text.toByteArray())
        return Base64.encodeToString(cipher.iv + data, Base64.NO_WRAP)
    }

    private fun decrypt(blob: String): String {
        val raw = Base64.decode(blob, Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw.copyOfRange(0, 12)))
        return String(cipher.doFinal(raw, 12, raw.size - 12))
    }
}
