package com.raddstream.app.player

/**
 * Player resilience (2026-11-25): how far ahead of the playhead playback can survive
 * without the network — the number the device network-cut proof measures. Pure, so the
 * arithmetic is pinnable without a player.
 *
 * Two covers, one total. The RAM buffer ([ramAheadMs]) is Media3's own look-ahead; the disk
 * cover ([diskAheadMs]) is the on-disk bytes ahead of the playhead, converted to time at the
 * title's mean bitrate (constant-bitrate approximation — the same one CoverageMapping uses).
 * The two overlap: when the player reads from the preview file, the RAM buffer sits inside
 * the downloaded prefix, so the total is the LONGER of the two, not their sum — adding them
 * would count the same seconds twice.
 *
 * Honesty note on [bytesSaved]: it is the engine's downloaded total ([CacheSnapshot.
 * bytesSaved]), not the verified contiguous prefix. For linear playback from the start the
 * two are identical (the fetcher fills low-to-high), so the disk cover is exact there. After
 * a forward seek a gap can sit between the old prefix and the new window, and the disk cover
 * becomes an upper bound — the true contiguous cover is smaller by the gap. The device proof
 * plays linearly, so the number it reports is exact.
 */
data class PlaybackCoverage(
    /** Media3's own RAM buffer ahead of the playhead, ms. */
    val ramAheadMs: Long,
    /** On-disk bytes ahead of the playhead, in ms at the mean bitrate. */
    val diskAheadMs: Long,
    /** The longer of the two: how long playback can continue from what is already held. */
    val totalAheadMs: Long,
    /** Engine-authoritative bytes durably fetched, for the log line. */
    val bytesSaved: Long,
)

/**
 * How far ahead of [playheadMs] playback can survive without the network.
 *
 * [totalBytes] null or <= 0 (unknown size, live/segmented) means the byte-to-time conversion
 * has no mean bitrate, so the disk cover is 0 and the total is the RAM buffer alone — the
 * function never invents a bitrate. Everything is clamped to the remaining duration: no
 * cover can exceed what is left to play.
 */
fun coverageAheadMs(
    playheadMs: Long,
    durationMs: Long,
    totalBytes: Long?,
    bytesSaved: Long,
    bufferedPositionMs: Long,
): PlaybackCoverage {
    val remainingMs = (durationMs - playheadMs).coerceAtLeast(0L)
    val ramAheadMs = (bufferedPositionMs - playheadMs).coerceIn(0L, remainingMs)
    val diskAheadMs = if (totalBytes == null || totalBytes <= 0L || durationMs <= 0L) {
        0L
    } else {
        val bytesPerSecond = totalBytes.toDouble() / (durationMs / 1_000.0)
        if (bytesPerSecond <= 0.0) {
            0L
        } else {
            val playheadByte = (playheadMs / 1_000.0) * bytesPerSecond
            val aheadBytes = (bytesSaved.toDouble() - playheadByte).coerceAtLeast(0.0)
            ((aheadBytes / bytesPerSecond) * 1_000.0).toLong().coerceIn(0L, remainingMs)
        }
    }
    return PlaybackCoverage(
        ramAheadMs = ramAheadMs,
        diskAheadMs = diskAheadMs,
        totalAheadMs = maxOf(ramAheadMs, diskAheadMs),
        bytesSaved = bytesSaved.coerceAtLeast(0L),
    )
}
