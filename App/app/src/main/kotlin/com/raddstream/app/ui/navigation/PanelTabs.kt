package com.raddstream.app.ui.navigation

/**
 * The bottom bar's tabs: Home · Search · Downloads · Profile, in that
 * order, locked in code (P0-1, audit B-1/B-2/C-1).
 *
 * The order is locked — the operator's panel `tabs` key can no longer
 * reorder the bar or resurrect a removed tab. Whatever the panel lists,
 * the bar shows this set: an operator list naming "settings",
 * "live_tv" or "my_list" (the old tab set) changes nothing, and an
 * operator list in a different order changes nothing either. Spatial
 * memory ("Home is always bottom-left") is the whole point of a tab
 * bar; a remote list must never move the chrome (audit B-4). The full
 * removal of the `tabs` key is P1-1; until then [resolve] accepts the
 * remote list and ignores it, so the callers keep compiling.
 *
 * One thing the panel still controls: Search, through its feature flag
 * ([resolve]'s `searchEnabled`). The flag is the operator's kill-switch
 * for Search, and the tab list must not resurrect a feature the flag
 * turned off — that rule survives the lock.
 *
 * Kids mode (NavGraph) drops Downloads from this set, leaving
 * Home / Search / Profile.
 *
 * An empty or all-unknown list means the same as any other list now:
 * the locked set. The function stays pure so the lock can be tested
 * without a server.
 */
object PanelTabs {

    /** The locked tab set, in locked order: Home · Search · Downloads · Profile. */
    val DEFAULT: List<String> = listOf("home", "search", "downloads", "profile")

    fun resolve(remote: List<String>, searchEnabled: Boolean): List<String> {
        // P0-1: `remote` is intentionally unread — the order is locked in
        // code and no operator entry may add, remove or reorder a tab.
        // (P1-1 removes the key and this parameter with it.)
        return if (searchEnabled) DEFAULT else DEFAULT - "search"
    }
}
