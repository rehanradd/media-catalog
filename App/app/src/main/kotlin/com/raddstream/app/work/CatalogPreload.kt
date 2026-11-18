package com.raddstream.app.work

import androidx.work.Constraints
import androidx.work.NetworkType
import com.raddstream.app.viewmodel.HomeSection
import com.raddstream.app.viewmodel.initialHomeSections
import com.raddstream.app.viewmodel.regionalHomeSections

/** First page size for a warmed rail — Home's own rail page (loadSection). */
internal const val WARM_RAIL_PAGE_LIMIT: Int = 18

/** One sync page — the paging layer's own page size (S6). */
internal const val SYNC_PAGE_LIMIT: Int = 20

/**
 * Titles per sync run — the S3c size math: top 5,000 titles ≈ 0.6 MB raw /
 * ~1–1.5 MB indexed, inside the ≤ 2 MB/day sync budget (A11). The 20k
 * background tier waits on the dedicated catalog-index endpoint (§5.2);
 * paging the browse route that far would be 1,000 requests a day.
 */
internal const val SYNC_MAX_TITLES_PER_RUN: Int = 5_000

/**
 * The overnight window, shared by both preload workers: unmetered network
 * only (never mobile data), charging, idle, battery not low, storage not
 * low. The trade-off is stated, not hidden: a phone that is rarely
 * charging on Wi-Fi overnight simply never warms — the app then behaves
 * exactly as it did before S8, because the warm is an optimisation, never
 * a requirement. The constraints are the guarantee, not a preference:
 * without UNMETERED the worker could burn the viewer's data plan, and
 * without battery-not-low it could spend the last of the battery.
 */
internal fun catalogPreloadConstraints(): Constraints = Constraints.Builder()
    .setRequiredNetworkType(NetworkType.UNMETERED)
    .setRequiresCharging(true)
    .setRequiresDeviceIdle(true)
    .setRequiresBatteryNotLow(true)
    .setRequiresStorageNotLow(true)
    .build()

/**
 * The rails the warm worker fetches: Home's own static rails, minus the
 * two kinds a background worker cannot fetch — panel-managed rails (the
 * operator's feed already owns their rows) and related rails (seeded from
 * the viewer's Continue Watching, which the worker does not have). The
 * list is a parameter so a test can pin the filter without Home.
 */
internal fun warmableSections(
    sections: List<HomeSection> = initialHomeSections() + regionalHomeSections(),
): List<HomeSection> = sections.filter { !it.panelManaged && it.relatedWorkId == null }
