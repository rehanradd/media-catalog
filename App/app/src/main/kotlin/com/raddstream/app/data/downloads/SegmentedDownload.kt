package com.raddstream.app.data.downloads

import com.raddstream.app.data.cache.NetworkClass
import com.raddstream.app.data.cache.RangeByteFetcher
import com.raddstream.app.brand.Brand
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicLong
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Segmented parallel file downloads (R3 — the aria2 / ADM model).
 *
 * One connection per segment, each a `Range` fetch into its own slice of a
 * single pre-allocated temp file; a segment journal (the aria2 control-file
 * lesson) persists which segments are done, so a resume — after a pause, a
 * killed process, or a dropped connection — re-fetches only the missing
 * segments instead of restarting a gigabyte file from zero.
 *
 * The module is split so the testable half is pure:
 *  - [planSegments] / [SegmentJournal] / [segmentCountFor] — pure, unit-tested.
 *  - [SegmentedDownloader] — orchestration over an injected [SegmentFetcher],
 *    so tests drive it with a fake fetcher and no socket.
 *  - [OkHttpSegmentFetcher] — the only real-network piece.
 *
 * Rules that never bend (R3/R7):
 *  - A partial file is NEVER marked READY: an unfinished download ends
 *    [SegmentedOutcome.Partial], never Complete, and the service turns that
 *    into the visible PARTIAL state with "retry missing segments".
 *  - Parallelism is capped ([DEFAULT_SEGMENTS] = 4, hard cap [MAX_SEGMENTS] = 8)
 *    and gated behind unmetered Wi-Fi ([segmentCountFor]); a 403 on any segment
 *    falls back to the single connection ([SegmentedOutcome.FallbackToSingle]),
 *    never hammers the host.
 *  - One download, one host: the service still runs one file at a time, so the
 *    per-host connection count is exactly the segment count.
 */

/** Never split a file smaller than this — the probe + journal cost more than it saves. */
const val MIN_SEGMENTED_BYTES = 1L * 1024 * 1024

/** Start at 4 parallel segments; never exceed 8, whatever the caller asks. */
const val DEFAULT_SEGMENTS = 4
const val MAX_SEGMENTS = 8

/** Never create a segment smaller than this, even when the caller asks for more. */
const val MIN_SEGMENT_BYTES = 256L * 1024

/** Retries per segment before it is declared missing (hlsfetch retries 5; 3 is enough here). */
const val MAX_SEGMENT_RETRIES = 3

/** One contiguous slice of `[0, totalBytes)`. `endExclusive` is one past the last byte. */
data class SegmentRange(val index: Int, val start: Long, val endExclusive: Long) {
    val length: Long get() = endExclusive - start
}

/**
 * Pure split of `[0, totalBytes)` into at most [wantedSegments] contiguous
 * ranges. The remainder bytes spread one each over the first segments, so no
 * segment is ever more than one byte longer than another; a file too small to
 * split at [MIN_SEGMENT_BYTES] per segment comes back as one range.
 */
fun planSegments(totalBytes: Long, wantedSegments: Int): List<SegmentRange> {
    require(totalBytes > 0) { "totalBytes must be positive" }
    val maxBySize = (totalBytes / MIN_SEGMENT_BYTES).toInt().coerceAtLeast(1)
    val count = wantedSegments.coerceIn(1, MAX_SEGMENTS).coerceAtMost(maxBySize)
    if (count == 1) return listOf(SegmentRange(0, 0, totalBytes))
    val base = totalBytes / count
    val remainder = (totalBytes % count).toInt()
    var start = 0L
    return (0 until count).map { i ->
        val length = base + if (i < remainder) 1 else 0
        SegmentRange(i, start, start + length).also { start += length }
    }
}

/**
 * How many segments this download may use on this network — the Wi-Fi-only
 * gate for aggressive parallelism (ADM does the same). Metered Wi-Fi counts
 * as mobile: a hotspot is not free bandwidth. Anything but unmetered Wi-Fi,
 * or a file under [MIN_SEGMENTED_BYTES], is a single connection.
 */
fun segmentCountFor(networkClass: NetworkClass, isMetered: Boolean, totalBytes: Long): Int {
    if (totalBytes < MIN_SEGMENTED_BYTES) return 1
    if (networkClass != NetworkClass.WIFI || isMetered) return 1
    return DEFAULT_SEGMENTS
}

