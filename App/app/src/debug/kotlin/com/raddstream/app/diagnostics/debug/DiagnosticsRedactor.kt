package com.raddstream.app.diagnostics.debug

import java.security.MessageDigest

/**
 * The redactor (APP-13, review B-1). Pure Kotlin, no Android dependencies,
 * so the unit tests run on the plain JVM. Debug-only: it lives in
 * `src/debug`, so no release build can call it — and no release build can
 * store a raw credential through it either.
 *
 * Why this exists: a media URL IS a bearer token for a live stream
 * (`ApiProvider` KDoc, App/AGENTS.md §3) — the credential rides in the query
 * string of signed URLs (FSL/R2/Google usercontent: `X-Amz-Signature`,
 * `sig`, `token`, `s`, `e`…) — and the sync key travels as
 * `Authorization: Bearer <key>`. Redaction happens BEFORE capture, inside
 * [DebugDiagnostics.record], never at export time: an event that was
 * recorded clean can never leak through a new export path. Export re-runs
 * the redactor defensively (review R-5).
 *
 * Rules (review B-1, parent steer 09:47 PKT):
 * - [redactUrl]: DROP the WHOLE URL (return [REDACTED]) when any query key
 *   is sensitive, or when the host matches a known media-URL pattern
 *   (`*.r2.cloudflarestorage.com`, `*.r2.dev`, `*.googleusercontent.com`).
 *   A clean URL passes through (after the free-text pass, so a stray
 *   `Bearer` fragment in a path cannot survive either).
 * - [redactHeader]: `Authorization`, `Cookie` / `Set-Cookie`, and any header
 *   whose name mentions token/key/credential/secret → [REDACTED].
 * - [redactText]: scrubs `Bearer` / `Basic` values, `token=` / `key=` /
 *   `session=` / `session_id=` / `secret=` / `password=` fragments, any
 *   32+ char base64url-looking value after `=`, and any full media-pattern
 *   URL in free text (an error message can carry one).
 * - [hashId]: SHA-256 hex for account/user ids before storage. None are
 *   recorded today; the function is pinned by test for the day one is.
 * - [truncate]: free-text fields are capped at [MAX_FIELD_CHARS] (200) at
 *   record time — search queries and titles (B-1) and every other field.
 * - [isSafe]: second-pass assertion the recorder runs after redacting —
 *   true when no surviving `Bearer <value>` (other than `Bearer ***REDACTED***`)
 *   remains. A field that fails it is replaced with [REDACTED_UNSAFE].
 *
 * HANDLES (parent steer, overriding the model's ALLOW): playback handles /
 * player IDs are bearer-token-like — they mint playable URLs. NO handle is
 * recorded anywhere in this feature (`displayLabel()` returns quality
 * language + playback label only; error summaries go through [redactText]),
 * and any handle-shaped URL that did reach a field is dropped by the
 * media-pattern rules above.
 */
object DiagnosticsRedactor {

    /** Marker written wherever a credential was removed (review R-5). */
    const val REDACTED: String = "***REDACTED***"

    /** Marker for a field whose redaction failed the safety check. */
    const val REDACTED_UNSAFE: String = "***REDACTED-UNSAFE***"

    /** Free-text fields are truncated to this length at record time (B-1). */
    const val MAX_FIELD_CHARS: Int = 200

    /**
     * Query keys whose VALUE is a credential. Long names match by substring
     * (so `api_key`, `X-Amz-Signature`, `access_token` all match); short
     * names match exactly, because a substring match on `e` or `s` would
     * match every key that contains the letter.
     */
    private val SENSITIVE_SUBSTRINGS: List<String> = listOf(
        "token", "auth", "sig", "secret", "password", "passwd",
        "access", "refresh", "session", "credential", "expires",
        "expiry", "md5", "hmac", "key",
    )

    /** Short sensitive keys: exact match only (see above). */
    private val SENSITIVE_EXACT: Set<String> = setOf("e", "s", "st")

    /** Host suffixes whose URLs are media URLs — dropped in full (B-1). */
    private val MEDIA_HOST_SUFFIXES: List<String> = listOf(
        ".r2.cloudflarestorage.com",
        ".r2.dev",
        ".googleusercontent.com",
    )

