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
 * Posters per sync run — the first 100 trending titles' art, in popularity
 * order. A tile-sized poster is ~30–60 KB, so 100 posters are ~3–6 MB on
 * the overnight unmetered window, and the morning's first offline search
 * hit shows art, not shimmer. The titles are banked first either way; the
 * posters are the warm on top of the index.
 */
internal const val SYNC_POSTER_WARM_MAX: Int = 100

/**
 * The overnight window, shared by both preload workers: unmetered network
 * only (never mobile data), charging, idle, battery not low, storage not
 * low. The trade-off is stated, not hidden: a phone that is rarely
 * charging on Wi-Fi overnight simply never warms — the app then behaves
 * exactly as it did before S8, because the warm is an optimisation, never
 * a requirement. The constraints are the guarantee, not a preference:
 * without UNMETERED the worker could burn the viewer's data plan, and
 * without battery-not-low it could spend the last of the battery.
 *
 * `setRequiresDeviceIdle` needs API 23. The app's minSdk is 23 (raised
 * by the 2026-11-23 library bump — Media3 1.11.1/NextLib require it), so
 * the call is unconditional now: the old SDK guard is gone, and with it
 * the unit-test fragility it carried (in a plain-JUnit test
 * Build.VERSION.SDK_INT comes from the android.jar stub, whose value
 * changed with compileSdk 36 and made the guard false — CI 45103781080).
 * On an API 21/22 device the warm ran on charging + Wi-Fi alone; those
 * devices can no longer install the app, so that path is moot.
 */
internal fun catalogPreloadConstraints(): Constraints {
    val builder = Constraints.Builder()
        .setRequiredNetworkType(NetworkType.UNMETERED)
        .setRequiresCharging(true)
        .setRequiresBatteryNotLow(true)
        .setRequiresStorageNotLow(true)
        .setRequiresDeviceIdle(true)
    return builder.build()
}

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
