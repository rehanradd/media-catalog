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
 *
 * Prod-leak audit (2026-11-20): the saved primary and the fallback used to be
 * the production addresses, so a real `ApiProvider` built real clients for them
 * here. The URLs below are deliberately unroutable test strings
 * (`catalog.test`, `127.0.0.1:9` — the same unroutable fallback
 * `RemoteConfigWatcherDiscoveryTest` already uses): the session-override logic
 * itself is what's under test, so a `PinnedApiProvider` would bypass the code
 * these tests pin — fake URLs are the fix, not the pin. No endpoint is ever
 * called through the provider here, so no bytes can leak whatever a read does.
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
        settings.saveServerUrl("https://catalog.test")
        apiProvider.current()

        apiProvider.useSessionServerUrl(
            "https://catalog.test",
            "http://127.0.0.1:9",
        )

        assertEquals("http://127.0.0.1:9/", apiProvider.currentBaseUrl())
        // The override steers this process only: the saved primary is unchanged.
        assertEquals("https://catalog.test", settings.serverUrl.first())
        // And the client built from the override is the fallback one.
        apiProvider.current()
        assertEquals("http://127.0.0.1:9/", apiProvider.currentBaseUrl())
        assertEquals("https://catalog.test", settings.serverUrl.first())
    }

    @Test
    fun `a mid-session server change invalidates the session override`() = runBlocking {
        val (apiProvider, settings) = provider()
        settings.saveServerUrl("https://catalog.test")
        apiProvider.current()
        apiProvider.useSessionServerUrl(
            "https://catalog.test",
            "http://127.0.0.1:9",
        )
        assertEquals("http://127.0.0.1:9/", apiProvider.currentBaseUrl())

        // The viewer points the app at a different catalog mid-session.
        settings.saveServerUrl("https://other.example")

        apiProvider.current()
        assertEquals("https://other.example/", apiProvider.currentBaseUrl())
    }

    @Test
    fun `clearing the session override returns to the saved primary`() = runBlocking {
        val (apiProvider, settings) = provider()
        settings.saveServerUrl("https://catalog.test")
        apiProvider.current()
        apiProvider.useSessionServerUrl(
            "https://catalog.test",
            "http://127.0.0.1:9",
        )
        assertEquals("http://127.0.0.1:9/", apiProvider.currentBaseUrl())

        apiProvider.clearSessionServerUrl()

        assertEquals("https://catalog.test/", apiProvider.currentBaseUrl())
        assertEquals("https://catalog.test", settings.serverUrl.first())
    }

    @Test
    fun `an override decided from a different saved value never applies`() = runBlocking {
        val (apiProvider, settings) = provider()
        settings.saveServerUrl("https://catalog.test")
        apiProvider.current()

        // The override was decided while some other address was saved.
        apiProvider.useSessionServerUrl(
            "https://somewhere-else.example",
            "http://127.0.0.1:9",
        )

        assertEquals("https://catalog.test/", apiProvider.currentBaseUrl())
    }
}
