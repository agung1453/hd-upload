package com.nimbus.browser.model

import android.content.Context
import android.os.Bundle
import android.webkit.WebView
import com.nimbus.browser.core.Constants
import com.nimbus.browser.web.WebViewFactory
import com.nimbus.browser.web.WebViewHost
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Process-wide tab store, deliberately a singleton so tabs survive activity
 * recreation (rotation, theme change, fullscreen) without any plumbing.
 *
 * Memory policy: at most [MAX_LIVE_WEBVIEWS] tabs keep a real [WebView].
 * Everything else parks its back/forward history in a Bundle.
 */
object TabManager {

    interface Listener {
        fun onTabsChanged() {}
        fun onTabSelected(tab: BrowserTab?) {}
        fun onTabUpdated(tab: BrowserTab) {}
    }

    private val tabs = mutableListOf<BrowserTab>()
    private val listeners = CopyOnWriteArrayList<Listener>()
    private var idCounter = 0

    /** Set by the hosting activity; required before a tab can be activated. */
    var host: WebViewHost? = null

    /** When true, newly created tabs are private. */
    var privateMode: Boolean = false

    var currentTab: BrowserTab? = null
        private set

    fun tabs(): List<BrowserTab> = tabs.toList()

    fun count(): Int = tabs.size

    fun find(id: String?): BrowserTab? = tabs.firstOrNull { it.id == id }

    fun privateTabs(): List<BrowserTab> = tabs.filter { it.isPrivate }

    fun hasPrivateTabs(): Boolean = tabs.any { it.isPrivate }

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    private fun notifyChanged() = listeners.forEach { it.onTabsChanged() }

    private fun notifySelected() = listeners.forEach { it.onTabSelected(currentTab) }

    private fun notifyUpdated(tab: BrowserTab) = listeners.forEach { it.onTabUpdated(tab) }

    fun notifyTabUpdated(tab: BrowserTab) = notifyUpdated(tab)

    // ------------------------------------------------------------------ //
    // Creation / removal
    // ------------------------------------------------------------------ //

    fun newTab(url: String? = null, isPrivate: Boolean = privateMode): BrowserTab {
        val tab = BrowserTab(id = "tab-${idCounter++}-${System.nanoTime()}", isPrivate = isPrivate)
        if (url.isNullOrBlank()) {
            tab.isStartPage = true
        } else {
            tab.isStartPage = false
            tab.url = url
        }
        tabs.add(tab)
        notifyChanged()
        return tab
    }

    fun closeTab(tab: BrowserTab) {
        val index = tabs.indexOf(tab)
        if (index < 0) return
        val wasCurrent = tab === currentTab
        val wasLastPrivate = tab.isPrivate && tabs.none { it !== tab && it.isPrivate }

        tab.releaseResources()
        tabs.removeAt(index)

        if (wasCurrent) {
            currentTab = tabs.getOrNull(index.coerceAtMost(tabs.lastIndex))
        }

        if (wasLastPrivate && !hasPrivateTabs()) {
            clearPrivateSessionState()
        }

        notifyChanged()
        notifySelected()
    }

    fun closeAllTabs() {
        tabs.forEach { it.releaseResources() }
        tabs.clear()
        currentTab = null
        notifyChanged()
        notifySelected()
    }

    fun closeOtherTabs(keep: BrowserTab) {
        tabs.filter { it !== keep }.forEach { it.releaseResources() }
        tabs.clear()
        tabs.add(keep)
        currentTab = keep
        notifyChanged()
        notifySelected()
    }

    fun closeAllPrivateTabs(): Int {
        val privateTabs = tabs.filter { it.isPrivate }
        privateTabs.forEach { it.releaseResources() }
        tabs.removeAll(privateTabs.toSet())
        if (currentTab?.isPrivate == true) {
            currentTab = tabs.firstOrNull()
        }
        if (privateTabs.isNotEmpty()) clearPrivateSessionState()
        notifyChanged()
        notifySelected()
        return privateTabs.size
    }

