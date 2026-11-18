package com.raddstream.app.data.downloads

import com.raddstream.app.data.cache.NetworkClass
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R3 — segmented parallel downloads: the plan, the journal, the engine's
 * resume/partial/fallback contract, and the real OkHttp fetcher's Range
 * behaviour — all without a socket (a fake fetcher, plus an OkHttp
 * interceptor answering Range requests from memory).
 *
 * The rule under test throughout: a partial file is NEVER marked READY —
 * an unfinished download ends Partial with its journal intact, and a
 * resume re-fetches only the missing segments.
 */
class SegmentedDownloadTest {

    private val fileBytes = ByteArray(1_000) { (it % 251).toByte() }

    // ── plan ─────────────────────────────────────────────────────────────────

    @Test
    fun `plan tiles the file exactly with the remainder spread over the first segments`() {
        val ranges = planSegments(1_001, 4)
        assertEquals(4, ranges.size)
        assertEquals(listOf(251L, 250L, 250L, 250L), ranges.map { it.length })
        assertEquals(0L, ranges.first().start)
        assertEquals(1_001L, ranges.last().endExclusive)
        ranges.zipWithNext { a, b -> assertEquals(a.endExclusive, b.start) }
    }

    @Test
    fun `plan never exceeds eight segments`() {
        assertEquals(8, planSegments(100_000_000, 16).size)
    }

    @Test
    fun `a small file is one segment whatever the caller asks`() {
        assertEquals(1, planSegments(512, 4).size)
        assertEquals(1, planSegments(MIN_SEGMENTED_BYTES - 1, 4).size)
    }

    @Test
    fun `a file too small to split at the minimum segment size stays whole`() {
        // 300 KB / 256 KB minimum = 1 segment, not 2 slivers.
        assertEquals(1, planSegments(300 * 1024, 4).size)
    }

    // ── segment count gate ───────────────────────────────────────────────────

    @Test
    fun `four segments only on unmetered wi-fi with a file worth splitting`() {
        assertEquals(4, segmentCountFor(NetworkClass.WIFI, false, 10L * 1024 * 1024))
        assertEquals(1, segmentCountFor(NetworkClass.MOBILE, false, 10L * 1024 * 1024))
        assertEquals(1, segmentCountFor(NetworkClass.WIFI, true, 10L * 1024 * 1024))
        assertEquals(1, segmentCountFor(NetworkClass.NONE, false, 10L * 1024 * 1024))
        assertEquals(1, segmentCountFor(NetworkClass.WIFI, false, 512))
    }

    // ── journal ──────────────────────────────────────────────────────────────

    @Test
    fun `journal round-trips through its encoding`() {
        val journal = SegmentJournal.fresh(1_000, planSegments(1_000, 4))
        val decoded = SegmentJournal.decode(journal.encode())
        assertEquals(journal, decoded)
    }

    @Test
    fun `journal decode rejects corrupt gapped and overlapping journals`() {
        assertNull(SegmentJournal.decode(null))
        assertNull(SegmentJournal.decode(""))
        assertNull(SegmentJournal.decode("garbage"))
        assertNull(SegmentJournal.decode("1000|0:0-250:250,1:300-500:0"))
        assertNull(SegmentJournal.decode("1000|0:0-250:250,1:200-500:0"))
        assertNull(SegmentJournal.decode("1000|0:0-250:250,1:250-500:9999"))
        assertNull(SegmentJournal.decode("999|0:0-250:250,1:250-500:0"))
    }

    @Test
    fun `journal marks a segment done only when every byte landed`() {
        val journal = SegmentJournal.fresh(1_000, planSegments(1_000, 4))
        assertFalse(journal.isComplete())
        assertEquals(0L, journal.bytesDownloaded())
        val done = journal.copy(
            segments = journal.segments.map { it.copy(downloadedBytes = it.range.length, done = true) },
        )
        assertTrue(done.isComplete())
        assertEquals(1_000L, done.bytesDownloaded())
        assertTrue(done.missingIndices().isEmpty())
    }

    // ── engine with a fake fetcher ───────────────────────────────────────────

