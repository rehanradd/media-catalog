package com.raddstream.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.MediaMetadata as CastMediaMetadata
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
        val info = converter.toMediaQueueItem(item(redirector, MimeTypes.APPLICATION_M3U8, "One Piece")).media!!
        assertEquals(redirector, info.contentId)
        assertEquals("application/x-mpegurl", info.contentType)
        assertEquals("One Piece", info.metadata?.getString(CastMediaMetadata.KEY_TITLE))
    }

    @Test
    fun `a DASH item converts with the dash type`() {
        val info = converter.toMediaQueueItem(item(redirector, MimeTypes.APPLICATION_MPD, "One Piece")).media!!
        assertEquals("application/dash+xml", info.contentType)
    }

    @Test
    fun `a plain mp4 address converts with the mp4 type`() {
        val info = converter.toMediaQueueItem(
            item("https://cdn.example.com/film.mp4", null, "Film"),
        ).media!!
        assertEquals("video/mp4", info.contentType)
    }

    @Test
    fun `an item without a title carries no title`() {
        val info = converter.toMediaQueueItem(item(redirector, MimeTypes.APPLICATION_M3U8, null)).media!!
        assertNull(info.metadata?.getString(CastMediaMetadata.KEY_TITLE))
    }

    @Test
    fun `a round trip keeps the address, the title and the HLS type`() {
        val original = item(redirector, MimeTypes.APPLICATION_M3U8, "One Piece")
        val back = converter.toMediaItem(converter.toMediaQueueItem(original))
        assertEquals(redirector, back.localConfiguration?.uri.toString())
        assertEquals(MimeTypes.APPLICATION_M3U8, back.localConfiguration?.mimeType)
        assertEquals("One Piece", back.mediaMetadata.title.toString())
    }
}
