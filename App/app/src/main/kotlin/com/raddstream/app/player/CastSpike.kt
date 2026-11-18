package com.raddstream.app.player

import android.content.Context
import com.google.android.gms.cast.CastMediaControlIntent
import com.google.android.gms.cast.MediaInfo
import com.google.android.gms.cast.MediaLoadOptions
import com.google.android.gms.cast.MediaMetadata
import com.google.android.gms.cast.framework.CastContext
import com.google.android.gms.cast.framework.CastOptions
import com.google.android.gms.cast.framework.OptionsProvider
import com.google.android.gms.cast.framework.SessionProvider

/**
 * E2 slice 3 — Chromecast SPIKE (2026-11-18). The smallest code that wires
 * the Cast SDK sender to the Default Media Receiver and loads a resolved
 * play URL onto it. No cast button, no mini-controller, no polished UX —
 * that is the follow-up slice, and only if the spike's real-device test
 * plays (see `App/docs/CAST_SPIKE_20261118.md`).
 *
 * WHAT THIS PROVES (once CI is green): the dependency resolves at this
 * app's minSdk 21, the OptionsProvider is found through the manifest
 * meta-data, and a [MediaInfo] can be built from the same [PlaybackInfo]
 * the ExoPlayer path already uses. WHAT IT DOES NOT PROVE: that a real
 * receiver plays a resolved URL — there is no Cast device on this VM, and
 * the receiver fetches the media itself, so the verdict belongs to the
 * receiver, not to this code. The open questions are in the spike note.
 *
 * The play URL handed here is OUR OWN redirector
 * (`/api/v1/stream?id=<handle>`), exactly as [PlaybackMediaItemFactory]
 * receives it — never an upstream file URL. The receiver follows the 302
 * itself, so the phone's cookies/auth do not travel; a presigned URL's
 * query params DO travel, because they are part of the URL. Two
 * consequences the follow-up slice must respect:
 *  - The Default Media Receiver refuses media whose final response lacks
 *    `Access-Control-Allow-Origin` (it loads, then drops to idle with
 *    `idleReason: error` and says nothing about CORS). Our server sends no
 *    CORS headers at all (`CORS_ALLOW_ORIGINS` is empty), and the 302 means
 *    the verdict belongs to the FILE HOST, not to our server. The server
 *    already records the answer per link as [PlaybackInfo.corsOpen] — the
 *    follow-up slice should cast `corsOpen` links first.
 *  - A presigned link expires. Cast it fresh from a just-resolved handle;
 *    never hand the receiver a stored presigned URL from an old session.
 *    [PlaybackInfo.isExpired] is the check; the load call below does not
 *    re-check it, because the caller (the player) owns freshness.
 */

// ── OptionsProvider ─────────────────────────────────────────────────────────

/**
 * The Cast framework finds this class through the manifest meta-data
 * (`OPTIONS_PROVIDER_CLASS_NAME` → this class) and asks it for the receiver
 * application ID. The Default Media Receiver
 * ([CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID], "CC1AD845")
 * needs no Cast console registration and no payment — that is the whole
 * point of the spike: the receiver is free and public.
 *
 * Only [getCastOptions] carries configuration; [getAdditionalSessionProviders]
 * returns null because there are no custom session providers for non-Cast
 * devices — the Google and Dolby samples override both methods this way.
 */
class RaddCastOptionsProvider : OptionsProvider {
    override fun getCastOptions(context: Context): CastOptions =
        CastOptions.Builder()
            .setReceiverApplicationId(CastMediaControlIntent.DEFAULT_MEDIA_RECEIVER_APPLICATION_ID)
            .build()

    override fun getAdditionalSessionProviders(context: Context): List<SessionProvider>? = null
}

// ── MediaInfo ───────────────────────────────────────────────────────────────

