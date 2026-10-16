package com.nimbus.browser.ui.browser

import android.app.DownloadManager
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ActivityInfo
import android.graphics.Color
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.webkit.CookieManager
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.widget.EditText
import android.widget.LinearLayout
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import com.nimbus.browser.R
import com.nimbus.browser.core.Constants
import com.nimbus.browser.core.Prefs
import com.nimbus.browser.core.Shortcut
import com.nimbus.browser.core.ThemeHelper
import com.nimbus.browser.core.UrlUtils
import com.nimbus.browser.core.applyBottomInset
import com.nimbus.browser.core.applyTopInset
import com.nimbus.browser.core.onClickThrottled
import com.nimbus.browser.core.setVisible
import com.nimbus.browser.core.toast
import com.nimbus.browser.data.BookmarkRepository
import com.nimbus.browser.databinding.ActivityBrowserBinding
import com.nimbus.browser.model.BrowserTab
import com.nimbus.browser.model.TabManager
import com.nimbus.browser.ui.about.AboutActivity
import com.nimbus.browser.ui.bookmarks.BookmarksActivity
import com.nimbus.browser.ui.common.BottomMenu
import com.nimbus.browser.ui.common.MenuEntry
import com.nimbus.browser.ui.history.HistoryActivity
import com.nimbus.browser.ui.settings.ClearDataDialog
import com.nimbus.browser.ui.settings.SettingsActivity
import com.nimbus.browser.ui.tabs.TabOverviewActivity
import com.nimbus.browser.web.WebViewHost

/**
 * The single browser screen: toolbar, address bar, WebView host, bottom
 * navigation and the actions that tie the other screens together.
 */
class BrowserActivity : AppCompatActivity(), WebViewHost, StartPageHost {

    private lateinit var binding: ActivityBrowserBinding
    private lateinit var startPage: StartPageController

    private val currentTab: BrowserTab? get() = TabManager.currentTab
    private val currentWeb get() = currentTab?.webView

    private var addressFocused = false
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val tabListener = object : TabManager.Listener {
        override fun onTabsChanged() = refreshTabUi()
        override fun onTabSelected(tab: BrowserTab?) = renderCurrentTab()
        override fun onTabUpdated(tab: BrowserTab) {
            if (tab === currentTab) updateChrome()
        }
    }

