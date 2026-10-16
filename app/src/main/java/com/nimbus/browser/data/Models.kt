package com.nimbus.browser.data

/** One row of the `history` table, already sorted for display by the caller. */
data class HistoryEntry(
    val url: String,
    val title: String,
    val lastVisit: Long,
    val visitCount: Int
)

/** One row of the `bookmarks` table. */
data class BookmarkEntry(
    val url: String,
    val title: String,
    val createdAt: Long
)