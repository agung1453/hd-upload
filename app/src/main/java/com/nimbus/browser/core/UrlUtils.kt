package com.nimbus.browser.core

import java.util.Locale

/**
 * All URL parsing / normalisation lives here so the UI can just hand over
 * whatever the user typed and receive something safe to load.
 */
object UrlUtils {

    private val SCHEME_PATTERN =
        Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:")

    private val IPV4_PATTERN =
        Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

    /**
     * Decides whether [input] should be treated as a web address instead of a
     * search query. Deliberately conservative: anything that contains a space
     * or has no plausible TLD is a search.
     */
    fun isProbablyUrl(input: String): Boolean {
        val text = input.trim()
        if (text.isEmpty()) return false

        // A scheme means the user typed a full URL on purpose.
        if (SCHEME_PATTERN.containsMatchIn(text)) return true

        // Spaces are a strong signal for a query.
        if (text.any { it.isWhitespace() }) return false

        val hostPart = text.substringBefore('/').substringBefore('?').substringBefore('#')
        val withoutPort = hostPart.substringBefore(':')
        if (withoutPort.isEmpty()) return false

        if (withoutPort.equals("localhost", ignoreCase = true)) return true
        if (IPV4_PATTERN.matches(withoutPort)) return true

        // Needs at least one dot plus a believable alphabetic TLD.
        val lastDot = withoutPort.lastIndexOf('.')
        if (lastDot <= 0 || lastDot == withoutPort.lastIndex) return false

        val tld = withoutPort.substring(lastDot + 1)
        if (tld.length < 2) return false
        if (!tld.all { it.isLetter() }) return false

        // Reject "3.5" style version numbers and "read.this.book" style prose
        // is fine (it has a valid TLD) so we allow it.
        return withoutPort.all { it.isLetterOrDigit() || it == '.' || it == '-' || it == '_' }
    }

    /** Adds `https://` when the user omitted a scheme. */
    fun ensureScheme(input: String): String {
        val text = input.trim()
        return if (SCHEME_PATTERN.containsMatchIn(text)) text
            else "${Constants.SCHEME_HTTPS}$text"
    }

    /**
     * The single entry point used by the address bar and the start page.
     * Returns either a navigable URL or a search-results URL.
     */
    fun resolve(input: String, engine: SearchEngine): String {
        val text = input.trim()
        if (text.isEmpty()) return engine.homepage
        return if (isProbablyUrl(text)) ensureScheme(text) else engine.searchUrl(text)
    }

    /** Host of [url] without a leading `www.`, or an empty string. */
    fun hostOf(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        return try {
            val host = java.net.URI(url).host
            host?.removePrefix("www.") ?: ""
        } catch (_: Exception) {
            url.removePrefix(Constants.SCHEME_HTTPS)
                .removePrefix(Constants.SCHEME_HTTP)
                .substringBefore('/')
                .removePrefix("www.")
        }
    }

    /** What we show inside the collapsed address bar pill. */
    fun displayUrl(url: String?): String {
        if (url.isNullOrEmpty()) return ""
        var out = url.trim()
        out = out.removePrefix("${Constants.SCHEME_HTTPS}://")
            .removePrefix("${Constants.SCHEME_HTTP}://")
        out = out.removePrefix("www.")
        if (out.endsWith("/")) out = out.dropLast(1)
        return out
    }

    fun isSecure(url: String?): Boolean =
        url != null && url.startsWith("${Constants.SCHEME_HTTPS}://", ignoreCase = true)

    fun isInternal(url: String?): Boolean =
        url != null && (url.startsWith("about:") || url == Constants.ABOUT_BLANK)

    /** Single glyph used as the site's "favicon" when we do not load real icons. */
    fun glyphFor(url: String?): String {
        val host = hostOf(url)
        val source = host.ifEmpty { url.orEmpty() }
        val cleaned = source.trim().removePrefix("-").removePrefix(".")
        return cleaned.firstOrNull { it.isLetterOrDigit() }?.uppercase(Locale.getDefault())
            ?: "?"
    }

    /** Stable index into the avatar colour palette (always 0..7). */
    fun avatarIndex(url: String?): Int {
        val key = hostOf(url).ifEmpty { url.orEmpty() }
        // floorMod rather than abs(): abs(Int.MIN_VALUE) is still negative.
        return Math.floorMod(key.hashCode(), 8)
    }

    /** `https://example.com` -> `example.com` */
    fun domainLabel(url: String?): String {
        val host = hostOf(url)
        if (host.isEmpty()) return ""
        val parts = host.split('.')
        return if (parts.size >= 2) parts[parts.size - 2] else host
    }
}