    /** In-memory fetcher: serves [bytes] per Range, with scripted failures per segment. */
    private class FakeFetcher(
        private val bytes: ByteArray,
        /** Segment index -> failures to throw before succeeding. */
        private val failFirst: Map<Int, Int> = emptyMap(),
        /** Segments that never succeed. */
        private val alwaysFail: Set<Int> = emptySet(),
        private val rangeSupported: Boolean = true,
    ) : SegmentFetcher {
        val fetchedIndices = ArrayList<Int>()
        private val attempts = HashMap<Int, Int>()

        override suspend fun probe(url: String): RangeProbe =
            RangeProbe(rangeSupported, bytes.size.toLong(), "video/mp4")

        override suspend fun fetch(
            url: String,
            range: SegmentRange,
            alreadyDone: Long,
            write: (ByteArray, Int, Int) -> Unit,
        ): Long {
            val attempt = (attempts[range.index] ?: 0) + 1
            attempts[range.index] = attempt
            fetchedIndices.add(range.index)
            if (range.index in alwaysFail) {
                throw SegmentFetchException(SegmentFailureKind.IO, message = "always down")
            }
            if (attempt <= (failFirst[range.index] ?: 0)) {
                throw SegmentFetchException(SegmentFailureKind.IO, message = "transient")
            }
            val start = (range.start + alreadyDone).toInt()
            val end = range.endExclusive.toInt()
            val chunk = bytes.copyOfRange(start, end)
            val half = chunk.size / 2
            if (half > 0) write(chunk, 0, half)
            write(chunk, half, chunk.size - half)
            return (end - start).toLong()
        }
    }

    private fun tempDir(): File = Files.createTempDirectory("seg-test").toFile()

    private fun engine(fetcher: SegmentFetcher): SegmentedDownloader =
        SegmentedDownloader(fetcher, backoffMs = { _, _ -> 0L })

