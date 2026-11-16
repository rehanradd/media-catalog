package com.raddstream.app.network

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.data.ServerSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The deadman switch's session-only rule, pinned at the provider: a plain-HTTP
 * fallback may steer this process, but it must never overwrite the saved HTTPS
 * primary — and a viewer who changes server mid-session must land on the new
 * one immediately, not stay frozen on a fallback decided from the old one.
 */
@RunWith(RobolectricTestRunner::class)
class ApiProviderSessionFallbackTest {

    private fun provider(): Pair<ApiProvider, ServerSettingsRepository> {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = ServerSettingsRepository(context)
        return ApiProvider(settings) to settings
    }

    @Test
    fun `a session override answers the http fallback while the saved https primary is untouched`() = runBlocking {
        val (apiProvider, settings) = provider()
        settings.saveServerUrl("https://raddstream.duckdns.org")
        apiProvider.current()

        apiProvider.useSessionServerUrl(
            "https://raddstream.duckdns.org",
            "http://92.4.95.252",
        )

        assertEquals("http://92.4.95.252/", apiProvider.currentBaseUrl())
        // The override steers this process only: the saved primary is unchanged.
        assertEquals("https://raddstream.duckdns.org", settings.serverUrl.first())
        // And the client built from the override is the fallback one.
        apiProvider.current()
        assertEquals("http://92.4.95.252/", apiProvider.currentBaseUrl())
        assertEquals("https://raddstream.duckdns.org", settings.serverUrl.first())
    }

    @Test
    fun `a mid-session server change invalidates the session override`() = runBlocking {
        val (apiProvider, settings) = provider()
        settings.saveServerUrl("https://raddstream.duckdns.org")
        apiProvider.current()
        apiProvider.useSessionServerUrl(
            "https://raddstream.duckdns.org",
            "http://92.4.95.252",
        )
        assertEquals("http://92.4.95.252/", apiProvider.currentBaseUrl())

        // The viewer points the app at a different catalog mid-session.
        settings.saveServerUrl("https://other.example")

        apiProvider.current()
        assertEquals("https://other.example/", apiProvider.currentBaseUrl())
    }

    @Test
    fun `clearing the session override returns to the saved primary`() = runBlocking {
        val (apiProvider, settings) = provider()
        settings.saveServerUrl("https://raddstream.duckdns.org")
        apiProvider.current()
        apiProvider.useSessionServerUrl(
            "https://raddstream.duckdns.org",
            "http://92.4.95.252",
        )
        assertEquals("http://92.4.95.252/", apiProvider.currentBaseUrl())

        apiProvider.clearSessionServerUrl()

        assertEquals("https://raddstream.duckdns.org/", apiProvider.currentBaseUrl())
        assertEquals("https://raddstream.duckdns.org", settings.serverUrl.first())
    }

    @Test
    fun `an override decided from a different saved value never applies`() = runBlocking {
        val (apiProvider, settings) = provider()
        settings.saveServerUrl("https://raddstream.duckdns.org")
        apiProvider.current()

        // The override was decided while some other address was saved.
        apiProvider.useSessionServerUrl(
            "https://somewhere-else.example",
            "http://92.4.95.252",
        )

        assertEquals("https://raddstream.duckdns.org/", apiProvider.currentBaseUrl())
    }
}