    private val prefListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == Prefs.KEY_THEME) {
            ThemeHelper.apply()
            recreate()
        }
    }

    private val fileChooserLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val callback = pendingFileCallback
            pendingFileCallback = null
            val uris = WebChromeClient.FileChooserParams.parseResult(
                result.resultCode,
                result.data
            )
            callback?.onReceiveValue(uris)
        }

    private val tabOverviewLauncher: ActivityResultLauncher<Intent> =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data ?: return@registerForActivityResult
            if (data.getBooleanExtra(TabOverviewActivity.RESULT_NEW_TAB, false)) {
                openNewTab()
                return@registerForActivityResult
            }
            val tabId = data.getStringExtra(TabOverviewActivity.RESULT_SELECTED_TAB)
                ?: return@registerForActivityResult
            TabManager.find(tabId)?.let { renderTab(it) }
        }

    private val backCallback = object : OnBackPressedCallback(true) {
        override fun handleOnBackPressed() {
            when {
                addressFocused -> collapseAddressBar()
                customView != null -> exitFullscreen()
                currentWeb?.canGoBack() == true -> currentWeb?.goBack()
                TabManager.count() > 1 -> {
                    currentTab?.let { TabManager.closeTab(it) }
                    renderCurrentTab()
                }
                else -> moveTaskToBack(true)
            }
        }
    }

    // ================================================================== //
    // Lifecycle
    // ================================================================== //

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        ThemeHelper.apply()
        super.onCreate(savedInstanceState)
        binding = ActivityBrowserBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        binding.topBar.applyTopInset()
        binding.bottomBar.applyBottomInset()

        TabManager.host = this
        TabManager.addListener(tabListener)
        Prefs.registerChangeListener(prefListener)
        onBackPressedDispatcher.addCallback(this, backCallback)

        startPage = StartPageController(this, binding.startPageContainer, this)

        setupToolbar()
        setupAddressBar()
        setupBottomBar()

        TabManager.restoreFrom(savedInstanceState)
        if (TabManager.count() == 0) {
            TabManager.newTab()
        }
        if (TabManager.currentTab == null) {
            TabManager.activate(this, TabManager.tabs().first())
        }

        handleIntent(intent)
        renderCurrentTab()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = intent?.getStringExtra(Constants.EXTRA_URL)?.trim().orEmpty()
        if (url.isEmpty()) return
        val tab = currentTab
        if (tab != null && tab.isStartPage) {
            openInTab(tab, url)
        } else {
            TabManager.newTab(url = url).also { renderTab(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        currentWeb?.onResume()
        startPage.refresh()
        updateChrome()
    }

    override fun onPause() {
        currentWeb?.onPause()
        CookieManager.getInstance().flush()
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        TabManager.saveTo(outState)
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        TabManager.onTrimMemory(level)
    }

    override fun onDestroy() {
        TabManager.removeListener(tabListener)
        TabManager.host = null
        Prefs.unregisterChangeListener(prefListener)
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        if (isFinishing && Prefs.closeTabsOnExit) TabManager.destroy()
        super.onDestroy()
    }

    // ================================================================== //
    // Setup
    // ================================================================== //

    private fun setupToolbar() {
        binding.btnTabs.onClickThrottled {
            tabOverviewLauncher.launch(Intent(this, TabOverviewActivity::class.java))
        }
        binding.btnReload.onClickThrottled {
            val tab = currentTab ?: return@onClickThrottled
            val web = currentWeb
            when {
                tab.isStartPage -> openNewTab()
                tab.isLoading && web != null -> {
                    web.stop()
                    tab.isLoading = false
                    updateReloadButton()
                    stopProgress()
                }
                else -> {
                    if (web == null) renderCurrentTab() else web.reload()
                    startProgress()
                }
            }
        }
    }

    private fun setupAddressBar() {
        binding.addressPill.setOnClickListener { focusAddressBar() }
        binding.etUrl.setOnEditorActionListener { view, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_GO || actionId == EditorInfo.IME_ACTION_DONE) {
                submitAddress(view.text?.toString().orEmpty())
                true
            } else {
                false
            }
        }
        binding.etUrl.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) collapseAddressBar()
        }
    }

    private fun setupBottomBar() {
        binding.btnBack.onClickThrottled {
            val web = currentWeb
            if (web?.canGoBack() == true) {
                web.goBack()
            } else {
                currentTab?.let { TabManager.closeTab(it) }
                renderCurrentTab()
            }
        }
        binding.btnForward.onClickThrottled { currentWeb?.takeIf { it.canGoForward() }?.goForward() }
        binding.btnShare.onClickThrottled { shareCurrentPage() }
        binding.btnBookmarks.onClickThrottled { toggleBookmark() }
        binding.btnBookmarks.setOnLongClickListener {
            startActivity(Intent(this, BookmarksActivity::class.java))
            true
        }
        binding.btnMenu.onClickThrottled { showMenu() }
    }

    // ================================================================== //
    // Address bar
    // ================================================================== //

    private fun focusAddressBar() {
        if (addressFocused) return
        addressFocused = true
        val tab = currentTab
        binding.addressPill.setBackgroundResource(R.drawable.bg_address_pill_focused)
        binding.etUrl.setVisible(true)
        binding.tvUrl.setVisible(false)
        binding.etUrl.setText(if (tab != null && !tab.isStartPage) tab.url else "")
        binding.etUrl.setSelection(binding.etUrl.text.length)
        binding.etUrl.requestFocus()
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.showSoftInput(binding.etUrl, InputMethodManager.SHOW_IMPLICIT)
    }

    private fun collapseAddressBar() {
        if (!addressFocused) return
        addressFocused = false
        val imm = getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
        imm?.hideSoftInputFromWindow(binding.etUrl.windowToken, 0)
        binding.etUrl.clearFocus()
        binding.etUrl.setVisible(false)
        binding.tvUrl.setVisible(true)
        binding.addressPill.setBackgroundResource(R.drawable.bg_address_pill)
        updateAddressBar()
    }

    private fun submitAddress(text: String) {
        val trimmed = text.trim()
        collapseAddressBar()
        if (trimmed.isEmpty()) {
            showStartPage()
        } else {
            navigate(trimmed)
        }
    }

    // ================================================================== //
    // Navigation
    // ================================================================== //

    /** Resolves [input] to a URL or search query and loads it in this tab. */
    private fun navigate(input: String) {
        val url = UrlUtils.resolve(input, Prefs.searchEngine)
        val tab = currentTab
        if (tab == null) {
            renderTab(TabManager.newTab(url = url))
            return
        }
        if (!tab.isStartPage && currentWeb != null) {
            // Same tab: keep it in the back/forward stack like a real browser.
            tab.isLoading = true
            currentWeb?.loadUrl(url)
            startProgress()
        } else {
            openInTab(tab, url)
        }
        updateChrome()
    }

    /** Forces [tab] to load [url] from scratch, ignoring any parked history. */
    private fun openInTab(tab: BrowserTab, url: String) {
        tab.isStartPage = false
        tab.url = url
        tab.title = ""
        tab.releaseWebView()
        tab.savedState = null
        renderTab(tab)
    }

    private fun openNewTab(url: String? = null) {
        renderTab(TabManager.newTab(url = url, isPrivate = TabManager.privateMode))
    }

    private fun showStartPage() {
        val tab = currentTab ?: TabManager.newTab()
        tab.isStartPage = true
        tab.releaseWebView()
        renderCurrentTab()
    }

    override fun onStartPageNavigate(input: String) {
        if (input.isEmpty()) showStartPage() else navigate(input)
    }

    override fun onStartPageShortcutLongPressed(shortcut: Shortcut, anchor: View) {
        BottomMenu.show(
            context = this,
            entries = listOf(
                MenuEntry(
                    id = "open",
                    title = shortcut.title,
                    icon = R.drawable.ic_external,
                    subtitle = UrlUtils.displayUrl(shortcut.url)
                ),
                MenuEntry(
                    id = "remove",
                    title = getString(R.string.menu_remove_shortcut),
                    icon = R.drawable.ic_trash,
                    danger = true
                )
            )
        ) { picked ->
            when (picked.id) {
                "open" -> navigate(shortcut.url)
                "remove" -> {
                    Prefs.removeShortcut(shortcut.url)
                    startPage.refresh()
                    toast(R.string.toast_shortcut_removed)
                }
            }
        }
    }

    // ================================================================== //
    // Rendering
    // ================================================================== //

    private fun renderCurrentTab() = renderTab(TabManager.currentTab)

    private fun renderTab(tab: BrowserTab?) {
        binding.webContainer.removeAllViews()

        if (tab == null) {
            binding.webContainer.setVisible(false)
            startPage.hide()
            refreshTabUi()
            return
        }

        val web = TabManager.activate(this, tab)
        if (web == null) {
            binding.webContainer.setVisible(false)
            startPage.show()
        } else {
            startPage.hide()
            binding.webContainer.setVisible(true)
            binding.webContainer.addView(web)
        }
        updateChrome()
    }

    private fun refreshTabUi() = updateChrome()

    private fun updateChrome() {
        if (!::binding.isInitialized) return
        updateAddressBar()
        updateBottomBar()
        updateTabBadge()
        updateBookmarkButton()
        updateReloadButton()
        updatePrivateBackground()
    }

    private fun updateAddressBar() {
        val tab = currentTab ?: return
        if (addressFocused) return
        binding.tvUrl.text = if (tab.isStartPage) {
            getString(R.string.url_hint)
        } else {
            UrlUtils.displayUrl(tab.url)
        }

        val secure = tab.isSecure()
        binding.icSecure.setImageResource(
            if (secure) R.drawable.ic_lock else R.drawable.ic_globe
        )
        binding.icSecure.contentDescription = getString(
            if (secure) R.string.cd_secure else R.string.cd_not_secure
        )
    }

    private fun updateBottomBar() {
        val tab = currentTab
        val web = currentWeb
        val canBack = web?.canGoBack() == true || (tab != null && !tab.isStartPage)
        val canForward = web?.canGoForward() == true
        val canShare = tab != null && !tab.isStartPage

        binding.btnBack.isEnabled = canBack
        binding.btnForward.isEnabled = canForward
        binding.btnShare.isEnabled = canShare

        binding.btnBack.alpha = if (canBack) 1f else 0.35f
        binding.btnForward.alpha = if (canForward) 1f else 0.35f
        binding.btnShare.alpha = if (canShare) 1f else 0.35f
    }

    private fun updateTabBadge() {
        val count = TabManager.count()
        if (count > 1) {
            binding.txtTabCount.setVisible(true)
            binding.txtTabCount.text = count.toString()
        } else {
            binding.txtTabCount.setVisible(false)
        }
    }

    private fun updateBookmarkButton() {
        val tab = currentTab
        val bookmarked = tab != null && !tab.isStartPage && BookmarkRepository.isBookmarked(tab.url)
        binding.btnBookmarks.setImageResource(
            if (bookmarked) R.drawable.ic_bookmark_filled else R.drawable.ic_bookmark
        )
        binding.btnBookmarks.setColorFilter(
            getColor(if (bookmarked) R.color.accent else R.color.text_secondary)
        )
    }

    private fun updateReloadButton() {
        val loading = currentTab?.isLoading == true
        binding.btnReload.setImageResource(
            if (loading) R.drawable.ic_stop else R.drawable.ic_reload
        )
        binding.btnReload.contentDescription =
            getString(if (loading) R.string.action_stop else R.string.action_reload)
    }

    private fun updatePrivateBackground() {
        val priv = currentTab?.isPrivate == true
        binding.contentContainer.setBackgroundColor(
            getColor(if (priv) R.color.private_surface else R.color.bg_root)
        )
    }

    private fun startProgress() {
        binding.progressBar.isIndeterminate = false
        binding.progressBar.setProgressCompat(currentTab?.progress ?: 0, false)
        binding.progressBar.setVisible(true)
    }

    private fun stopProgress() {
        binding.progressBar.setVisible(false)
    }

    // ================================================================== //
    // Menu + actions
    // ================================================================== //

    private fun showMenu() {
        val tab = currentTab
        val onPage = tab != null && !tab.isStartPage
        val bookmarked = onPage && BookmarkRepository.isBookmarked(tab.url)
        val inShortcuts = onPage && Prefs.shortcuts().any { it.url == tab.url }

        val entries = buildList {
            add(MenuEntry("new_tab", getString(R.string.action_new_tab), R.drawable.ic_add))
            add(
                MenuEntry(
                    "new_private",
                    getString(R.string.action_new_private_tab),
                    R.drawable.ic_private
                )
            )
            if (onPage) {
                add(
                    MenuEntry(
                        "bookmark",
                        getString(
                            if (bookmarked) R.string.menu_remove_bookmark
                            else R.string.menu_add_bookmark
                        ),
                        R.drawable.ic_bookmark,
                        checked = bookmarked
                    )
                )
                add(
                    MenuEntry(
                        "shortcut",
                        getString(
                            if (inShortcuts) R.string.menu_remove_shortcut
                            else R.string.menu_add_shortcut
                        ),
                        R.drawable.ic_grid,
                        checked = inShortcuts
                    )
                )
                add(MenuEntry("copy", getString(R.string.menu_copy_link), R.drawable.ic_link))
                add(MenuEntry("share", getString(R.string.menu_share_link), R.drawable.ic_share))
                add(
                    MenuEntry(
                        "desktop",
                        getString(R.string.menu_desktop_site),
                        R.drawable.ic_desktop,
                        checked = Prefs.desktopSite
                    )
                )
            }
            add(MenuEntry("bookmarks", getString(R.string.action_bookmarks), R.drawable.ic_bookmark_filled))
            add(MenuEntry("history", getString(R.string.action_history), R.drawable.ic_history))
            add(MenuEntry("downloads", getString(R.string.action_downloads), R.drawable.ic_download))
            add(
                MenuEntry(
                    "private",
                    getString(R.string.menu_close_private_tabs),
                    R.drawable.ic_private_eye_slash,
                    subtitle = getString(R.string.private_detail)
                )
            )
            add(MenuEntry("clear", getString(R.string.menu_clear_browsing_data), R.drawable.ic_trash))
            add(MenuEntry("settings", getString(R.string.action_settings), R.drawable.ic_settings))
            add(MenuEntry("about", getString(R.string.action_about), R.drawable.ic_info))
        }

        BottomMenu.show(this, entries = entries) { onMenuEntry(it) }
    }

    private fun onMenuEntry(entry: MenuEntry) {
        val tab = currentTab
        when (entry.id) {
            "new_tab" -> openNewTab()
            "new_private" -> {
                val fresh = TabManager.newTab(isPrivate = true)
                toast(R.string.private_start)
                renderTab(fresh)
            }
            "bookmark" -> toggleBookmark()
            "shortcut" -> tab?.let { promptShortcut(it) }
            "copy" -> tab?.let { copyLink(it) }
            "share" -> shareCurrentPage()
            "desktop" -> toggleDesktopSite()
            "bookmarks" -> startActivity(Intent(this, BookmarksActivity::class.java))
            "history" -> startActivity(Intent(this, HistoryActivity::class.java))
            "downloads" -> openDownloads()
            "private" -> closePrivateTabs()
            "clear" -> ClearDataDialog().show(supportFragmentManager, "clear_data")
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            "about" -> startActivity(Intent(this, AboutActivity::class.java))
        }
    }

    private fun shareCurrentPage() {
        val tab = currentTab
        if (tab == null || tab.isStartPage) {
            toast(R.string.toast_no_history)
            return
        }
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, tab.displayTitle())
            putExtra(Intent.EXTRA_TEXT, "${tab.displayTitle()}\n${tab.url}")
        }
        runCatching {
            startActivity(Intent.createChooser(send, getString(R.string.action_share)))
        }.onFailure { toast(R.string.toast_no_app_found) }
    }

    private fun copyLink(tab: BrowserTab) {
        val clip = getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        clip?.setPrimaryClip(ClipData.newPlainText(getString(R.string.app_name), tab.url))
        toast(R.string.toast_link_copied)
    }

    private fun toggleBookmark() {
        val tab = currentTab
        if (tab == null || tab.isStartPage) {
            startActivity(Intent(this, BookmarksActivity::class.java))
            return
        }
        val nowBookmarked = BookmarkRepository.toggleBookmark(tab.url, tab.displayTitle())
        updateBookmarkButton()
        Snackbar.make(
            binding.root,
            getString(
                if (nowBookmarked) R.string.toast_bookmark_added
                else R.string.toast_bookmark_removed
            ),
            Snackbar.LENGTH_SHORT
        ).setAction(R.string.action_undo) {
            BookmarkRepository.toggleBookmark(tab.url, tab.displayTitle())
        }.show()
    }

    private fun promptShortcut(tab: BrowserTab) {
        if (Prefs.shortcuts().any { it.url == tab.url }) {
            Prefs.removeShortcut(tab.url)
            startPage.refresh()
            toast(R.string.toast_shortcut_removed)
            return
        }

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 24, 48, 8)
        }
        val titleField = EditText(this).apply {
            setHint(R.string.dialog_shortcut_name_hint)
            setText(tab.displayTitle())
            setSingleLine()
        }
        val urlField = EditText(this).apply {
            setHint(R.string.dialog_shortcut_url_hint)
            setText(tab.url)
            setSingleLine()
        }
        container.addView(titleField)
        container.addView(urlField)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dialog_shortcut_title)
            .setView(container)
            .setNegativeButton(R.string.action_cancel, null)
            .setPositiveButton(R.string.action_save) { _, _ ->
                Prefs.addShortcut(
                    Shortcut(
                        title = titleField.text.toString().ifBlank { UrlUtils.hostOf(tab.url) },
                        url = urlField.text.toString().ifBlank { tab.url }
                    )
                )
                startPage.refresh()
                toast(R.string.toast_shortcut_added)
            }
            .show()
    }

    private fun toggleDesktopSite() {
        val enabled = !Prefs.desktopSite
        Prefs.putBoolean(Prefs.KEY_DESKTOP_SITE, enabled)
        // The user agent can only change before a document loads, so rebuild.
        val tab = currentTab
        if (tab != null && !tab.isStartPage) {
            val url = tab.url
            tab.releaseWebView()
            tab.savedState = null
            tab.url = url
            renderTab(tab)
        }
        toast(R.string.toast_reloading_settings)
    }

    private fun openDownloads() {
        val intent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            toast(R.string.toast_no_app_found)
        }
    }

    private fun closePrivateTabs() {
        val count = TabManager.closeAllPrivateTabs()
        if (count == 0) {
            toast(R.string.private_nothing_to_close)
            return
        }
        toast(R.string.private_end)
        if (TabManager.count() == 0) {
            TabManager.newTab()
        }
        renderCurrentTab()
    }

    // ================================================================== //
    // Fullscreen video
    // ================================================================== //

    override fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) {
            callback.onCustomViewHidden()
            return
        }
        customView = view
        customCallback = callback
        binding.root.addView(
            view,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )
        binding.topBar.setVisible(false)
        binding.bottomBar.setVisible(false)
        binding.contentContainer.setBackgroundColor(Color.BLACK)

        if (Prefs.fullscreen) {
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        setSystemBarsVisible(false)
    }

    override fun onHideCustomView() = exitFullscreen()

    private fun exitFullscreen() {
        val view = customView ?: return
        binding.root.removeView(view)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        binding.topBar.setVisible(true)
        binding.bottomBar.setVisible(true)
        updatePrivateBackground()
        setSystemBarsVisible(true)
    }

    private fun setSystemBarsVisible(visible: Boolean) {
        val controller = WindowInsetsControllerCompat(window, binding.root)
        if (visible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    // ================================================================== //
    // WebViewHost
    // ================================================================== //

    override fun onPageStarted(tab: BrowserTab, url: String, favicon: Bitmap?) {
        if (tab !== currentTab) return
        startPage.hide()
        binding.webContainer.setVisible(true)
        updateAddressBar()
        startProgress()
    }

    override fun onPageFinished(tab: BrowserTab, url: String) {
        if (tab !== currentTab) return
        tab.isLoading = false
        stopProgress()
        updateChrome()
    }

    override fun onProgressChanged(tab: BrowserTab, progress: Int) {
        if (tab !== currentTab) return
        tab.progress = progress
        binding.progressBar.setProgressCompat(progress, true)
        if (progress >= 100) stopProgress()
        updateReloadButton()
    }

    override fun onReceivedTitle(tab: BrowserTab, title: String) {
        if (tab !== currentTab) return
        tab.title = title
        updateAddressBar()
    }

    override fun onHistoryChanged(tab: BrowserTab, canBack: Boolean, canForward: Boolean) {
        if (tab !== currentTab) return
        tab.canGoBack = canBack
        tab.canGoForward = canForward
        updateBottomBar()
    }

    override fun onOpenExternal(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        if (runCatching { startActivity(intent) }.isFailure) {
            toast(R.string.toast_no_app_found)
        }
    }

    override fun onRequestNewTab(url: String, isPrivate: Boolean) {
        if (url.isBlank() || url == Constants.ABOUT_BLANK) return
        renderTab(TabManager.newTab(url = url, isPrivate = isPrivate))
    }

    override fun openFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<Uri>>
    ): Boolean {
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = callback
        return try {
            val intent = params.createIntent().apply { addCategory(Intent.CATEGORY_OPENABLE) }
            fileChooserLauncher.launch(
                Intent.createChooser(intent, getString(R.string.title_file_chooser))
            )
            true
        } catch (e: ActivityNotFoundException) {
            pendingFileCallback = null
            toast(R.string.error_file_chooser)
            false
        }
    }
}