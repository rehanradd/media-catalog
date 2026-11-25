package com.raddstream.app.viewmodel

import com.raddstream.app.player.NoRewardedAdProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * APP-03: the "rewarded" VIP pass must never be grantable without an ad.
 *
 * The free tap is gone: the offer renders only when a rewarded network is bound AND the
 * operator published a zone, the grant lives behind the provider's completion callback,
 * and [VipViewModel.grantRewardedVip] is private so no screen can call it. These tests pin
 * all three halves — the state derivation (plain JUnit, no ViewModel needed), the
 * no-network binding, and the source contract (same file-reading style as
 * VipCancelContractTest: the wiring IS the guarantee, so the wiring is what is read).
 *
 * Plain JUnit4 on the JVM: no Robolectric, no Android runtime, no network.
 */
class RewardedVipPassGatingTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(File(dir, "Fast_Fetch"), "accounts_api.py").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("Fast_Fetch/accounts_api.py not found above ${File("").absolutePath}")
    }

    private fun mainSource(relative: String): String =
        File(File(repoRoot, "App/app/src/main/kotlin/com/raddstream/app"), relative).readText()

    private val vipViewModelSource: String by lazy { mainSource("viewmodel/VipViewModel.kt") }
    private val vipUpgradeScreenSource: String by lazy { mainSource("ui/screens/VipUpgradeScreen.kt") }
    private val detailViewModelSource: String by lazy { mainSource("viewmodel/DetailViewModel.kt") }
    private val adModuleSource: String by lazy { mainSource("di/AdModule.kt") }
    private val rewardedProviderSource: String by lazy { mainSource("player/RewardedAdProvider.kt") }

    // ── State derivation: both halves, or no offer ───────────────────────────

    @Test
    fun `a fresh state offers no rewarded pass`() {
        assertFalse(VipUiState().rewardedAvailable)
        assertFalse(VipUiState().hasRewardedZone)
    }

    @Test
    fun `a bound network alone is not an offer without a published zone`() {
        val state = VipUiState(rewardedProviderAvailable = true, rewardedZoneId = "")
        assertFalse(state.hasRewardedZone)
        assertFalse(state.rewardedAvailable)
    }

    @Test
    fun `a published zone alone is not an offer without a bound network`() {
        val state = VipUiState(rewardedProviderAvailable = false, rewardedZoneId = "zone-1")
        assertTrue(state.hasRewardedZone)
        assertFalse(state.rewardedAvailable)
    }

    @Test
    fun `a blank zone is no zone, however many spaces it holds`() {
        val state = VipUiState(rewardedProviderAvailable = true, rewardedZoneId = "   ")
        assertFalse(state.hasRewardedZone)
        assertFalse(state.rewardedAvailable)
    }

    @Test
    fun `network plus zone is the only state that offers the pass`() {
        val state = VipUiState(rewardedProviderAvailable = true, rewardedZoneId = "zone-1")
        assertTrue(state.hasRewardedZone)
        assertTrue(state.rewardedAvailable)
    }

    // ── No-network binding: unavailable, and never rewards ───────────────────

    @Test
    fun `the shipped provider is unavailable`() {
        assertFalse(NoRewardedAdProvider().isAvailable)
    }

    @Test
    fun `the shipped provider answers unavailable and never rewards`() {
        var rewarded = false
        var unavailable = false
        NoRewardedAdProvider().showRewarded(
            zoneId = "zone-1",
            onRewarded = { rewarded = true },
            onUnavailable = { unavailable = true },
        )
        assertFalse("no network exists, so no completion may ever be reported", rewarded)
        assertTrue(unavailable)
    }

    @Test
    fun `the shipped provider answers unavailable even with no zone`() {
        var unavailable = false
        NoRewardedAdProvider().showRewarded(
            zoneId = "",
            onRewarded = { error("no network exists, so no completion may ever be reported") },
            onUnavailable = { unavailable = true },
        )
        assertTrue(unavailable)
    }

    // ── Source contract: the wiring that makes the free tap impossible ────────

    @Test
    fun `the screen never calls the grant function`() {
        assertFalse(
            "VipUpgradeScreen must not name grantRewardedVip: the tap goes through " +
                "requestRewardedPass and the ad, never straight to the grant",
            vipUpgradeScreenSource.contains("grantRewardedVip"),
        )
    }

    @Test
    fun `the screen renders the rewarded section only when an ad can play`() {
        assertTrue(
            "the section must sit behind the rewardedAvailable guard (or a live pass's countdown)",
            vipUpgradeScreenSource.contains("if (state.rewardedAvailable"),
        )
        assertTrue(
            "the tap must route through the ad-first entry point",
            vipUpgradeScreenSource.contains("onActivatePass = viewModel::requestRewardedPass"),
        )
    }

    @Test
    fun `the screen no longer promises a pass with no ad`() {
        assertFalse(vipUpgradeScreenSource.contains("No ad plays when you tap this"))
        assertFalse(vipUpgradeScreenSource.contains("Activate 4-Hour VIP Pass"))
        assertTrue(vipUpgradeScreenSource.contains("Watch Ad for 4-Hour VIP Pass"))
    }

    @Test
    fun `the grant is private and reachable only through the ad-first entry point`() {
        assertTrue(
            "grantRewardedVip must be private: a private function cannot be tapped",
            vipViewModelSource.contains("private fun grantRewardedVip"),
        )
        assertTrue(vipViewModelSource.contains("fun requestRewardedPass()"))
        assertTrue(
            "the grant must happen only in the provider's completion callback",
            vipViewModelSource.contains("onRewarded = { grantRewardedVip() }"),
        )
        assertTrue(
            "a close or failure must answer in words, never grant",
            vipViewModelSource.contains("The ad didn't finish, so no pass was activated."),
        )
        assertEquals(
            "exactly one call to the ViewModel's own grant may exist, inside requestRewardedPass",
            1,
            // The repository's own grant (settingsRepository.grantRewardedVip) is a
            // different function; only the bare call below is this ViewModel granting.
            vipViewModelSource.split("grantRewardedVip()").size - 1,
        )
    }

    @Test
    fun `the zone id is folded from remote config into state`() {
        assertTrue(
            vipViewModelSource.contains("rewardedZoneId = config.ads.monetagRewardedZoneId"),
        )
    }

    @Test
    fun `the no-network provider is the bound production binding`() {
        assertTrue(adModuleSource.contains("fun provideRewardedAdProvider()"))
        assertTrue(adModuleSource.contains("NoRewardedAdProvider()"))
        assertTrue(rewardedProviderSource.contains("interface RewardedAdProvider"))
        assertTrue(rewardedProviderSource.contains("class NoRewardedAdProvider"))
    }

    @Test
    fun `Detail no longer collects the rewarded expiry it never read`() {
        // N-05: the field was written on every pass change and read nowhere — the pass
        // gates nothing on Detail, so the collection was dead state.
        assertFalse(detailViewModelSource.contains("rewardedVipExpiresAt"))
    }
}
