package com.raddstream.app.baselineprofile

import androidx.benchmark.macro.BaselineProfileRule
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Feel-instant S7 — Baseline Profile generator: the Startup Profile
 * covering Home → Search → Detail.
 *
 * What it collects:
 * - `startup`: cold start to the first Home paint. This is the profile
 *   the system uses before the app has ever run — the code the dex
 *   pre-compiles so the first frame lands fast.
 * - `homeSearchDetail`: the same startup, then the critical journey —
 *   Search tab, type a query, open the first result's Detail, back to
 *   Home. Both tests set `includeInStartupProfile = true`, so both
 *   journeys' code lands in the Startup Profile, not just the launch.
 *
 * How to generate (on a real device or emulator — never in CI, which has
 * no device and only compiles this module):
 * 1. Install a release build on the device, with the server already
 *    saved, the region answered, and the disclaimer accepted — the
 *    generator does not set up an account or accept terms for you (see
 *    below). A signed-in account is not required.
 * 2. `./gradlew :app:generateBaselineProfile` (or
 *    `:app:generateNonMinifiedReleaseBaselineProfile` for the
 *    non-minified variant the plugin adds). The generated profile lands
 *    in `app/src/main/generated/baselineProfiles/baseline-prof.txt` —
 *    commit it; regenerate per release, because a profile from an older
 *    build silently covers code that no longer exists.
 * 3. Measure before/after on the same device with a Macrobenchmark
 *    startup run (not written here — a benchmark run is a device run,
 *    and this slice has no device). The spec's target is ≥ 20%
 *    cold-start improvement vs the pre-S7 baseline, sub-1,000 ms on
 *    mid-range hardware. Do not quote a number until a device has
 *    produced one.
 *
 * First-run sheets: the generator skips the analytics question ("Skip
 * for now" answers nothing — it only hides the sheet for this launch),
 * but it deliberately does NOT tap the disclaimer ("I understand").
 * Accepting terms is the viewer's act; if the disclaimer is showing,
 * the run fails with a message telling the operator to accept it once by
 * hand and re-run. A fresh install that has never seen Home must be
 * opened once by hand before generating.
 *
 * The journey depends on the live server: it searches for [SEARCH_QUERY]
 * ("One Piece", a title the catalog carries) and taps the first result
 * whose tile announces that title. A device with no server, or a server
 * with no such title, fails the run loudly instead of profiling an
 * empty search.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun startup() = baselineProfileRule.collect(
        packageName = APP_PACKAGE,
        includeInStartupProfile = true,
    ) {
        startActivityAndWait()
        waitForHome()
        dismissAnalyticsSheetIfPresent()
    }

    @Test
    fun homeSearchDetail() = baselineProfileRule.collect(
        packageName = APP_PACKAGE,
        includeInStartupProfile = true,
    ) {
        startActivityAndWait()
        waitForHome()
        dismissAnalyticsSheetIfPresent()

        // Search tab — the bottom-nav tab's spoken name (NavGraph sets
        // contentDescription = label on every tab).
        val searchTab = device.findObject(By.desc("Search"))
            ?: error("Baseline profile: no Search tab — is Home the start destination?")
        searchTab.click()

        // Search field — its label is the hint text.
        val field = device.findObject(By.text("Search titles or cast"))
            ?: error("Baseline profile: no search field on the Search screen")
        field.click()
        val editText = device.findObject(By.clazz("android.widget.EditText"))
            ?: error("Baseline profile: search field did not take focus")
        editText.setText(SEARCH_QUERY)

        // First result — PosterCard announces its title as its content
        // description, so the result is found by the title it carries.
        val result = device.wait(
            Until.findObject(By.descContains(SEARCH_QUERY)),
            RESULT_TIMEOUT_MS,
        ) ?: error("Baseline profile: no result for \"$SEARCH_QUERY\" — is the server configured?")
        result.click()
        device.waitForIdle()

        // Back to Home: the Detail half of the journey is the open, and
        // the return is the back stack the viewer actually uses.
        device.pressBack()
        device.waitForIdle()
    }

    /**
     * Home is up when the bottom bar is: the "Home" tab's spoken name is
     * present once the start destination has painted. The disclaimer is
     * checked first — if it is showing, the run stops with instructions
     * instead of tapping through terms nobody read.
     */
    private fun MacrobenchmarkScope.waitForHome() {
        if (device.findObject(By.text("I understand")) != null) {
            error(
                "Baseline profile: the disclaimer is showing — accept it once " +
                    "by hand on this device, then re-run the generator.",
            )
        }
        if (!device.wait(Until.hasObject(By.desc("Home")), HOME_TIMEOUT_MS)) {
            error("Baseline profile: Home did not appear — is a server saved?")
        }
    }

    /**
     * The analytics question ("Skip for now") answers nothing — it only
     * hides the sheet for this launch — so dismissing it here is benign.
     * Absent on a device that already answered: `findObject` without a
     * wait returns null at once, so this is a no-op there.
     */
    private fun MacrobenchmarkScope.dismissAnalyticsSheetIfPresent() {
        device.findObject(By.text("Skip for now"))?.click()
        device.waitForIdle()
    }

    companion object {
        private const val APP_PACKAGE = "com.raddstream.app"
        private const val SEARCH_QUERY = "One Piece"
        private const val HOME_TIMEOUT_MS = 10_000L
        private const val RESULT_TIMEOUT_MS = 15_000L
    }
}
