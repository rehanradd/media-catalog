package com.raddstream.app.player

import com.raddstream.app.network.models.Playability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Player resilience (2026-11-25): a signed host URL rots mid-play — the handle played, then
 * the redirector answers 403/404/410 for it. That is an expiry until proven otherwise, so
 * the machine re-resolves the same rendition once (a fresh 302 for the same handle) before
 * it hops to a backup. These tests pin the decision logic; the service owns the reload with
 * the stale expiresAt cleared and the position preserved.
 */
class PlayerResilienceFailoverTest {

    private fun rung(
        rank: Int,
        handle: String,
        backups: List<String> = emptyList(),
        playability: Playability = Playability.STREAM,
        url: String? = "http://127.0.0.1:8080/api/v1/stream?id=$handle",
    ) = PlaybackRendition(
        rank = rank,
        releaseId = 7,
        print = PrintInfo(label = "Title"),
        playback = PlaybackInfo(
            playUrl = url,
            streamId = handle,
            label = "Fast direct",
            kind = "direct",
            playabilityWire = playability.wire,
            resumable = true,
            backupStreamIds = backups,
        ),
    )

    private fun machine(vararg renditions: PlaybackRendition) = FailoverStateMachine(
        renditions.toList(), PlaybackFailurePolicy(retrySame = 1, maxRanks = 3, backoffMs = 500),
    )

    @Test
    fun `a mid-play dead handle re-resolves once, then hops, then steps down`() {
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())

        // First mid-play 403: the signed URL rotted — re-resolve the same rendition.
        val reResolve = subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
        reResolve as FailoverDecision.ReResolveSame
        assertEquals(0, reResolve.index)

        // The re-resolved load fails too: the guard is spent, so the ordinary hop takes over.
        val hop = subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
        hop as FailoverDecision.AdvanceBackup
        assertEquals("b-a1", hop.streamId)

        // No runner-up left: step down a rank.
        val step = subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
        step as FailoverDecision.Advance
        assertEquals(1, step.index)
    }

    @Test
    fun `a dead handle before the first frame hops at once, never re-resolves`() {
        // S2: a start failure hops, never retries the same address — a handle that never
        // played cannot have expired mid-play.
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())
        val decision = subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = true)
        decision as FailoverDecision.AdvanceBackup
        assertEquals("b-a1", decision.streamId)
    }

    @Test
    fun `a dead handle on a local-copy attempt hops like before, never re-resolves`() {
        // The attempt that failed was the loopback copy, not the rendition's own address —
        // the re-resolve is for a live link that rotted, so this path keeps the machine's
        // existing hop behaviour.
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())
        val decision = subject.onFailure(
            "HTTP 403",
            isDeadHandle = true,
            beforeFirstFrame = false,
            isLocalCopyAttempt = true,
        )
        decision as FailoverDecision.AdvanceBackup
        assertEquals("b-a1", decision.streamId)
    }

    @Test
    fun `the re-resolve does not spend the retry budget`() {
        // A re-resolve and a network retry are different kinds of second chance, like the
        // decoder retries: a plain mid-play blip after a re-resolve still gets its RetrySame.
        val subject = machine(rung(1, "h-a"), rung(2, "h-b"))
        assertEquals(0, subject.selectInitial())
        assertTrue(
            subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
                is FailoverDecision.ReResolveSame,
        )
        val retry = subject.onFailure("network timeout", beforeFirstFrame = false)
        retry as FailoverDecision.RetrySame
        assertEquals(0, retry.index)
    }

    @Test
    fun `the re-resolve is one per rendition, not one per session`() {
        // Each rung gets its own second chance: after rung 1 spends its re-resolve and hops
        // away, a dead handle on rung 2 re-resolves there too.
        val subject = machine(
            rung(1, "h-a", backups = listOf("b-a1")),
            rung(2, "h-b"),
        )
        assertEquals(0, subject.selectInitial())
        assertTrue(
            subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
                is FailoverDecision.ReResolveSame,
        )
        assertTrue(
            subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
                is FailoverDecision.AdvanceBackup,
        )
        assertTrue(
            subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
                is FailoverDecision.Advance,
        )
        assertTrue(
            subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
                is FailoverDecision.ReResolveSame,
        )
    }

    @Test
    fun `the re-resolve names the expiry in the failover log`() {
        val subject = machine(rung(1, "h-a"), rung(2, "h-b"))
        assertEquals(0, subject.selectInitial())
        subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
        assertEquals(
            "re-resolved the expired link (same position)",
            subject.log.single().action,
        )
    }

    @Test
    fun `a re-resolve reports nothing to the server`() {
        // The rendition is not abandoned — it gets another chance — so the server must not
        // learn it as dead. A later hop reports as before; the re-resolve itself is silent.
        val subject = machine(rung(1, "h-a"), rung(2, "h-b"))
        assertEquals(0, subject.selectInitial())
        val decision = subject.onFailure("HTTP 403", isDeadHandle = true, beforeFirstFrame = false)
        assertNull(midPlaybackReportClass(decision, isDeadHandle = true))
    }
}
