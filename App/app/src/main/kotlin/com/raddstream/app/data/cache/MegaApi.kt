package com.raddstream.app.data.cache

import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The MEGA public file-info API, minted by the APP — never by the server.
 *
 * `POST https://g.api.mega.co.nz/cs` with `[{"a":"g","g":1,"p":"<node_id>"}]`
 * answers 200, no account, no CAPTCHA (proven by the Phase C mega lane):
 * `{"g": "<gfs URL>", "at": "<attrs>", "s": <size>}`. The app mints its own
 * g URL per press, so the free transfer quota (~5 GB / 6 h) accrues on the
 * USER's IP, not the shared server IP — and whether g URLs are bound to the
 * minting IP never matters, because the minter and the fetcher are the same
 * device.
 *
 * The response JSON is parsed by hand (flat shape, one regex per field): the
 * class must stay unit-testable on plain JVM, where `org.json` is a stub.
 * The link key is never sent here — the `#` fragment never leaves the device —
 * and the g URL is never logged or persisted: it is minted per press and held
 * in memory for the proxy's lifetime only.
 */
class MegaApi(
    private val httpPost: (url: String, body: String) -> HttpResult = defaultHttpPost(),
) {

    /** A raw HTTP answer: status code + body text. */
    data class HttpResult(val status: Int, val body: String)

    /** The minted download: ciphertext URL, encrypted attrs, plaintext size. */
    data class MegaMint(val gUrl: String, val attrsBase64Url: String, val size: Long)

    sealed class MintResult {
        data class Ok(val mint: MegaMint) : MintResult()
        /** The file is gone at MEGA (-9 not found, -11 access, -16 banned uploader). */
        data class Dead(val errorCode: Int) : MintResult()
        /** MEGA's free transfer quota for this IP is spent (-18 / HTTP 509). */
        data object Quota : MintResult()
        /** No answer, or an answer this parser cannot use: a transport failure, never dead. */
        data class Transport(val detail: String) : MintResult()
    }

    fun mint(nodeId: String): MintResult {
        if (nodeId.isBlank()) return MintResult.Transport("empty node id")
        val body = """[{"a":"g","g":1,"p":"${nodeId.trim()}"}]"""
        val result = try {
            httpPost(MEGA_API_URL, body)
        } catch (e: Exception) {
            return MintResult.Transport(e.javaClass.simpleName)
        }
        if (result.status == 509) return MintResult.Quota
        if (result.status != 200) return MintResult.Transport("HTTP ${result.status}")
        val text = result.body.trim()
        // An error is a bare JSON integer, e.g. `-9`.
        val errorCode = text.toIntOrNull()
        if (errorCode != null) {
            return when (errorCode) {
                -18 -> MintResult.Quota
                -9, -11, -16 -> MintResult.Dead(errorCode)
                else -> MintResult.Transport("MEGA error $errorCode")
            }
        }
        val gUrl = extractStringField(text, "g")
        val attrs = extractStringField(text, "at")
        val size = extractLongField(text, "s")
        if (gUrl.isNullOrBlank() || attrs.isNullOrBlank() || size == null || size < 0) {
            return MintResult.Transport("unusable g API answer")
        }
        return MintResult.Ok(MegaMint(gUrl, attrs, size))
    }

    internal fun extractStringField(json: String, name: String): String? {
        val regex = Regex(""""$name"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        val raw = regex.find(json)?.groupValues?.getOrNull(1) ?: return null
        return raw.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\/", "/")
    }

    internal fun extractLongField(json: String, name: String): Long? {
        val regex = Regex(""""$name"\s*:\s*(-?\d+)""")
        return regex.find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()
    }

    companion object {
        const val MEGA_API_URL = "https://g.api.mega.co.nz/cs"

        /** The production transport: OkHttp, short timeouts, no logging of the body. */
        fun defaultHttpPost(): (String, String) -> HttpResult {
            val client = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
            val mediaType = "application/json".toMediaType()
            return { url, body ->
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody(mediaType))
                    .header("User-Agent", "RaddStream/1.0")
                    .build()
                client.newCall(request).execute().use { response ->
                    HttpResult(response.code, response.body?.string().orEmpty())
                }
            }
        }
    }
}
