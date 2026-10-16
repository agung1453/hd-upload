package com.nimbus.browser.util

import android.content.Context
import android.text.format.DateUtils
import com.nimbus.browser.R
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

private const val MINUTE_MS = 60_000L
private const val HOUR_MS = 60L * MINUTE_MS
private const val DAY_MS = 24L * HOUR_MS
private const val WEEK_MS = 7L * DAY_MS

/**
 * Turns a wall-clock timestamp into the short "2 h ago" label used by the
 * history and bookmarks lists.
 *
 * The rules, in order:
 *  - under a minute   -> "Just now"
 *  - same calendar day -> "5 min ago" / "2 h ago"
 *  - yesterday        -> "Yesterday"
 *  - under a week     -> "3 days ago"
 *  - anything older   -> an absolute date, because "47 days ago" is useless
 *
 * The short labels come from resources so they localise with the rest of the
 * app; only the absolute fallback uses a hard-coded pattern.
 */
fun relativeTime(context: Context, millis: Long): String {
    if (millis <= 0L) return ""

    val now = System.currentTimeMillis()
    val delta = now - millis

    // A clock skew that puts the timestamp in the future should not render as
    // "-3 min ago"; treat it as "now".
    if (delta < MINUTE_MS) return context.getString(R.string.time_just_now)

    if (isSameDay(millis, now)) {
        if (delta < HOUR_MS) {
            val minutes = (delta / MINUTE_MS).toInt().coerceAtLeast(1)
            return context.getString(R.string.time_minutes_ago, minutes)
        }
        val hours = (delta / HOUR_MS).toInt().coerceAtLeast(1)
        return context.getString(R.string.time_hours_ago, hours)
    }

    if (isYesterday(millis, now)) return context.getString(R.string.time_yesterday)

    if (delta < WEEK_MS) {
        val days = (delta / DAY_MS).toInt().coerceAtLeast(1)
        return context.resources.getQuantityString(R.plurals.time_days_ago, days, days)
    }

    return absoluteDate(millis)
}

/** e.g. "12 Mar 2026". */
fun absoluteDate(millis: Long): String =
    SimpleDateFormat("d MMM yyyy", Locale.getDefault()).format(Date(millis))

/** e.g. "12 Mar 2026, 14:05". */
fun absoluteDateTime(millis: Long): String =
    SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault()).format(Date(millis))

/**
 * The platform's own wording ("2 hr. ago"), kept for the few places that want
 * the system's localisation rather than the compact labels above.
 */
fun platformRelativeTime(millis: Long): CharSequence =
    DateUtils.getRelativeTimeSpanString(
        millis,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE
    )

private fun startOfDay(millis: Long): Long = Calendar.getInstance().apply {
    timeInMillis = millis
    set(Calendar.HOUR_OF_DAY, 0)
    set(Calendar.MINUTE, 0)
    set(Calendar.SECOND, 0)
    set(Calendar.MILLISECOND, 0)
}.timeInMillis

private fun isSameDay(a: Long, b: Long): Boolean = startOfDay(a) == startOfDay(b)

private fun isYesterday(millis: Long, now: Long): Boolean =
    startOfDay(millis) == startOfDay(now - DAY_MS)