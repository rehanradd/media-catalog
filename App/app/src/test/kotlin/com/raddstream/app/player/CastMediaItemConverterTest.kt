package com.raddstream.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * MX #9 — Cast: the MediaItem → MediaInfo bridge, under Robolectric.
 *
 * The converter needs a real `android.net.Uri` (the MediaItem holds one),
 * which the plain JVM stubs cannot give — the same reason
 * [PlayerExtractorsTest] runs under Robolectric. What no JVM test can
 * prove is that a receiver plays the payload; that is the real-device
 * step in the session log.
 */
@RunWith(RobolectricTestRunner::class)
class CastMediaItemConverterTest {

    private val converter = CastMediaItemConverter()
    private val redirector = "https://raddstream.duckdns.org/api/v1/stream?id=abc123"

    private fun item(url: String, mimeType: String?, title: String?): MediaItem {
        val metadata = MediaMetadata.Builder().apply {
            title?.let { setTitle(it) }
        }.build()
        return MediaItem.Builder()
            .setUri(url)
            .apply { mimeType?.let { setMimeType(it) } }
            .setMediaMetadata(metadata)
            .build()
    }

    @Test
    fun `an HLS item converts with the redirector address and the mpegurl type`() {
        val info = converter.toMediaInfo(item(redirector, MimeTypes.APPLICATION_M3U8, "One Piece"))
        assertEquals(redirector, info.contentId)
        assertEquals("application/x-mpegurl", info.contentType)
        assertEquals("One Piece", info.metadata?.title.toString())
    }

    @Test
    fun `a DASH item converts with the dash type`() {
        val info = converter.toMediaInfo(item(redirector, MimeTypes.APPLICATION_MPD, "One Piece"))
        assertEquals("application/dash+xml", info.contentType)
    }

    @Test
    fun `a plain mp4 address converts with the mp4 type`() {
        val info = converter.toMediaInfo(
            item("https://cdn.example.com/film.mp4", null, "Film"),
        )
        assertEquals("video/mp4", info.contentType)
    }

    @Test
    fun `an item without a title carries no title`() {
        val info = converter.toMediaInfo(item(redirector, MimeTypes.APPLICATION_M3U8, null))
        assertNull(info.metadata?.title)
    }
}
