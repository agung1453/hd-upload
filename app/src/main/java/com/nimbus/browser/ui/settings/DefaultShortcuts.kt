package com.nimbus.browser.ui.settings

import android.content.Context
import com.nimbus.browser.R
import com.nimbus.browser.core.Prefs
import com.nimbus.browser.core.Shortcut
import com.nimbus.browser.core.toast

/**
 * The start-page shortcut set shipped with a fresh install.
 *
 * [ensureSeeded] writes it exactly once (guarded by [Prefs.KEY_SEEDED_SHORTCUTS]),
 * [restoreDefaults] is what the Settings -> "Reset shortcuts" action calls.
 */
object DefaultShortcuts {

    /** The shipped set; private so callers can only ever get a copy. */
    private val defaults: List<Shortcut> = listOf(
        Shortcut("Wikipedia", "https://www.wikipedia.org"),
        Shortcut("News", "https://www.bing.com/news"),
        Shortcut("YouTube", "https://m.youtube.com"),
        Shortcut("Maps", "https://www.openstreetmap.org"),
        Shortcut("Mail", "https://mail.google.com"),
        Shortcut("GitHub", "https://github.com")
    )

    /**
     * Seeds the default shortcuts the first time the app runs.
     *
     * Safe (and cheap) to call on every app start: the flag is set right after
     * the list, so a second invocation is a no-op.
     */
    fun ensureSeeded() {
        if (Prefs.getBoolean(Prefs.KEY_SEEDED_SHORTCUTS, false)) return
        Prefs.setShortcuts(defaults)
        Prefs.putBoolean(Prefs.KEY_SEEDED_SHORTCUTS, true)
    }

    /** Settings -> Data -> "Reset shortcuts". Unconditional, then a toast. */
    fun restoreDefaults(context: Context) {
        Prefs.setShortcuts(defaults)
        context.toast(R.string.toast_shortcuts_reset)
    }
}