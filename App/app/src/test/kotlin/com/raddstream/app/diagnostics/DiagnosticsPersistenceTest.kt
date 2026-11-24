package com.raddstream.app.diagnostics

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.diagnostics.debug.DebugDiagnostics
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The graceful-shutdown persist round-trip (APP-13, review R-1): the buffer
 * is written to a private file only on graceful shutdown and rehydrated
 * when the screen opens. A corrupt or missing file reads as empty.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsPersistenceTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        DebugDiagnostics.resetForTests()
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, DebugDiagnostics.PERSIST_FILE_NAME).delete()
    }

    @After
    fun tearDown() {
        DebugDiagnostics.resetForTests()
        File(context.filesDir, DebugDiagnostics.PERSIST_FILE_NAME).delete()
    }

    @Test
    fun `persist then load restores the events`() {
        DebugDiagnostics.record(DiagnosticsType.API, DiagnosticsSeverity.INFO, "first event")
        DebugDiagnostics.record(DiagnosticsType.PLAYBACK, DiagnosticsSeverity.ERROR, "second event")
        DebugDiagnostics.persist(context)
        assertTrue(File(context.filesDir, DebugDiagnostics.PERSIST_FILE_NAME).isFile)

        DebugDiagnostics.resetForTests()
        assertTrue(DebugDiagnostics.snapshot().isEmpty())
        DebugDiagnostics.loadPersisted(context)

        val restored = DebugDiagnostics.snapshot()
        assertEquals(2, restored.size)
        assertEquals(listOf("first event", "second event"), restored.map { it.summary })
        assertTrue(restored.all { it.sessionId == DebugDiagnostics.sessionId })
    }

    @Test
    fun `load with no persisted file reads as empty`() {
        DebugDiagnostics.loadPersisted(context)
        assertTrue(DebugDiagnostics.snapshot().isEmpty())
    }

    @Test
    fun `load with a corrupt file reads as empty and does not crash`() {
        File(context.filesDir, DebugDiagnostics.PERSIST_FILE_NAME).writeBytes(byteArrayOf(1, 2, 3, 4))
        DebugDiagnostics.record(DiagnosticsType.API, DiagnosticsSeverity.INFO, "kept event")
        DebugDiagnostics.loadPersisted(context)
        // The in-memory event survives; the corrupt file adds nothing.
        assertEquals(listOf("kept event"), DebugDiagnostics.snapshot().map { it.summary })
    }
}
