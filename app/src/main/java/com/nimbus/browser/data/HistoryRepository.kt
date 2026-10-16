package com.nimbus.browser.data

import android.content.Context
import com.nimbus.browser.core.UrlUtils
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * History storage with an in-memory mirror of every row.
 *
 * The start page needs "recent" and "frequently visited" while it is still
 * laying out, so every read has to be answerable without touching disk. The
 * cache is filled once on [Dispatchers.IO] and then kept in step with the
 * database: mutations update the cache first (so the UI reacts immediately)
 * and the write + refresh happens off the main thread.
 */
object HistoryRepository {

    /** Re-visiting a page inside this window counts as another visit. */
    private const val REVISIT_WINDOW_MS = 30L * 60L * 1000L

    private val cache = ArrayList<HistoryEntry>()
    private val cacheLock = Any()

    @Volatile
    private var cacheLoaded = false
    private var loadJob: Job? = null

    // ------------------------------------------------------------------ setup

    /** Safe to call repeatedly; only the first call does any work. */
    fun init(context: Context) {
        AppContext.install(context)
        ensureStore()
        ensureLoadedAsync()
    }

    // --------------------------------------------------------------- recording

    /**
     * Records a page load. Revisiting the same URL inside
     * [REVISIT_WINDOW_MS] bumps `visit_count`; a later visit only moves
     * `last_visit` forward so an old favourite does not look freshly used.
     */
    fun recordVisit(url: String, title: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        ensureStore()

        val now = System.currentTimeMillis()
        val existing = snapshot().firstOrNull { it.url == key }
        val entry = if (existing == null) {
            HistoryEntry(
                url = key,
                title = title.trim().ifEmpty { UrlUtils.displayUrl(key) },
                lastVisit = now,
                visitCount = 1
            )
        } else {
            val isRepeat = now - existing.lastVisit <= REVISIT_WINDOW_MS
            existing.copy(
                title = title.trim().ifEmpty { existing.title },
                lastVisit = now,
                visitCount = if (isRepeat) existing.visitCount + 1 else existing.visitCount
            )
        }

        upsertCache(entry)
        refresh { it.recordVisit(key, entry.title, now, REVISIT_WINDOW_MS) }
    }

    fun deleteVisit(url: String) {
        val key = url.trim()
        if (key.isEmpty()) return
        ensureStore()
        removeCache(key)
        refresh { it.deleteHistory(key) }
    }

    fun clearHistory() {
        ensureStore()
        clearCache()
        refresh(NimbusStore::clearHistory)
    }

    // ---------------------------------------------------- synchronous readers

    /** Newest first. Served straight from the cache. */
    fun recent(limit: Int = 25): List<HistoryEntry> {
        ensureStore()
        ensureLoadedAsync()
        return snapshot().take(limit.coerceAtLeast(0))
    }

    /** Most visited first, ties broken by the most recent visit. */
    fun frequent(limit: Int = 8): List<HistoryEntry> {
        ensureStore()
        ensureLoadedAsync()
        return snapshot()
            .sortedWith(
                compareByDescending<HistoryEntry> { it.visitCount }
                    .thenByDescending { it.lastVisit }
            )
            .take(limit.coerceAtLeast(0))
    }

    /** Cached search; [searchAsync] is preferred when a coroutine is around. */
    fun search(query: String): List<HistoryEntry> {
        ensureStore()
        ensureLoadedAsync()
        val needle = query.trim()
        if (needle.isEmpty()) return emptyList()
        return snapshot().filter {
            it.url.contains(needle, ignoreCase = true) ||
                it.title.contains(needle, ignoreCase = true)
        }
    }

    // --------------------------------------------------------- async readers

    suspend fun recentAsync(limit: Int = 25): List<HistoryEntry> {
        awaitLoaded()
        return DataStore.use { it.readRecentHistory(limit) }
    }

    suspend fun frequentAsync(limit: Int = 8): List<HistoryEntry> {
        awaitLoaded()
        return DataStore.use { it.readFrequentHistory(limit) }
    }

    suspend fun searchAsync(query: String): List<HistoryEntry> {
        val needle = query.trim()
        if (needle.isEmpty()) return recentAsync()
        awaitLoaded()
        return DataStore.use { it.searchHistory(needle) }
    }

    // -------------------------------------------------------------- internals

    private fun ensureStore() = DataStore.store()

    private fun snapshot(): List<HistoryEntry> = synchronized(cacheLock) { cache.toList() }

    /** Writes to the database, then re-reads it so the cache stays truthful. */
    private fun refresh(mutate: (NimbusStore) -> Unit) {
        DataStore.scope().launch {
            DataStore.use { store ->
                mutate(store)
                replaceCache(store.readAllHistory())
            }
        }
    }

    private fun replaceCache(rows: List<HistoryEntry>) = synchronized(cacheLock) {
        cache.clear()
        cache.addAll(rows)
        cacheLoaded = true
    }

    private fun upsertCache(entry: HistoryEntry) = synchronized(cacheLock) {
        cache.removeAll { it.url == entry.url }
        cache.add(entry)
        cache.sortByDescending { it.lastVisit }
        cacheLoaded = true
    }

    private fun removeCache(url: String) = synchronized(cacheLock) {
        cache.removeAll { it.url == url }
    }

    private fun clearCache() = synchronized(cacheLock) {
        cache.clear()
        cacheLoaded = true
    }

    /** Starts the one-time background load; never blocks the caller. */
    private fun ensureLoadedAsync() {
        if (cacheLoaded) return
        synchronized(this) {
            if (cacheLoaded || loadJob != null) return
            loadJob = DataStore.scope().launch {
                DataStore.use { replaceCache(it.readAllHistory()) }
            }
        }
    }

    private suspend fun awaitLoaded() {
        if (cacheLoaded) return
        ensureStore()
        val job: Job = synchronized(this) {
            loadJob ?: DataStore.scope().launch {
                DataStore.use { replaceCache(it.readAllHistory()) }
            }.also { loadJob = it }
        }
        job.join()
    }
}