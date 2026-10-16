package com.nimbus.browser.data

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Plumbing shared by [HistoryRepository] and [BookmarkRepository]:
 *
 *  - one lazily created [NimbusStore] (creation itself is cheap, the schema is
 *    not touched until the first query),
 *  - a single background scope so no caller can accidentally hit SQLite from
 *    the main thread,
 *  - one mutex, because history and bookmarks live in the same file and writes
 *    to the same connection should not interleave,
 *  - a main-thread poster for change listeners that mutate views.
 */
internal object DataStore {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var store: NimbusStore? = null

    /** Callers must stay on a background thread before asking for this. */
    fun store(): NimbusStore {
        store?.let { return it }
        synchronized(this) {
            store?.let { return it }
            val created = NimbusStore(AppContext.require())
            store = created
            return created
        }
    }

    fun scope(): CoroutineScope = scope

    /**
     * Runs [block] on [Dispatchers.IO] while holding the database mutex and
     * returns its result. Suspends, so it must be called from a coroutine.
     */
    suspend fun <T> use(block: (NimbusStore) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock { block(store()) }
    }

    /** Posts [action] to the main thread, or runs it inline when already there. */
    fun onMain(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }
}