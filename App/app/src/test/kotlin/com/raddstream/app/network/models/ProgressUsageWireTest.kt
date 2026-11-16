package com.raddstream.app.network.models

import com.google.gson.Gson
import com.raddstream.app.data.PlaybackBeacon
import com.raddstream.app.data.Telemetry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tracking A: the `usage` block rides `POST /api/v2/account/progress`, so
 * this pins the wire shape both ways — what the phone sends, and what the
 * server's `record_client_usage` will read back out of it.
 *
 * Plain JUnit4 on the JVM: no Robolectric, no Android runtime, no network.
 */
class ProgressUsageWireTest {

    private val gson = Gson()

    @Test
    fun `a progress write carries the usage block`() {
        val request = ProgressRequest(
            workRef = "w1",
            positionSeconds = 120.0,
            durationSeconds = 600.0,
            clientUpdatedAt = 1794064800.0,
            usage = mapOf("crashes" to 2, "playback_attempts" to 5),
        )
        val decoded = gson.fromJson(gson.toJson(request), ProgressRequest::class.java)
        assertEquals("w1", decoded.workRef)
        assertEquals(120.0, decoded.positionSeconds, 0.0)
        assertEquals(mapOf("crashes" to 2, "playback_attempts" to 5), decoded.usage)
    }

    @Test
    fun `no usage block means no usage key on the wire`() {
        // Gson omits nulls, so a viewer who declined totals sends a body
        // byte-identical to the one before this field existed.
        val request = ProgressRequest(workRef = "w1", positionSeconds = 10.0)
        val json = gson.toJson(request)
        assertFalse("usage key leaked into a body with no block: $json", json.contains("usage"))
        val decoded = gson.fromJson(json, ProgressRequest::class.java)
        assertEquals(null, decoded.usage)
    }

    @Test
    fun `every usage field name the app sends is one the server counts`() {
        // The server's CLIENT_FIELDS is the closed vocabulary; a field the
        // app invents would be ignored on arrival and read as a gap here.
        val usage = mapOf(
            "ad_requests" to 1,
            "ads_filled" to 1,
            "ad_impressions" to 1,
            "ad_clicks" to 1,
            "rewarded_completions" to 1,
            "coins_granted" to 1,
            "playback_attempts" to 1,
            "playback_buffer_failures" to 1,
            "playback_errors" to 1,
            "crashes" to 1,
            "errors" to 1,
        )
        val request = ProgressRequest(workRef = "w1", usage = usage)
        val decoded = gson.fromJson(gson.toJson(request), ProgressRequest::class.java)
        assertEquals(usage, decoded.usage)
        assertTrue(decoded.usage!!.keys.all { it in Telemetry.USAGE_FIELDS })
    }

    @Test
    fun `a progress write carries the beacon block`() {
        val request = ProgressRequest(
            workRef = "w1",
            positionSeconds = 120.0,
            durationSeconds = 600.0,
            clientUpdatedAt = 1794064800.0,
            playbackBeacon = listOf(
                PlaybackBeacon(
                    sid = "sid-1",
                    streamId = "handle-1",
                    band = "480p",
                    brBps = 1_200_000L,
                    bytesTotal = 8_000_000L,
                    loadMsTotal = 4000L,
                    ttfbMs = 850L,
                    stalls = 1,
                    rebufferMs = 1200L,
                    outcome = "played",
                    error = null,
                    net = "wifi",
                ),
            ),
        )
        val decoded = gson.fromJson(gson.toJson(request), ProgressRequest::class.java)
        assertEquals(1, decoded.playbackBeacon?.size)
        assertEquals("sid-1", decoded.playbackBeacon?.first()?.sid)
        assertEquals("handle-1", decoded.playbackBeacon?.first()?.streamId)
        assertEquals("played", decoded.playbackBeacon?.first()?.outcome)
    }

    @Test
    fun `no beacon block means no beacon key on the wire`() {
        // Gson omits nulls, so a viewer who declined analytics sends a body
        // byte-identical to the one before this field existed.
        val request = ProgressRequest(workRef = "w1", positionSeconds = 10.0)
        val json = gson.toJson(request)
        assertFalse("playback_beacon key leaked into a body with no block: $json", json.contains("playback_beacon"))
        val decoded = gson.fromJson(json, ProgressRequest::class.java)
        assertEquals(null, decoded.playbackBeacon)
    }
}
