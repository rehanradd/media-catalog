package com.raddstream.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone-side half of the beacon, on the JVM: no Android runtime, no
 * DataStore, no network — the queue is pure logic over a store, and the
 * wire shape is a JSON round-trip.
 *
 * The contract with the server is pinned the same way `TelemetryCountersTest`
 * pins the usage vocabulary: the field names are the ones
 * `usage_stats.record_playback_beacon` reads, so a rename on either side
 * fails here instead of silently becoming a beacon the operator never sees.
 */
class PlaybackBeaconTest {

    private class FakeStore : PlaybackBeaconStore {
        private var pending: List<PlaybackBeacon> = emptyList()

        override fun enqueue(beacon: PlaybackBeacon) {
            pending = (pending + beacon).takeLast(PlaybackBeaconQueue.MAX_PENDING)
        }

        override fun snapshot(): List<PlaybackBeacon> = pending

        override fun acknowledge(sent: List<PlaybackBeacon>) {
            val sentIds = sent.map { it.sid }.toSet()
            pending = pending.filterNot { it.sid in sentIds }
        }
    }

    private fun beacon(sid: String, outcome: String = PlaybackBeaconOutcomes.PLAYED) =
        PlaybackBeacon(
            sid = sid,
            streamId = "handle-$sid",
            band = "480p",
            brBps = 1_200_000L,
            bytesTotal = 8_000_000L,
            loadMsTotal = 4000L,
            ttfbMs = 850L,
            stalls = 1,
            rebufferMs = 1200L,
            outcome = outcome,
            error = null,
            net = PlaybackBeaconNetworks.WIFI,
        )

    @Test
    fun `the queue records only while the gate says consented`() {
        var consented = false
        val queue = PlaybackBeaconQueue(FakeStore(), gate = { consented })
        queue.record(beacon("s1"))
        assertTrue(queue.takePending().isEmpty())
        consented = true
        queue.record(beacon("s1"))
        assertEquals(listOf("s1"), queue.takePending().map { it.sid })
    }

    @Test
    fun `the queue caps at twenty and sheds the oldest`() {
        val queue = PlaybackBeaconQueue(FakeStore(), gate = { true })
        (1..21).forEach { queue.record(beacon("s$it")) }
        val pending = queue.takePending()
        assertEquals(20, pending.size)
        assertEquals("s2", pending.first().sid)
        assertEquals("s21", pending.last().sid)
    }

    @Test
    fun `acknowledge forgets exactly what was sent`() {
        val queue = PlaybackBeaconQueue(FakeStore(), gate = { true })
        queue.record(beacon("s1"))
        queue.record(beacon("s2"))
        val sent = queue.takePending()
        queue.record(beacon("s3"))
        queue.acknowledge(sent)
        assertEquals(listOf("s3"), queue.takePending().map { it.sid })
    }

    @Test
    fun `a beacon survives a JSON round-trip with every field`() {
        val original = beacon("s1", PlaybackBeaconOutcomes.FAILED)
            .copy(error = PlaybackBeaconErrors.NETWORK)
        val decoded = PlaybackBeacon.fromJsonString(original.toJsonString())
        assertEquals(original, decoded)
    }

    @Test
    fun `a malformed stored entry decodes to null instead of throwing`() {
        assertNull(PlaybackBeacon.fromJsonString("{}"))
        assertNull(PlaybackBeacon.fromJsonString("not json"))
        assertEquals(emptyList<PlaybackBeacon>(), PlaybackBeacon.listFromJsonString("not json"))
    }

    @Test
    fun `the wire shape carries exactly the fields the server reads`() {
        val json = com.google.gson.JsonParser.parseString(beacon("s1").toJsonString()).asJsonObject
        val keys = json.keySet()
        assertEquals(
            setOf(
                "sid", "stream_id", "band", "br", "bytes", "load_ms",
                "ttfb_ms", "stalls", "rebuffer_ms", "outcome", "net",
            ),
            keys,
        )
        val withError = com.google.gson.JsonParser.parseString(
            beacon("s1", PlaybackBeaconOutcomes.FAILED)
                .copy(error = PlaybackBeaconErrors.TIMEOUT).toJsonString(),
        ).asJsonObject
        assertEquals("timeout", withError.get("error").asString)
    }
}
