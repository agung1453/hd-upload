package com.nimbus.browser.model

import android.os.Bundle
import android.webkit.WebView
import com.nimbus.browser.core.Constants
import com.nimbus.browser.core.UrlUtils

/**
 * A single browser tab.
 *
 * A tab owns *at most one* live [WebView]. When the tab is not one of the few
 * "hot" tabs the WebView is destroyed and its navigation history is parked in
 * [savedState] (produced by [WebView.saveState]), which can be restored later
 * without a network round-trip. This keeps memory flat no matter how many tabs
 * the user opens.
 */
class BrowserTab(
    val id: String,
    var isPrivate: Boolean = false
) {

    /** True while the tab shows the native start page instead of a web page. */
    var isStartPage: Boolean = true

    var url: String = ""
    var title: String = ""

    var canGoBack: Boolean = false
    var canGoForward: Boolean = false
    var isLoading: Boolean = false
    var progress: Int = 0

    /** Bumped on every navigation so adapters can tell pages apart. */
    var revision: Long = 0L

    var lastAccessed: Long = System.currentTimeMillis()

    var savedState: Bundle? = null

    /** The live WebView while this tab is hot. Null once trimmed. */
    var webView: WebView? = null
        internal set

    /** Set by the WebViewClient so the activity can react to external intents. */
    var pendingExternalUrl: String? = null

    fun touch() {
        lastAccessed = System.currentTimeMillis()
    }

    /** Human readable text shown on the tab card and the start page. */
    fun displayTitle(): String = when {
        title.isNotEmpty() -> title
        else -> UrlUtils.hostOf(url).ifEmpty { url }
    }

    fun displayUrl(): String = if (isStartPage) "" else UrlUtils.displayUrl(url)

    fun isSecure(): Boolean = UrlUtils.isSecure(url)

    fun glyph(): String = if (isStartPage) "N" else UrlUtils.glyphFor(url)

    /** Attaches a freshly created or recycled WebView to this tab. */
    internal fun attachWebView(web: WebView) {
        webView = web
        touch()
    }

    /**
     * Parks the navigation history and tears the WebView down so the memory
     * can be reclaimed.
     */
    internal fun releaseWebView() {
        webView?.let { web ->
            savedState = runCatching { web.saveState() }.getOrNull()
            web.stopLoading()
            web.webViewClient = android.webkit.WebViewClient()
            web.webChromeClient = null
            (web.parent as? android.view.ViewGroup)?.removeView(web)
            web.destroy()
        }
        webView = null
        isLoading = false
        progress = 0
    }

    fun releaseResources() {
        releaseWebView()
        savedState = null
    }

    fun toBundle(): Bundle = Bundle().apply {
        putString(KEY_ID, id)
        putBoolean(KEY_PRIVATE, isPrivate)
        putBoolean(KEY_START, isStartPage)
        putString(KEY_URL, url)
        putString(KEY_TITLE, title)
        putLong(KEY_ACCESSED, lastAccessed)
        savedState?.let { putBundle(KEY_SAVED_STATE, it) }
    }

    companion object {
        private const val KEY_ID = "id"
        private const val KEY_PRIVATE = "private"
        private const val KEY_START = "start"
        private const val KEY_URL = "url"
        private const val KEY_TITLE = "title"
        private const val KEY_ACCESSED = "accessed"
        private const val KEY_SAVED_STATE = "savedState"

        fun fromBundle(bundle: Bundle): BrowserTab {
            val tab = BrowserTab(
                id = bundle.getString(KEY_ID).orEmpty(),
                isPrivate = bundle.getBoolean(KEY_PRIVATE, false)
            )
            tab.isStartPage = bundle.getBoolean(KEY_START, true)
            tab.url = bundle.getString(KEY_URL).orEmpty()
            tab.title = bundle.getString(KEY_TITLE).orEmpty()
            tab.lastAccessed = bundle.getLong(KEY_ACCESSED, System.currentTimeMillis())
            tab.savedState = runCatching { bundle.getBundle(KEY_SAVED_STATE) }.getOrNull()
            if (!tab.isStartPage && tab.url.isEmpty()) tab.isStartPage = true
            if (tab.url == Constants.ABOUT_BLANK) tab.isStartPage = true
            return tab
        }
    }
}