package com.raddstream.app.resolve

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.raddstream.app.resolve.MiniJson.long
import com.raddstream.app.resolve.MiniJson.obj
import com.raddstream.app.resolve.MiniJson.string
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The press export JSON shape and summary — Robolectric only for the string resources. */
@RunWith(RobolectricTestRunner::class)
class PressTestExportTest {

    private val context = ApplicationProvider.getApplicationContext<Context>()

    private val header = PressRunHeader(
        deviceModel = "Pixel 8",
        androidVersion = "Android 14 (SDK 34)",
        appVersion = "1.0.0",
        runStartedEpochMs = 1_700_000_000_000L,
        networkType = "wifi",
        runKind = "initial",
    )

    private fun press(
        linkId: Long,
        service: String,
        kind: AttemptKind,
        t1: Long?,
        t2: Long?,
        t3: Long?,
    ): PressResult = PressResult(
        linkId = linkId,
        postId = 123L,
        service = service,
        attemptKind = kind,
        pressStartedAtMs = 1_700_000_000_000L,
        mintStartedAtMs = 1_700_000_000_100L,
        mintFinishedAtMs = 1_700_000_000_150L,
        mintTo302Ms = t1,
        mintHttpStatus = 302,
        mintedUrl = "https://cdn.example.com/film.mp4",
        pressToFirstFrameMs = t2,
        rebufferCount = 1,
        droppedFrames = 2,
        bytesRead = 1_048_576L,
        statsTotalPlayTimeMs = 4_000L,
        statsRebufferCount = 1,
        playError = null,
        downloadTapStartedAtMs = 1_700_000_000_200L,
        tapToFirstByteMs = t3,
        downloadCancelled = true,
        downloadError = null,
    )

    @Test
    fun `json carries phase run kind minted url and timestamps`() {
        val results = listOf(
            press(1L, "filepress", AttemptKind.NORMAL, 50L, 900L, 450L),
            press(2L, "1fichier", AttemptKind.REPEAT, 60L, 1_100L, 500L),
        )
        val json = PressTestExport.buildJson(header, results)
        assertTrue(json.contains("\"phase\": \"press\""))
        assertTrue(json.contains("\"run_kind\": \"initial\""))
        assertTrue(json.contains("\"minted_url\": \"https://cdn.example.com/film.mp4\""))
        assertTrue(json.contains("\"press_started_at_ms\": 1700000000000"))
        assertTrue(json.contains("\"mint_started_at_ms\": 1700000000100"))
        assertTrue(json.contains("\"post_id\": 123"))
        assertTrue(json.contains("\"tap_to_first_byte_ms\": 450"))
        assertTrue(json.contains("\"download_cancelled\": true"))
        assertTrue(json.contains("\"attempt_kind\": \"repeat\""))
        // The shape parses back with the harness's own reader.
        val parsed = MiniJson.parse(json) as? JsonValue.Obj
        assertEquals("press", parsed?.obj("header")?.string("phase"))
        assertEquals(1_700_000_000_000L, parsed?.obj("header")?.long("run_started_epoch_ms"))
    }

    @Test
    fun `summary prints pass counts and wilson intervals per group`() {
        val results = listOf(
            press(1L, "filepress", AttemptKind.NORMAL, 50L, 900L, 450L),
            press(2L, "1fichier", AttemptKind.NORMAL, 60L, 1_100L, 500L),
            press(3L, "filepress", AttemptKind.REPEAT, 55L, 950L, 480L),
        )
        val summary = PressTestExport.buildSummary(context, header, results)
        assertTrue(summary.contains("filepress:"))
        assertTrue(summary.contains("1fichier:"))
        assertTrue(summary.contains("overall:"))
        assertTrue(summary.contains("T-1: 1 of 1 pass (Wilson 95% CI"))
        assertTrue(summary.contains("T-2: 1 of 1 pass (Wilson 95% CI"))
        assertTrue(summary.contains("T-3: 1 of 1 pass (Wilson 95% CI"))
        assertTrue(summary.contains("Repeat runs (scored separately):"))
        assertTrue(summary.contains("server log"))
    }
}