/** One segment's durable state: its range and how many of its bytes are already on disk. */
data class SegmentState(val range: SegmentRange, val downloadedBytes: Long, val done: Boolean)

/**
 * The segment journal — the aria2 control file. Encoded as one flat string
 * (`total|index:start-end:done,...`) so it stores in the same SharedPreferences
 * row as the download entry with no new database and no JSON dependency.
 * A corrupt journal decodes to null and the download re-plans from scratch;
 * it never trusts bytes it cannot prove.
 */
data class SegmentJournal(val totalBytes: Long, val segments: List<SegmentState>) {

    fun bytesDownloaded(): Long = segments.sumOf { it.downloadedBytes }

    fun isComplete(): Boolean = segments.isNotEmpty() && segments.all { it.done }

    fun missingIndices(): List<Int> = segments.filter { !it.done }.map { it.range.index }

    fun encode(): String = buildString {
        append(totalBytes)
        append('|')
        segments.joinTo(this, ",") { s ->
            "${s.range.index}:${s.range.start}-${s.range.endExclusive}:${s.downloadedBytes}"
        }
    }

    companion object {
        fun fresh(totalBytes: Long, ranges: List<SegmentRange>): SegmentJournal =
            SegmentJournal(totalBytes, ranges.map { SegmentState(it, 0L, false) })

        fun decode(raw: String?): SegmentJournal? {
            if (raw.isNullOrBlank()) return null
            return runCatching {
                val bar = raw.indexOf('|')
                if (bar < 0) return null
                val total = raw.substring(0, bar).toLongOrNull()?.takeIf { it > 0 } ?: return null
                val segments = raw.substring(bar + 1).split(",").map { part ->
                    val colon = part.split(":")
                    if (colon.size != 3) return null
                    val index = colon[0].toIntOrNull() ?: return null
                    val bounds = colon[1].split("-")
                    if (bounds.size != 2) return null
                    val start = bounds[0].toLongOrNull() ?: return null
                    val end = bounds[1].toLongOrNull() ?: return null
                    val done = colon[2].toLongOrNull() ?: return null
                    if (start < 0 || end <= start || end > total || done < 0 || done > end - start) return null
                    SegmentState(SegmentRange(index, start, end), done, done == end - start)
                }
                if (segments.isEmpty()) return null
                // Ranges must tile [0, total) exactly, in order, with no gaps or overlaps.
                var expect = 0L
                segments.sortedBy { it.range.index }.forEach { s ->
                    if (s.range.start != expect) return null
                    expect = s.range.endExclusive
                }
                if (expect != total) return null
                SegmentJournal(total, segments.sortedBy { it.range.index })
            }.getOrNull()
        }
    }
}

/** Why one segment fetch failed — the engine's retry/fallback decision rides on this, not on text. */
enum class SegmentFailureKind {
    /** Transient I/O or 5xx — retry with backoff. */
    IO,
    /** The host refuses parallel Range (or Range at all) — fall back to one connection. */
    HTTP_403,
    /** The URL rotted — nothing to resume against. */
    HTTP_404,
    /** Gone for good. */
    HTTP_410,
    /** Slow down, honouring Retry-After. */
    HTTP_429,
    /** Any other non-2xx — retry with backoff. */
    HTTP_OTHER,
    /** The server answered 200 to a Range request — it ignored the range. */
    NO_RANGE,
}

/** A segment fetch failure, carrying the retry signal (Retry-After seconds, when the host gave one). */
class SegmentFetchException(
    val kind: SegmentFailureKind,
    val retryAfterMs: Long? = null,
    message: String? = null,
) : IOException(message ?: kind.name)

/** What the 1-byte probe learned: whether Range works, the total, and the content type. */
data class RangeProbe(
    val rangeSupported: Boolean,
    val totalBytes: Long,
    val contentType: String,
)

/** The network half of a segmented download — the seam tests fake. */
interface SegmentFetcher {
    /** One-byte `Range: bytes=0-0` probe: total size, content type, and whether Range is honoured. */
    suspend fun probe(url: String): RangeProbe

