package com.raddstream.app.player

import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaMetadata
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * E2 slice 3 spike tests (plain JVM JUnit, no Robolectric): the content-type
 * mapping is pure Kotlin, and [MediaInfo]/[MediaMetadata] are plain
 * SafeParcelable value holders whose constructors and getters do no Android
 * work — so the LOAD payload itself is assertable here. What no JVM test
 * can prove is that a receiver plays it; that is the real-device step in
 * `App/docs/CAST_SPIKE_20261118.md`.
 */
class CastSpikeTest {

    private val redirector = "https://raddstream.duckdns.org/api/v1/stream?id=abc123"

    @Test
    fun hlsProtocolDeclaresMpegUrl() {
        assertEquals(
            "application/x-mpegurl",
            castContentType(PlaybackInfo(playUrl = redirector, protocol = "hls")),
        )
    }

    @Test
    fun m3u8AddressDeclaresMpegUrlEvenWithoutProtocol() {
        assertEquals(
            "application/x-mpegurl",
            castContentType(PlaybackInfo(playUrl = "https://cdn.example.com/play.m3u8")),
        )
    }

    @Test
    fun dashProtocolDeclaresDashXml() {
        assertEquals(
            "application/dash+xml",
            castContentType(PlaybackInfo(playUrl = redirector, protocol = "dash")),
        )
    }

    @Test
    fun mpdAddressDeclaresDashXmlEvenWithoutProtocol() {
        assertEquals(
            "application/dash+xml",
            castContentType(PlaybackInfo(playUrl = "https://cdn.example.com/play.mpd")),
        )
    }

    @Test
    fun progressiveFallsBackToMp4ForTheReceiverToSniff() {
        assertEquals(
            "video/mp4",
            castContentType(PlaybackInfo(playUrl = redirector, protocol = "progressive")),
        )
    }

    @Test
    fun unknownProtocolFallsBackToMp4RatherThanFailing() {
        assertEquals("video/mp4", castContentType(PlaybackInfo(playUrl = redirector)))
    }

    @Test
    fun defaultReceiverApplicationIdIsThePublicOne() {
        // The spike casts to the Default Media Receiver, which needs no Cast
        // console registration: pin the ID so a future edit cannot silently
        // point the provider at a custom receiver that does not exist.
        assertEquals(
            "CC1AD845",
            CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID,
        )
    }

    @Test
    fun mediaInfoCarriesRedirectorUrlContentTypeAndTitle() {
        val playback = PlaybackInfo(playUrl = redirector, protocol = "hls")
        val info = buildCastMediaInfo(redirector, "One Piece", playback)
        assertEquals(redirector, info.contentId)
        assertEquals("application/x-mpegurl", info.contentType)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, info.streamType)
        assertEquals("One Piece", info.metadata?.getString(MediaMetadata.KEY_TITLE))
    }

    @Test
    fun mediaInfoForProgressiveDeclaresMp4() {
        val playback = PlaybackInfo(playUrl = redirector, protocol = "progressive")
        val info = buildCastMediaInfo(redirector, "Kantara", playback)
        assertEquals("video/mp4", info.contentType)
        assertEquals(MediaInfo.STREAM_TYPE_BUFFERED, info.streamType)
    }
}
