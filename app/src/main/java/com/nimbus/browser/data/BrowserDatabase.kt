package com.nimbus.browser.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

/**
 * The app's single SQLite store. Deliberately tiny: two tables, no joins and
 * no migrations yet, so the whole schema fits on one screen.
 *
 * All SQL lives here. The repositories never touch [SQLiteDatabase] directly,
 * which keeps the "what is stored" question answerable in a single file.
 */
class NimbusStore(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    DB_NAME,
    null,
    DB_VERSION
) {

    // Opening the helper is cheap; touching `writableDatabase` does the I/O,
    // so callers must stay off the main thread before asking for this.
    private val db: SQLiteDatabase get() = writableDatabase

    // ---------------------------------------------------------------- history

    fun readAllHistory(): List<HistoryEntry> = query(
        "SELECT url, title, last_visit, visit_count FROM history ORDER BY last_visit DESC"
    ) { c ->
        HistoryEntry(
            url = c.getString(0),
            title = c.getString(1),
            lastVisit = c.getLong(2),
            visitCount = c.getInt(3)
        )
    }

    fun readRecentHistory(limit: Int): List<HistoryEntry> = query(
        "SELECT url, title, last_visit, visit_count FROM history " +
            "ORDER BY last_visit DESC LIMIT ${limit.coerceAtLeast(0)}"
    ) { c ->
        HistoryEntry(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3))
    }

    fun readFrequentHistory(limit: Int): List<HistoryEntry> = query(
        "SELECT url, title, last_visit, visit_count FROM history " +
            "ORDER BY visit_count DESC, last_visit DESC LIMIT ${limit.coerceAtLeast(0)}"
    ) { c ->
        HistoryEntry(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3))
    }

    fun searchHistory(query: String): List<HistoryEntry> {
        val like = "%${escapeLike(query)}%"
        val out = ArrayList<HistoryEntry>()
        db.rawQuery(
            "SELECT url, title, last_visit, visit_count FROM history " +
                "WHERE url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' " +
                "ORDER BY last_visit DESC LIMIT $MAX_SEARCH_RESULTS",
            arrayOf(like, like)
        ).use { c ->
            while (c.moveToNext()) {
                out += HistoryEntry(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3))
            }
        }
        return out
    }

    /**
     * Inserts or updates the row for [url] in one transaction.
     *
     * The repeat-visit rule lives here rather than in the repository so the
     * count stays correct even if a visit is recorded before the in-memory
     * cache has finished loading.
     */
    fun recordVisit(url: String, title: String, now: Long, revisitWindowMs: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.query(
                TABLE_HISTORY,
                arrayOf(COL_ID, COL_TITLE, COL_LAST_VISIT, COL_VISIT_COUNT),
                "$COL_URL = ?",
                arrayOf(url),
                null,
                null,
                null
            ).use { c ->
                if (c.moveToFirst()) {
                    val id = c.getLong(0)
                    val previousTitle = c.getString(1)
                    val previousVisit = c.getLong(2)
                    val previousCount = c.getInt(3)
                    val isRepeat = now - previousVisit <= revisitWindowMs
                    val values = ContentValues(3).apply {
                        put(COL_TITLE, title.ifEmpty { previousTitle })
                        put(COL_LAST_VISIT, now)
                        put(COL_VISIT_COUNT, if (isRepeat) previousCount + 1 else previousCount)
                    }
                    db.update(TABLE_HISTORY, values, "$COL_ID = ?", arrayOf(id.toString()))
                } else {
                    val values = ContentValues(4).apply {
                        put(COL_URL, url)
                        put(COL_TITLE, title)
                        put(COL_LAST_VISIT, now)
                        put(COL_VISIT_COUNT, 1)
                    }
                    db.insert(TABLE_HISTORY, null, values)
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    fun deleteHistory(url: String) {
        db.delete(TABLE_HISTORY, "$COL_URL = ?", arrayOf(url))
    }

    fun clearHistory() {
        db.delete(TABLE_HISTORY, null, null)
    }

    // -------------------------------------------------------------- bookmarks

    fun readAllBookmarks(): List<BookmarkEntry> = query(
        "SELECT url, title, created_at FROM bookmarks ORDER BY created_at DESC"
    ) { c ->
        BookmarkEntry(url = c.getString(0), title = c.getString(1), createdAt = c.getLong(2))
    }

    fun searchBookmarks(query: String): List<BookmarkEntry> {
        val like = "%${escapeLike(query)}%"
        val out = ArrayList<BookmarkEntry>()
        db.rawQuery(
            "SELECT url, title, created_at FROM bookmarks " +
                "WHERE url LIKE ? ESCAPE '\\' OR title LIKE ? ESCAPE '\\' " +
                "ORDER BY created_at DESC LIMIT $MAX_SEARCH_RESULTS",
            arrayOf(like, like)
        ).use { c ->
            while (c.moveToNext()) {
                out += BookmarkEntry(c.getString(0), c.getString(1), c.getLong(2))
            }
        }
        return out
    }

    fun upsertBookmark(entry: BookmarkEntry) {
        val values = ContentValues(3).apply {
            put(COL_URL, entry.url)
            put(COL_TITLE, entry.title)
            put(COL_CREATED_AT, entry.createdAt)
        }
        val rows = db.update(TABLE_BOOKMARKS, values, "$COL_URL = ?", arrayOf(entry.url))
        if (rows == 0) db.insert(TABLE_BOOKMARKS, null, values)
    }

    fun deleteBookmark(url: String) {
        db.delete(TABLE_BOOKMARKS, "$COL_URL = ?", arrayOf(url))
    }

    fun clearBookmarks() {
        db.delete(TABLE_BOOKMARKS, null, null)
    }

    // --------------------------------------------------------------- plumbing

    private inline fun <T> query(sql: String, map: (android.database.Cursor) -> T): List<T> {
        val out = ArrayList<T>()
        db.rawQuery(sql, null).use { c ->
            while (c.moveToNext()) out += map(c)
        }
        return out
    }

    /** Keeps user text from being interpreted as LIKE wildcards. */
    private fun escapeLike(raw: String): String = buildString(raw.length) {
        raw.forEach { ch ->
            when (ch) {
                '%', '_', '\\' -> append('\\').append(ch)
                else -> append(ch)
            }
        }
    }

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """
            CREATE TABLE $TABLE_HISTORY (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_URL TEXT NOT NULL UNIQUE,
                $COL_TITLE TEXT NOT NULL,
                $COL_LAST_VISIT INTEGER NOT NULL,
                $COL_VISIT_COUNT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_history_last_visit ON $TABLE_HISTORY($COL_LAST_VISIT)")

        db.execSQL(
            """
            CREATE TABLE $TABLE_BOOKMARKS (
                $COL_ID INTEGER PRIMARY KEY AUTOINCREMENT,
                $COL_URL TEXT NOT NULL UNIQUE,
                $COL_TITLE TEXT NOT NULL,
                $COL_CREATED_AT INTEGER NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("CREATE INDEX idx_bookmarks_created_at ON $TABLE_BOOKMARKS($COL_CREATED_AT)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // Version 1 is the first shipped schema, so there is nothing to migrate
        // yet. When v2 lands this should become an explicit migration chain;
        // until then a destructive recreate keeps the dev flow simple.
        db.execSQL("DROP TABLE IF EXISTS $TABLE_HISTORY")
        db.execSQL("DROP TABLE IF EXISTS $TABLE_BOOKMARKS")
        onCreate(db)
    }

    override fun onDowngrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        onUpgrade(db, oldVersion, newVersion)
    }

    companion object {
        const val DB_NAME = "nimbus.db"
        const val DB_VERSION = 1

        const val TABLE_HISTORY = "history"
        const val TABLE_BOOKMARKS = "bookmarks"

        const val COL_ID = "id"
        const val COL_URL = "url"
        const val COL_TITLE = "title"
        const val COL_LAST_VISIT = "last_visit"
        const val COL_VISIT_COUNT = "visit_count"
        const val COL_CREATED_AT = "created_at"

        /** Keeps a paste of "a%" from scanning the whole table. */
        const val MAX_SEARCH_RESULTS = 200
    }
}