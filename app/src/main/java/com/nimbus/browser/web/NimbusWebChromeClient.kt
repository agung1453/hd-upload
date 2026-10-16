package com.nimbus.browser.web

import android.content.Context
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import com.nimbus.browser.model.BrowserTab

class NimbusWebChromeClient(
    private val context: Context,
    private val tab: BrowserTab,
    private val host: WebViewHost
) : WebChromeClient() {

    override fun onProgressChanged(view: WebView, newProgress: Int) {
        tab.progress = newProgress
        tab.isLoading = newProgress < 100
        host.onProgressChanged(tab, newProgress)
    }

    override fun onReceivedTitle(view: WebView, title: String?) {
        // Handled by NimbusWebViewClient#onReceivedTitle.
    }

    // ----------------------------------------------------------------- //
    // File uploads
    // ----------------------------------------------------------------- //

    override fun onShowFileChooser(
        webView: WebView,
        filePathCallback: ValueCallback<Array<android.net.Uri>>,
        fileChooserParams: FileChooserParams
    ): Boolean = host.openFileChooser(fileChooserParams, filePathCallback)

    // ----------------------------------------------------------------- //
    // target=_blank / window.open
    // ----------------------------------------------------------------- //

    override fun onCreateWindow(
        view: WebView,
        isDialog: Boolean,
        isUserGesture: Boolean,
        resultMsg: android.os.Message
    ): Boolean {
        val transport = resultMsg.obj as? WebView.WebViewTransport ?: return false

        // A throwaway WebView is used purely to capture the URL the page
        // asked us to open, then it is immediately destroyed.
        val probe = WebView(view.context)
        probe.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                probeView: WebView,
                request: WebResourceRequest
            ): Boolean {
                val url = request.url?.toString().orEmpty()
                if (url.isNotEmpty() && url != "about:blank") {
                    host.onRequestNewTab(url, tab.isPrivate)
                }
                probeView.destroy()
                return true
            }

            override fun onPageStarted(probeView: WebView, url: String, favicon: android.graphics.Bitmap?) {
                if (url == "about:blank") return
                host.onRequestNewTab(url, tab.isPrivate)
                probeView.destroy()
            }
        }
        transport.webView = probe
        resultMsg.sendToTarget()
        return true
    }

    // ----------------------------------------------------------------- //
    // Fullscreen video
    // ----------------------------------------------------------------- //

    override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
        if (view == null || callback == null) return
        host.onShowCustomView(view, callback)
    }

    override fun onHideCustomView() = host.onHideCustomView()

    // ----------------------------------------------------------------- //
    // Permissions: privacy first, we do not hand out anything by default.
    // ----------------------------------------------------------------- //

    override fun onPermissionRequest(request: android.webkit.PermissionRequest) {
        request.deny()
    }

    override fun onGeolocationPermissionsShowPrompt(
        origin: String?,
        callback: android.webkit.GeolocationPermissions.Callback?
    ) {
        // Requires the ACCESS_FINE_LOCATION permission which we deliberately
        // do not request, so this is always a denial.
        callback?.invoke(origin, false, false)
    }
}