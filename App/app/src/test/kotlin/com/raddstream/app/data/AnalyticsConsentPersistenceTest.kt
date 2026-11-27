package com.raddstream.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Video audit #12 (Nov 2026): the "Help improve Radd Stream" dialog
 * reappeared on every launch. The re-asking the video shows is the
 * "Skip for now" path — a skip answers nothing by design, so the sheet
 * asks again on the next launch — but the audit's open question was
 * whether a REAL choice sticks: Allow / Don't allow must never re-ask.
 * These tests pin exactly that, through the real
 * [ServerSettingsRepository] on a test DataStore: a saved answer read
 * back from a FRESH repository instance — the relaunch shape, a new
 * reader over the same persisted file — is still the answer, for both
 * choices, and the two choices are distinct values, not one "answered"
 * bit. Each test saves before it asserts: the Robolectric DataStore file
 * is shared across tests in the process, so no test here may assume a
 * pristine "never asked" state.
 */
@RunWith(RobolectricTestRunner::class)
class AnalyticsConsentPersistenceTest {

    private fun settings(): ServerSettingsRepository {
        val context = ApplicationProvider.getApplicationContext<Context>()
        return ServerSettingsRepository(context)
    }

    @Test
    fun `an Allow choice survives a relaunch`() = runBlocking {
        val settings = settings()
        settings.saveAnalyticsConsent(true)

        // A new repository instance: the next launch's reader.
        val relaunched = settings()
        assertTrue(relaunched.analyticsConsent.first() == true)
    }

    @Test
    fun `a Don't allow choice survives a relaunch`() = runBlocking {
        val settings = settings()
        settings.saveAnalyticsConsent(false)

        // A new repository instance: the next launch's reader.
        val relaunched = settings()
        assertTrue(relaunched.analyticsConsent.first() == false)
    }

    @Test
    fun `Allow and Don't allow are distinct persisted answers`() = runBlocking {
        val settings = settings()

        settings.saveAnalyticsConsent(true)
        assertEquals(true, settings.analyticsConsent.first())
        settings.saveAnalyticsConsent(false)
        assertEquals(false, settings.analyticsConsent.first())
        settings.saveAnalyticsConsent(true)
        assertEquals(true, settings.analyticsConsent.first())
    }
}
