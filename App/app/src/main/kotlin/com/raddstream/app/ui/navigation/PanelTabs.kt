package com.raddstream.app.ui.navigation

/**
 * The bottom bar's tabs: Home · Search · Downloads · Profile, in that
 * order, locked in code (P0-1, audit B-1/B-2/C-1).
 *
 * There is no operator tab list any more (P1-1, audit B-4): the panel's
 * `tabs` tuning key is gone from the app entirely. The server may still
 * send a `tabs` field on the remote-config body, but nothing reads it —
 * the body decodes without the field (Gson ignores unknown fields, the
 * same retirement the `link_shorteners` key got in VipRemoteConfig),
 * nothing mirrors it into DataStore, and nothing passes it to the bar.
 * Spatial memory ("Home is always bottom-left") is the whole point of a
 * tab bar; a remote list must never move the chrome.
 *
 * One thing the panel still controls: Search, through its feature flag
 * ([resolve]'s `searchEnabled`). The flag is the operator's kill-switch
 * for Search, and no tab list exists any more that could resurrect a
 * feature the flag turned off — that rule survives the lock.
 *
 * Kids mode (NavGraph) drops Downloads from this set, leaving
 * Home / Search / Profile.
 *
 * The function stays pure so the lock can be tested without a server.
 */
object PanelTabs {

    /** The locked tab set, in locked order: Home · Search · Downloads · Profile. */
    val DEFAULT: List<String> = listOf("home", "search", "downloads", "profile")

    fun resolve(searchEnabled: Boolean): List<String> {
        return if (searchEnabled) DEFAULT else DEFAULT - "search"
    }
}
