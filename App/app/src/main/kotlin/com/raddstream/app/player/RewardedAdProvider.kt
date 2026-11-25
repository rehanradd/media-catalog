package com.raddstream.app.player

/**
 * The one rewarded-ad surface: a viewer watches an ad, the network reports a completed view,
 * and only then does the app grant the pass.
 *
 * The shape mirrors [AdInventory] deliberately: the question "can this be filled" is answered
 * through an interface, and the production binding until an SDK exists is the one that says no.
 * A rewarded offer whose tap grants a pass with no ad behind it is a monetisation gate giving
 * itself away (APP-03), so the screen renders the offer only when [isAvailable] is true AND the
 * operator published a zone for it — and the grant itself lives behind [showRewarded]'s
 * completion callback, never behind a tap.
 *
 * The caller never hands an Activity in: the implementation resolves the foreground Activity
 * itself (a Monetag binding tracks it from the Application's lifecycle callbacks), so a
 * ViewModel can drive this without holding UI — and so this contract stays unit-testable on
 * the plain JVM with no Robolectric and no Android runtime.
 */
interface RewardedAdProvider {
    /** True only when a real rewarded network is bound and able to show an ad. */
    val isAvailable: Boolean

    /**
     * Show a rewarded ad for [zoneId].
     *
     * [onRewarded] runs only after the network reports a completed view — that callback, and
     * nothing else, is what grants the pass. [onUnavailable] runs for every other outcome: no
     * fill, a load failure, a close before completion, an error. A close without a completion
     * is never a reward, however far into the ad the viewer got.
     */
    fun showRewarded(
        zoneId: String,
        onRewarded: () -> Unit,
        onUnavailable: () -> Unit,
    )
}

/**
 * The production binding until a rewarded SDK is wired: no network, no ad, no reward.
 *
 * Wiring a real network replaces this binding — and only this binding. The screen, the
 * ViewModel and the grant path already route through [RewardedAdProvider], so the SDK lane
 * adds its implementation (Monetag rewarded interstitial, zone id from remote config) and
 * swaps the `@Provides` in [com.raddstream.app.di.AdModule]; no call site changes.
 * Choosing that network and creating its dashboard zone is the owner's call, not this
 * binding's: this class exists so the choice can be made later without the app ever
 * shipping a free-grant tap in the meantime.
 */
class NoRewardedAdProvider : RewardedAdProvider {
    override val isAvailable: Boolean = false

    override fun showRewarded(
        zoneId: String,
        onRewarded: () -> Unit,
        onUnavailable: () -> Unit,
    ) {
        onUnavailable()
    }
}
