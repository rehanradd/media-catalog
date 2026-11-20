package com.raddstream.app.data.local

import androidx.room.Entity

/**
 * REA #5 (Netflix, Nov 2026): one row per episode Smart Downloads fetched
 * for the viewer — the record "Downloads For You" is built from. The
 * episode, not the series: a series with three fetched episodes is three
 * rows, and the Home row folds them to one tile per series.
 *
 * [handle] is the opaque handle the runner enqueued, so the row and the
 * storage budget can both ask "is this episode still on the device" by
 * handle instead of by title — two episodes sharing a title must never
 * cross-count each other. [sizeBytes] is the size the server stated at
 * fetch time (null when it did not say); the budget counts it as zero,
 * never as a guess. [fetchedAt] is epoch millis, newest first — the row
 * reads in fetch order, so the latest fetch leads.
 *
 * A row outlives its keep on purpose: the player deletes a watched
 * episode's keep (auto-delete, D5) without touching this table, and the
 * runner's next pass sweeps rows whose handle is no longer kept. A row
 * whose handle is gone is invisible to the row and the budget — never
 * shown, never counted — until that sweep removes it.
 */
@Entity(
    tableName = "downloads_for_you",
    primaryKeys = ["postId", "season", "episode"],
)
data class DownloadsForYouEntry(
    val postId: Int,
    val season: Int,
    val episode: Int,
    val title: String,
    val handle: String,
    val sizeBytes: Long?,
    val fetchedAt: Long,
)
