package com.raddstream.app.player

import com.raddstream.app.data.PlaybackBeaconErrors
import com.raddstream.app.data.PlaybackBeaconNetworks
import com.raddstream.app.data.PlaybackBeaconOutcomes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The recorder's state machine, on the JVM: no Android runtime, no player —
 * the clock and the session id are injected, and every callback is a plain
 * method call in the order the service makes it.
 */
class PlaybackBeaconRecorderTest {

    private class Clock(var now: Long = 1_000L) {
        fun tick(ms: Long) {
            now += ms
        }
    }

    private fun recorder(clock: Clock): PlaybackBeaconRecorder {
        var n = 0
        return PlaybackBeaconRecorder(
            nowMs = { clock.now },
            newSid = { "sid-${++n}" },
        )
    }

    private fun start(recorder: PlaybackBeaconRecorder) {
        recorder.start("handle-1", "480p", 1_200_000L, PlaybackBeaconNetworks.WIFI)
    }

    @Test
    fun `a clean play records bytes, first-load time and a played outcome`() {
        val clock = Clock()
        val recorder = recorder(clock)
        start(recorder)
        recorder.noteLoadSample(4_000_000L, 900L)
        recorder.noteLoadSample(4_000_000L, 3100L)
        recorder.noteFirstFrame()
        val beacon = recorder.finishCurrent()
        assertEquals("sid-1", beacon?.sid)
        assertEquals("handle-1", beacon?.streamId)
        assertEquals("480p", beacon?.band)
        assertEquals(1_200_000L, beacon?.brBps)
        assertEquals(8_000_000L, beacon?.bytesTotal)
        assertEquals(4000L, beacon?.loadMsTotal)
        assertEquals(900L, beacon?.ttfbMs)
        assertEquals(0, beacon?.stalls)
        assertEquals(PlaybackBeaconOutcomes.PLAYED, beacon?.outcome)
        assertNull(beacon?.error)
        assertEquals(PlaybackBeaconNetworks.WIFI, beacon?.net)
    }

    @Test
    fun `buffering before the first frame is the start, not a stall`() {
        val clock = Clock()
        val recorder = recorder(clock)
        start(recorder)
        recorder.noteBufferingStarted()
        clock.tick(5_000L)
        recorder.noteBufferingEnded()
        recorder.noteFirstFrame()
        val beacon = recorder.finishCurrent()
        assertEquals(0, beacon?.stalls)
        assertEquals(0L, beacon?.rebufferMs)
    }

    @Test
    fun `buffering after the first frame counts one stall with its time`() {
        val clock = Clock()
        val recorder = recorder(clock)
        start(recorder)
        recorder.noteFirstFrame()
        recorder.noteBufferingStarted()
        clock.tick(2_400L)
        recorder.noteBufferingEnded()
        recorder.noteBufferingStarted()
        clock.tick(600L)
        val beacon = recorder.finishCurrent()
        assertEquals(2, beacon?.stalls)
        assertEquals(3_000L, beacon?.rebufferMs)
    }

    @Test
    fun `an error before the first frame fails the session`() {
        val clock = Clock()
        val recorder = recorder(clock)
        start(recorder)
        recorder.noteError(PlaybackBeaconErrors.NETWORK)
        val beacon = recorder.finishCurrent()
        assertEquals(PlaybackBeaconOutcomes.FAILED, beacon?.outcome)
        assertEquals(PlaybackBeaconErrors.NETWORK, beacon?.error)
    }

    @Test
    fun `a watchdog timeout fails the session even with no player error`() {
        val clock = Clock()
        val recorder = recorder(clock)
        start(recorder)
        recorder.noteWatchdogTimeout()
        val beacon = recorder.finishCurrent()
        assertEquals(PlaybackBeaconOutcomes.FAILED, beacon?.outcome)
        assertEquals(PlaybackBeaconErrors.TIMEOUT, beacon?.error)
    }

    @Test
    fun `a load replaced before any frame is abandoned`() {
        val clock = Clock()
        val recorder = recorder(clock)
        start(recorder)
        val abandoned = recorder.start(
            "handle-2", "720p", 3_000_000L, PlaybackBeaconNetworks.MOBILE,
        )
        assertEquals(PlaybackBeaconOutcomes.ABANDONED, abandoned?.outcome)
        assertEquals("sid-1", abandoned?.sid)
        assertEquals(0L, abandoned?.ttfbMs)
        val second = recorder.finishCurrent()
        assertEquals("sid-2", second?.sid)
        assertEquals("handle-2", second?.streamId)
        assertEquals("720p", second?.band)
        assertEquals(PlaybackBeaconNetworks.MOBILE, second?.net)
    }

    @Test
    fun `a load replaced after its first frame played`() {
        val clock = Clock()
        val recorder = recorder(clock)
        start(recorder)
        recorder.noteFirstFrame()
        val played = recorder.start(
            "handle-2", "480p", 1_200_000L, PlaybackBeaconNetworks.WIFI,
        )
        assertEquals(PlaybackBeaconOutcomes.PLAYED, played?.outcome)
    }

    @Test
    fun `finishing with no session returns null`() {
        val recorder = recorder(Clock())
        assertNull(recorder.finishCurrent())
    }

    @Test
    fun `samples and stalls with no session are ignored`() {
        val recorder = recorder(Clock())
        recorder.noteLoadSample(1000L, 100L)
        recorder.noteBufferingStarted()
        recorder.noteFirstFrame()
        recorder.noteError(PlaybackBeaconErrors.NETWORK)
        assertNull(recorder.finishCurrent())
    }

    @Test
    fun `the network mapping names the coarse transport`() {
        assertEquals(
            PlaybackBeaconNetworks.WIFI,
            beaconNetFor(com.raddstream.app.data.cache.NetworkClass.WIFI),
        )
        assertEquals(
            PlaybackBeaconNetworks.MOBILE,
            beaconNetFor(com.raddstream.app.data.cache.NetworkClass.METERED_ROAMING),
        )
        assertEquals(
            PlaybackBeaconNetworks.NONE,
            beaconNetFor(com.raddstream.app.data.cache.NetworkClass.NONE),
        )
    }
}
