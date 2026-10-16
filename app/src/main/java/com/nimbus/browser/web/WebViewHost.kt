package com.nimbus.browser.web

import android.graphics.Bitmap
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import com.nimbus.browser.model.BrowserTab

/**
 * Everything the WebView plumbing needs from the hosting activity. Keeps the
 * WebView clients free of any reference to a concrete Activity.
 */
interface WebViewHost {

    fun onPageStarted(tab: BrowserTab, url: String, favicon: Bitmap?)

    fun onPageFinished(tab: BrowserTab, url: String)

    fun onProgressChanged(tab: BrowserTab, progress: Int)

    fun onReceivedTitle(tab: BrowserTab, title: String)

    fun onHistoryChanged(tab: BrowserTab, canBack: Boolean, canForward: Boolean)

    /** A non-web scheme (tel:, mailto:, market:, intent:…) needs another app. */
    fun onOpenExternal(url: String)

    /** target="_blank" or a user-requested new tab. */
    fun onRequestNewTab(url: String, isPrivate: Boolean)

    fun onShowCustomView(view: View, callback: WebChromeClient.CustomViewCallback)

    fun onHideCustomView()

    /** Must launch the system picker and feed the result back to the WebView. */
    fun openFileChooser(
        params: WebChromeClient.FileChooserParams,
        callback: ValueCallback<Array<android.net.Uri>>
    ): Boolean
}