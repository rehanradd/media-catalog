package com.raddstream.app.data.cache

import android.net.Uri
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * The loopback server a MEGA file plays through. Same shape as [LocalMediaProxy]
 * — binds only `127.0.0.1`, speaks plain HTTP/1.1, answers `Range` requests —
 * but the bytes come from MEGA's ciphertext URL and are AES-CTR-decrypted on
 * the fly, so Media3 sees clear media and never knows the file was encrypted.
 *
 * Why this exists: a MEGA download URL serves ciphertext only, and ExoPlayer
 * cannot play AES-CTR bytes. The server cannot decrypt (no AES in the Python
 * stdlib) and must not relay (the ~5 GB / 6 h free quota is per egress IP, and
 * the VM has one). The app has `javax.crypto` and its own IP, so it fetches
 * the ciphertext, decrypts while streaming, and serves clear bytes to the
 * player — with the quota then applying to the user's own connection.
 *
 * The key lives in memory only (never disk, Room, DataStore, or a log) and
 * dies with this object. Seeking works because CTR is a stream cipher: byte
 * offset N lives in block N/16, whose counter is `IV || N/16` — no re-fetch
 * of the head, no re-key. A 2 MB aligned sliding window keeps ExoPlayer's
 * sequential reads from becoming one MEGA request per chunk.
 *
 * Call [verify] before handing [uriFor] to the player: it decrypts the `at`
 * attributes (must start `MEGA{`) and checks the first decrypted window for
 * a container magic — a bad key fails fast here, never plays garbage.
 */