    private fun isSensitiveKey(rawKey: String): Boolean {
        val key = rawKey.lowercase()
        if (key in SENSITIVE_EXACT) return true
        return SENSITIVE_SUBSTRINGS.any { key.contains(it) }
    }

    private fun isMediaUrl(url: String): Boolean {
        val host = runCatching {
            val afterScheme = url.substringAfter("://", "")
            afterScheme.substringBefore('/').substringBefore('?').lowercase()
        }.getOrNull().orEmpty()
        return MEDIA_HOST_SUFFIXES.any { host.endsWith(it) }
    }

    private val bearerRegex = Regex("(?i)Bearer\\s+[^\\s]+")
    private val basicRegex = Regex("(?i)Basic\\s+[^\\s]+")
    private val keyValueRegex =
        Regex("(?i)(token|key|session|session_id|secret|password|passwd|auth|sig|signature|expires|expiry|access|refresh|credential|hmac|md5)=[^&\\s\"']+")
    private val longTokenRegex = Regex("=([A-Za-z0-9_\\-]{32,})")
    private val unsafeBearerRegex = Regex("(?i)Bearer\\s+(?!\\*\\*\\*REDACTED\\*\\*\\*)[^\\s]+")
    private val mediaUrlRegex = Regex(
        "(?i)https?://[^\\s\"']*\\.(r2\\.cloudflarestorage\\.com|r2\\.dev|googleusercontent\\.com)[^\\s\"']*",
    )

    /**
     * Redact a URL. The whole URL is dropped ([REDACTED]) when it is a
     * media URL or when any query key is sensitive — a bearer-token URL
     * keeps neither its host nor its path, because the path itself can
     * identify the file. A clean URL passes through unchanged (after the
     * free-text pass). Never throws: a malformed input is returned through
     * [redactText] unchanged apart from the scrubbing.
     */
    fun redactUrl(url: String): String {
        if (isMediaUrl(url)) return REDACTED
        val queryStart = url.indexOf('?')
        if (queryStart < 0) return redactText(url)
        val query = url.substring(queryStart + 1)
        if (query.isEmpty()) return redactText(url.substring(0, queryStart))
        val anySensitive = query.split('&').any { param ->
            isSensitiveKey(param.substringBefore('='))
        }
        if (anySensitive) return REDACTED
        return redactText(url)
    }

    /**
     * Redact one header value by header name. The recorder never records
     * header values at all except through this function — the API interceptor
     * records method + path + status + duration only.
     */
    fun redactHeader(name: String, value: String): String {
        val lower = name.lowercase()
        if (lower == "authorization" || lower == "cookie" || lower == "set-cookie") {
            return REDACTED
        }
        if (lower.contains("token") || lower.contains("key") ||
            lower.contains("credential") || lower.contains("secret")
        ) {
            return REDACTED
        }
        return redactText(value)
    }

    /** Scrub credential-shaped fragments out of free text. */
    fun redactText(text: String): String {
        var out = mediaUrlRegex.replace(text, REDACTED)
        out = bearerRegex.replace(out, "Bearer $REDACTED")
        out = basicRegex.replace(out, "Basic $REDACTED")
        out = keyValueRegex.replace(out, "$1=$REDACTED")
        out = longTokenRegex.replace(out, "=$REDACTED")
        return out
    }

    /**
     * True when [text] holds no surviving credential value: no `Bearer`
     * value other than `Bearer ***REDACTED***`. The recorder's second pass.
     */
    fun isSafe(text: String): Boolean = !unsafeBearerRegex.containsMatchIn(text)

    /**
     * SHA-256 hex of an account/user id, for storage (review B-1: HASH
     * before storage). No account id is recorded today — this is the pinned
     * capability for the day one is.
     */
    fun hashId(id: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { it.toInt().and(0xFF).toString(16).padStart(2, '0') }
    }

    /** Truncate a free-text field to [MAX_FIELD_CHARS] (B-1). */
    fun truncate(field: String): String = field.take(MAX_FIELD_CHARS)
}
