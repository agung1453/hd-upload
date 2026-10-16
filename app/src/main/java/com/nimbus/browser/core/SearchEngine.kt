package com.nimbus.browser.core

/**
 * The built-in search providers. `template` uses [String.format] and must
 * contain a single `%s` placeholder for the query.
 */
enum class SearchEngine(
    val id: String,
    val displayName: String,
    val template: String,
    val homepage: String
) {
    GOOGLE("google", "Google", "https://www.google.com/search?q=%s", "https://www.google.com"),
    DUCKDUCKGO(
        "duckduckgo",
        "DuckDuckGo",
        "https://duckduckgo.com/?q=%s",
        "https://duckduckgo.com"
    ),
    BING("bing", "Bing", "https://www.bing.com/search?q=%s", "https://www.bing.com"),
    BRAVE("brave", "Brave", "https://search.brave.com/search?q=%s", "https://search.brave.com"),
    STARTPAGE(
        "startpage",
        "Startpage",
        "https://www.startpage.com/sp/search?query=%s",
        "https://www.startpage.com"
    ),
    ECOSIA("ecosia", "Ecosia", "https://www.ecosia.org/search?q=%s", "https://www.ecosia.org"),
    MOZILLA(
        "mozilla",
        "Mozilla Firefox",
        "https://www.mozilla.org/search?q=%s",
        "https://www.mozilla.org"
    );

    /** Builds the URL used to run [query] on this engine. */
    fun searchUrl(query: String): String =
        String.format(template, java.net.URLEncoder.encode(query, "UTF-8"))

    companion object {
        val DEFAULT: SearchEngine = DUCKDUCKGO

        /** Entries/values arrays live in `res/values/arrays.xml` and must stay in sync. */
        val entries: List<SearchEngine> get() = values().toList()

        fun fromId(id: String?): SearchEngine =
            entries.firstOrNull { it.id == id } ?: DEFAULT

        fun fromIndex(index: Int): SearchEngine = entries.getOrElse(index) { DEFAULT }
    }
}