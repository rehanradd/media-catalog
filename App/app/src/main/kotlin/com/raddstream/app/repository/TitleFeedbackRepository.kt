package com.raddstream.app.repository

import com.raddstream.app.data.local.TitleFeedback
import com.raddstream.app.data.local.TitleFeedbackDao
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * REA #2 (Netflix, Nov 2026): the thumbs behind the Match % — the
 * viewer's own up/down votes on works, beside [FollowedSeriesRepository]:
 * the same shape (a Room table the viewer owns, a Flow the screen
 * observes, a toggle the Detail row calls), for a different question —
 * not "tell me when this grows" but "more / less like this".
 *
 * A vote is keyed by the work's handle, never its post id: a new episode
 * is a new post, and a vote must survive the thing it was cast on
 * growing. 0 is never a stored value — clearing a vote deletes its row.
 */
class TitleFeedbackRepository @Inject constructor(
    private val feedbackDao: TitleFeedbackDao,
) {

    /** Every vote as workRef → thumb (1 / -1). A failure reads as no votes. */
    suspend fun allFeedback(): Map<String, Int> =
        runCatching { feedbackDao.allOnce().associate { it.workRef to it.thumb } }
            .getOrDefault(emptyMap())

    /** This work's current vote, or null when the viewer has none. */
    fun thumbFor(workRef: String): Flow<Int?> = feedbackDao.thumbFor(workRef)

    /** Set a vote: 1 or -1 writes the row, 0 deletes it (no opinion). */
    suspend fun setThumb(workRef: String, thumb: Int) {
        if (thumb == 0) {
            feedbackDao.deleteByWorkRef(workRef)
        } else {
            feedbackDao.insert(TitleFeedback(workRef = workRef, thumb = thumb))
        }
    }

    /**
     * Tap the same thumb again to clear it, else switch to it. Returns
     * the new vote, so the caller can paint it at once instead of waiting
     * for the Flow to re-emit.
     */
    suspend fun toggleThumb(workRef: String, thumb: Int): Int {
        val current = runCatching { feedbackDao.findByWorkRef(workRef)?.thumb ?: 0 }
            .getOrDefault(0)
        val next = if (current == thumb) 0 else thumb
        setThumb(workRef, next)
        return next
    }
}
