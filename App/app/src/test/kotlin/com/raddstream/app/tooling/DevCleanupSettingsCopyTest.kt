package com.raddstream.app.tooling

import com.raddstream.app.brand.Brand
import com.raddstream.app.sync.Refusal
import com.raddstream.app.sync.SyncReport
import com.raddstream.app.ui.screens.aboutBuildDetailsLine
import com.raddstream.app.ui.screens.aboutVersionLine
import com.raddstream.app.viewmodel.AccountCardCopy
import com.raddstream.app.viewmodel.AdultPolicy
import com.raddstream.app.viewmodel.SettingsViewModel
import com.raddstream.app.viewmodel.refusalSentence
import com.raddstream.app.viewmodel.syncSentence
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dev-cleanup (Nov 2026): the Settings/Account plain-words fix, pinned in one place.
 *
 * A normal viewer must see nothing developer-facing on these screens: no "server", no
 * "sync key", no stored counts, no addresses or ports, no version in painted text. The
 * sentences below are the replacements, and the source scans are the guard against the
 * old words creeping back — every refusal is paired with the new wording that must still
 * be present, so a deleted screen fails here instead of passing by accident.
 */
class DevCleanupSettingsCopyTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private fun source(path: String): File = File(repoRoot, path).also {
        assertTrue("the audit has nothing to read at $path", it.isFile)
    }

    private fun mainSource(relative: String): String =
        source("App/app/src/main/kotlin/com/raddstream/app/$relative").readText()

    // ── syncSentence: success names no count ─────────────────────────────────

    @Test
    fun `a finished sync says the list is up to date and names no count`() {
        val sentence = syncSentence(SyncReport(completed = true), paused = false, signedIn = true)
        assertEquals("Your saved list is up to date on this device.", sentence)
    }

    @Test
    fun `a paused sync says so without a count, signed in or not`() {
        assertEquals(
            "Signed in, but syncing is paused until you restart the app.",
            syncSentence(SyncReport(completed = false), paused = true, signedIn = true),
        )
        assertEquals(
            "Syncing is paused until you restart the app.",
            syncSentence(SyncReport(completed = false), paused = true, signedIn = false),
        )
    }

    @Test
    fun `an unfinished first sync says nothing was lost and will try again`() {
        assertEquals(
            "Signed in on this device, but the first sync did not finish. Nothing was lost: " +
                "it will try again when you open this screen.",
            syncSentence(SyncReport(completed = false), paused = false, signedIn = true),
        )
        assertEquals(
            "Sync did not finish. Nothing was lost: your saved list is still on this device.",
            syncSentence(SyncReport(completed = false), paused = false, signedIn = false),
        )
    }

    @Test
    fun `pulled and unparsable titles get plain sentences, never counts of rows`() {
        val sentence = syncSentence(
            SyncReport(completed = true, favoritesPulled = 2, favoritesUnknownHere = 1, favoritesUnnamable = 1),
            paused = false,
            signedIn = true,
        )
        assertTrue(sentence, sentence.startsWith("Your saved list is up to date on this device."))
        assertTrue(sentence, sentence.contains("2 saved titles from your other device are now on this device too."))
        assertTrue(
            sentence,
            sentence.contains(
                "Some saved titles from your other device aren't on this phone yet — open one to add it here.",
            ),
        )
        assertTrue(
            sentence,
            sentence.contains("Some titles you saved here can't move to your other devices yet."),
        )
        assertFalse("no row count survives in the sentence", sentence.contains("saves sent"))
        assertFalse("no row count survives in the sentence", sentence.contains("pulled down"))
    }

    // ── refusalSentence: every refusal is plain words ────────────────────────

    @Test
    fun `every refusal names the fix in plain words and changes nothing`() {
        assertEquals(
            "Nothing to sign in to yet — connect to a catalog first.",
            refusalSentence(Refusal.NO_SERVER),
        )
        assertEquals(
            "Enter the 16-character account code from the other device. A mobile number is not a code.",
            refusalSentence(Refusal.WEAK_KEY),
        )
        assertEquals(
            "That code wasn't recognised. Nothing was changed on this device.",
            refusalSentence(Refusal.REJECTED),
        )
        assertEquals(
            "Syncing is paused until you restart the app.",
            refusalSentence(Refusal.THROTTLED),
        )
        assertEquals(
            "Couldn't reach the catalog. Nothing was changed on this device.",
            refusalSentence(Refusal.OFFLINE),
        )
        assertEquals(
            "That code is no longer valid here, so this device is signed out.",
            refusalSentence(Refusal.SIGNED_OUT),
        )
        for (reason in Refusal.entries) {
            val sentence = refusalSentence(reason)
            assertFalse("a refusal must not say server: $sentence", sentence.contains("server", ignoreCase = true))
            assertFalse("a refusal must not say sync key: $sentence", sentence.contains("sync key", ignoreCase = true))
        }
    }

    // ── revealWarning: the warning keeps its meaning, loses the vocabulary ───

    @Test
    fun `the reveal warning names the risk in plain words on either transport`() {
        val plain = SettingsViewModel.revealWarning("http://192.168.1.4:8080")
        assertTrue(plain, plain.startsWith("This is the only time the account code is shown."))
        assertTrue(plain, plain.contains("anyone on this network could read it"))
        assertTrue(plain, plain.contains("Losing it means starting a new list."))
        val secured = SettingsViewModel.revealWarning("https://raddstream.duckdns.org")
        assertTrue(secured, secured.startsWith("This is the only time the account code is shown."))
        assertTrue(secured, secured.contains("anyone who reads it can open your list."))
        assertFalse("the secured warning must not claim the network can read it", secured.contains("could read it"))
        for (warning in listOf(plain, secured)) {
            assertFalse("the warning must not say server: $warning", warning.contains("server", ignoreCase = true))
            assertFalse("the warning must not say sync key: $warning", warning.contains("sync key", ignoreCase = true))
        }
    }

    // ── AccountCardCopy: the trial and link warnings, plain words ────────────

    @Test
    fun `the trial and link copy keeps its meaning in plain words`() {
        assertTrue(AccountCardCopy.TRIAL_HONESTY, AccountCardCopy.TRIAL_HONESTY.contains("per phone"))
        assertTrue(AccountCardCopy.TRIAL_HONESTY, AccountCardCopy.TRIAL_HONESTY.contains("don't lock your phone"))
        assertTrue(
            AccountCardCopy.LINK_TRANSPORT_WARNING,
            AccountCardCopy.LINK_TRANSPORT_WARNING.contains("could read what you type here"),
        )
        assertTrue(
            AccountCardCopy.LINK_TRANSPORT_WARNING,
            AccountCardCopy.LINK_TRANSPORT_WARNING.contains("don't use a PIN you use anywhere else"),
        )
        assertEquals(
            "Don't use a PIN you use anywhere else.",
            AccountCardCopy.LINK_TRANSPORT_WARNING_SECURED,
        )
        assertTrue(
            AccountCardCopy.LINK_PIN_MEANING,
            AccountCardCopy.LINK_PIN_MEANING.contains("cannot turn a number into an account code"),
        )
        assertTrue(
            AccountCardCopy.LINK_WHICH_SERVER,
            AccountCardCopy.LINK_WHICH_SERVER.contains("catalog shown in the card above"),
        )
        for (sentence in listOf(
            AccountCardCopy.TRIAL_HONESTY,
            AccountCardCopy.LINK_TRANSPORT_WARNING,
            AccountCardCopy.LINK_TRANSPORT_WARNING_SECURED,
            AccountCardCopy.LINK_PIN_MEANING,
            AccountCardCopy.LINK_WHICH_SERVER,
        )) {
            assertFalse("trial/link copy must not say server: $sentence", sentence.contains("server", ignoreCase = true))
            assertFalse("trial/link copy must not say sync key: $sentence", sentence.contains("sync key", ignoreCase = true))
        }
    }

    // ── hiddenNotice and the version line ────────────────────────────────────

    @Test
    fun `hidden titles are no longer available, never no longer offered by a server`() {
        assertNull(AdultPolicy.hiddenNotice(0))
        assertEquals("1 saved title is no longer available.", AdultPolicy.hiddenNotice(1))
        assertEquals("3 saved titles are no longer available.", AdultPolicy.hiddenNotice(3))
    }

    @Test
    fun `the painted version line is the version name only`() {
        assertEquals("Version ${Brand.versionName}", aboutVersionLine())
        val details = aboutBuildDetailsLine()
        assertTrue(details, details.contains(Brand.versionCode.toString()))
        assertTrue(details, details.endsWith(Brand.schemeName))
    }

    // ── the old words are gone from the Settings/Account files ───────────────

    @Test
    fun `the old Settings and Account words are gone`() {
        val settingsScreen = mainSource("ui/screens/SettingsScreen.kt")
        val settingsViewModel = mainSource("viewmodel/SettingsViewModel.kt")
        val setupScreen = mainSource("ui/screens/SetupScreen.kt")
        val maintenanceScreen = mainSource("ui/screens/MaintenanceScreen.kt")
        val profileScreen = mainSource("ui/screens/ProfileScreen.kt")
        val disclaimerDialog = mainSource("ui/screens/DisclaimerDialog.kt")
        val accountViewModel = mainSource("viewmodel/AccountViewModel.kt")
        val adultPolicy = mainSource("viewmodel/AdultPolicy.kt")
        val syncContract = mainSource("sync/SyncContract.kt")
        val bridge = mainSource("player/PlayerPolicyBridge.kt")

        val goneFromSettings = listOf(
            "Your sync key", "Sync key", "Create a random key", "Test & Save Connection",
            "Server URL", "No catalog server set.", "Storage & cache", "Clear poster images",
            "Done showing this key", "Use a different server", "Server address",
            "Listening on ", "@ \$shownEmail", "no longer offered by this server",
            "Sync only ever names a title", "connectionNotice",
        )
        for (phrase in goneFromSettings) {
            assertFalse("SettingsScreen still says $phrase", settingsScreen.contains(phrase))
        }
        assertFalse("the Sync now button is back", settingsScreen.contains("::syncNow"))
        assertFalse("the cache card's state is back", settingsScreen.contains("posterCacheBytes"))

        val goneFromViewModel = listOf(
            "connectionNotice", "This server stores", "in the clear", "per install",
            "cannot turn a number into a key", "Server card",
            "Do not enter a PIN you also use somewhere else.", "fun syncNow()",
        )
        for (phrase in goneFromViewModel) {
            assertFalse("SettingsViewModel still says $phrase", settingsViewModel.contains(phrase))
        }

        val goneFromSetup = listOf("Server address", "Test connection", "Use a different server")
        for (phrase in goneFromSetup) {
            assertFalse("SetupScreen still says $phrase", setupScreen.contains(phrase))
        }
        assertFalse(maintenanceScreen.contains("Use a different server"))
        assertFalse(profileScreen.contains("sync key"))
        assertFalse(profileScreen.contains("Last seen:"))
        assertFalse(profileScreen.contains("key the server recognises"))
        assertFalse(disclaimerDialog.contains("Version \${disclaimer.version}"))
        assertFalse(accountViewModel.contains("Could not reach the server"))
        assertFalse(adultPolicy.contains("offered by this server"))
        assertFalse("the preview still shows the first six characters", syncContract.contains("take(6)"))
        assertFalse(bridge.contains("Requested \$requested%"))
        assertFalse(bridge.contains("no software gain is available on this build"))

        // Positive controls: the new words must actually be there, or the
        // refusals above pass on files that render nothing.
        assertTrue(settingsScreen.contains("Account code"))
        assertTrue(settingsScreen.contains("Create account"))
        assertTrue(settingsScreen.contains("Your catalog"))
        assertTrue(settingsScreen.contains("Catalog address"))
        assertTrue(settingsScreen.contains("No catalog connected yet."))
        assertTrue(settingsScreen.contains("Sharing on — devices on your Wi-Fi can play"))
        assertTrue(settingsScreen.contains("What this app is, and what it is not."))
        assertTrue(settingsScreen.contains("Saved on this device."))
        assertTrue(settingsScreen.contains("aboutBuildDetailsLine()"))
        assertTrue(setupScreen.contains("Use your own catalog"))
        assertTrue(setupScreen.contains("Catalog address"))
        assertTrue(maintenanceScreen.contains("Use a different catalog"))
        assertTrue(profileScreen.contains("Last opened:"))
        assertTrue(profileScreen.contains("an account is just an account code"))
        assertTrue(syncContract.contains("takeLast(4)"))
        // MX idea #8: the ceiling sentence is gone on purpose — the
        // software gain stage applies the excess now, so the caption
        // states the applied total instead of pointing at the device volume.
        assertTrue(bridge.contains("Volume \${appliedTotalPercent(level, userVolume)}%"))
    }
}