    /**
     * Makes [tab] the visible tab and returns its live WebView, creating one if
     * needed. Returns null when the tab shows the native start page.
     *
     * Deliberately does **not** fire [Listener.onTabSelected]: the caller's job
     * is to put the returned WebView on screen, and that render already comes
     * back through here. Notifying from inside `activate` would recurse
     * (activate -> listener -> render -> activate -> ...) and blow the stack.
     * Callers render explicitly; the listener is only for changes they did not
     * initiate, such as `closeTab`.
     */
    fun activate(context: Context, tab: BrowserTab): WebView? {
        currentTab = tab
        tab.touch()

        if (tab.isStartPage) {
            tab.releaseWebView()
            trim()
            return null
        }

        val web = tab.webView ?: buildWebView(context, tab)
        trim()
        return web
    }

    private fun buildWebView(context: Context, tab: BrowserTab): WebView {
        val host = requireNotNull(host) {
            "TabManager.host must be assigned by BrowserActivity before activating tabs"
        }
        val web = WebViewFactory.create(context, tab, host)
        val state = tab.savedState
        var restored = false
        if (state != null) {
            restored = runCatching { web.restoreState(state) }.getOrNull() != null
            tab.savedState = null
        }
        tab.attachWebView(web)
        if (!restored || web.url.isNullOrEmpty()) {
            web.loadUrl(tab.url.ifEmpty { Constants.ABOUT_BLANK })
        }
        return web
    }

    /** Releases the WebViews of all but the [MAX_LIVE_WEBVIEWS] hottest tabs. */
    private fun trim() {
        val live = tabs.filter { it.webView != null }.sortedBy { it.lastAccessed }
        var excess = live.size - MAX_LIVE_WEBVIEWS
        if (excess <= 0) return
        for (tab in live) {
            if (excess <= 0) break
            if (tab === currentTab) continue
            tab.releaseWebView()
            notifyUpdated(tab)
            excess--
        }
    }

    /** Called when the system asks us to free memory. */
    fun onTrimMemory(level: Int) {
        when {
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> {
                tabs.filter { it !== currentTab }.forEach { it.releaseWebView() }
            }
            level >= android.content.ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                tabs.forEach { it.releaseWebView() }
            }
        }
    }

    fun destroy() {
        tabs.forEach { it.releaseResources() }
        tabs.clear()
        currentTab = null
    }

    private fun clearPrivateSessionState() {
        runCatching {
            android.webkit.WebStorage.getInstance().deleteAllData()
        }
    }

    // ------------------------------------------------------------------ //
    // State persistence (process death)
    // ------------------------------------------------------------------ //

    fun saveTo(out: Bundle) {
        out.putParcelableArrayList(
            KEY_TABS,
            ArrayList(tabs.map { it.toBundle() })
        )
        out.putString(KEY_CURRENT, currentTab?.id)
        out.putBoolean(KEY_PRIVATE_MODE, privateMode)
        out.putInt(KEY_COUNTER, idCounter)
    }

    fun restoreFrom(saved: Bundle?) {
        if (saved == null) return
        if (tabs.isNotEmpty()) return // already restored in this process
        @Suppress("DEPRECATION")
        val stored = saved.getParcelableArrayList<Bundle>(KEY_TABS)
        val restored = stored.orEmpty().mapNotNull { runCatching { BrowserTab.fromBundle(it) }.getOrNull() }
        tabs.clear()
        tabs.addAll(restored)
        idCounter = saved.getInt(KEY_COUNTER, restored.size)
        privateMode = saved.getBoolean(KEY_PRIVATE_MODE, false)
        currentTab = restored.firstOrNull { it.id == saved.getString(KEY_CURRENT) } ?: restored.firstOrNull()
    }

    const val MAX_LIVE_WEBVIEWS = 3

    private const val KEY_TABS = "nimbus.tabs"
    private const val KEY_CURRENT = "nimbus.currentTab"
    private const val KEY_PRIVATE_MODE = "nimbus.privateMode"
    private const val KEY_COUNTER = "nimbus.counter"
}