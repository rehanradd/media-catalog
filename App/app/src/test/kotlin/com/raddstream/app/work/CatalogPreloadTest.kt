package com.raddstream.app.work

import androidx.work.NetworkType
import com.raddstream.app.viewmodel.HomeSection
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

/**
 * Feel-instant S8: the preload contract, pinned without a device — the
 * overnight window is the safety case (a constraint silently dropped is
 * either a warm that burns mobile data or one that never runs), and the
 * rail filter is the scope (a worker must never fetch a rail it cannot
 * serve). Plain JUnit4: nothing here touches the Android framework.
 */
@RunWith(JUnit4::class)
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
