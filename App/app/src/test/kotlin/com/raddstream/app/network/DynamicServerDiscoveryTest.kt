package com.raddstream.app.network

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.ServerSettingsRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Robolectric (plain Application, on purpose): the recovery-flow tests below
 * need a real [ServerSettingsRepository], which is DataStore-backed and
 * needs a Context. A plain Application keeps the real remote-config watcher
 * out of the process — the manifest's application would start it in
 * onCreate, and its first tick could fire the real deadman switch into
 * the same DataStore these fixtures assert on (see
 * RemoteConfigWatcherDiscoveryTest). The pure parsing tests run under the
 * same runner; they touch no Android API beyond Brand's BuildConfig
 * fallback.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class DynamicServerDiscoveryTest {

    // ── JSON shape (what the gateway serves today) ────────────────────────

    @Test
    fun parseJsonNodes_parsesLiveBlogPayload() {
        // The exact body https://rehan-radd.blogspot.com/ served on 2026-11-16.
        val liveBody = """{"status":"ok","primary":"http://92.4.95.252","backups":["https://backup1.yourdomain.com","https://backup2.yourdomain.com"],"v":"1.0.0"}"""

        val (primary, backups) = DynamicServerDiscovery.parseJsonNodes(liveBody)

        assertEquals("http://92.4.95.252", primary)
        assertEquals(2, backups.size)
        assertEquals("https://backup1.yourdomain.com", backups[0])
        assertEquals("https://backup2.yourdomain.com", backups[1])
    }

    @Test
    fun parseJsonNodes_parsesHttpsPrimaryAndHttpBackup() {
        val body = """{"status":"ok","primary":"https://raddstream.duckdns.org","backups":["http://92.4.95.252"],"v":"1.0.0"}"""

        val (primary, backups) = DynamicServerDiscovery.parseJsonNodes(body)

        assertEquals("https://raddstream.duckdns.org", primary)
        assertEquals(listOf("http://92.4.95.252"), backups)
    }

    @Test
    fun parseJsonNodes_emptyBackupsAndMissingPrimary() {
        val (primary, backups) = DynamicServerDiscovery.parseJsonNodes(
            """{"status":"ok","backups":[],"v":"1.0.0"}"""
        )

        assertEquals(null, primary)
        assertEquals(0, backups.size)
    }

    @Test
    fun parseJsonNodes_emptyOnMalformedJson() {
        val (primary, backups) = DynamicServerDiscovery.parseJsonNodes(
            """{"status":"ok","primary": 42, "backups": "not-an-array"}"""
        )

        assertEquals(null, primary)
        assertEquals(0, backups.size)
    }

    @Test
    fun parseJsonNodes_toleratesWhitespaceAndKeyOrder() {
        val body = """
            {
              "v" : "1.0.0",
              "backups" : [ "https://one.example", "https://two.example" ],
              "status" : "ok",
              "primary" : "https://primary.example"
            }
        """.trimIndent()

        val (primary, backups) = DynamicServerDiscovery.parseJsonNodes(body)

        assertEquals("https://primary.example", primary)
        assertEquals(listOf("https://one.example", "https://two.example"), backups)
    }

    // ── Shape selection: JSON first, meta tags as fallback ───────────────

    @Test
    fun parsePublishedNodes_prefersJsonOverMetaTags() {
        val body = """
            {"status":"ok","primary":"https://json.example","backups":["https://json-backup.example"],"v":"1.0.0"}
            <meta content="https://meta.example" name="radd-primary"/>
        """.trimIndent()

        val (primary, backups) = DynamicServerDiscovery.parsePublishedNodes(body)

        assertEquals("https://json.example", primary)
        assertEquals(listOf("https://json-backup.example"), backups)
    }

    @Test
    fun parsePublishedNodes_fallsBackToMetaTagsWhenNoJson() {
        val html = """
            <html><head>
              <meta content="https://meta.example" name="radd-primary"/>
              <meta content="https://meta-backup.example" name="radd-backup-1"/>
            </head></html>
        """.trimIndent()

        val (primary, backups) = DynamicServerDiscovery.parsePublishedNodes(html)

        assertEquals("https://meta.example", primary)
        assertEquals(listOf("https://meta-backup.example"), backups)
    }

    @Test
    fun parsePublishedNodes_emptyWhenNeitherShape() {
        val (primary, backups) = DynamicServerDiscovery.parsePublishedNodes(
            "<html><body>Nothing here</body></html>"
        )

        assertEquals(null, primary)
        assertEquals(0, backups.size)
    }

    // ── Placeholder filter ────────────────────────────────────────────────

    @Test
    fun isPlaceholderCandidate_flagsYourdomainAndGarbage() {
        assertTrue(DynamicServerDiscovery.isPlaceholderCandidate("https://backup1.yourdomain.com"))
        assertTrue(DynamicServerDiscovery.isPlaceholderCandidate("https://YOURDOMAIN.com/x"))
        assertTrue(DynamicServerDiscovery.isPlaceholderCandidate("https://example.com"))
        assertTrue(DynamicServerDiscovery.isPlaceholderCandidate("not a url at all"))
        assertTrue(DynamicServerDiscovery.isPlaceholderCandidate(""))

        assertFalse(DynamicServerDiscovery.isPlaceholderCandidate("https://raddstream.duckdns.org"))
        assertFalse(DynamicServerDiscovery.isPlaceholderCandidate("http://92.4.95.252"))
        assertFalse(DynamicServerDiscovery.isPlaceholderCandidate("https://raddstream.cc.cd"))
    }

    // ── Persistence rule ──────────────────────────────────────────────────

    @Test
    fun isPersistableCandidate_onlyHttpsMayOverwriteTheSavedPrimary() {
        assertTrue(DynamicServerDiscovery.isPersistableCandidate("https://raddstream.duckdns.org"))
        assertTrue(DynamicServerDiscovery.isPersistableCandidate("https://raddstream.cc.cd"))

        // The plain-HTTP fallback IP is session-only: it must never be persisted.
        assertFalse(DynamicServerDiscovery.isPersistableCandidate("http://92.4.95.252"))
        assertFalse(DynamicServerDiscovery.isPersistableCandidate("http://10.0.0.1:8080"))
        assertFalse(DynamicServerDiscovery.isPersistableCandidate("not a url"))
        assertFalse(DynamicServerDiscovery.isPersistableCandidate(""))
    }

    // ── Scope guard ───────────────────────────────────────────────────────

    @Test
    fun isOurServer_onlyOurHostsQualify() {
        // The shipped default.
        assertTrue(DynamicServerDiscovery.isOurServer("https://raddstream.duckdns.org"))
        assertTrue(DynamicServerDiscovery.isOurServer("https://raddstream.duckdns.org/"))
        // The ccwu.cc primary the Blogger gateway publishes — the one-shot
        // bug returned false here, so a migration onto it killed recovery.
        assertTrue(DynamicServerDiscovery.isOurServer("https://radd-stream.ccwu.cc"))
        assertTrue(DynamicServerDiscovery.isOurServer("https://radd-stream.ccwu.cc/"))
        assertTrue(DynamicServerDiscovery.isOurServer("https://RADD-STREAM.CCWU.CC"))
        // The documented plain-HTTP fallback IP.
        assertTrue(DynamicServerDiscovery.isOurServer("http://92.4.95.252"))
        assertTrue(DynamicServerDiscovery.isOurServer("http://92.4.95.252:8080"))

        // A viewer who pointed the app at their own catalog is never moved.
        assertFalse(DynamicServerDiscovery.isOurServer("https://someone-else.example"))
        assertFalse(DynamicServerDiscovery.isOurServer("https://duckdns.org"))
        assertFalse(DynamicServerDiscovery.isOurServer("https://ccwu.cc"))
        // Suffix traps: a host that merely ends with one of ours is not ours.
        assertFalse(DynamicServerDiscovery.isOurServer("https://raddstream.duckdns.org.evil.com"))
        assertFalse(DynamicServerDiscovery.isOurServer("https://radd-stream.ccwu.cc.evil.com"))
        assertFalse(DynamicServerDiscovery.isOurServer("https://92.4.95.252.evil.com"))
        assertFalse(DynamicServerDiscovery.isOurServer("not a url"))
        assertFalse(DynamicServerDiscovery.isOurServer(""))
    }

    @Test
    fun ourServerHosts_listsAllThreeHosts() {
        assertEquals(
            listOf("raddstream.duckdns.org", "radd-stream.ccwu.cc", "92.4.95.252"),
            Brand.ourServerHosts,
        )
    }

    // ── Recovery flow: the one-shot bug ───────────────────────────────────

    /**
     * A discovery whose gateway answers with a canned working URL — no
     * network, no real gateway, no probes.
     */
    private class CannedDiscovery(
        settings: ServerSettingsRepository,
        apiProvider: ApiProvider,
        var working: String?,
    ) : DynamicServerDiscovery(settings, apiProvider) {
        override suspend fun fetchPublishedNodes(gatewayUrl: String): DiscoveryResult =
            DiscoveryResult(primary = working, backups = emptyList(), verifiedWorkingUrl = working)
    }

    /** One settings instance shared by the discovery and the assertions. */
    private class FlowFixture(savedUrl: String, working: String?) {
        val settings = ServerSettingsRepository(ApplicationProvider.getApplicationContext<Context>())
        val discovery = CannedDiscovery(settings, ApiProvider(settings), working)

        init {
            runBlocking { settings.saveServerUrl(savedUrl) }
        }
    }

    @Test
    fun recoveryTriggersAgainAfterMigratingToCcwu() = runBlocking {
        // duckdns fails -> recovery migrates the saved primary to ccwu.cc.
        val fixture = FlowFixture("https://raddstream.duckdns.org", "https://radd-stream.ccwu.cc")
        val first = fixture.discovery.discoverAndPersistWorkingServer(nowMs = 1_000_000L)
        assertEquals("https://radd-stream.ccwu.cc", first)
        assertEquals("https://radd-stream.ccwu.cc", fixture.settings.serverUrl.first())

        // ccwu.cc fails later -> recovery must trigger AGAIN. Before the
        // fix, isOurServer(ccwu.cc) was false, so this call returned null
        // and the app stayed on a dead server forever.
        fixture.discovery.working = "https://raddstream.duckdns.org"
        val second = fixture.discovery.discoverAndPersistWorkingServer(
            nowMs = 1_000_000L + DynamicServerDiscovery.RECOVERY_COOLDOWN_MS + 1,
        )
        assertEquals("https://raddstream.duckdns.org", second)
        assertEquals("https://raddstream.duckdns.org", fixture.settings.serverUrl.first())
    }

    @Test
    fun recoveryNeverRunsForAViewerChosenServer() = runBlocking {
        val fixture = FlowFixture("https://someone-else.example", "https://radd-stream.ccwu.cc")

        val result = fixture.discovery.discoverAndPersistWorkingServer(nowMs = 1_000_000L)

        assertEquals(null, result)
        assertEquals(0L, fixture.discovery.lastRecoveryAttemptMs)
        assertEquals("https://someone-else.example", fixture.settings.serverUrl.first())
    }

    // ── Cooldown ──────────────────────────────────────────────────────────

    @Test
    fun isRecoveryDue_spacedByThirtyMinutes() {
        val cooldown = DynamicServerDiscovery.RECOVERY_COOLDOWN_MS
        assertEquals(30L * 60_000L, cooldown)

        assertTrue(DynamicServerDiscovery.isRecoveryDue(0L, 1_000L))
        assertTrue(DynamicServerDiscovery.isRecoveryDue(1_000L, 1_000L + cooldown))
        assertFalse(DynamicServerDiscovery.isRecoveryDue(1_000L, 1_000L + cooldown - 1))
        assertFalse(DynamicServerDiscovery.isRecoveryDue(1_000L, 1_000L + 1_000L))
        // A clock that moved backwards does not grant an early retry.
        assertFalse(DynamicServerDiscovery.isRecoveryDue(1_000_000L, 999_999L))
    }

    // ── HTML meta-tag shape (unchanged) ───────────────────────────────────

    @Test
    fun parseHtmlNodes_extractsPrimaryAndBackups() {
        val sampleHtml = """
            <!DOCTYPE html>
            <html>
            <head>
              <meta content="noindex, nofollow" name="robots"/>
              <meta content="http://92.4.95.252" name="radd-primary"/>
              <meta content="https://backup1.yourdomain.com" name="radd-backup-1"/>
              <meta content="https://backup2.yourdomain.com" name="radd-backup-2"/>
            </head>
            <body>ok</body>
            </html>
        """.trimIndent()

        val (primary, backups) = DynamicServerDiscovery.parseHtmlNodes(sampleHtml)

        assertEquals("http://92.4.95.252", primary)
        assertEquals(2, backups.size)
        assertEquals("https://backup1.yourdomain.com", backups[0])
        assertEquals("https://backup2.yourdomain.com", backups[1])
    }

    @Test
    fun parseHtmlNodes_handlesNameFirstMetaTags() {
        val sampleHtml = """
            <html>
            <head>
              <meta name='radd-primary' content='http://10.0.0.1:8080'/>
              <meta name='radd-backup-alpha' content='https://fallback.node.com'/>
            </head>
            </html>
        """.trimIndent()

        val (primary, backups) = DynamicServerDiscovery.parseHtmlNodes(sampleHtml)

        assertEquals("http://10.0.0.1:8080", primary)
        assertEquals(1, backups.size)
        assertEquals("https://fallback.node.com", backups[0])
    }

    @Test
    fun parseHtmlNodes_emptyOnIrrelevantHtml() {
        val (primary, backups) = DynamicServerDiscovery.parseHtmlNodes("<html><body>Nothing here</body></html>")
        assertEquals(null, primary)
        assertEquals(0, backups.size)
    }
}
