package com.raddstream.app.ui.navigation

/**
 * The bottom bar's tabs, as the operator ordered them in the panel's `tabs`
 * tuning key, resolved against what this build can actually show.
 *
 * The operator's list is authoritative: a tab it does not name does not show,
 * and the order it names is the order the bar shows. Three rules sit above it
 * and cannot be overridden from the panel, because the app breaks without
 * them: Home is always first (a bar with no way back to Home strands the
 * viewer), Settings is always last (a mid-list Settings reads as a bug — the
 * live panel once put Live TV after it; home review, Nov 2026), and Search
 * only shows when its feature flag is on (the flag is the operator's
 * kill-switch for Search, and the tab list must not resurrect a feature the
 * flag turned off).
 *
 * An empty or all-unknown list means "the deployment never set tabs" — an
 * older server, an unwired panel — and the bar falls back to the five tabs
 * this build has always shipped, in their shipped order. The function is
 * pure so the fallback can be tested without a server.
 */
object PanelTabs {

    /** The five tabs this build has always shipped, in their shipped order. */
    val DEFAULT: List<String> = listOf("home", "live_tv", "search", "my_list", "settings")

    private val KNOWN: Set<String> = DEFAULT.toSet()

    fun resolve(remote: List<String>, searchEnabled: Boolean): List<String> {
        val named = remote
            .map { it.trim().lowercase() }
            .filter { it in KNOWN }
            .distinct()
        val ordered = if (named.isEmpty()) DEFAULT else (listOf("home") + named).distinct()
        // Settings is always last, whatever the operator listed: a mid-list
        // Settings is a bug the bar wears in public (home review, Nov 2026 —
        // the live panel put Live TV after it). Home stays first; only
        // Settings moves.
        val settled = (ordered - "settings") + listOf("settings").filter { it in ordered }
        return if (searchEnabled) settled else settled.filter { it != "search" }
    }
}
