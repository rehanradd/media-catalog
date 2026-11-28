package com.raddstream.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The crash-trace reader — the other half of Telemetry's last-crash.txt
 * write (Search-crash fix, 2026-11-28). The write is only useful if the
 * trace can be read back on the device that crashed: the debug Diagnostics
 * screen shows it under its Crash tab, so a Detail crash on the owner's
 * phone is diagnosable without adb.
 *
 * Runs under Robolectric (x86_64-only like every Robolectric suite — see
 * the arm64 exclusion in `app/build.gradle.kts`).
 */
@RunWith(RobolectricTestRunner::class)
class TelemetryCrashTraceTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    @After
    fun deleteTrace() {
        File(context.filesDir, Telemetry.CRASH_TRACE_FILE).delete()
    }

    @Test
    fun `no trace file means no crash recorded`() {
        assertNull(Telemetry.readLastCrashTrace(context))
    }

    @Test
    fun `a written trace reads back verbatim`() {
        val trace = "time=1795822617000\nthread=main\njava.lang.RuntimeException: boom\n\tat com.example.Foo.bar(Foo.kt:1)"
        File(context.filesDir, Telemetry.CRASH_TRACE_FILE).writeText(trace)

        assertEquals(trace, Telemetry.readLastCrashTrace(context))
    }

    @Test
    fun `a blank trace file means no crash recorded`() {
        File(context.filesDir, Telemetry.CRASH_TRACE_FILE).writeText("   \n")

        assertNull(Telemetry.readLastCrashTrace(context))
    }
}
