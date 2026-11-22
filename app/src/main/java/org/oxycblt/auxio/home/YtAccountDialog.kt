/*
 * Copyright (c) 2026 Beyond Player
 * YtAccountDialog.kt is part of Beyond Player, a fork of Auxio.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */

package org.oxycblt.auxio.home

import android.graphics.Color
import android.graphics.Outline
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.Window
import android.webkit.CookieManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatDialogFragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.color.MaterialColors
import kotlinx.coroutines.launch
import org.oxycblt.auxio.R
import org.oxycblt.auxio.music.youtube.YtCovers
import org.oxycblt.auxio.music.youtube.YtSession

/**
 * The account dialog: the photo, name and email of the linked YouTube Music account, arrows to flip
 * between linked accounts, "Cambiar de cuenta" (link another one), "Cerrar sesión" (unlink this one)
 * and, with no account, "Iniciar sesión". It redraws by itself as the name and photo arrive.
 */
class YtAccountDialog : AppCompatDialogFragment() {
    private var render: (() -> Unit)? = null

    override fun onCreateDialog(savedInstanceState: Bundle?): android.app.Dialog {
        val dialog = super.onCreateDialog(savedInstanceState)
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE)
        return dialog
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View {
        val context = requireContext()
        val d = resources.displayMetrics.density
        val onSurface =
            MaterialColors.getColor(context, com.google.android.material.R.attr.colorOnSurface, 0)
        val variant =
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorOnSurfaceVariant,
                0,
            )
        val surface =
            MaterialColors.getColor(
                context,
                com.google.android.material.R.attr.colorSurfaceVariant,
                0,
            )
        val wrap = ViewGroup.LayoutParams.WRAP_CONTENT
        val match = ViewGroup.LayoutParams.MATCH_PARENT

        fun styled(appearance: Int, color: Int) =
            TextView(context).apply {
                setTextAppearance(appearance)
                setTextColor(color)
                gravity = Gravity.CENTER
            }

