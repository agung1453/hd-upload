package com.nimbus.browser.core

import android.content.Context
import android.view.View
import android.widget.ImageView
import android.widget.Toast
import androidx.annotation.ColorRes
import androidx.annotation.StringRes
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

fun View.setVisible(visible: Boolean) {
    visibility = if (visible) View.VISIBLE else View.GONE
}

fun Context.toast(@StringRes message: Int) = toast(getString(message))

fun Context.toast(message: String) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}

/** Guards against accidental double taps on toolbar buttons. */
fun View.onClickThrottled(throttleMs: Long = 350L, action: (View) -> Unit) {
    var lastClick = 0L
    setOnClickListener { view ->
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastClick >= throttleMs) {
            lastClick = now
            action(view)
        }
    }
}

/**
 * Pads a toolbar by the status-bar inset while keeping its designed height.
 * The designed padding is captured once, because the listener runs again on
 * every inset dispatch (rotation, keyboard, gesture changes).
 */
fun View.applyTopInset() {
    val initialTop = paddingTop
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(view.paddingLeft, initialTop + bars.top, view.paddingRight, view.paddingBottom)
        insets
    }
}

/** Adds the navigation-bar inset as bottom padding. */
fun View.applyBottomInset(extraPx: Int = 0) {
    val initialBottom = paddingBottom
    ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
        val bars: Insets = insets.getInsets(WindowInsetsCompat.Type.systemBars())
        view.setPadding(
            view.paddingLeft,
            view.paddingTop,
            view.paddingRight,
            initialBottom + bars.bottom + extraPx
        )
        insets
    }
}

fun ImageView.setIconTint(@ColorRes colorRes: Int) {
    setColorFilter(context.getColor(colorRes))
}