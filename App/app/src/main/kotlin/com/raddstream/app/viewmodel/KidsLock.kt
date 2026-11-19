package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.PostDetail
import java.security.MessageDigest

/**
 * MX idea #7 — Kids Lock: the pure rules. No Android imports, no
 * DataStore, no network — every decision here is testable on the JVM.
 *
 * What "kids mode" means in this app, and what it does not. The public
 * port never carries `is_18_plus` ([AdultPolicy] documents the
 * measurement), so the server already hides flagged rows from every
 * fresh catalog read — that is layer 0, and it holds in kids mode
 * exactly as in adult mode. What this file adds is the client half:
 *
 * Layer 1 — hard, never negotiable: [AdultPolicy.mayList]. A row the
 * app positively knows is flagged, or the server refused, never lists
 * in kids mode — even if its genres look friendly. This is the
 * guarantee, and it is what closes the cached-row hole (a title watched
 * before it was flagged resurfacing in Continue Watching, or in the
 * local FTS echo) that kids mode would otherwise reopen.
 *
 * Layer 2 — heuristic, documented as one: a genre allowlist. The wire
 * carries no maturity rating and no `kid_safe` projection, so the
 * genres a row carries are the only signal the client has. A post is
 * kid-appropriate when at least one of its known genres is in
 * [KIDS_GENRES] (intersection, not "all genres in the set" — a cartoon
 * tagged `Animation, Hindi Dub` counts). A post with NO known genre is
 * NOT kid-appropriate: fail closed, an unclassified title does not
 * appear. `isAnimated` alone is NOT a pass — anime spans the whole
 * maturity range, and the flag layer is what catches its adult end.
 *
 * Honest limit, stated here and on the setup screen: the genre rule
 * shapes browsing; it is not a maturity rating. A mainstream R-rated
 * comedy in the Comedy genre is the known residual — the follow-up
 * that actually fixes it is a server-side maturity signal, not a
 * longer client list.
 *
 * The PIN is a 4-digit parent code. It is stored only as a salted
 * SHA-256 hash (see [hashPin]) — never plaintext, never in a log.
 * There is no recovery without clearing app data: this app has no
 * account to prove ownership to, and a reset question a child can
 * answer is not a lock. The setup screen says so out loud.
 */
object KidsLock {

    /** The PIN is exactly four digits — the same shape as Netflix's Profile Lock. */
    const val PIN_LENGTH = 4

    /** Failed PIN attempts before the pad locks out. */
    const val MAX_FAILED_ATTEMPTS = 5

    /** How long the pad stays locked after [MAX_FAILED_ATTEMPTS] failures. */
    const val LOCKOUT_MS = 30_000L

    /**
     * The genres a kids-mode list may show. Lowercase, compared after
     * trimming + lowercasing the wire value. The set is drawn from the
     * server's own genre universe (`fast_fetch.py` COMMON_GENRES);
     * anything not in it — Action, Biography, Crime, Drama, History,
     * Horror, Mystery, Romance, Sci-Fi, Thriller, War, and every junk
     * token the column carries ("Hindi Dub", "NETFLIX") — keeps the
     * title out of kids mode.
     */
    val KIDS_GENRES: Set<String> = setOf(
        "animation", "family", "kids", "children", "cartoon", "comedy",
        "adventure", "fantasy", "musical", "documentary", "educational", "sport",
    )

    /** True for exactly four ASCII digits — nothing else is a PIN. */
    fun isValidPinFormat(pin: String): Boolean =
        pin.length == PIN_LENGTH && pin.all { it in '0'..'9' }

    /**
     * The stored form of a PIN: SHA-256 of `salt:pin`, hex-encoded.
     * The salt is a random UUID minted at setup and stored beside the
     * hash; two installs with the same PIN store different hashes.
     */
    fun hashPin(pin: String, salt: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val bytes = digest.digest("$salt:$pin".toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    /**
     * Whether [pin] is the PIN behind [hash] under [salt]. False when
     * no PIN was ever set (null hash or salt), and false for a malformed
     * PIN — a format check is not a guess worth hashing.
     */
    fun verifyPin(pin: String, salt: String?, hash: String?): Boolean {
        if (salt == null || hash == null) return false
        if (!isValidPinFormat(pin)) return false
        return hashPin(pin, salt) == hash
    }

    /** Layer 2 on its own: at least one known genre is a kids genre. */
    fun isKidAppropriateGenres(genres: List<String>): Boolean =
        genres.any { it.trim().lowercase() in KIDS_GENRES }

    /**
     * Both layers together. `is18Plus == null` is "never told", which
     * [AdultPolicy] reads as listable — the fresh rows kids mode shows
     * arrive pre-filtered by the server, and the flag is what the app
     * knows about cached rows.
     */
    fun isKidAppropriate(
        is18Plus: Boolean?,
        refusedByServer: Boolean,
        genres: List<String>,
    ): Boolean {
        if (!AdultPolicy.mayList(is18Plus, refusedByServer)) return false
        return isKidAppropriateGenres(genres)
    }

    /** A list row, whose flag the public port never carries. */
    fun isKidAppropriate(post: Post): Boolean =
        isKidAppropriate(
            is18Plus = null,
            refusedByServer = false,
            genres = post.genreList,
        )

    /** A detail row, same rule — the last line of defence for deep links. */
    fun isKidAppropriate(detail: PostDetail): Boolean =
        isKidAppropriate(
            is18Plus = null,
            refusedByServer = false,
            genres = detail.genreList,
        )

    /** The whole list rule, so every screen filters through one function. */
    fun <Row> filterPosts(
        rows: List<Row>,
        genresOf: (Row) -> List<String>,
        flagOf: (Row) -> Boolean? = { null },
        refusedOf: (Row) -> Boolean = { false },
    ): List<Row> = rows.filter { isKidAppropriate(flagOf(it), refusedOf(it), genresOf(it)) }

    // ── PIN-attempt throttle ─────────────────────────────────────────────

    /**
     * True while the pad must refuse guesses: [MAX_FAILED_ATTEMPTS]
     * failures with the latest one inside [LOCKOUT_MS]. Pure so a test
     * can pin the boundary without a clock: a lockout that expired is
     * over, and a future timestamp (a clock that moved backwards) never
     * locks.
     */
    fun isPinLockedOut(failedAttempts: Int, lastFailedAtMs: Long?, nowMs: Long): Boolean {
        if (failedAttempts < MAX_FAILED_ATTEMPTS) return false
        val last = lastFailedAtMs ?: return false
        val elapsed = nowMs - last
        return elapsed in 0..<LOCKOUT_MS
    }

    /** Milliseconds left on a lockout, or 0 when the pad is open. */
    fun lockoutRemainingMs(failedAttempts: Int, lastFailedAtMs: Long?, nowMs: Long): Long {
        if (!isPinLockedOut(failedAttempts, lastFailedAtMs, nowMs)) return 0L
        return LOCKOUT_MS - (nowMs - (lastFailedAtMs ?: nowMs))
    }
}
