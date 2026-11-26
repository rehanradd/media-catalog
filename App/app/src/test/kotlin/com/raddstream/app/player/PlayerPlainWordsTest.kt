package com.raddstream.app.player

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Dev-cleanup (Nov 2026): the player's failure and status copy is fixed plain
 * words — no raw exception text, no rank numbers, no internal vocabulary.
 * These strings are the contract: if the wording changes, change the test
 * with it, deliberately.
 *
 * The context is a deferred getter, not a field initializer: the two older
 * Robolectric tests in this repo (PlayerMockupTest, PlayerReviewFixesTest)
 * do the same — an ApplicationProvider call at class-instantiation time runs
 * before the sandbox is ready. E1's sentence is not here: it is a ViewModel
 * literal (PLAYER_SERVICE_UNAVAILABLE_MESSAGE), pinned by
 * PlayerViewModelErrorMessageTest, because the ChromeCopy ratchet requires
 * every player_* resource to be painted from PlayerScreen.kt.
 */
@RunWith(RobolectricTestRunner::class)
class PlayerPlainWordsTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `pip refusal is one fixed line with no exception slot`() {
        val value = context.getString(R.string.player_pip_refused)
        assertEquals("Picture-in-picture isn't available right now.", value)
        assertFalse(value.contains("%1\$s"))
    }

    @Test
    fun `embed failures are fixed sentences with no raw text slot`() {
        assertEquals(
            "This page couldn't be opened. Try another version.",
            context.getString(R.string.embed_load_failed_unknown),
        )
        assertEquals(
            "This page can't be opened here. Try another version.",
            context.getString(R.string.embed_blocked_scheme),
        )
        assertFalse(context.getString(R.string.embed_load_failed_unknown).contains("%1\$s"))
        assertFalse(context.getString(R.string.embed_blocked_scheme).contains("%1\$s"))
    }

    @Test
    fun `the start wait and give-up speak versions, not copies`() {
        assertEquals("Trying another version…", context.getString(R.string.player_attempt_counter))
        assertEquals(
            "This version didn't start in time.",
            context.getString(R.string.player_start_giveup_message),
        )
        assertFalse(context.getString(R.string.player_attempt_counter).contains("%1\$d"))
    }

    @Test
    fun `the sheet and rows speak versions, not sources or candidates`() {
        assertEquals("Switch version", context.getString(R.string.player_prints_title))
        assertEquals("Trying the best version first", context.getString(R.string.player_finding_note))
        assertEquals("Retry this version", context.getString(R.string.player_retry_row))
    }

    @Test
    fun `an expired version says so in plain words`() {
        assertEquals(
            "This version is no longer available",
            context.getString(R.string.player_giveup_expired),
        )
        // Popup cleanup (26 Nov 2026): the give-up card is title + one-line
        // message only — the per-cause body paragraph is gone, and its
        // string with it. The title above is the whole "says so" now.
    }

    @Test
    fun `the raw-error strings are gone`() {
        val resources = context.resources
        assertEquals(0, resources.getIdentifier("player_tag_expiring", "string", context.packageName))
        assertEquals(0, resources.getIdentifier("embed_load_failed", "string", context.packageName))
    }
}
