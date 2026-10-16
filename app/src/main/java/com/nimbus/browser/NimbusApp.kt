package com.nimbus.browser

import android.app.Application
import android.webkit.WebView
import com.nimbus.browser.core.Prefs
import com.nimbus.browser.core.ThemeHelper
import com.nimbus.browser.data.AppContext
import com.nimbus.browser.data.BookmarkRepository
import com.nimbus.browser.data.HistoryRepository
import com.nimbus.browser.ui.settings.DefaultShortcuts

class NimbusApp : Application() {

    override fun onCreate() {
        super.onCreate()

        // Preferences first: everything else reads from them.
        Prefs.init(this)
        ThemeHelper.apply()

        // Open (and warm) the history / bookmark stores off the main thread.
        AppContext.install(this)
        HistoryRepository.init(this)
        BookmarkRepository.init(this)

        // First launch only: give the start page its default shortcut tiles.
        DefaultShortcuts.ensureSeeded()

        // WebView debugging is only ever enabled in debuggable builds.
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
    }
}