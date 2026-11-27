package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gap A6: the "Pick 3 you like" card shows only to a brand-new viewer, and
 * only once a rail can offer three real titles. First-run (video audit
 * #11, Nov 2026): and only while the onboarding window is still open —
 * the viewer has not left Home yet. The old B5 rule made the card wait
 * for the first browse or play, so it could only ever appear AFTER the
 * viewer had left Home and come back — the mid-session pop-in the audit
 * caught, right after Live TV. The wait is gone: the card offers itself
 * on the first Home visit, as soon as the rails can offer three titles,
 * and leaving Home retires it unshown rather than deferring it.
 *
 * Pick 3 (home review, Nov 2026): the picks never repeat the hero — the
 * hero shows its section's first six slides, so the candidates are the
 * next three of that rail, or the next rail when it runs short. And the
 * card is a one-shot: the first display retires it, whether the viewer
 * picked, dismissed, or scrolled past.
 *
 * Fixed 2026-11-15: the once is a whole Home visit, not one frame. The
 * first build set only the persisted shown flag on display, and the gate
 * required it false — so the card retired itself the instant it composed
 * and no user or Robo run ever saw it. The visit latch keeps the card up
 * while it is showing; leaving Home clears the latch, and the persisted
 * flag then keeps it away.
 */
class PickThreeTest {

    private fun post(id: Int, year: Int? = null) = Post(id = id, title = "Title $id", year = year)

    private fun rail(items: List<Post>, key: String = "recent") =
        HomeSection(key = key, title = "Recently added", type = null, items = items)

    /** Nine titles: the hero takes the first six, the card offers the next three. */
    private fun fullRail() = rail((1..9).map { post(it) })

    private fun state(
        sections: List<HomeSection> = listOf(fullRail()),
        continueWatching: List<ResumeTile> = emptyList(),
        favoriteIds: Set<Int> = emptySet(),
        pickThreeDone: Boolean = false,
        pickThreeShown: Boolean = false,
        pickThreeShownThisVisit: Boolean = false,
        onboardingSeen: Boolean = false,
    ) = HomeUiState(
        sections = sections,
        continueWatching = continueWatching,
        favoriteIds = favoriteIds,
        pickThreeDone = pickThreeDone,
        pickThreeShown = pickThreeShown,
        pickThreeShownThisVisit = pickThreeShownThisVisit,
        onboardingSeen = onboardingSeen,
    )

    @Test
    fun `brand-new viewer on the first Home visit sees the card with the three after the hero`() {
        val s = state()

        assertTrue(s.showPickThree)
        assertEquals(listOf(7, 8, 9), s.pickThreeCandidates.map { it.id })
    }

    @Test
    fun `hero titles are never offered as picks`() {
        val s = state()

        assertTrue(s.pickThreeCandidates.none { it.id in 1..6 })
    }

    @Test
    fun `a rail short on eligible titles falls through to the next rail`() {
        val s = state(
            sections = listOf(
                rail((1..7).map { post(it) }),
                rail(listOf(post(11), post(12), post(13)), key = "movie"),
            ),
        )

        // Only one eligible title in the first rail (its seventh) — the
        // second rail supplies all three picks.
        assertTrue(s.showPickThree)
        assertEquals(listOf(11, 12, 13), s.pickThreeCandidates.map { it.id })
    }

    @Test
    fun `card needs no browse first — it offers itself on the first Home visit`() {
        // Video audit #11: the old B5 wait-for-browse is what pushed the
        // card to mid-session — it could only appear after the viewer had
        // left Home and come back. A brand-new viewer with full rails sees
        // it at onboarding, before any browse or play.
        val s = state()

        assertEquals(listOf(7, 8, 9), s.pickThreeCandidates.map { it.id })
        assertTrue(s.showPickThree)
    }

    @Test
    fun `leaving Home closes the onboarding window so the card never interrupts later`() {
        // Video audit #11, the exact video shape: the viewer left Home
        // for Live TV and came back to full rails. The card must not pop
        // in on the return — the window closed when they left, even
        // though the card never appeared on the first visit.
        val s = state(onboardingSeen = true)

        assertEquals(listOf(7, 8, 9), s.pickThreeCandidates.map { it.id })
        assertFalse(s.showPickThree)
    }

    @Test
    fun `continue watching hides the card`() {
        val tile = ResumeTile.of(
            com.raddstream.app.data.local.WatchHistoryEntity(
                workRef = "w1",
                postId = 1,
                title = "Title 1",
                posterUrl = null,
                seasonNumber = 0,
                episodeNumber = 0,
                positionMs = 1_000L,
                durationMs = 10_000L,
                updatedAt = 1_000L,
            ),
        )
        assertFalse(state(continueWatching = listOfNotNull(tile)).showPickThree)
    }

    @Test
    fun `any saved title hides the card`() {
        assertFalse(state(favoriteIds = setOf(1)).showPickThree)
    }

    @Test
    fun `dismissed card never returns`() {
        assertFalse(state(pickThreeDone = true).showPickThree)
    }

    @Test
    fun `shown card never returns on a later visit even without a pick or a dismiss`() {
        val s = state(pickThreeShown = true)

        // The picks are still computed — the card is retired, not empty.
        assertEquals(listOf(7, 8, 9), s.pickThreeCandidates.map { it.id })
        assertFalse(s.showPickThree)
    }

    @Test
    fun `display latches the card visible for the rest of the visit`() {
        // This is the bug that hid the card from every user and both Robo
        // runs: display used to retire the card the same frame it
        // appeared. The latch is what keeps it up while it is showing.
        val shown = state().onPickThreeShown()

        assertTrue(shown.pickThreeShown)
        assertTrue(shown.pickThreeShownThisVisit)
        assertTrue(shown.showPickThree)
        assertEquals(listOf(7, 8, 9), shown.pickThreeCandidates.map { it.id })
    }

    @Test
    fun `leaving Home ends the visit so the card never returns`() {
        val ended = state().onPickThreeShown().onPickThreeVisitEnded()

        assertTrue("the persisted shown flag survives the visit", ended.pickThreeShown)
        assertFalse(ended.pickThreeShownThisVisit)
        assertFalse(ended.showPickThree)
    }

    @Test
    fun `card stays visible after one pick on the same visit`() {
        // The empty-favourites gate is an entry condition, not a live
        // one — one or two picks must not hide a card mid-visit.
        val s = state(
            favoriteIds = setOf(7),
            pickThreeShown = true,
            pickThreeShownThisVisit = true,
        )

        assertTrue(s.showPickThree)
    }

    @Test
    fun `third pick retires the card even mid-visit`() {
        val s = state(
            pickThreeDone = true,
            pickThreeShown = true,
            pickThreeShownThisVisit = true,
        )

        assertFalse(s.showPickThree)
    }

    @Test
    fun `a rail with fewer than three eligible titles offers nothing`() {
        val s = state(sections = listOf(rail((1..7).map { post(it) })))

        assertTrue(s.pickThreeCandidates.isEmpty())
        assertFalse(s.showPickThree)
    }

    @Test
    fun `a rail with fewer than three titles offers nothing`() {
        val s = state(sections = listOf(rail(listOf(post(1), post(2)))))

        assertTrue(s.pickThreeCandidates.isEmpty())
        assertFalse(s.showPickThree)
    }

    @Test
    fun `a stale panel hero excludes the fallback rail titles instead`() {
        val staleHero = HomeSection(
            key = "hero",
            title = "Hero",
            type = null,
            items = listOf(post(101, year = 1963), post(102, year = 1946)),
            panelManaged = true,
        )
        val recent = rail((1..9).map { post(it, year = 2026) })
        val s = state(sections = listOf(staleHero, recent))

        // The stale panel list lost the hero slot to Recently added, so
        // the exclusion follows the fallback — Recent's first six are
        // out, its next three are the picks, and no stale title appears.
        assertTrue(s.showPickThree)
        assertEquals(listOf(7, 8, 9), s.pickThreeCandidates.map { it.id })
    }
}
