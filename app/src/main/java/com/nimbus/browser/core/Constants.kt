package com.nimbus.browser.core

/**
 * App-wide constants. Kept intentionally tiny so nothing here forces the
 * UI layer to know about the data layer.
 */
object Constants {

    // Persistence keys used for saved instance state / process death.
    const val STATE_SELECTED_TAB = "nimbus.state.selectedTab"
    const val STATE_PRIVATE = "nimbus.state.private"
    const val STATE_TAB_COUNT = "nimbus.state.tabCount"

    // Intent extras
    const val EXTRA_URL = "nimbus.extra.url"
    const val EXTRA_PRIVATE = "nimbus.extra.private"

    // Schemes
    const val SCHEME_HTTP = "http"
    const val SCHEME_HTTPS = "https"
    const val SCHEME_FILE = "file"
    const val SCHEME_INTENT = "intent"
    const val SCHEME_JAVASCRIPT = "javascript"
    const val SCHEME_DATA = "data"
    const val SCHEME_BLOB = "blob"
    const val SCHEME_CONTENT = "content"
    const val SCHEME_ABOUT = "about"

    const val ABOUT_BLANK = "about:blank"

    // Hard limit so a runaway tab loop cannot OOM the process.
    const val MAX_TABS = 50

    // Only these schemes are allowed to be handed to the WebView.
    val WEB_SCHEMES = setOf(SCHEME_HTTP, SCHEME_HTTPS, SCHEME_FILE, SCHEME_DATA, SCHEME_BLOB, SCHEME_CONTENT)

    // User-Agent variants
    const val UA_DESKTOP =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/126.0.0.0 Safari/537.36"
}