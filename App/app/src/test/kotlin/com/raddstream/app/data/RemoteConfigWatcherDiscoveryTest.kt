package com.raddstream.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.data.local.AdCadenceDao
import com.raddstream.app.data.local.AdCadenceEntity
import com.raddstream.app.network.ApiProvider
import com.raddstream.app.network.DynamicServerDiscovery
import com.raddstream.app.network.RaddStreamApi
import com.raddstream.app.network.models.VipRemoteConfig
import com.raddstream.app.player.AdCadenceGovernor
import com.raddstream.app.player.AdInventory
import com.raddstream.app.player.AdSurface
import com.raddstream.app.player.AdSwitch
import com.raddstream.app.player.FixedAdWallClock
import com.raddstream.app.repository.CatalogRepository
import java.lang.reflect.Proxy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The deadman switch is wired into the remote-config watcher's failure path —
 * and nowhere else. These tests pin both directions of that wiring:
 *
 *  - a failed poll attempts recovery (and leaves the saved address alone
 *    when the gateway has nothing working to offer);
 *  - a healthy poll never invokes discovery at all;
 *  - a failed poll against a server that is not ours never attempts recovery.
 *
 * No test here touches the real gateway: the recording discovery points the
 * recovery at an unroutable loopback address, which fails fast with a refused
 * connection.
 */
@RunWith(RobolectricTestRunner::class)
class RemoteConfigWatcherDiscoveryTest {

    private class FakeDao : AdCadenceDao {
        override suspend fun all(): List<AdCadenceEntity> = emptyList()
        override suspend fun put(row: AdCadenceEntity) = Unit
        override suspend fun clear() = Unit
    }

    private class FakeSwitch : AdSwitch {
        override suspend fun adsEnabled(): Boolean = false
    }

    private class FakeInventory : AdInventory {
        override fun canFill(surface: AdSurface): Boolean = false
    }

    /** A provider that fails before any request, as an unreachable server would. */
    private class ThrowingProvider(settings: ServerSettingsRepository) : ApiProvider(settings) {
        override suspend fun current(): RaddStreamApi =
            throw IllegalStateException("no server answers")
    }

    /** A provider whose API answers, but with success=false — a healthy poll. */
    private class HealthyProvider(settings: ServerSettingsRepository) : ApiProvider(settings) {
        override suspend fun current(): RaddStreamApi = Proxy.newProxyInstance(
            RaddStreamApi::class.java.classLoader,
            arrayOf(RaddStreamApi::class.java),
        ) { _, method, _ ->
            if (method.name == "getRemoteConfig") VipRemoteConfig() else throw UnsupportedOperationException(method.name)
        } as RaddStreamApi
    }

    /**
     * Records every call the watcher makes, then runs the real recovery
     * against an unroutable gateway — connection refused, fast fail, no real
     * network, no real gateway.
     */
    private class RecordingDiscovery(
        settings: ServerSettingsRepository,
        apiProvider: ApiProvider,
    ) : DynamicServerDiscovery(settings, apiProvider) {
        var calls = 0
        override suspend fun discoverAndPersistWorkingServer(
            gatewayUrl: String,
            nowMs: Long,
        ): String? {
            calls++
            return super.discoverAndPersistWorkingServer("http://127.0.0.1:9/", nowMs)
        }
    }

    /** One settings instance shared by every collaborator, as production does. */
    private class Fixture(
        savedUrl: String,
        repositoryProvider: (ServerSettingsRepository) -> ApiProvider,
    ) {
        val settings = ServerSettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val repository = CatalogRepository(repositoryProvider(settings), settings)
        val discovery = RecordingDiscovery(settings, ApiProvider(settings))
        val governor = AdCadenceGovernor(FakeDao(), FixedAdWallClock(), FakeInventory(), FakeSwitch())
        val watcher = RemoteConfigWatcher(repository, settings, governor, discovery, scope)

        init {
            runBlocking { settings.saveServerUrl(savedUrl) }
        }

        fun close() = scope.cancel()
    }

    @Test
    fun `a failed poll attempts recovery and leaves the saved address alone`() = runBlocking {
        val fixture = Fixture("https://raddstream.duckdns.org", ::ThrowingProvider)
        try {
            fixture.watcher.refreshNow()
            withTimeout(10_000) {
                while (fixture.discovery.lastRecoveryAttemptMs == 0L) delay(25)
            }
            assertEquals(1, fixture.discovery.calls)
            assertTrue(fixture.discovery.lastRecoveryAttemptMs > 0L)
            // Nothing working was found, so nothing was written anywhere.
            assertEquals("https://raddstream.duckdns.org", fixture.settings.serverUrl.first())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a healthy poll never invokes discovery`() = runBlocking {
        val fixture = Fixture("https://raddstream.duckdns.org", ::HealthyProvider)
        try {
            fixture.watcher.refreshNow()
            // The healthy path completes synchronously on the Unconfined scope:
            // the stub answers without suspending and success=false returns
            // before any write. If discovery were invoked, the call would
            // already be recorded.
            assertEquals(0, fixture.discovery.calls)
            assertEquals(0L, fixture.discovery.lastRecoveryAttemptMs)
            assertEquals("https://raddstream.duckdns.org", fixture.settings.serverUrl.first())
        } finally {
            fixture.close()
        }
    }

    @Test
    fun `a failed poll against a foreign server never attempts recovery`() = runBlocking {
        val fixture = Fixture("https://someone-else.example", ::ThrowingProvider)
        try {
            fixture.watcher.refreshNow()
            withTimeout(10_000) {
                while (fixture.discovery.calls == 0) delay(25)
            }
            // The watcher did call discovery — but the guard refused to attempt
            // a recovery for a server that is not ours.
            assertEquals(1, fixture.discovery.calls)
            delay(500)
            assertEquals(0L, fixture.discovery.lastRecoveryAttemptMs)
            assertEquals("https://someone-else.example", fixture.settings.serverUrl.first())
        } finally {
            fixture.close()
        }
    }
}