/**
 * The content type the receiver needs, from the same server knowledge the
 * ExoPlayer path uses ([PlaybackMediaItemFactory.manifestMimeType]).
 *
 * The receiver cannot infer it: every address this app plays is our own
 * extensionless redirector, and the 302 happens long after the receiver
 * has picked its player — the same failure the player documents for an
 * undeclared HLS link, only on the TV instead of the phone. HLS and DASH
 * must be declared; progressive MP4/MKV/WebM is left to the receiver's
 * own sniffing, exactly as the player leaves it to Media3's.
 */
fun castContentType(playback: PlaybackInfo): String = when {
    playback.isHls() -> "application/x-mpegurl"
    playback.isDash() -> "application/dash+xml"
    else -> "video/mp4"
}

/**
 * The LOAD payload for one rendition: the redirector URL as the content ID,
 * buffered (VOD) stream type, the declared content type above, and the
 * title as the receiver's on-screen metadata. Subtitles, DRM and queue
 * entries are NOT carried here — the spike loads one video and watches
 * whether it plays; the follow-up slice adds tracks once playback is proven.
 */
fun buildCastMediaInfo(playUrl: String, title: String, playback: PlaybackInfo): MediaInfo {
    val metadata = MediaMetadata(MediaMetadata.MEDIA_TYPE_MOVIE).apply {
        putString(MediaMetadata.KEY_TITLE, title)
    }
    return MediaInfo.Builder(playUrl)
        .setStreamType(MediaInfo.STREAM_TYPE_BUFFERED)
        .setContentType(castContentType(playback))
        .setMetadata(metadata)
        .build()
}

// ── Load path ───────────────────────────────────────────────────────────────

/** The outcome of one attempt to hand the current title to the receiver. */
sealed interface CastLoadResult {
    /** No Cast session is live (no receiver chosen on the network). */
    data object NoReceiverConnected : CastLoadResult

    /** The LOAD request was sent to the receiver's media client. */
    data object Loaded : CastLoadResult

    /** The load could not even be attempted (no URL, no client, SDK error). */
    data class Failed(val message: String) : CastLoadResult
}

/**
 * The spike's load path, callable from the player once a receiver is
 * connected: build the [MediaInfo] from the rendition the player already
 * resolved and hand it to the session's remote media client at the
 * position the viewer is at, so a cast mid-film resumes instead of
 * restarting. The phone keeps no playback role after the LOAD — the
 * receiver plays, the phone becomes the remote.
 *
 * Returns [CastLoadResult.Loaded] when the LOAD was SENT, not when the
 * receiver starts playing: playback state arrives asynchronously through
 * the session's media-status callbacks, which the follow-up slice
 * observes. A sent LOAD that never plays is the CORS/302 failure the
 * spike note warns about — check the receiver, not this return value.
 */
object CastSpike {

    fun isReceiverConnected(context: Context): Boolean = runCatching {
        CastContext.getSharedInstance(context.applicationContext)
            .sessionManager.currentCastSession?.isConnected == true
    }.getOrDefault(false)

    fun loadOnReceiver(
        context: Context,
        playback: PlaybackInfo,
        title: String,
        positionMs: Long = 0L,
    ): CastLoadResult {
        val playUrl = playback.playUrl?.trim().takeIf { it.isNotEmpty() }
            ?: return CastLoadResult.Failed("no play URL on this rendition")
        return try {
            val session = CastContext.getSharedInstance(context.applicationContext)
                .sessionManager.currentCastSession
                ?: return CastLoadResult.NoReceiverConnected
            val client = session.remoteMediaClient
                ?: return CastLoadResult.NoReceiverConnected
            val options = MediaLoadOptions.Builder()
                .setAutoplay(true)
                .setPlayPosition(positionMs)
                .build()
            client.load(buildCastMediaInfo(playUrl, title, playback), options)
            CastLoadResult.Loaded
        } catch (e: Exception) {
            CastLoadResult.Failed(e.message ?: e.javaClass.simpleName)
        }
    }
}