    /**
     * Fetch [range] starting [alreadyDone] bytes into it, calling [write] for
     * every chunk. Returns the bytes written. Throws [SegmentFetchException]
     * for anything that is not a complete 206 segment body.
     */
    suspend fun fetch(
        url: String,
        range: SegmentRange,
        alreadyDone: Long,
        write: (ByteArray, Int, Int) -> Unit,
    ): Long
}

/** Terminal outcome of a segmented download. Every outcome carries the journal that proves it. */
sealed interface SegmentedOutcome {
    /** Every segment done; [contentType] is the probe's, for the completion gate. */
    data class Complete(val journal: SegmentJournal, val contentType: String) : SegmentedOutcome

    /** Some segments exhausted their retries — bytes kept, journal kept, retry only the missing. */
    data class Partial(val journal: SegmentJournal, val missingSegments: List<Int>) : SegmentedOutcome

    /** A hard failure (dead source, gate refusal, probe I/O) — nothing to resume against. */
    data class Failed(
        val journal: SegmentJournal,
        val reason: String,
        /**
         * Phase 4 (Downloads #5): the stable failure kind behind [reason]
         * — the same vocabulary as [DeviceDownloadErrorKind]. The reason
         * stays the stored diagnostic; the row paints the mapped sentence.
         */
        val kind: String = DeviceDownloadErrorKind.TRANSFER_FAILED,
    ) : SegmentedOutcome

    /** The host will not do Range (200 to a Range request, or 403) — run the single connection. */
    data object FallbackToSingle : SegmentedOutcome
}

internal sealed interface SegmentResult {
    data class Done(val index: Int, val downloadedBytes: Long) : SegmentResult
    data class Missing(val index: Int, val downloadedBytes: Long) : SegmentResult
    data object HostRefusedParallel : SegmentResult
    data object SourceDead : SegmentResult
    data object NoRange : SegmentResult
}

/**
 * Downloads [journal]'s segments into [tempFile] in parallel, resuming each
 * segment from its journaled offset. The temp file is pre-allocated to the
 * total size first, so out-of-order writes land at the right offset; every
 * segment opens its OWN [RandomAccessFile] handle (independent file pointers),
 * so concurrent writes to disjoint ranges never interleave.
 *
 * [onProgress] fires with (total bytes done, current journal) after every
 * chunk — the caller throttles persistence; the journal it receives is always
 * the latest proven state, so a kill loses at most the caller's throttle window.
 */