class MegaDecryptProxy(
    private val gUrl: String,
    private val key: MegaCrypto.MergedKey,
    private val size: Long,
    private val contentType: String = "application/octet-stream",
    private val fetcher: MegaCiphertextFetcher,
    private val windowBytes: Long = DEFAULT_WINDOW_BYTES,
) : AutoCloseable {

    /** Fetches ciphertext for an inclusive byte range; throws on any failure. */
    fun interface MegaCiphertextFetcher {
        @Throws(Exception::class)
        fun fetch(start: Long, end: Long): ByteArray
    }

    private val server: ServerSocket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
    private val port: Int = server.localPort
    @Volatile private var running = true
    private val worker: Thread = thread(name = "radd-mega-proxy-$port", isDaemon = true) { acceptLoop() }

    // One decrypted window, aligned to [windowBytes]: the cache ExoPlayer's
    // sequential reads hit, and a seek outside it re-fetches exactly one window.
    @Volatile private var windowStart: Long = -1L
    @Volatile private var windowPlain: ByteArray = ByteArray(0)
    private val windowLock = Any()

    /** A loopback `http://127.0.0.1:port/mega` URI for the player. */
    fun uriFor(): Uri = Uri.parse("http://127.0.0.1:$port/mega")

    val baseHttpUrl: String get() = "http://127.0.0.1:$port/mega"

    /**
     * The key check: decrypt `at` (must start `MEGA{`) and the first window
     * (must start with a container magic). False means a bad key or a dead
     * file — close this proxy and report, never play.
     */
    fun verify(attrsBase64Url: String?): Boolean {
        if (MegaCrypto.decryptAttrs(attrsBase64Url, key.aesKey) == null) return false
        return try {
            val head = readPlain(0L, minOf(HEAD_CHECK_BYTES, size).toInt())
            MegaCrypto.isContainerMagic(head)
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Decrypted bytes for [start, start + length). Served from the cached
     * window when the range lies inside it; otherwise fetches exactly the
     * window containing [start] and decrypts it. Throws when MEGA cannot
     * serve the window — the HTTP layer turns that into a dropped connection,
     * which the player's recovery treats as a failed rendition.
     */
    fun readPlain(start: Long, length: Int): ByteArray {
        require(start >= 0 && length >= 0 && start + length <= size) {
            "readPlain [$start, ${start + length}) outside file size $size"
        }
        val out = ByteArray(length)
        var copied = 0
        while (copied < length) {
            val at = start + copied
            val window = windowContaining(at)
            val inWindow = (at - window.first).toInt()
            val take = minOf(length - copied, window.second.size - inWindow)
            window.second.copyInto(out, copied, inWindow, inWindow + take)
            copied += take
        }
        return out
    }

    /** The decrypted window holding [offset]: cached, or fetched + decrypted. */
    private fun windowContaining(offset: Long): Pair<Long, ByteArray> {
        synchronized(windowLock) {
            if (windowStart >= 0 && offset in windowStart until windowStart + windowPlain.size) {
                return windowStart to windowPlain
            }
            val aligned = (offset / windowBytes) * windowBytes
            val want = minOf(windowBytes, size - aligned)
            val cipher = fetcher.fetch(aligned, aligned + want - 1)
            require(cipher.size == want.toInt()) { "ciphertext window short: ${cipher.size} != $want" }
            val plain = MegaCrypto.decryptCtr(
                cipher, key.aesKey, MegaCrypto.counterFor(key.iv, aligned / BLOCK_BYTES),
            )
            windowStart = aligned
            windowPlain = plain
            return aligned to plain
        }
    }

    private fun acceptLoop() {
        while (running) {
            val socket = try {
                server.accept()
            } catch (_: Exception) {
                break
            }
            thread(isDaemon = true) { handle(socket) }
        }
        runCatching { server.close() }
    }

    private fun handle(socket: Socket) {
        try {
            socket.use { s ->
                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val requestLine = reader.readLine() ?: return
                val parts = requestLine.split(" ")
                if (parts.size < 2 || parts[0] != "GET") {
                    respond(s.getOutputStream(), 405, "text/plain", 0, null, "method not allowed".toByteArray())
                    return
                }
                var rangeHeader: String? = null
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isEmpty()) break
                    val idx = line.indexOf(':')
                    if (idx > 0 && line.substring(0, idx).trim().equals("Range", ignoreCase = true)) {
                        rangeHeader = line.substring(idx + 1).trim()
                    }
                }
                if (size <= 0) {
                    respond(s.getOutputStream(), 416, contentType, 0, "bytes */0", ByteArray(0))
                    return
                }
                val range = LocalMediaProxy.parseRange(rangeHeader, size)
                val start = range?.first ?: 0L
                val end = range?.second ?: (size - 1)
                if (start >= size) {
                    respond(s.getOutputStream(), 416, contentType, 0, "bytes */$size", ByteArray(0))
                    return
                }
                val effectiveEnd = minOf(end, size - 1)
                val len = (effectiveEnd - start + 1).coerceAtLeast(0L)
                val contentRange = "bytes $start-$effectiveEnd/$size"
                val status = if (range != null) 206 else 200
                // Fetch the first chunk BEFORE writing headers: a dead MEGA
                // fetch must drop the connection, not answer 200 with an
                // empty body. The window cache makes the streaming loop
                // below reuse this chunk — no second fetch.
                val firstChunk = try {
                    readPlain(start, minOf(len, LocalMediaProxy.STREAM_CHUNK_BYTES.toLong()).toInt())
                } catch (_: Exception) {
                    return
                }
                respondStreaming(s.getOutputStream(), status, contentType, len, contentRange, start, firstChunk)
            }
        } catch (_: Exception) {
            /* a dropped player connection, or a failed MEGA fetch mid-stream, is
               normal: the player reports it and its recovery fails over. */
        }
    }

    /**
     * Stream a byte range as clear bytes without materialising it: 64 KB
     * plaintext chunks, each decrypted from the sliding window — a whole-file
     * buffer would turn the first frame into an OOM on a 900 MB film.
     */
    private fun respondStreaming(
        out: OutputStream,
        status: Int,
        type: String,
        contentLength: Long,
        contentRange: String?,
        start: Long,
        firstChunk: ByteArray,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status ${reasonOf(status)}\r\n")
        sb.append("Content-Type: $type\r\n")
        sb.append("Content-Length: $contentLength\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (contentRange != null) sb.append("Content-Range: $contentRange\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        var remaining = contentLength
        var at = start
        if (firstChunk.isNotEmpty()) {
            out.write(firstChunk)
            at += firstChunk.size
            remaining -= firstChunk.size
        }
        while (remaining > 0) {
            val want = minOf(remaining, LocalMediaProxy.STREAM_CHUNK_BYTES.toLong()).toInt()
            val chunk = readPlain(at, want)
            out.write(chunk)
            at += chunk.size
            remaining -= chunk.size
        }
        out.flush()
    }

    private fun respond(
        out: OutputStream,
        status: Int,
        type: String,
        contentLength: Long,
        contentRange: String?,
        body: ByteArray,
    ) {
        val sb = StringBuilder()
        sb.append("HTTP/1.1 $status ${reasonOf(status)}\r\n")
        sb.append("Content-Type: $type\r\n")
        sb.append("Content-Length: $contentLength\r\n")
        sb.append("Accept-Ranges: bytes\r\n")
        if (contentRange != null) sb.append("Content-Range: $contentRange\r\n")
        sb.append("Connection: close\r\n\r\n")
        out.write(sb.toString().toByteArray())
        if (body.isNotEmpty()) out.write(body)
        out.flush()
    }

    private fun reasonOf(status: Int): String = when (status) {
        200 -> "OK"; 206 -> "Partial Content"; 405 -> "Method Not Allowed"; 416 -> "Range Not Satisfiable"
        else -> "OK"
    }

    override fun close() {
        running = false
        runCatching { server.close() }
        runCatching { worker.interrupt() }
    }

    companion object {
        /** One AES block; the CTR counter advances per block. */
        const val BLOCK_BYTES = 16L
        /** Aligned sliding window: one MEGA fetch per 2 MB of sequential play. */
        const val DEFAULT_WINDOW_BYTES = 2L * 1024 * 1024
        /** Bytes the key check reads to recognise a container. */
        const val HEAD_CHECK_BYTES = 64L

        /**
         * The production ciphertext fetcher: a plain Range GET against the g
         * URL (206 proven by the Phase C lane). A 200 — the host ignoring
         * Range — is a failure, not a fallback: seeking through it would
         * serve the wrong bytes as if they were the requested window.
         */
        fun okHttpFetcher(gUrl: String, client: OkHttpClient): MegaCiphertextFetcher =
            MegaCiphertextFetcher { start, end ->
                val request = Request.Builder()
                    .url(gUrl)
                    .header("Range", "bytes=$start-$end")
                    .header("User-Agent", "RaddStream/1.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.code != 206) {
                        throw IllegalStateException("MEGA ciphertext HTTP ${response.code}")
                    }
                    response.body?.bytes() ?: throw IllegalStateException("MEGA empty body")
                }
            }

        /** A production fetcher with the timeouts a player can afford to wait on. */
        fun defaultOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
