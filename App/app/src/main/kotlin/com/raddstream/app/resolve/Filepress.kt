package com.raddstream.app.resolve

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * Engine 1 — the filepress 2-POST FileBee chain, mirroring the server's
 * `unwrap_filepress` exactly: same fixed base, same endpoints, same headers,
 * same error vocabulary. No JavaScript, no page load, no cookies beyond the
 * chain's own tokens.
 */
object Filepress {

    const val BASE_URL = "https://new3.filepress.lat"
    const val USER_AGENT =
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    const val METHOD = "indexDownlaod"
    const val TIMEOUT_SECONDS = 15L

    /** Wall cap per attempt (plan §3.3): 10 s, aborted as `unknown`. */
    const val WALL_CAP_MS = 10_000L

    private val ID_IN_URL = Regex("/(?:file/)?([0-9a-f]{24})(?:[/?#]|$)")
    private val BARE_ID = Regex("^[0-9a-f]{24}$")

    /** The 24-hex file id out of a filepress/filebee page URL, or "". */
    fun extractId(url: String): String {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return ""
        ID_IN_URL.find(trimmed)?.let { return it.groupValues[1] }
        if (BARE_ID.matches(trimmed)) return trimmed
        return ""
    }

    /**
     * Percent-encode the last path segment of a minted link: percent-decode
     * it first (so an already-encoded segment is not double-encoded), then
     * encode everything except the unreserved set plus `. - _ ~` — the
     * server's `_filepress_encode_filename`. A `+` stays a `+`: it is decoded
     * only as `%XX`, never as a space.
     */
    fun encodeFilename(url: String): String {
        if (url.isEmpty()) return url
        val head = url.substringBeforeLast('/', "")
        if (head == url) return url
        val tail = url.substringAfterLast('/')
        val decoded = percentDecode(tail)
        return head + "/" + percentEncode(decoded)
    }

    internal fun percentDecode(segment: String): String {
        val bytes = ArrayList<Byte>()
        var i = 0
        while (i < segment.length) {
            val c = segment[i]
            if (c == '%' && i + 2 < segment.length) {
                val hex = segment.substring(i + 1, i + 3)
                val value = hex.toIntOrNull(16)
                if (value != null) {
                    bytes.add(value.toByte())
                    i += 3
                    continue
                }
            }
            val encoded = c.toString().toByteArray(Charsets.UTF_8)
            bytes.addAll(encoded.toList())
            i++
        }
        return String(bytes.toByteArray(), Charsets.UTF_8)
    }

    internal fun percentEncode(segment: String): String {
        val out = StringBuilder()
        for (byte in segment.toByteArray(Charsets.UTF_8)) {
            val c = byte.toInt() and 0xFF
            val ch = c.toChar()
            if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_' || ch == '~') {
                out.append(ch)
            } else {
                out.append('%')
                out.append(String.format("%02X", c))
            }
        }
        return out.toString()
    }

    /** One FileBee API POST, parsed. `Ok` carries the raw `data` value. */
    sealed interface ApiOutcome {
        data class Ok(val data: JsonValue) : ApiOutcome
        /** The short machine-readable note — the server's error vocabulary, verbatim. */
        data class Fail(val note: String) : ApiOutcome
    }

    fun parseApiResponse(status: Int, rawBody: String): ApiOutcome {
        if (status == 403 || status == 429) return ApiOutcome.Fail("blocked:$status")
        if (status == 404 || status == 410) return ApiOutcome.Fail("dead:$status")
        if (status != 200) return ApiOutcome.Fail("http:$status")
        val parsed = MiniJson.parse(rawBody) as? JsonValue.Obj ?: return ApiOutcome.Fail("bad_json")
        val statusValue = parsed.map["status"] as? JsonValue.Bool
        if (statusValue?.value != true) {
            val data = parsed.map["data"]
            val message = parsed.map["message"]
            val note = when {
                data is JsonValue.Str && data.value.isNotEmpty() -> data.value
                message is JsonValue.Str && message.value.isNotEmpty() -> message.value
                data is JsonValue.Num -> data.raw
                data is JsonValue.Bool -> data.value.toString()
                else -> "api_error"
            }
            return ApiOutcome.Fail(note)
        }
        return ApiOutcome.Ok(parsed.map["data"] ?: JsonValue.Null)
    }
}

sealed interface ChainResult {
    data class Minted(val mintedUrl: String, val probe: ProbeResult) : ChainResult
    data class Failed(val note: String, val probe: ProbeResult?) : ChainResult
}

/** Engine 1 runner: the two POSTs, then the 512-byte Range probe on the mint. */
class HttpChainEngine(private val transport: ResolveHttpTransport) {

    suspend fun resolve(bridgeUrl: String): ChainResult {
        return try {
            withTimeout(Filepress.WALL_CAP_MS) { resolveUntimed(bridgeUrl) }
        } catch (_: TimeoutCancellationException) {
            ChainResult.Failed("wall_cap", null)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            ChainResult.Failed("fetch_error:${e.javaClass.simpleName}", null)
        }
    }

    private suspend fun resolveUntimed(bridgeUrl: String): ChainResult {
        val fileId = Filepress.extractId(bridgeUrl)
        if (fileId.isEmpty()) return ChainResult.Failed("no_file_id", null)
        val headers = mapOf(
            "Content-Type" to "application/json",
            "Referer" to Filepress.BASE_URL,
            "User-Agent" to Filepress.USER_AGENT,
        )
        val firstBody = """{"id":"$fileId","method":"${Filepress.METHOD}","captchaValue":null}"""
        val first = apiCall("/api/file/downlaod/", firstBody, headers)
        if (first is Filepress.ApiOutcome.Fail) return ChainResult.Failed("downlaod: ${first.note}", null)
        val token = (first as Filepress.ApiOutcome.Ok).data as? JsonValue.Str
        if (token == null || token.value.isEmpty()) {
            return ChainResult.Failed("downlaod: empty token", null)
        }
        val secondBody = """{"id":"${token.value}","method":"${Filepress.METHOD}","captchaValue":null}"""
        val second = apiCall("/api/file/downlaod2/", secondBody, headers)
        if (second is Filepress.ApiOutcome.Fail) return ChainResult.Failed("downlaod2: ${second.note}", null)
        val links = (second as Filepress.ApiOutcome.Ok).data as? JsonValue.Arr
        val firstLink = links?.items?.firstOrNull() as? JsonValue.Str
        if (firstLink == null || firstLink.value.isEmpty()) {
            return ChainResult.Failed("downlaod2: empty link list", null)
        }
        val minted = Filepress.encodeFilename(firstLink.value)
        val probe = runRangeProbe(transport, minted)
        return ChainResult.Minted(minted, probe)
    }

    private suspend fun apiCall(endpoint: String, body: String, headers: Map<String, String>): Filepress.ApiOutcome {
        val response = try {
            transport.postJson(Filepress.BASE_URL + endpoint, body, headers)
        } catch (e: Exception) {
            return Filepress.ApiOutcome.Fail("fetch_error:${e.javaClass.simpleName}")
        }
        return Filepress.parseApiResponse(response.status, response.body.toString(Charsets.UTF_8))
    }
}
