package com.raddstream.app.viewmodel

/**
 * Gap A5 — the sync/status line: one honest sentence about how fresh what
 * is on screen is.
 *
 * Both functions are pure so the wording is pinned by a test; the
 * ViewModels only supply the facts (a timestamp) and the current time.
 */

/** "just now" / "1 min ago" / "N min ago" / "1 hour ago" / "N hours ago" / "N days ago". */
internal fun relativeTimeAgo(epochMs: Long, nowMs: Long): String {
    val diffMs = (nowMs - epochMs).coerceAtLeast(0L)
    val minutes = diffMs / 60_000L
    return when {
        minutes < 1L -> "just now"
        minutes < 60L -> if (minutes == 1L) "1 min ago" else "$minutes min ago"
        minutes < 60L * 24L -> {
            val hours = minutes / 60L
            if (hours == 1L) "1 hour ago" else "$hours hours ago"
        }
        else -> {
            val days = minutes / (60L * 24L)
            if (days == 1L) "1 day ago" else "$days days ago"
        }
    }
}

/**
 * The Home status line. Null when Home has never loaded (nothing to date).
 * Offline says so plainly and names the age of the saved rows on screen,
 * so a stale cache never passes as fresh.
 */
internal fun homeSyncLine(lastUpdatedAtMs: Long?, isOffline: Boolean, nowMs: Long): String? {
    val atMs = lastUpdatedAtMs ?: return null
    val age = relativeTimeAgo(atMs, nowMs)
    return if (isOffline) "Offline — showing saved Home from $age" else "Updated $age"
}
