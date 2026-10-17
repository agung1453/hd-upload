package com.nimbus.browser.web

import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.webkit.SslErrorHandler
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import com.nimbus.browser.core.Constants
import com.nimbus.browser.core.UrlUtils
import com.nimbus.browser.data.HistoryRepository
import com.nimbus.browser.model.BrowserTab

class NimbusWebViewClient(
    private val tab: BrowserTab,
    private val host: WebViewHost
) : WebViewClient() {

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
        handleUrl(view, request.url)

    @Deprecated("Kept for API 29 devices that still call the String overload")
    override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean =
        handleUrl(view, Uri.parse(url))

    private fun handleUrl(view: WebView, uri: Uri): Boolean {
        val scheme = uri.scheme?.lowercase() ?: return false
        val url = uri.toString()

        return when (scheme) {
            Constants.SCHEME_INTENT -> {
                dispatchIntent(uri)
                true
            }
            Constants.SCHEME_HTTP,
            Constants.SCHEME_HTTPS,
            Constants.SCHEME_FILE,
            Constants.SCHEME_CONTENT,
            Constants.SCHEME_DATA,
            Constants.SCHEME_BLOB,
            Constants.SCHEME_ABOUT,
            Constants.SCHEME_JAVASCRIPT -> false
            else -> {
                // tel:, mailto:, market:, geo:, sms:, whatsapp: …
                host.onOpenExternal(url)
                true
            }
        }
    }

    /**
     * Reads one `key=value` pair out of an `intent://` fragment, e.g.
     * `intent://host/path#Intent;scheme=https;S.browser_fallback_url=x;end`.
     *
     * `Uri.getStringExtra()` is not public SDK API, so the fragment is parsed
     * by hand instead of relying on it.
     */
    private fun intentExtra(uri: Uri, key: String): String? =
        uri.fragment
            ?.split(';')
            ?.firstOrNull { it.startsWith("$key=") }
            ?.substringAfter('=')
            ?.takeIf { it.isNotEmpty() }

    private fun dispatchIntent(uri: Uri) {
        val scheme = intentExtra(uri, "scheme")
        val fallback = intentExtra(uri, "browser_fallback_url")
        val target = when {
            !scheme.isNullOrEmpty() -> {
                val host = uri.host.orEmpty()
                val path = uri.path.orEmpty()
                "$scheme://$host$path"
            }
            !fallback.isNullOrEmpty() -> fallback
            else -> null
        }
        if (target != null) host.onOpenExternal(target)
    }

    // ----------------------------------------------------------------- //

    override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
        tab.isStartPage = false
        tab.url = url
        tab.isLoading = true
        tab.revision++
        tab.touch()
        host.onPageStarted(tab, url, favicon)
    }

    override fun onPageFinished(view: WebView, url: String) {
        tab.isStartPage = false
        tab.url = url
        tab.isLoading = false
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
        if (tab.title.isEmpty()) {
            tab.title = view.title.orEmpty()
        }
        tab.touch()

        if (!tab.isPrivate) {
            val title = tab.title.ifEmpty { UrlUtils.hostOf(url) }
            HistoryRepository.recordVisit(url, title)
        }
        host.onPageFinished(tab, url)
    }

    /**
     * The API 35 stub declares this with three parameters
     * (`(Landroid/webkit/WebView;Ljava/lang/String;Z)V`); the four-parameter
     * form carrying `isAddingToBackForwardList` does not exist in the SDK and
     * fails with "'doUpdateVisitedHistory' overrides nothing".
     */
    override fun doUpdateVisitedHistory(
        view: WebView,
        url: String,
        isReload: Boolean
    ) {
        tab.canGoBack = view.canGoBack()
        tab.canGoForward = view.canGoForward()
        tab.url = view.url ?: tab.url
        host.onHistoryChanged(tab, tab.canGoBack, tab.canGoForward)
    }

    override fun onReceivedError(
        view: WebView,
        request: WebResourceRequest,
        error: WebResourceError
    ) {
        if (!request.isForMainFrame) return
        tab.isLoading = false
        ErrorPage.show(view, request.url?.toString().orEmpty(), describe(error.errorCode))
        host.onProgressChanged(tab, 100)
    }

    override fun onReceivedHttpError(
        view: WebView,
        request: WebResourceRequest,
        errorResponse: WebResourceResponse
    ) {
        // Sub-resource failures are ignored on purpose; the page handles them.
    }

    override fun onReceivedSslError(view: WebView, handler: SslErrorHandler, error: SslError) {
        // We never bypass certificate errors: doing so would make the browser
        // useless as a privacy tool.
        handler.cancel()
        tab.isLoading = false
        ErrorPage.show(view, tab.url, "certificate")
        host.onProgressChanged(tab, 100)
    }

    private fun describe(errorCode: Int): String = when (errorCode) {
        ERROR_HOST_LOOKUP -> "host"
        ERROR_CONNECT -> "connect"
        ERROR_TIMEOUT -> "timeout"
        ERROR_IO -> "network"
        ERROR_PROXY_AUTHENTICATION -> "proxy"
        ERROR_UNSUPPORTED_SCHEME -> "scheme"
        else -> "network"
    }

    private companion object {
        const val ERROR_HOST_LOOKUP = -2
        const val ERROR_CONNECT = -6
        const val ERROR_TIMEOUT = -8
        const val ERROR_IO = -7
        const val ERROR_PROXY_AUTHENTICATION = -9
        const val ERROR_UNSUPPORTED_SCHEME = -11
    }
}