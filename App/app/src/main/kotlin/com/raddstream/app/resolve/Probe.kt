package com.raddstream.app.resolve

import java.io.IOException
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/** One HTTP round trip, as the engines see it. */
data class TransportResponse(
    val status: Int,
    val headers: Map<String, String>,
    val body: ByteArray,
)

/**
 * The only network surface the resolve engines use. Tests substitute a fake;
 * production code uses [OkHttpResolveTransport]. The probe reads at most
 * [MAX_PROBE_BODY_BYTES] so a host that ignores Range and returns a whole
 * file cannot fill memory — the verdict only ever needs the first bytes.
 */
interface ResolveHttpTransport {
    suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): TransportResponse
    suspend fun getRange(url: String, headers: Map<String, String> = emptyMap()): TransportResponse
    fun close()
}

internal const val PROBE_BYTES = 512
internal const val MAX_PROBE_BODY_BYTES = 8192

/** The result of the 512-byte Range probe on a minted URL. */
data class ProbeResult(
    val httpStatus: Int?,
    val contentType: String?,
    val contentLength: Long?,
    val verdict: ProbeVerdict,
    val note: String?,
)

private val EBML_MAGIC = byteArrayOf(0x1A, 0x45, 0xDF.toByte(), 0xA3.toByte())
private val HLS_MARKER = "#EXTM3U".toByteArray(Charsets.UTF_8)

/**
 * The verdict logic, mirroring the server's `probe_range` — except where the
 * task spec says otherwise, and those two divergences are deliberate:
 * a 200 that holds no media is UNKNOWN here (the server calls it blocked),
 * and any other unlisted status is UNKNOWN here (the server calls it error).
 * A 200 that holds media is active with the note `full_body`, as on the server.
 */
fun probeVerdict(status: Int, contentType: String?, body: ByteArray, contentLength: Long?): ProbeResult {
    val type = (contentType ?: "").substringBefore(';').trim().lowercase()
    val isVideo = type.startsWith("video/")
    val isHls = type.contains("mpegurl") || body.startsWith(HLS_MARKER)
    val magic = containerMagic(body)
    val isMedia = isVideo || isHls || magic.isNotEmpty()
    return when {
        status <= 0 -> ProbeResult(status, type.ifEmpty { null }, contentLength, ProbeVerdict.ERROR, "fetch_error")
        status == 206 && isMedia -> ProbeResult(
            status, type.ifEmpty { null }, contentLength, ProbeVerdict.ACTIVE,
            when {
                isVideo -> null
                isHls -> "206 $type holds an HLS stream"
                else -> "206 $type holds a $magic container"
            },
        )
        status == 206 -> ProbeResult(status, type.ifEmpty { null }, contentLength, ProbeVerdict.UNKNOWN, "206 $type")
        status == 200 && isMedia -> ProbeResult(
            status, type.ifEmpty { null }, contentLength, ProbeVerdict.ACTIVE,
            when {
                isVideo -> "full_body"
                isHls -> "full_body, $type holds an HLS stream"
                else -> "full_body, $type holds a $magic container"
            },
        )
        status == 200 -> ProbeResult(status, type.ifEmpty { null }, contentLength, ProbeVerdict.UNKNOWN, "200 $type")
        status in setOf(401, 403, 429) -> ProbeResult(status, type.ifEmpty { null }, contentLength, ProbeVerdict.BLOCKED, "HTTP $status")
        status in setOf(404, 410) -> ProbeResult(status, type.ifEmpty { null }, contentLength, ProbeVerdict.DEAD, "HTTP $status")
        status in 500..599 -> ProbeResult(status, type.ifEmpty { null }, contentLength, ProbeVerdict.ERROR, "HTTP $status")
        else -> ProbeResult(status, type.ifEmpty { null }, contentLength, ProbeVerdict.UNKNOWN, "HTTP $status")
    }
}

/** `matroska` for EBML (`.mkv`/`.webm`), `mp4` for `ftyp` at offset 4, else "". */
fun containerMagic(body: ByteArray): String {
    if (body.size >= 4 && body.sliceArray(0..3).contentEquals(EBML_MAGIC)) return "matroska"
    if (body.size >= 8 && body.sliceArray(4..7).contentEquals("ftyp".toByteArray(Charsets.US_ASCII))) return "mp4"
    return ""
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
    if (size < prefix.size) return false
    return sliceArray(prefix.indices).contentEquals(prefix)
}

/**
 * Runs the Range probe over a transport and returns the verdict. The
 * content-length comes from the Content-Range total, else the Content-Length
 * header, else the bytes actually read.
 */
suspend fun runRangeProbe(transport: ResolveHttpTransport, url: String): ProbeResult {
    val response = try {
        transport.getRange(url)
    } catch (_: Exception) {
        return ProbeResult(null, null, null, ProbeVerdict.ERROR, "fetch_error")
    }
    val headers = response.headers
    val contentType = headers["content-type"]
    val total = headers["content-range"]
        ?.substringAfterLast('/', "")
        ?.trim()
        ?.toLongOrNull()
        ?: headers["content-length"]?.trim()?.toLongOrNull()
        ?: response.body.size.toLong()
    return probeVerdict(response.status, contentType, response.body, total)
}

/**
 * The engines' own client: no redirects (a mint that 302s is a mint to
 * follow by hand, never silently), its own connection pool, 15 s timeouts —
 * never the player/download client, so a resolve can never occupy a slot
 * the player or a download needs.
 */
class OkHttpResolveTransport : ResolveHttpTransport {
    private val client = OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    override suspend fun postJson(url: String, jsonBody: String, headers: Map<String, String>): TransportResponse {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return execute(request)
    }

    override suspend fun getRange(url: String, headers: Map<String, String>): TransportResponse {
        val request = Request.Builder()
            .url(url)
            .header("Range", "bytes=0-${PROBE_BYTES - 1}")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        return execute(request)
    }

    private fun execute(request: Request): TransportResponse {
        client.newCall(request).execute().use { response ->
            val headerMap = LinkedHashMap<String, String>()
            for (name in response.headers.names()) {
                headerMap[name.lowercase()] = response.headers[name] ?: ""
            }
            val body = response.body?.source()?.use { source ->
                val buffer = okio.Buffer()
                var remaining = MAX_PROBE_BODY_BYTES.toLong()
                while (remaining > 0 && !source.exhausted()) {
                    val read = source.read(buffer, minOf(remaining, 8192L))
                    if (read == -1L) break
                    remaining -= read
                }
                buffer.readByteArray()
            } ?: ByteArray(0)
            return TransportResponse(response.code, headerMap, body)
        }
    }

    override fun close() {
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
    }
}
