package com.nimbus.browser.web

import android.webkit.WebView
import com.nimbus.browser.core.UrlUtils

/**
 * A self-contained, offline error page. Injected straight into the WebView so
 * it inherits the page's scroll container and does not need another Activity.
 */
object ErrorPage {

    fun show(web: WebView, url: String, reason: String) {
        web.loadDataWithBaseURL(null, build(url, reason), "text/html", "UTF-8", null)
    }

    private fun build(url: String, reason: String): String {
        val host = UrlUtils.hostOf(url).ifEmpty { url }
        val message = messageFor(reason)
        return """
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
<title>@@TITLE@@</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; -webkit-tap-highlight-color: transparent; }
  html, body {
    margin:0; padding:0; height:100%;
    font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",Roboto,Helvetica,Arial,sans-serif;
    -webkit-font-smoothing:antialiased;
  }
  body {
    display:flex; align-items:center; justify-content:center;
    background:#F1F2F5; color:#11151C; padding:24px;
  }
  @media (prefers-color-scheme: dark) {
    body { background:#0B0C0F; color:#F2F3F5; }
    .card { background:#14161B; }
    .sub { color:#9AA1AC; }
    .url { color:#8B929C; }
    button { background:#0A84FF; }
  }
  .wrap { width:100%; max-width:420px; text-align:center; }
  .glyph {
    width:72px; height:72px; margin:0 auto 20px; border-radius:50%;
    background:#FF3B30; display:flex; align-items:center; justify-content:center;
  }
  .glyph svg { width:36px; height:36px; stroke:#fff; stroke-width:2; fill:none;
    stroke-linecap:round; stroke-linejoin:round; }
  h1 { font-size:20px; font-weight:600; margin:0 0 8px; letter-spacing:-0.01em; }
  .sub { font-size:15px; line-height:1.45; color:#6B7280; margin:0 0 22px; }
  .card {
    background:#fff; border-radius:16px; padding:14px 16px; text-align:left;
    margin-bottom:22px; border:1px solid rgba(0,0,0,0.06);
  }
  .url { font-size:13px; color:#8B929C; word-break:break-all; line-height:1.4; }
  button {
    -webkit-appearance:none; appearance:none; border:0; cursor:pointer;
    background:#0A7CFF; color:#fff; font-size:15px; font-weight:600;
    padding:13px 22px; border-radius:14px; width:100%;
  }
  button:active { opacity:.85; }
</style>
</head>
<body>
  <div class="wrap">
    <div class="glyph">
      <svg viewBox="0 0 24 24"><path d="M12 8v5"/><path d="M12 16.5h.01"/>
        <path d="M10.3 3.9 2.5 17.4A2 2 0 0 0 4.2 20.4h15.6a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0z"/></svg>
    </div>
    <h1>@@TITLE@@</h1>
    <p class="sub">@@MESSAGE@@</p>
    <div class="card"><div class="url">@@HOST@@</div></div>
    <button onclick="location.reload()">@@RETRY@@</button>
  </div>
</body>
</html>
        """.trimIndent()
            .replace("@@TITLE@@", titleOf(reason))
            .replace("@@MESSAGE@@", message)
            .replace("@@HOST@@", escape(host))
            .replace("@@RETRY@@", retry)
    }

    private fun titleOf(reason: String): String = when (reason) {
        "certificate" -> "Connection not private"
        else -> "This page could not be opened"
    }

    private fun messageFor(reason: String): String = when (reason) {
        "host" -> "The server address could not be found. Check the spelling and your internet connection."
        "connect" -> "The server refused the connection. It may be down or blocking this device."
        "timeout" -> "The server took too long to respond."
        "proxy" -> "A proxy on this network needs a username and password."
        "scheme" -> "This address uses a link type that cannot be opened."
        "certificate" -> "This site's security certificate is not trusted, so the connection was stopped."
        else -> "Check the address and your internet connection, then try again."
    }

    private fun escape(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")

    private const val retry = "Try again"
}