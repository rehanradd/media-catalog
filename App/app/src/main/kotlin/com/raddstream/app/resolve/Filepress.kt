package com.raddstream.app.resolve

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
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
    // The API's own spelling is "indexDownlaod" and "/api/file/downlaod[/2]/":
    // those strings go on the wire unchanged. Only the harness's step labels
    // in failure notes use correct English ("download:" / "download2:") —
    // deliberately diverging from the server's host_resolvers.py vocabulary,
    // which still writes "downlaod:".
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
        // rpartition semantics: no '/' means no segment to encode.
        val slash = url.lastIndexOf('/')
        if (slash < 0) return url
        val head = url.substring(0, slash)
        val tail = url.substring(slash + 1)
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
            // ASCII letters/digits only: urllib quote's always-safe set is
            // [A-Za-z0-9_.-~]. Char.isLetterOrDigit() is wrong here — it
            // accepts non-ASCII letters, so byte 0xC3 would leak out as a
            // raw 'Ã' instead of %C3.
            if ((ch in 'a'..'z' || ch in 'A'..'Z' || ch in '0'..'9') ||
                ch == '.' || ch == '-' || ch == '_' || ch == '~'
            ) {
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
            // The transport does blocking OkHttp execute() calls: they must
            // run on Dispatchers.IO, never on the ViewModel's Main thread —
            // two 15 s POSTs + a probe on Main would ANR the test phone.
            withContext(Dispatchers.IO) {
                withTimeout(Filepress.WALL_CAP_MS) { resolveUntimed(bridgeUrl) }
            }
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
        if (first is Filepress.ApiOutcome.Fail) return ChainResult.Failed("download: ${first.note}", null)
        val token = (first as Filepress.ApiOutcome.Ok).data as? JsonValue.Str
        if (token == null || token.value.isEmpty()) {
            return ChainResult.Failed("download: empty token", null)
        }
        val secondBody = """{"id":"${token.value}","method":"${Filepress.METHOD}","captchaValue":null}"""
        val second = apiCall("/api/file/downlaod2/", secondBody, headers)
        if (second is Filepress.ApiOutcome.Fail) return ChainResult.Failed("download2: ${second.note}", null)
        val links = (second as Filepress.ApiOutcome.Ok).data as? JsonValue.Arr
        val firstLink = links?.items?.firstOrNull() as? JsonValue.Str
        if (firstLink == null || firstLink.value.isEmpty()) {
            return ChainResult.Failed("download2: empty link list", null)
        }
        val minted = Filepress.encodeFilename(firstLink.value)
        val probe = runRangeProbe(transport, minted)
        return ChainResult.Minted(minted, probe)
    }

    private suspend fun apiCall(endpoint: String, body: String, headers: Map<String, String>): Filepress.ApiOutcome {
        // Run 1 (22:00–22:13 PKT 16 Nov, owner's phone): all 20 first POSTs
        // came back HTTP 301. The transport never follows redirects, so the
        // chain died on the redirect itself. The FileBee mirrors rotate
        // (server note: new2 301s to new3), so follow 301/302/303/307/308 by
        // re-POSTing the same JSON body to the Location, up to a cap — a GET
        // would be meaningless to this API, so 301/302/303 are followed with
        // the same POST, like 307/308. The Referer is re-based to the new
        // origin on each hop: a mismatched Referer turns the answer into
        // INVALID_REQUEST. A redirect with no Location, or past the cap,
        // fails like any other non-200.
        var url = Filepress.BASE_URL + endpoint
        var currentHeaders = headers
        repeat(MAX_REDIRECTS + 1) {
            val response = try {
                transport.postJson(url, body, currentHeaders)
            } catch (e: Exception) {
                return Filepress.ApiOutcome.Fail("fetch_error:${e.javaClass.simpleName}")
            }
            val status = response.status
            if (status in REDIRECT_STATUSES) {
                val location = response.headers["location"]?.trim().orEmpty()
                val next = if (location.isEmpty()) null else resolveUrl(url, location)
                if (next == null) return Filepress.ApiOutcome.Fail("http:$status")
                url = next
                currentHeaders = currentHeaders + ("Referer" to originOf(url))
            } else {
                return Filepress.parseApiResponse(status, response.body.toString(Charsets.UTF_8))
            }
        }
        return Filepress.ApiOutcome.Fail("redirect_loop")
    }

    internal fun resolveUrl(base: String, location: String): String? {
        return try {
            java.net.URI(base).resolve(location).toString()
        } catch (_: Exception) {
            null
        }
    }

    internal fun originOf(url: String): String {
        return try {
            val uri = java.net.URI(url)
            "${uri.scheme}://${uri.authority}"
        } catch (_: Exception) {
            Filepress.BASE_URL
        }
    }

    companion object {
        /** Redirect statuses the chain follows by re-POSTing to the Location. */
        val REDIRECT_STATUSES = setOf(301, 302, 303, 307, 308)

        /** Max redirect hops per API call before failing as `redirect_loop`. */
        const val MAX_REDIRECTS = 5
    }
}