class SegmentedDownloader(
    private val fetcher: SegmentFetcher,
    private val maxRetriesPerSegment: Int = MAX_SEGMENT_RETRIES,
    private val backoffMs: (attempt: Int, retryAfterMs: Long?) -> Long = ::defaultBackoffMs,
) {

    suspend fun download(
        url: String,
        tempFile: File,
        journal: SegmentJournal,
        onProgress: (Long, SegmentJournal) -> Unit,
    ): SegmentedOutcome {
        val probe = try {
            fetcher.probe(url)
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: SegmentFetchException) {
            return when (e.kind) {
                SegmentFailureKind.HTTP_403 -> SegmentedOutcome.FallbackToSingle
                // Phase 4 (Downloads #5): the raw code never survives into
                // the reason — the clean form is the precedent (the
                // SourceDead branch below already says it this way).
                SegmentFailureKind.HTTP_404, SegmentFailureKind.HTTP_410 ->
                    SegmentedOutcome.Failed(journal, "The source is no longer available.", "link_expired")
                SegmentFailureKind.HTTP_429 ->
                    SegmentedOutcome.Failed(journal, e.message ?: "The download could not start.", "rate_limited")
                else -> SegmentedOutcome.Failed(journal, e.message ?: "The download could not start.")
            }
        } catch (io: IOException) {
            return SegmentedOutcome.Failed(journal, io.message ?: "The download could not start.")
        }
        if (!probe.rangeSupported) return SegmentedOutcome.FallbackToSingle
        if (probe.totalBytes <= 0) {
            // No size to split against — the single connection owns
            // unknown-size downloads (and refuses them at its own gate).
            return SegmentedOutcome.FallbackToSingle
        }
        if (probe.totalBytes != journal.totalBytes) {
            // The server's file is not the one the journal describes — never
            // stitch bytes from two different files together. A retry must
            // re-resolve and restart from zero, like a dead link.
            return SegmentedOutcome.Failed(journal, "The file changed on the server; the saved parts no longer match it.", "link_expired")
        }

        RandomAccessFile(tempFile, "rw").use { it.setLength(journal.totalBytes) }
        val totalDone = AtomicLong(journal.bytesDownloaded())
        val states = journal.segments.associateBy { it.range.index }.toMutableMap()
        val lock = Any()
        fun updateState(index: Int, done: Long, finished: Boolean) {
            synchronized(lock) {
                val current = states[index] ?: return
                states[index] = current.copy(downloadedBytes = done, done = finished)
            }
        }
        fun report() {
            val snapshot = synchronized(lock) {
                SegmentJournal(journal.totalBytes, states.values.sortedBy { it.range.index })
            }
            onProgress(totalDone.get(), snapshot)
        }

        val results = supervisorScope {
            journal.segments.filter { !it.done }.map { segment ->
                async {
                    runCatching {
                        downloadSegment(url, tempFile, segment, totalDone) { index, done, finished ->
                            updateState(index, done, finished)
                            report()
                        }
                    }.getOrElse { failure ->
                        // A bug in our own code must not masquerade as a missing segment.
                        if (failure is CancellationException) throw failure
                        SegmentResult.Missing(segment.range.index, segment.downloadedBytes)
                    }
                }
            }.awaitAll()
        }
        val finalJournal = SegmentJournal(
            journal.totalBytes,
            states.values.sortedBy { it.range.index },
        )
        return when {
            results.any { it is SegmentResult.HostRefusedParallel } -> SegmentedOutcome.FallbackToSingle
            results.any { it is SegmentResult.NoRange } -> SegmentedOutcome.FallbackToSingle
            results.any { it is SegmentResult.SourceDead } ->
                SegmentedOutcome.Failed(finalJournal, "The source is no longer available.", "link_expired")
            results.any { it is SegmentResult.Missing } ->
                SegmentedOutcome.Partial(finalJournal, results.filterIsInstance<SegmentResult.Missing>().map { it.index })
            else -> SegmentedOutcome.Complete(finalJournal, probe.contentType)
        }
    }

    private suspend fun downloadSegment(
        url: String,
        tempFile: File,
        segment: SegmentState,
        totalDone: AtomicLong,
        onSegmentProgress: (index: Int, done: Long, finished: Boolean) -> Unit,
    ): SegmentResult {
        var done = segment.downloadedBytes
        var attempt = 0
        while (true) {
            coroutineContext.ensureActive()
            try {
                RandomAccessFile(tempFile, "rw").use { raf ->
                    raf.seek(segment.range.start + done)
                    fetcher.fetch(url, segment.range, done) { buffer, offset, length ->
                        raf.write(buffer, offset, length)
                        done += length
                        totalDone.addAndGet(length.toLong())
                        onSegmentProgress(segment.range.index, done, false)
                    }
                    if (done != segment.range.length) {
                        return SegmentResult.Missing(segment.range.index, done)
                    }
                }
                onSegmentProgress(segment.range.index, done, true)
                return SegmentResult.Done(segment.range.index, done)
            } catch (ce: CancellationException) {
                throw ce
            } catch (e: SegmentFetchException) {
                when (e.kind) {
                    SegmentFailureKind.HTTP_403 -> return SegmentResult.HostRefusedParallel
                    SegmentFailureKind.HTTP_404, SegmentFailureKind.HTTP_410 -> return SegmentResult.SourceDead
                    SegmentFailureKind.NO_RANGE -> return SegmentResult.NoRange
                    SegmentFailureKind.HTTP_429, SegmentFailureKind.IO, SegmentFailureKind.HTTP_OTHER -> {
                        if (++attempt > maxRetriesPerSegment) {
                            return SegmentResult.Missing(segment.range.index, done)
                        }
                        kotlinx.coroutines.delay(backoffMs(attempt, e.retryAfterMs))
                    }
                }
            }
        }
    }

    companion object {
        /** Exponential backoff (1s, 2s, 4s… capped at 30s), honouring Retry-After when given. */
        fun defaultBackoffMs(attempt: Int, retryAfterMs: Long?): Long {
            retryAfterMs?.let { return it.coerceIn(0L, 60_000L) }
            var delay = 1_000L
            repeat(attempt.coerceAtLeast(1) - 1) { delay = (delay * 2).coerceAtMost(30_000L) }
            return delay
        }
    }
}

