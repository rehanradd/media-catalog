package com.raddstream.app.player

import androidx.media3.cast.MediaItemConverter
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import com.google.android.gms.cast.MediaQueueItem

/**
 * MX #9 — Cast: the bridge from our MediaItem to the receiver's queue item.
 *
 * Reuses the spike's mapping ([castContentType] + [buildCastMediaInfo]) so
 * the receiver sees exactly what the spike proved the Default Receiver
 * accepts: the stream type (live vs buffered), the content type, and the
 * title. The factory already declares HLS/DASH through the MediaItem's
 * mimeType, so the converter reads it back into the protocol the spike's
 * mapper understands — a URL alone cannot be trusted to say `.m3u8`.
 *
 * The 1.3.1 shape is [MediaItemConverter.toMediaQueueItem]: the converter
 * hands the CastPlayer a queue item wrapping the MediaInfo (the newer
 * `toMediaInfo` shape arrived with the 1.9.0 rewrite and does not exist
 * here). The queue item carries no autoplay or preload flags — the
 * defaults are the receiver's own.
 *
 * Not carried, deliberately (same limits as the spike): subtitles (the
 * Default Receiver would need text tracks in the MediaInfo — a follow-up),
 * DRM (no catalog title carries a license flow the receiver could use),
 * and per-track audio selection (the receiver plays its own default).
 */
class CastMediaItemConverter : MediaItemConverter {

    override fun toMediaQueueItem(mediaItem: MediaItem): MediaQueueItem {
        val config = mediaItem.localConfiguration
        val url = config?.uri?.toString().orEmpty()
        val protocol = when (config?.mimeType) {
            MimeTypes.APPLICATION_M3U8 -> "hls"
            MimeTypes.APPLICATION_MPD -> "dash"
            else -> null
        }
        val playback = PlaybackInfo(
            playUrl = url,
            protocol = protocol,
            mimeType = config?.mimeType,
        )
        val info = buildCastMediaInfo(
            playUrl = url,
            title = mediaItem.mediaMetadata.title?.toString().orEmpty(),
            playback = playback,
        )
        return MediaQueueItem.Builder(info).build()
    }
}