    @Test
    fun `all segments complete and reassemble in order`() = runBlocking {
        val dir = tempDir()
        try {
            val temp = File(dir, "out.tmp")
            val fetcher = FakeFetcher(fileBytes)
            val journal = SegmentJournal.fresh(fileBytes.size.toLong(), planSegments(fileBytes.size.toLong(), 4))
            val outcome = engine(fetcher).download("https://host.test/file", temp, journal) { _, _ -> }
            assertTrue(outcome is SegmentedOutcome.Complete)
            assertEquals("video/mp4", (outcome as SegmentedOutcome.Complete).contentType)
            assertTrue(outcome.journal.isComplete())
            assertEquals(fileBytes.toList(), temp.readBytes().toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a transient segment failure retries and still completes`() = runBlocking {
        val dir = tempDir()
        try {
            val temp = File(dir, "out.tmp")
            val fetcher = FakeFetcher(fileBytes, failFirst = mapOf(1 to 2))
            val journal = SegmentJournal.fresh(fileBytes.size.toLong(), planSegments(fileBytes.size.toLong(), 4))
            val outcome = engine(fetcher).download("https://host.test/file", temp, journal) { _, _ -> }
            assertTrue(outcome is SegmentedOutcome.Complete)
            assertEquals(fileBytes.toList(), temp.readBytes().toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a segment that never succeeds ends partial with the other bytes on disk`() = runBlocking {
        val dir = tempDir()
        try {
            val temp = File(dir, "out.tmp")
            val fetcher = FakeFetcher(fileBytes, alwaysFail = setOf(2))
            val journal = SegmentJournal.fresh(fileBytes.size.toLong(), planSegments(fileBytes.size.toLong(), 4))
            val outcome = engine(fetcher).download("https://host.test/file", temp, journal) { _, _ -> }
            assertTrue(outcome is SegmentedOutcome.Partial)
            val partial = outcome as SegmentedOutcome.Partial
            assertEquals(listOf(2), partial.missingSegments)
            // The finished segments' bytes are on disk, exactly where they belong.
            val bytes = temp.readBytes()
            assertEquals(fileBytes[0].toInt(), bytes[0].toInt())
            assertEquals(fileBytes[750].toInt(), bytes[750].toInt())
            // The journal still proves the done segments — a resume trusts it.
            assertEquals(listOf(2), partial.journal.missingIndices())
            assertEquals(750L, partial.journal.bytesDownloaded())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `resume never re-fetches a finished segment`() = runBlocking {
        val dir = tempDir()
        try {
            val temp = File(dir, "out.tmp")
            val ranges = planSegments(fileBytes.size.toLong(), 4)
            // Segment 0 already on disk, journaled done.
            temp.parentFile?.mkdirs()
            java.io.RandomAccessFile(temp, "rw").use { raf ->
                raf.setLength(fileBytes.size.toLong())
                raf.seek(0)
                raf.write(fileBytes, 0, ranges[0].length.toInt())
            }
            val journal = SegmentJournal(
                fileBytes.size.toLong(),
                ranges.mapIndexed { i, range ->
                    if (i == 0) SegmentState(range, range.length, true)
                    else SegmentState(range, 0L, false)
                },
            )
            val fetcher = FakeFetcher(fileBytes)
            val outcome = engine(fetcher).download("https://host.test/file", temp, journal) { _, _ -> }
            assertTrue(outcome is SegmentedOutcome.Complete)
            assertFalse(fetcher.fetchedIndices.contains(0))
            assertEquals(fileBytes.toList(), temp.readBytes().toList())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a host without range support falls back to the single connection`() = runBlocking {
        val dir = tempDir()
        try {
            val temp = File(dir, "out.tmp")
            val fetcher = FakeFetcher(fileBytes, rangeSupported = false)
            val journal = SegmentJournal.fresh(fileBytes.size.toLong(), planSegments(fileBytes.size.toLong(), 4))
            val outcome = engine(fetcher).download("https://host.test/file", temp, journal) { _, _ -> }
            assertTrue(outcome is SegmentedOutcome.FallbackToSingle)
            assertTrue(fetcher.fetchedIndices.isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `a journal for a different file size is refused, never stitched`() = runBlocking {
        val dir = tempDir()
        try {
            val temp = File(dir, "out.tmp")
            val fetcher = FakeFetcher(fileBytes)
            val stale = SegmentJournal.fresh(500, planSegments(500, 4))
            val outcome = engine(fetcher).download("https://host.test/file", temp, stale) { _, _ -> }
            assertTrue(outcome is SegmentedOutcome.Failed)
        } finally {
            dir.deleteRecursively()
        }
    }

    // ── real OkHttp fetcher through an in-memory interceptor ─────────────────

    /** Serves [bytes] per Range header; scriptable per-path behaviour. */
    private class RangeClient(
        private val bytes: ByteArray,
        private val supportRange: Boolean = true,
        private val forbidden: Boolean = false,
    ) {
        val client: OkHttpClient = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                val range = request.header("Range")
                if (forbidden) {
                    return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(403)
                        .message("Forbidden")
                        .body(ByteArray(0).toResponseBody(null))
                        .build()
                }
                if (!supportRange || range == null) {
                    return@addInterceptor Response.Builder()
                        .request(request).protocol(Protocol.HTTP_1_1).code(200)
                        .message("OK")
                        .header("Content-Length", bytes.size.toString())
                        .header("Content-Type", "video/mp4")
                        .body(bytes.toResponseBody(null))
                        .build()
                }
                val wanted = range.removePrefix("bytes=").split("-")
                val start = wanted[0].toLong()
                val end = wanted[1].toLong()
                val slice = bytes.copyOfRange(start.toInt(), (end + 1).toInt())
                Response.Builder()
                    .request(request).protocol(Protocol.HTTP_1_1).code(206)
                    .message("Partial Content")
                    .header("Content-Range", "bytes $start-$end/${bytes.size}")
                    .header("Content-Type", "video/mp4")
                    .body(slice.toResponseBody(null))
                    .build()
            }
            .build()
    }

    @Test
    fun `real fetcher probes 206 and fetches a segment slice`() = runBlocking {
        val fetcher = OkHttpSegmentFetcher(RangeClient(fileBytes).client)
        val probe = fetcher.probe("https://host.test/file")
        assertTrue(probe.rangeSupported)
        assertEquals(fileBytes.size.toLong(), probe.totalBytes)
        assertEquals("video/mp4", probe.contentType)
        val dir = tempDir()
        try {
            val temp = File(dir, "out.tmp")
            java.io.RandomAccessFile(temp, "rw").use { it.setLength(fileBytes.size.toLong()) }
            val range = SegmentRange(1, 250, 500)
            val written = fetcher.fetch("https://host.test/file", range, 0) { _, _, _ -> }
            assertEquals(250L, written)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `real fetcher reports no range support on a 200 probe`() = runBlocking {
        val fetcher = OkHttpSegmentFetcher(RangeClient(fileBytes, supportRange = false).client)
        val probe = fetcher.probe("https://host.test/file")
        assertFalse(probe.rangeSupported)
        assertEquals(fileBytes.size.toLong(), probe.totalBytes)
    }

    @Test
    fun `a 403 anywhere in the segmented path falls back to one connection`() = runBlocking {
        val dir = tempDir()
        try {
            val temp = File(dir, "out.tmp")
            val fetcher = OkHttpSegmentFetcher(RangeClient(fileBytes, forbidden = true).client)
            val journal = SegmentJournal.fresh(fileBytes.size.toLong(), planSegments(fileBytes.size.toLong(), 4))
            val outcome = engine(fetcher).download("https://host.test/file", temp, journal) { _, _ -> }
            assertTrue(outcome is SegmentedOutcome.FallbackToSingle)
        } finally {
            dir.deleteRecursively()
        }
    }
}