        val caption =
            styled(com.google.android.material.R.style.TextAppearance_Material3_LabelLarge, onSurface)
                .apply {
                    text = getString(R.string.lbl_yt_account).uppercase()
                    letterSpacing = 0.12f
                }
        val avatar =
            ImageView(context).apply {
                setBackgroundColor(Color.argb(40, 128, 128, 128))
                clipToOutline = true
                outlineProvider =
                    object : ViewOutlineProvider() {
                        override fun getOutline(view: View, outline: Outline) {
                            outline.setOval(0, 0, view.width, view.height)
                        }
                    }
            }
        fun arrow(symbol: String, step: Int) =
            TextView(context).apply {
                text = symbol
                textSize = 40f
                setTextColor(onSurface)
                gravity = Gravity.CENTER
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), (6 * d).toInt())
                setOnClickListener {
                    val n = YtSession.accounts().size
                    if (n > 1) YtSession.switchTo((YtSession.activeIndex() + step + n) % n)
                }
            }
        val previous = arrow("‹", -1)
        val next = arrow("›", 1)
        val avatarRow =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                addView(previous)
                addView(
                    avatar,
                    LinearLayout.LayoutParams((112 * d).toInt(), (112 * d).toInt()).apply {
                        marginStart = (8 * d).toInt()
                        marginEnd = (8 * d).toInt()
                    },
                )
                addView(next)
            }
        val name =
            styled(com.google.android.material.R.style.TextAppearance_Material3_TitleMedium, onSurface)
                .apply { setTypeface(typeface, android.graphics.Typeface.BOLD) }
        val email =
            styled(com.google.android.material.R.style.TextAppearance_Material3_BodySmall, variant)
        val status =
            styled(com.google.android.material.R.style.TextAppearance_Material3_BodyMedium, variant)
                .apply { setText(R.string.lbl_yt_logged_in) }

        fun textButton(label: Int, onClick: () -> Unit) =
            MaterialButton(
                    context,
                    null,
                    androidx.appcompat.R.attr.borderlessButtonStyle,
                )
                .apply {
                    setText(label)
                    setOnClickListener { onClick() }
                }

        val switchButton = textButton(R.string.lbl_yt_switch) { startLogin() }
        val logoutButton =
            textButton(R.string.lbl_yt_logout) { YtSession.logout(requireContext()) }
        val closeButton = textButton(R.string.lbl_yt_close) { dismissAllowingStateLoss() }
        val buttons =
            LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(logoutButton)
                addView(View(context), LinearLayout.LayoutParams(0, 1, 1f))
                addView(closeButton)
            }
        val privacy =
            styled(com.google.android.material.R.style.TextAppearance_Material3_BodySmall, variant)
                .apply { setText(R.string.lbl_yt_privacy) }

        val root =
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                val p = (24 * d).toInt()
                setPadding(p, p, p, (16 * d).toInt())
                background =
                    GradientDrawable().apply {
                        cornerRadius = 28 * d
                        setColor(surface)
                    }
                addView(caption)
                addView(
                    avatarRow,
                    LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = (16 * d).toInt() },
                )
                addView(
                    name,
                    LinearLayout.LayoutParams(match, wrap).apply { topMargin = (16 * d).toInt() },
                )
                addView(email, LinearLayout.LayoutParams(match, wrap))
                addView(
                    switchButton,
                    LinearLayout.LayoutParams(wrap, wrap).apply { topMargin = (8 * d).toInt() },
                )
                addView(status, LinearLayout.LayoutParams(match, wrap))
                addView(
                    buttons,
                    LinearLayout.LayoutParams(match, wrap).apply { topMargin = (16 * d).toInt() },
                )
                addView(
                    privacy,
                    LinearLayout.LayoutParams(match, wrap).apply { topMargin = (12 * d).toInt() },
                )
            }

        render = {
            val accounts = YtSession.accounts()
            val account = accounts.getOrNull(YtSession.activeIndex())
            val logged = YtSession.isLoggedIn
            name.text =
                if (!logged) getString(R.string.lbl_yt_no_account)
                else account?.name?.ifEmpty { null } ?: getString(R.string.lbl_yt_acc_loading)
            val mail = account?.email.orEmpty()
            email.text = mail
            email.visibility = if (logged && mail.isNotEmpty()) View.VISIBLE else View.GONE
            status.visibility = if (logged) View.VISIBLE else View.GONE
            logoutButton.visibility = if (logged) View.VISIBLE else View.INVISIBLE
            val several = accounts.size > 1
            previous.visibility = if (several) View.VISIBLE else View.INVISIBLE
            next.visibility = if (several) View.VISIBLE else View.INVISIBLE
            switchButton.setText(if (logged) R.string.lbl_yt_switch else R.string.lbl_yt_login)

            val photo = account?.photo
            avatar.tag = photo
            if (photo.isNullOrBlank()) {
                avatar.scaleType = ImageView.ScaleType.FIT_CENTER
                val pad = (28 * d).toInt()
                avatar.setPadding(pad, pad, pad, pad)
                avatar.setImageResource(R.drawable.ic_account_24)
            } else {
                avatar.scaleType = ImageView.ScaleType.CENTER_CROP
                avatar.setPadding(0, 0, 0, 0)
                val cached = YtCovers.cached(context, photo)
                if (cached != null) {
                    avatar.setImageURI(Uri.fromFile(cached))
                } else {
                    avatar.setImageDrawable(null)
                    YtCovers.load(context, photo) { file ->
                        if (avatar.tag == photo) avatar.setImageURI(Uri.fromFile(file))
                    }
                }
            }
        }
        render?.invoke()
        return root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch { YtSession.profiles.collect { render?.invoke() } }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.apply {
            setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            setLayout(
                (resources.displayMetrics.widthPixels * 0.9f).toInt(),
                ViewGroup.LayoutParams.WRAP_CONTENT,
            )
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        render = null
    }

    /** Link (another) account: the web login is cleared so Google asks for the account again. */
    private fun startLogin() {
        val manager = parentFragmentManager
        val linked = YtSession.isLoggedIn
        dismissAllowingStateLoss()
        val show = { YtLoginSheet().show(manager, "yt_login") }
        if (linked) {
            val cookies = CookieManager.getInstance()
            cookies.removeAllCookies { show() }
            cookies.flush()
        } else {
            show()
        }
    }
}
