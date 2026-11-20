package com.raddstream.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * REA #3 (VLC, Nov 2026): one viewer's audio + subtitle sync offsets
 * for a work, by the server-minted [workRef] — the only name a work has
 * that survives across its posts. A new episode lands as a new post, so
 * an id would go stale the moment the thing the viewer corrected
 * actually grows.
 *
 * A delay is a correction to one file's muxing, not a taste: the same
 * host's files tend to be off by the same amount, so the offset is
 * remembered per work and re-applied when the title opens again. The
 * two offsets are one row because they are set in one sheet and read
 * in one load; either may be 0. A row with both offsets 0 is never
 * stored — clearing both deletes the row, so "in sync" and "never
 * asked" are the same absence. The values are the viewer's own, stored
 * on this device only — they never leave the phone.
 */
@Entity(tableName = "title_sync_prefs")
data class TitleSyncPrefs(
    @PrimaryKey
    val workRef: String,
    val audioDelayMs: Long,
    val subtitleDelayMs: Long,
    val updatedAt: Long = System.currentTimeMillis(),
)
