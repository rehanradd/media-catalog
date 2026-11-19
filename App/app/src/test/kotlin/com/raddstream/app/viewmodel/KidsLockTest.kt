package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * MX #7 — Kids Lock: the pure rules, on the JVM.
 *
 * Two layers, and the tests pin both plus their interaction: a flagged
 * row never lists even when its genres look friendly (layer 1 outranks
 * layer 2), and a friendly-looking row with no known genre does not list
 * either (fail closed). The PIN half pins the format, the salted hash
 * round-trip, and the attempt throttle boundary.
 */
class KidsLockTest {

    private fun post(genres: String? = null) = Post(
        id = 1,
        title = "Title",
        genres = genres,
    )

    // ── PIN format ───────────────────────────────────────────────────────

    @Test
    fun pinFormat_acceptsExactlyFourDigits() {
        assertTrue(KidsLock.isValidPinFormat("1234"))
        assertTrue(KidsLock.isValidPinFormat("0000"))
    }

    @Test
    fun pinFormat_rejectsAnythingElse() {
        assertFalse(KidsLock.isValidPinFormat(""))
        assertFalse(KidsLock.isValidPinFormat("123"))
        assertFalse(KidsLock.isValidPinFormat("12345"))
        assertFalse(KidsLock.isValidPinFormat("12a4"))
        assertFalse(KidsLock.isValidPinFormat(" 123"))
        assertFalse(KidsLock.isValidPinFormat("123 "))
    }

    // ── PIN hash / verify ────────────────────────────────────────────────

    @Test
    fun pinHash_roundTripsForTheRightPin() {
        val salt = "test-salt"
        val hash = KidsLock.hashPin("1234", salt)
        assertTrue(KidsLock.verifyPin("1234", salt, hash))
    }

    @Test
    fun pinHash_wrongPinFails() {
        val hash = KidsLock.hashPin("1234", "test-salt")
        assertFalse(KidsLock.verifyPin("4321", "test-salt", hash))
    }

    @Test
    fun pinHash_saltChangesTheHash() {
        val a = KidsLock.hashPin("1234", "salt-a")
        val b = KidsLock.hashPin("1234", "salt-b")
        assertTrue(a != b)
        assertFalse(KidsLock.verifyPin("1234", "salt-b", a))
    }

    @Test
    fun pinHash_isNotThePinInPlaintext() {
        val hash = KidsLock.hashPin("1234", "test-salt")
        assertFalse(hash.contains("1234"))
    }

    @Test
    fun pinVerify_falseWithNoStoredPin() {
        assertFalse(KidsLock.verifyPin("1234", null, null))
        assertFalse(KidsLock.verifyPin("1234", "salt", null))
        assertFalse(KidsLock.verifyPin("1234", null, "hash"))
    }

    @Test
    fun pinVerify_malformedPinNeverVerifies() {
        val hash = KidsLock.hashPin("1234", "salt")
        assertFalse(KidsLock.verifyPin("123", "salt", hash))
        assertFalse(KidsLock.verifyPin("12a4", "salt", hash))
    }

    // ── Layer 2: the genre allowlist ─────────────────────────────────────

    @Test
    fun genreRule_allowsFamilyGenres() {
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Animation")))
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Family")))
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Kids")))
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Comedy")))
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Adventure")))
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Fantasy")))
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Musical")))
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Documentary")))
    }

