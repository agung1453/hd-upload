package com.nimbus.browser.web

import android.content.Context
import android.os.Build
import android.view.View
import android.webkit.WebSettings
import android.webkit.WebView
import com.nimbus.browser.R
import com.nimbus.browser.core.Constants
import com.nimbus.browser.core.Prefs
import com.nimbus.browser.core.WebCacheMode
import com.nimbus.browser.model.BrowserTab
import com.nimbus.browser.util.DownloadHelper

/**
 * Builds WebViews with one consistent, performance-conscious configuration.
 */
object WebViewFactory {

    fun create(context: Context, tab: BrowserTab, host: WebViewHost): WebView {
        val web = WebView(context)
        web.isFocusable = true
        web.isFocusableInTouchMode = true
        web.overScrollMode = View.OVER_SCROLL_NEVER
        web.isVerticalScrollBarEnabled = false
        web.isHorizontalScrollBarEnabled = false
        web.isLongClickable = true
        web.setBackgroundColor(context.getColor(R.color.bg_surface))
        web.setLayerType(View.LAYER_TYPE_HARDWARE, null)

        applySettings(web, tab.isPrivate)

        web.webViewClient = NimbusWebViewClient(tab, host)
        web.webChromeClient = NimbusWebChromeClient(context, tab, host)

        web.setDownloadListener { url, userAgent, contentDisposition, mimeType, contentLength ->
            DownloadHelper.enqueue(context, url, userAgent, contentDisposition, mimeType, contentLength)
        }

        return web
    }

    fun applySettings(web: WebView, isPrivate: Boolean) {
        val settings: WebSettings = web.settings

        settings.javaScriptEnabled = true
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(true)
        settings.loadsImagesAutomatically = true
        settings.blockNetworkImage = false
        settings.blockNetworkLoads = false
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.setGeolocationEnabled(false)
        // Tied to the "fullscreen video" preference.
        settings.mediaPlaybackRequiresUserGesture = !Prefs.fullscreen
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        settings.allowFileAccess = false
        settings.allowContentAccess = true
        settings.textZoom = (Prefs.textZoom * 100).toInt()
        settings.cacheMode = cacheModeFor(isPrivate)

        // Desktop mode is opt-in, otherwise we keep the platform default UA.
        if (Prefs.desktopSite) {
            settings.userAgentString = Constants.UA_DESKTOP
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = !isPrivate
        }

        // Private tabs must not leave anything behind on disk.
        settings.domStorageEnabled = !isPrivate
    }

    private fun cacheModeFor(isPrivate: Boolean): Int = when {
        isPrivate -> WebSettings.LOAD_NO_CACHE
        Prefs.webCacheMode == WebCacheMode.NEVER -> WebSettings.LOAD_NO_CACHE
        Prefs.webCacheMode == WebCacheMode.REDUCED -> WebSettings.LOAD_CACHE_ELSE_NETWORK
        else -> WebSettings.LOAD_DEFAULT
    }
}