package com.raddstream.app.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * REA #2 (Netflix, Nov 2026): one viewer's thumbs up/down on a work, by
 * the server-minted [workRef] — the only name a work has that survives
 * across its posts. A new episode lands as a new post, so an id would go
 * stale the moment the thing the viewer voted on actually grows.
 *
 * [thumb] is 1 (liked) or -1 (disliked); 0 is never stored — clearing a
 * thumb deletes the row, so "no opinion" and "never asked" are the same
 * absence. A down-vote excludes the work from the "For you" row; an
 * up-vote lends its genres and title words to the ranker's affinity
 * bonus (see [feedbackSignals] in ForYou.kt). The vote is the viewer's
 * own, stored on this device only — it never leaves the phone.
 */
@Entity(tableName = "title_feedback")
data class TitleFeedback(
    @PrimaryKey
    val workRef: String,
    val thumb: Int,
    val updatedAt: Long = System.currentTimeMillis(),
)