    @Test
    fun genreRule_rejectsAdultGenres() {
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("Horror")))
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("Thriller")))
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("Crime")))
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("Romance")))
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("War")))
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("Mystery")))
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("Action")))
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("Drama")))
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("Sci-Fi")))
    }

    @Test
    fun genreRule_intersectionNotAll() {
        // A cartoon tagged with a junk token still counts — one kids
        // genre is enough, and junk tokens are not genres at all.
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Animation", "Hindi Dub")))
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("Action", "Family")))
        // But junk alone is not a kids genre.
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("Hindi Dub")))
        assertFalse(KidsLock.isKidAppropriateGenres(listOf("NETFLIX")))
    }

    @Test
    fun genreRule_caseAndWhitespaceInsensitive() {
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("ANIMATION")))
        assertTrue(KidsLock.isKidAppropriateGenres(listOf("  Family  ")))
    }

    @Test
    fun genreRule_emptyMeansNotAppropriate() {
        assertFalse(KidsLock.isKidAppropriateGenres(emptyList()))
    }

    // ── Both layers together ─────────────────────────────────────────────

    @Test
    fun kidAppropriate_allowsFriendlyPost() {
        assertTrue(KidsLock.isKidAppropriate(post("Animation, Adventure")))
        assertTrue(KidsLock.isKidAppropriate(post("Family")))
    }

    @Test
    fun kidAppropriate_rejectsUnclassifiedPost() {
        // Fail closed: no genre at all, and a genre outside the set.
        assertFalse(KidsLock.isKidAppropriate(post(null)))
        assertFalse(KidsLock.isKidAppropriate(post("")))
        assertFalse(KidsLock.isKidAppropriate(post("Action, Thriller")))
    }

    @Test
    fun kidAppropriate_flagOutranksFriendlyGenres() {
        // Layer 1 outranks layer 2: a flagged row never lists, even
        // when its genres look friendly — and a refused row neither.
        assertFalse(
            KidsLock.isKidAppropriate(
                is18Plus = true,
                refusedByServer = false,
                genres = listOf("Family"),
            ),
        )
        assertFalse(
            KidsLock.isKidAppropriate(
                is18Plus = null,
                refusedByServer = true,
                genres = listOf("Animation"),
            ),
        )
        assertTrue(
            KidsLock.isKidAppropriate(
                is18Plus = false,
                refusedByServer = false,
                genres = listOf("Animation"),
            ),
        )
    }

    @Test
    fun filterPosts_keepsOnlyKidAppropriate() {
        val rows = listOf(
            post("Animation"),
            post("Horror"),
            post(null),
            post("Family, Comedy"),
        )
        val kept = KidsLock.filterPosts(rows, genresOf = { it.genreList })
        assertEquals(listOf(rows[0], rows[3]), kept)
    }

    // ── PIN-attempt throttle ─────────────────────────────────────────────

    @Test
    fun throttle_openBelowFiveFailures() {
        assertFalse(KidsLock.isPinLockedOut(0, null, 1_000L))
        assertFalse(KidsLock.isPinLockedOut(4, 1_000L, 2_000L))
    }

    @Test
    fun throttle_locksAtFiveWithinThirtySeconds() {
        assertTrue(KidsLock.isPinLockedOut(5, 1_000L, 1_000L + 29_999L))
        assertTrue(KidsLock.isPinLockedOut(6, 1_000L, 1_000L + 1_000L))
    }

    @Test
    fun throttle_opensAgainAfterThirtySeconds() {
        assertFalse(KidsLock.isPinLockedOut(5, 1_000L, 1_000L + 30_000L))
        assertFalse(KidsLock.isPinLockedOut(5, 1_000L, 1_000L + 60_000L))
    }

    @Test
    fun throttle_futureTimestampNeverLocks() {
        assertFalse(KidsLock.isPinLockedOut(5, 5_000L, 1_000L))
    }

    @Test
    fun throttle_remainingCountsDown() {
        assertEquals(30_000L, KidsLock.lockoutRemainingMs(5, 1_000L, 1_000L))
        assertEquals(20_000L, KidsLock.lockoutRemainingMs(5, 1_000L, 1_000L + 10_000L))
        assertEquals(0L, KidsLock.lockoutRemainingMs(4, 1_000L, 1_000L))
        assertEquals(0L, KidsLock.lockoutRemainingMs(5, 1_000L, 1_000L + 30_000L))
    }
}
