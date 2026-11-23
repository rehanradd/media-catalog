package com.raddstream.app.work

import androidx.work.NetworkType
import com.raddstream.app.viewmodel.HomeSection
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Feel-instant S8: the preload contract, pinned without a device — the
 * overnight window is the safety case (a constraint silently dropped is
 * either a warm that burns mobile data or one that never runs), and the
 * rail filter is the scope (a worker must never fetch a rail it cannot
 * serve). Robolectric, not plain JUnit4: WorkManager's
 * Constraints.Builder.build() ANDs requiresDeviceIdle with
 * Build.VERSION.SDK_INT >= 23, and under compileSdk 36 the plain-JUnit
 * android.jar stub reports SDK_INT = 0, so the idle flag is silently
 * dropped and the "warm must wait for idle" assert fails even though the
 * function calls setRequiresDeviceIdle(true). Robolectric provides a real
 * SDK_INT (targetSdk 35), so the flag survives build().
 */
@RunWith(RobolectricTestRunner::class)
class CatalogPreloadTest {

    @Test
    fun `constraints are the overnight window`() {
        val constraints = catalogPreloadConstraints()
        assertEquals(NetworkType.UNMETERED, constraints.requiredNetworkType)
        assertTrue("warm must wait for charging", constraints.requiresCharging())
        assertTrue("warm must wait for idle", constraints.requiresDeviceIdle())
        assertTrue("warm must wait for battery", constraints.requiresBatteryNotLow())
        assertTrue("warm must wait for storage", constraints.requiresStorageNotLow())
    }

    @Test
    fun `warmable sections are Home's own rails`() {
        val sections = warmableSections()
        assertEquals(7, sections.size)
        assertTrue(sections.all { !it.panelManaged && it.relatedWorkId == null })
    }

    @Test
    fun `panel-managed and related rails never warm`() {
        val sections = warmableSections(
            listOf(
                HomeSection(key = "a", title = "A", type = null),
                HomeSection(key = "p", title = "P", type = null, panelManaged = true),
                HomeSection(key = "r", title = "R", type = null, relatedWorkId = 42),
            ),
        )
        assertEquals(listOf("a"), sections.map { it.key })
    }

    @Test
    fun `warm request is daily in the overnight window`() {
        val request = CatalogWarmWorker.request()
        // WorkSpec.intervalDuration is a long in milliseconds, not a
        // Duration — comparing a Duration to it compiles (Object overload)
        // and fails at runtime.
        assertEquals(TimeUnit.DAYS.toMillis(1), request.workSpec.intervalDuration)
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
    }

    @Test
    fun `sync request is daily in the overnight window`() {
        val request = CatalogIndexSyncWorker.request()
        assertEquals(TimeUnit.DAYS.toMillis(1), request.workSpec.intervalDuration)
        assertEquals(NetworkType.UNMETERED, request.workSpec.constraints.requiredNetworkType)
    }
}
