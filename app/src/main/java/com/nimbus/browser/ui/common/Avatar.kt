package com.nimbus.browser.ui.common

import android.graphics.drawable.Drawable
import android.widget.FrameLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import com.nimbus.browser.R
import com.nimbus.browser.core.UrlUtils

/**
 * Renders the letter avatar used in place of a real favicon.
 *
 * This is deliberately *not* `TextView.bindGlyph()`: that helper paints a
 * solid background on the TextView itself, which would cover the circular
 * `bg_favicon_circle` drawn by the enclosing `faviconHolder`. Here the circle
 * keeps its shape and only its fill changes, so the avatar stays round and
 * still varies per site.
 */
internal fun FrameLayout.bindAvatar(glyphView: TextView, url: String?) {
    glyphView.text = UrlUtils.glyphFor(url)
    val color = context.getColor(avatarColorRes(url))
    // mutate() so tinting one row's shared ConstantState copy does not recolour
    // every other row that inflated the same drawable instance.
    background = background?.mutate()?.tinted(color) ?: fallbackBackground(color)
}

private fun Drawable.tinted(color: Int): Drawable = also {
    setTint(color)
    alpha = 255
}

private fun fallbackBackground(color: Int): Drawable =
    android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        setColor(color)
    }

@ColorRes
private fun avatarColorRes(url: String?): Int = AVATAR_PALETTE[
    UrlUtils.avatarIndex(url).coerceIn(AVATAR_PALETTE.indices)
]

private val AVATAR_PALETTE = intArrayOf(
    R.color.avatar_1, R.color.avatar_2, R.color.avatar_3, R.color.avatar_4,
    R.color.avatar_5, R.color.avatar_6, R.color.avatar_7, R.color.avatar_8
)