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
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber as L

/** What the app shows of a linked account. */
data class YtAccount(val name: String, val email: String, val photo: String?)

/**
 * The YouTube Music accounts of the person (one or several, one of them active). Signing in happens
 * in a web view (see [YtLoginSheet]); what is kept is the cookies of each session, which are as
 * powerful as a password, so they are stored encrypted with a key that lives in the phone's keystore
 * and never leaves it. The password itself is never seen or stored by the app.
 */
object YtSession {
    private const val PREFS = "yt_session"
    private const val KEY = "blob"
    private const val ALIAS = "beyond_yt_session"
    private const val ORIGIN = "https://music.youtube.com"

    private class Entry(
        val cookie: String,
        val visitor: String?,
        val authUser: String,
        var name: String = "",
        var email: String = "",
        var photo: String? = null,
        var loading: Boolean = false,
    )

    private val lock = Any()
    private val entries = mutableListOf<Entry>()
    private var active = 0

    @Volatile private var appContext: Context? = null
    @Volatile private var restored = false

    val loggedIn: StateFlow<Boolean>
        field = MutableStateFlow(false)

    /** Counts changes of the signed-in session (sign in, sign out, switch): pages reload on it. */
    val sessions: StateFlow<Int>
        field = MutableStateFlow(0)

    /** Counts any change, including the name and photo arriving: the icon and the dialog redraw. */
    val profiles: StateFlow<Int>
        field = MutableStateFlow(0)

    val isLoggedIn: Boolean
        get() = synchronized(lock) { entries.isNotEmpty() }

    fun accounts(): List<YtAccount> =
        synchronized(lock) { entries.map { YtAccount(it.name, it.email, it.photo) } }

    fun activeIndex(): Int = synchronized(lock) { active }

    private fun current(): Entry? = synchronized(lock) { entries.getOrNull(active) }

    private fun bump(sessionChanged: Boolean) {
        if (sessionChanged) sessions.value = sessions.value + 1
        profiles.value = profiles.value + 1
    }

