package com.nimbus.browser.data

import android.content.Context
import com.nimbus.browser.core.UrlUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Bookmark storage, mirrored in memory so the toolbar star can answer
 * [isBookmarked] without a round trip to SQLite.
 *
 * Two kinds of listener are exposed because two different things care:
 *
 *  - state listeners ([addListener]) fire when *one URL* flips between
 *    bookmarked and not; the argument is the state after the change,
 *  - list listeners ([addChangeListener]) fire when the collection itself
 *    changed, which is what the bookmarks screen redraws on.
 *
 * All callbacks are delivered on the main thread.
 */
object BookmarkRepository {

    private val cache = ArrayList<BookmarkEntry>()
    private val cacheLock = Any()

    @Volatile
    private var cacheLoaded = false
    private var loadJob: Job? = null

    private val stateListeners = ArrayList<(Boolean) -> Unit>()
    private val listListeners = ArrayList<() -> Unit>()
    private val listenerLock = Any()

    // ------------------------------------------------------------------ setup

    /** Safe to call repeatedly; only the first call does any work. */
    fun init(context: Context) {
        AppContext.install(context)
        ensureStore()
        ensureLoadedAsync()
    }

    /** Fires with the bookmarked state after each toggle of the active URL. */
    fun addListener(l: (Boolean) -> Unit) = synchronized(listenerLock) {
        stateListeners.add(l)
    }

    fun removeListener(l: (Boolean) -> Unit) = synchronized(listenerLock) {
        stateListeners.remove(l)
    }

    /** Fires whenever the set of bookmarks changes (add / remove / rename). */
    fun addChangeListener(l: () -> Unit) = synchronized(listenerLock) {
        listListeners.add(l)
    }

    fun removeChangeListener(l: () -> Unit) = synchronized(listenerLock) {
        listListeners.remove(l)
    }

    // ------------------------------------------------------------------ reads

    fun isBookmarked(url: String): Boolean {
        val key = url.trim()
        if (key.isEmpty()) return false
        ensureStore()
        ensureLoadedAsync()
        return snapshot().any { it.url == key }
    }

    /** Newest first. */
    fun bookmarks(): List<BookmarkEntry> {
        ensureStore()
        ensureLoadedAsync()
        return snapshot()
    }

    suspend fun bookmarksAsync(): List<BookmarkEntry> {
        awaitLoaded()
        return DataStore.use { it.readAllBookmarks() }
    }

    suspend fun searchAsync(query: String): List<BookmarkEntry> {
        val needle = query.trim()
        if (needle.isEmpty()) return bookmarksAsync()
        awaitLoaded()
        return DataStore.use { it.searchBookmarks(needle) }
    }

    // --------------------------------------------------------------- mutations

    /** Adds [url] if missing. Returns the state afterwards. */
    fun addBookmark(url: String, title: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        ensureStore()
        val existing = snapshot().firstOrNull { it.url == key }
        val entry = BookmarkEntry(
            url = key,
            title = title.trim().ifEmpty { existing?.title ?: UrlUtils.displayUrl(key) },
            createdAt = existing?.createdAt ?: System.currentTimeMillis()
        )
        upsertCache(entry)
        refresh(key) { it.upsertBookmark(entry) }
    }

    fun removeBookmark(url: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        ensureStore()
        removeCache(key)
        refresh(key) { it.deleteBookmark(key) }
    }

    /** Flips the stored state and reports the state *after* the toggle. */
    fun toggleBookmark(url: String, title: String): Boolean {
        val key = url.trim()
        if (key.isEmpty()) return false
        return if (isBookmarked(key)) {
            removeBookmark(key)
            false
        } else {
            addBookmark(key, title)
            true
        }
    }

    fun updateTitle(url: String, title: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        ensureStore()
        val existing = snapshot().firstOrNull { it.url == key } ?: return
        val cleanTitle = title.trim()
        if (cleanTitle.isEmpty() || cleanTitle == existing.title) return
        val updated = existing.copy(title = cleanTitle)
        upsertCache(updated)
        refresh(null) { it.upsertBookmark(updated) }
    }

    fun clearBookmarks() {
        ensureStore()
        clearCache()
        refresh(null) { it.clearBookmarks() }
    }

    // -------------------------------------------------------------- internals

    private fun ensureStore() = DataStore.store()

    private fun snapshot(): List<BookmarkEntry> = synchronized(cacheLock) { cache.toList() }

    /**
     * Applies [mutate] on the background dispatcher, re-reads the table so the
     * cache stays authoritative, then notifies listeners on the main thread.
     *
     * @param changedUrl the URL whose bookmarked state may have flipped, or
     *   null when only the collection (and not a single row's state) changed.
     */
    private fun refresh(changedUrl: String?, mutate: (NimbusStore) -> Unit) {
        DataStore.scope().launch {
            DataStore.use { store ->
                mutate(store)
                replaceCache(store.readAllBookmarks())
            }
            changedUrl?.let { url -> notifyState(snapshot().any { it.url == url }) }
            notifyList()
        }
    }

    private fun replaceCache(rows: List<BookmarkEntry>) = synchronized(cacheLock) {
        cache.clear()
        cache.addAll(rows)
        cacheLoaded = true
    }

    private fun upsertCache(entry: BookmarkEntry) = synchronized(cacheLock) {
        cache.removeAll { it.url == entry.url }
        cache.add(entry)
        cache.sortByDescending { it.createdAt }
        cacheLoaded = true
    }

    private fun removeCache(url: String) = synchronized(cacheLock) {
        cache.removeAll { it.url == url }
    }

    private fun clearCache() = synchronized(cacheLock) {
        cache.clear()
        cacheLoaded = true
    }

    private fun notifyState(isBookmarked: Boolean) {
        val snapshot = synchronized(listenerLock) { stateListeners.toList() }
        if (snapshot.isEmpty()) return
        DataStore.onMain { snapshot.forEach { it(isBookmarked) } }
    }

    private fun notifyList() {
        val snapshot = synchronized(listenerLock) { listListeners.toList() }
        if (snapshot.isEmpty()) return
        DataStore.onMain { snapshot.forEach { it() } }
    }

    private fun ensureLoadedAsync() {
        if (cacheLoaded) return
        synchronized(this) {
            if (cacheLoaded || loadJob != null) return
            loadJob = DataStore.scope().launch {
                DataStore.use { replaceCache(it.readAllBookmarks()) }
            }
        }
    }

    private suspend fun awaitLoaded() {
        if (cacheLoaded) return
        ensureStore()
        val job: Job = synchronized(this) {
            loadJob ?: DataStore.scope().launch {
                DataStore.use { replaceCache(it.readAllBookmarks()) }
            }.also { loadJob = it }
        }
        job.join()
    }
}