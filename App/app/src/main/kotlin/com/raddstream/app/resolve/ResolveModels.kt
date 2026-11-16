package com.raddstream.app.resolve

/** The probe's verdict on a minted URL, mirroring the server's `probe_range`. */
enum class ProbeVerdict {
    ACTIVE, BLOCKED, DEAD, ERROR, UNKNOWN,
}

/** What the bridge page showed while the WebView engine watched it. */
enum class Challenge {
    NONE, TURNSTILE, CAPTCHA, WAIT_TIMER, OTHER,
}

enum class AttemptKind {
    NORMAL, SESSION_CHECK, REPEAT,
}

enum class ResolveEngine {
    HTTP_CHAIN, WEBVIEW,
}

/** One row of the sample asset. */
data class ResolveSample(
    val linkId: Long,
    val bridgeUrl: String,
    val service: String,
    val title: String,
    val year: Int?,
    val quality: String,
    val postId: Long,
    val blocklist: Boolean,
)

/** Everything the field test records for one attempt, per plan §8.2. */
data class AttemptResult(
    val linkId: Long,
    val bridgeUrl: String,
    val service: String,
    val engine: ResolveEngine,
    val blocklist: Boolean,
    val attemptKind: AttemptKind,
    val startedAtMs: Long,
    val finishedAtMs: Long,
    val timeToResolveMs: Long,
    val success: Boolean,
    /** The minted URL in full — never truncated, never hashed. */
    val mintedUrl: String?,
    val probeContentType: String?,
    val probeContentLength: Long?,
    val probeHttpStatus: Int?,
    val probeVerdict: ProbeVerdict?,
    /** The probe's own note (`full_body`, `206 text/html holds …`) — part of the probe record. */
    val probeNote: String?,
    /** TrafficStats UID rx+tx delta; null when the platform reports UNSUPPORTED. */
    val dataUsedBytes: Long?,
    val batteryPctBefore: Int?,
    val batteryPctAfter: Int?,
    /** WebView attempts only; null for HTTP-chain attempts. */
    val webviewPssKbBefore: Long?,
    val webviewPssKbAfter: Long?,
    val challenge: Challenge,
    /** The short machine-readable note for a failure (error vocabulary, verbatim). */
    val error: String?,
)

/** The run header written at the top of every export. */
data class RunHeader(
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String,
    val runStartedEpochMs: Long,
    /** wifi / mobile / other / none — never an SSID. */
    val networkType: String,
    val webviewSuffixApplied: Boolean,
)
