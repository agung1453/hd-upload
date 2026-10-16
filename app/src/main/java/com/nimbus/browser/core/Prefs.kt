package com.nimbus.browser.core

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

enum class ThemeMode(val id: String, val label: String) {
    SYSTEM("system", "Follow system"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark");

    companion object {
        fun fromId(id: String?): ThemeMode = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

enum class WebCacheMode(val label: String) {
    AUTO("Automatic"),
    REDUCED("Reduced"),
    NEVER("Never");

    companion object {
        fun fromIndex(index: Int): WebCacheMode = entries.getOrElse(index) { AUTO }
    }
}

/** A user-pinned tile on the start page. */
data class Shortcut(val title: String, val url: String)

/**
 * Thin, strongly-typed wrapper around the app's single SharedPreferences file.
 * The settings screen writes to the very same file (via PreferenceManager) so
 * changes propagate without any extra plumbing.
 */
object Prefs {

    const val FILE = "nimbus_prefs"

    // ---- Keys (also used as preference keys in res/xml/preferences.xml) ----
    const val KEY_THEME = "pref_theme"
    const val KEY_SEARCH_ENGINE = "pref_search_engine"
    const val KEY_DESKTOP_SITE = "pref_desktop_site"
    const val KEY_FULLSCREEN = "pref_fullscreen"
    const val KEY_START_PAGE_SEARCH = "pref_start_page_search"
    const val KEY_SHOW_SHORTCUTS = "pref_show_shortcuts"
    const val KEY_SHOW_FREQUENT = "pref_show_frequent"
    const val KEY_SHOW_RECENT = "pref_show_recent"
    const val KEY_DEFAULT_PRIVATE = "pref_default_private"
    const val KEY_OPEN_TABS_BACKGROUND = "pref_open_in_background"
    const val KEY_TEXT_ZOOM = "pref_text_zoom"
    const val KEY_CLOSE_TABS_ON_EXIT = "pref_close_tabs_on_exit"
    const val KEY_WEB_CACHE = "pref_web_cache"
    const val KEY_BLOCK_THIRD_PARTY = "pref_block_third_party"
    const val KEY_SEEDED_SHORTCUTS = "pref_seeded_shortcuts"

    // ---- Defaults ----
    const val DEFAULT_TEXT_ZOOM_INDEX = 2

    /** Text-zoom steps exposed in settings, matching a 1.0 centre. */
    val TEXT_ZOOM_STEPS = floatArrayOf(0.85f, 0.925f, 1.0f, 1.075f, 1.15f)

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs == null) {
                prefs = context.applicationContext
                    .getSharedPreferences(FILE, Context.MODE_PRIVATE)
            }
        }
    }

    private fun sp(): SharedPreferences = requireNotNull(prefs) {
        "Prefs.init() must be called from Application.onCreate()"
    }

    fun getString(key: String, default: String = ""): String = sp().getString(key, default) ?: default

    fun getBoolean(key: String, default: Boolean): Boolean = sp().getBoolean(key, default)

    fun getInt(key: String, default: Int): Int = sp().getInt(key, default)

    fun putString(key: String, value: String) = edit { putString(key, value) }

    fun putBoolean(key: String, value: Boolean) = edit { putBoolean(key, value) }

    fun putInt(key: String, value: Int) = edit { putInt(key, value) }

    fun remove(key: String) = edit { remove(key) }

    fun edit(action: SharedPreferences.Editor.() -> Unit) {
        sp().edit().apply(action).apply()
    }

    fun registerChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = sp().registerOnSharedPreferenceChangeListener(listener)

    fun unregisterChangeListener(
        listener: SharedPreferences.OnSharedPreferenceChangeListener
    ) = sp().unregisterOnSharedPreferenceChangeListener(listener)

    // ---- Typed accessors ----

    var themeMode: ThemeMode
        get() = ThemeMode.fromId(getString(KEY_THEME, ThemeMode.SYSTEM.id))
        set(value) = putString(KEY_THEME, value.id)

    val searchEngine: SearchEngine
        get() = SearchEngine.fromId(getString(KEY_SEARCH_ENGINE, SearchEngine.DEFAULT.id))

    val desktopSite: Boolean
        get() = getBoolean(KEY_DESKTOP_SITE, false)

    val fullscreen: Boolean
        get() = getBoolean(KEY_FULLSCREEN, false)

    val showStartPageSearch: Boolean
        get() = getBoolean(KEY_START_PAGE_SEARCH, true)

    val showShortcuts: Boolean
        get() = getBoolean(KEY_SHOW_SHORTCUTS, true)

    val showFrequent: Boolean
        get() = getBoolean(KEY_SHOW_FREQUENT, true)

    val showRecent: Boolean
        get() = getBoolean(KEY_SHOW_RECENT, true)

    val defaultPrivate: Boolean
        get() = getBoolean(KEY_DEFAULT_PRIVATE, false)

    val openTabsInBackground: Boolean
        get() = getBoolean(KEY_OPEN_TABS_BACKGROUND, false)

    val closeTabsOnExit: Boolean
        get() = getBoolean(KEY_CLOSE_TABS_ON_EXIT, false)

    val blockThirdPartyCookies: Boolean
        get() = getBoolean(KEY_BLOCK_THIRD_PARTY, false)

    val webCacheMode: WebCacheMode
        get() = WebCacheMode.fromIndex(getInt(KEY_WEB_CACHE, 0))

    val textZoom: Float
        get() = TEXT_ZOOM_STEPS[getInt(KEY_TEXT_ZOOM, DEFAULT_TEXT_ZOOM_INDEX)
            .coerceIn(TEXT_ZOOM_STEPS.indices)]

    // ---- Start-page shortcuts (stored as a tiny JSON array) ----

    fun shortcuts(): List<Shortcut> {
        val raw = getString(KEY_SHOW_SHORTCUTS + "_list", "")
        if (raw.isEmpty()) return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val item = array.optJSONObject(i) ?: continue
                    val url = item.optString("url")
                    val title = item.optString("title")
                    if (url.isNotEmpty()) add(Shortcut(title, url))
                }
            }
        }.getOrDefault(emptyList())
    }

    fun setShortcuts(list: List<Shortcut>) {
        val array = JSONArray()
        list.forEach { item ->
            array.put(JSONObject().apply {
                put("title", item.title)
                put("url", item.url)
            })
        }
        putString(KEY_SHOW_SHORTCUTS + "_list", array.toString())
    }

    fun addShortcut(shortcut: Shortcut) {
        val current = shortcuts().toMutableList()
        if (current.none { it.url == shortcut.url }) {
            current.add(0, shortcut)
        }
        setShortcuts(current.take(MAX_SHORTCUTS))
    }

    fun removeShortcut(url: String) {
        setShortcuts(shortcuts().filterNot { it.url == url })
    }

    const val MAX_SHORTCUTS = 12
}