/** The real [SegmentFetcher]: OkHttp Range requests against our own redirector URL. */
class OkHttpSegmentFetcher(private val client: OkHttpClient) : SegmentFetcher {

    override suspend fun probe(url: String): RangeProbe = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Brand.userAgent)
            .header("Range", "bytes=0-0")
            .build()
        client.newCall(request).execute().use { response ->
            val contentType = response.header("Content-Type").orEmpty()
            when (response.code) {
                206 -> {
                    val total = RangeByteFetcher.parseTotalFromContentRange(response.header("Content-Range"))
                        ?: response.body?.contentLength()?.takeIf { it > 0 }
                        ?: 0L
                    RangeProbe(rangeSupported = true, totalBytes = total, contentType = contentType)
                }
                200 -> RangeProbe(
                    rangeSupported = false,
                    totalBytes = response.body?.contentLength()?.takeIf { it > 0 } ?: 0L,
                    contentType = contentType,
                )
                403 -> throw SegmentFetchException(
                    SegmentFailureKind.HTTP_403,
                    message = "The host refused the range request (HTTP 403).",
                )
                404 -> throw SegmentFetchException(SegmentFailureKind.HTTP_404, message = "HTTP 404.")
                410 -> throw SegmentFetchException(SegmentFailureKind.HTTP_410, message = "HTTP 410.")
                429 -> throw SegmentFetchException(
                    SegmentFailureKind.HTTP_429,
                    retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
                    message = "HTTP 429.",
                )
                else -> throw SegmentFetchException(
                    SegmentFailureKind.HTTP_OTHER,
                    message = "HTTP ${response.code}.",
                )
            }
        }
    }

    override suspend fun fetch(
        url: String,
        range: SegmentRange,
        alreadyDone: Long,
        write: (ByteArray, Int, Int) -> Unit,
    ): Long = withContext(Dispatchers.IO) {
        val start = range.start + alreadyDone
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", Brand.userAgent)
            .header("Range", "bytes=$start-${range.endExclusive - 1}")
            .build()
        client.newCall(request).execute().use { response ->
            when (response.code) {
                206 -> {
                    val body = response.body ?: throw SegmentFetchException(
                        SegmentFailureKind.IO,
                        message = "Empty response body.",
                    )
                    var written = 0L
                    val buffer = ByteArray(CHUNK_BYTES)
                    body.byteStream().use { input ->
                        while (true) {
                            coroutineContext.ensureActive()
                            val want = minOf(
                                buffer.size.toLong(),
                                range.endExclusive - start - written,
                            ).toInt()
                            if (want <= 0) break
                            val read = input.read(buffer, 0, want)
                            if (read < 0) break
                            write(buffer, 0, read)
                            written += read
                        }
                    }
                    written
                }
                200 -> throw SegmentFetchException(
                    SegmentFailureKind.NO_RANGE,
                    message = "The server ignored the range request (HTTP 200).",
                )
                403 -> throw SegmentFetchException(
                    SegmentFailureKind.HTTP_403,
                    message = "HTTP 403.",
                )
                404 -> throw SegmentFetchException(SegmentFailureKind.HTTP_404, message = "HTTP 404.")
                410 -> throw SegmentFetchException(SegmentFailureKind.HTTP_410, message = "HTTP 410.")
                429 -> throw SegmentFetchException(
                    SegmentFailureKind.HTTP_429,
                    retryAfterMs = parseRetryAfterMs(response.header("Retry-After")),
                    message = "HTTP 429.",
                )
                else -> throw SegmentFetchException(
                    SegmentFailureKind.HTTP_OTHER,
                    message = "HTTP ${response.code}.",
                )
            }
        }
    }

    private fun parseRetryAfterMs(header: String?): Long? {
        val seconds = header?.trim()?.toLongOrNull() ?: return null
        return (seconds * 1_000L).coerceAtLeast(0L)
    }

    private companion object {
        const val CHUNK_BYTES = 64 * 1024
    }
}