    /** Load the saved sessions (once). Safe to call again. */
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
            synchronized(lock) {
                entries.clear()
                val list = json.optJSONArray("accounts")
                if (list != null) {
                    for (i in 0 until list.length()) {
                        readEntry(list.getJSONObject(i))?.let { entries.add(it) }
                    }
                    active = json.optInt("active", 0)
                } else {
                    // The format of before the accounts list: a single session.
                    readEntry(json)?.let { entries.add(it) }
                    active = 0
                }
                active = if (entries.isEmpty()) 0 else active.coerceIn(0, entries.size - 1)
            }
            loggedIn.value = isLoggedIn
            ensureProfile()
        } catch (e: Exception) {
            L.w("YT session could not be restored: ${e.message}")
            synchronized(lock) {
                entries.clear()
                active = 0
            }
            persist()
            loggedIn.value = false
        }
    }

    private fun readEntry(o: JSONObject): Entry? {
        val cookie = o.optString("cookie").ifEmpty { null } ?: return null
        return Entry(
            cookie,
            o.optString("visitor").ifEmpty { null },
            o.optString("authUser").ifEmpty { "0" },
            o.optString("name"),
            o.optString("email"),
            o.optString("photo").ifEmpty { null },
        )
    }

    private fun persist() {
        val context = appContext ?: return
        try {
            val snapshot =
                synchronized(lock) {
                    if (entries.isEmpty()) {
                        null
                    } else {
                        val list = JSONArray()
                        for (e in entries) {
                            list.put(
                                JSONObject()
                                    .put("cookie", e.cookie)
                                    .put("visitor", e.visitor.orEmpty())
                                    .put("authUser", e.authUser)
                                    .put("name", e.name)
                                    .put("email", e.email)
                                    .put("photo", e.photo.orEmpty())
                            )
                        }
                        JSONObject().put("active", active).put("accounts", list)
                    }
                }
            val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            if (snapshot == null) edit.clear() else edit.putString(KEY, encrypt(snapshot.toString()))
            edit.apply()
        } catch (e: Exception) {
            // Still signed in until the app closes.
            L.w("YT session could not be saved: ${e.message}")
        }
    }

    /** Keep a new session (right after the web view login). It becomes the active account. */
    fun save(context: Context, cookie: String, visitor: String?, authUser: String) {
        appContext = context.applicationContext
        restored = true
        synchronized(lock) {
            entries.add(Entry(cookie, visitor?.ifEmpty { null }, authUser.ifEmpty { "0" }))
            active = entries.size - 1
        }
        persist()
        YtExplore.clearCache()
        loggedIn.value = true
        bump(true)
        ensureProfile()
    }

    /** Make another saved account the active one. */
    fun switchTo(index: Int) {
        synchronized(lock) {
            if (index !in entries.indices || index == active) return
            active = index
        }
        persist()
        YtExplore.clearCache()
        bump(true)
        ensureProfile()
    }

    /** Sign out of the active account (the others stay) and forget the login of the web view. */
    fun logout(context: Context) {
        appContext = context.applicationContext
        removeActive()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    /** YouTube refused the session (it expired or was closed from another place). */
    fun onAuthRejected() {
        if (appContext == null || !isLoggedIn) return
        L.w("YT session rejected, signing out of it")
        removeActive()
    }

    private fun removeActive() {
        val left =
            synchronized(lock) {
                if (entries.isNotEmpty()) {
                    entries.removeAt(active.coerceIn(0, entries.size - 1))
                }
                active = if (entries.isEmpty()) 0 else active.coerceIn(0, entries.size - 1)
                entries.size
            }
        persist()
        YtExplore.clearCache()
        loggedIn.value = left > 0
        bump(true)
        if (left > 0) ensureProfile()
    }

    /** Ask for the name, email and photo of the active account, if they are not known yet. */
    fun ensureProfile() {
        val entry =
            synchronized(lock) {
                val e = entries.getOrNull(active) ?: return
                if (e.name.isNotEmpty() || e.loading) return
                e.loading = true
                e
            }
        Thread {
                try {
                    val found = parseProfile(YtExplore.accountMenu())
                    if (found != null) {
                        synchronized(lock) {
                            entry.name = found.name
                            entry.email = found.email
                            entry.photo = found.photo
                            if (entry.email.isNotEmpty() && entries.contains(entry)) {
                                // The same account signed in twice: keep the newest session only.
                                entries.removeAll { it !== entry && it.email == entry.email }
                                active = entries.indexOf(entry).coerceAtLeast(0)
                            }
                        }
                        persist()
                        bump(false)
                    }
                } catch (e: Exception) {
                    L.w("YT profile could not be read: ${e.message}")
                } finally {
                    synchronized(lock) { entry.loading = false }
                }
            }
            .start()
    }

    private fun parseProfile(text: String): YtAccount? {
        val header =
            JSONObject(text)
                .optJSONArray("actions")
                ?.optJSONObject(0)
                ?.optJSONObject("openPopupAction")
                ?.optJSONObject("popup")
                ?.optJSONObject("multiPageMenuRenderer")
                ?.optJSONObject("header")
                ?.optJSONObject("activeAccountHeaderRenderer") ?: return null
        fun runText(o: JSONObject?): String =
            o?.optJSONArray("runs")?.optJSONObject(0)?.optString("text").orEmpty()
        val name = runText(header.optJSONObject("accountName"))
        if (name.isEmpty()) return null
        val thumbs = header.optJSONObject("accountPhoto")?.optJSONArray("thumbnails")
        val photo =
            thumbs
                ?.optJSONObject(thumbs.length() - 1)
                ?.optString("url")
                ?.ifEmpty { null }
                // Ask for a big square picture: the icon is small but the dialog shows it large.
                ?.replace(Regex("=s\\d+"), "=s256")
        return YtAccount(name, runText(header.optJSONObject("email")), photo)
    }

    fun visitorData(): String? = current()?.visitor

    /** The headers that make a request come from the signed-in account, or null when signed out. */
    fun authHeaders(): Map<String, String>? {
        val e = current() ?: return null
        val c = e.cookie
        val sapisid = cookieValue(c, "SAPISID") ?: cookieValue(c, "__Secure-3PAPISID") ?: return null
        val now = System.currentTimeMillis() / 1000
        val hash = sha1("$now $sapisid $ORIGIN")
        val headers =
            mutableMapOf(
                "Cookie" to c,
                "Authorization" to "SAPISIDHASH ${now}_$hash",
                "X-Goog-AuthUser" to e.authUser,
                "X-Origin" to ORIGIN,
            )
        e.visitor?.let { headers["X-Goog-Visitor-Id"] = it }
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
