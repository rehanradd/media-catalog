package com.raddstream.app.ui.navigation

/**
 * Where a cold start lands, as a pure rule — no store, no network, no clock.
 *
 * Popup cleanup (26 Nov 2026): the Setup screen is no longer a welcome
 * gate. A fresh install used to be fail-closed — a shipped default that
 * did not answer the 1 s silent probe opened Setup, so a normal viewer on
 * slow Wi-Fi met a "Connect" screen before they could watch anything.
 * The rule is fail-open now: a dead, slow, or never-probed default routes
 * to Home (or Region, when the region question is still unanswered), the
 * connection retries in the background from Home's own catalog load, and
 * if it stays down Home shows its existing inline offline notice /
 * ErrorState ("Couldn't reach the catalog" + Retry + "Change server
 * address"). Setup itself still exists as a destination, reachable from
 * Settings, from Home's error state, and from the maintenance notice —
 * that is where a custom address is entered, never on the way in.
 *
 * `regionUnanswered` is the only input left: whether this install has
 * been through the region screen. The saved-url / probe inputs are gone
 * with the gate — nothing about the server decides the first route any
 * more, so there is nothing for a slow network to fail.
 */
enum class StartDestination {
    Region,
    Home,
}

fun resolveStartDestination(
    regionUnanswered: Boolean,
): StartDestination = when {
    regionUnanswered -> StartDestination.Region
    else -> StartDestination.Home
}
