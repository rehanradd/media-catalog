package com.raddstream.app.network

import com.raddstream.app.brand.Brand
import com.raddstream.app.data.ServerSettingsRepository
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext

/**
 * Result of querying the unkillable gateway (Blogger deadman switch).
 */
data class DiscoveryResult(
    val primary: String?,
    val backups: List<String>,
    val verifiedWorkingUrl: String?,
)

/**
 * Dynamic Server Discovery — Unkillable Deadman Switch Fallback.
 *
 * If the configured node becomes unreachable or changes, this client queries the stealth
 * Blogger gateway (https://rehan-radd.blogspot.com/), reads the server list the owner
 * publishes there, verifies candidates by probing them, and applies the first working
 * one.
 *
 * The gateway serves JSON today — `{"status":"ok","primary":"...","backups":[...],"v":"..."}` —
 * so the JSON shape is parsed first; the old HTML meta tags (`radd-primary` and
 * `radd-backup-*`) stay as a fallback for a page that still carries them.
 *
 * Two rules are load-bearing and must not be relaxed:
 *  1. Only an HTTPS candidate may overwrite the saved primary. A plain-HTTP candidate
 *     (the fallback IP) is session-only: it is handed to [ApiProvider] as an in-memory
 *     override and never written to settings, so the next launch tries HTTPS first again.
 *  2. Discovery only ever runs for the app's own servers (the shipped default host and
 *     the documented fallback IP). A viewer who pointed the app at their own catalog
 *     must never be moved onto ours because theirs went down.
 *
 * It runs only when the configured server has already failed — the caller is the
 * remote-config watcher's failure path — never on a healthy launch and never on a
 * timer of its own. Attempts are spaced by [RECOVERY_COOLDOWN_MS] so a long outage
 * does not hammer the gateway every poll.
 */
@Singleton
open class DynamicServerDiscovery @Inject constructor(
    private val settingsRepository: ServerSettingsRepository,
    private val apiProvider: ApiProvider,
) {
    companion object {
        /** Minimum gap between recovery attempts, so a long outage stays quiet. */
        const val RECOVERY_COOLDOWN_MS: Long = 30L * 60_000L

        private val META_CONTENT_FIRST_REGEX = Regex(
            """<meta\s+content=['"]([^'"]+)['"]\s+name=['"]radd-(primary|backup-[^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        )
        private val META_NAME_FIRST_REGEX = Regex(
            """<meta\s+name=['"]radd-(primary|backup-[^'"]+)['"]\s+content=['"]([^'"]+)['"]""",
            RegexOption.IGNORE_CASE
        )

        // The gateway's JSON shape, hand-rolled (no org.json) so this stays usable
        // from plain JVM unit tests without Robolectric — the same reason Brand
        // hand-rolls its flat-JSON reader. Values are URLs, so escapes are rare,
        // but \" and \\ inside a value are honoured.
        private val JSON_PRIMARY_REGEX =
            Regex(""""primary"\s*:\s*"((?:[^"\\]|\\.)*)"""")
        private val JSON_BACKUPS_REGEX =
            Regex(""""backups"\s*:\s*\[([^\]]*)\]""")
        private val JSON_STRING_REGEX =
            Regex(""""((?:[^"\\]|\\.)*)"""")

        private fun unescapeJsonString(raw: String): String {
            if ('\\' !in raw) return raw
            val out = StringBuilder(raw.length)
            var i = 0
            while (i < raw.length) {
                val ch = raw[i]
                if (ch == '\\' && i + 1 < raw.length) {
                    when (raw[i + 1]) {
                        '"', '\\', '/' -> {
                            out.append(raw[i + 1])
                            i += 2
                        }
                        'n' -> {
                            out.append('\n')
                            i += 2
                        }
                        'r' -> {
                            out.append('\r')
                            i += 2
                        }
                        't' -> {
                            out.append('\t')
                            i += 2
                        }
                        else -> {
                            out.append(ch)
                            i++
                        }
                    }
                } else {
                    out.append(ch)
                    i++
                }
            }
            return out.toString()
        }

        /**
         * Parses the gateway's JSON body into primary and backup node lists, without
         * network calls. Returns null primary + empty backups when the text carries
         * no `primary`/`backups` keys at all, so the caller can fall back to the
         * meta-tag shape instead of treating a wrong page as an empty server list.
         */
        fun parseJsonNodes(raw: String): Pair<String?, List<String>> {
            val primary = JSON_PRIMARY_REGEX.find(raw)
                ?.groupValues?.get(1)?.let(::unescapeJsonString)?.trim()
                ?.takeIf { it.isNotEmpty() }
            val backupsBlock = JSON_BACKUPS_REGEX.find(raw)?.groupValues?.get(1)
            val backups = backupsBlock
                ?.let { block -> JSON_STRING_REGEX.findAll(block).map { unescapeJsonString(it.groupValues[1]).trim() }.toList() }
                .orEmpty()
                .filter { it.isNotEmpty() }
            if (primary == null && backups.isEmpty()) return Pair(null, emptyList())
            return Pair(primary, backups.distinct())
        }

        /**
         * Parses raw HTML into primary and backup node lists without network calls.
         */
        fun parseHtmlNodes(rawHtml: String): Pair<String?, List<String>> {
            var primary: String? = null
            val backups = mutableListOf<String>()

            META_CONTENT_FIRST_REGEX.findAll(rawHtml).forEach { match ->
                val url = match.groupValues[1].trim()
                val type = match.groupValues[2].lowercase()
                if (type == "primary") {
                    if (primary == null) primary = url
                } else {
                    backups.add(url)
                }
            }

            META_NAME_FIRST_REGEX.findAll(rawHtml).forEach { match ->
                val type = match.groupValues[1].lowercase()
                val url = match.groupValues[2].trim()
                if (type == "primary") {
                    if (primary == null) primary = url
                } else {
                    backups.add(url)
                }
            }

            return Pair(primary, backups.distinct())
        }

        /**
         * Parses whatever the gateway served: the JSON shape first, the meta-tag
         * shape when the text carries no JSON server list. A page that is neither
         * yields null primary + no backups, and the caller treats that as "no
         * servers published", never as an error.
         */
        fun parsePublishedNodes(raw: String): Pair<String?, List<String>> {
            val json = parseJsonNodes(raw)
            if (json.first != null || json.second.isNotEmpty()) return json
            return parseHtmlNodes(raw)
        }

        private fun hostOf(url: String): String? =
            runCatching { URI(url.trim()).host }.getOrNull()?.lowercase()?.takeIf { it.isNotEmpty() }

        /**
         * True for a value that is a placeholder, not a server: unparseable, or a
         * host the owner never pointed at anything (the `yourdomain.com` stand-ins
         * the gateway shipped with). Placeholders are dropped before probing, so
         * a stale gateway body cannot spend probe time — or a lucky DNS hit — on
         * a name that was never a server.
         */
        fun isPlaceholderCandidate(url: String): Boolean {
            val host = hostOf(url) ?: return true
            if (host.contains("yourdomain")) return true
            if (host == "example.com" || host.endsWith(".example.com")) return true
            if (host.contains("placeholder")) return true
            return false
        }

        /**
         * True only for a candidate that may overwrite the saved primary: an HTTPS
         * origin. A plain-HTTP candidate is a session-only fallback and must never
         * be persisted — see [discoverAndPersistWorkingServer].
         */
        fun isPersistableCandidate(url: String): Boolean {
            val scheme = runCatching { URI(url.trim()).scheme }.getOrNull()?.lowercase() ?: return false
            return scheme == "https"
        }

        /**
         * True only when the configured server is one of ours — the shipped default
         * host, or the documented plain-HTTP fallback IP. A viewer who pointed the
         * app at their own catalog is never moved onto ours because theirs went
         * down: their server is theirs, and this switch has nothing to say about it.
         */
        fun isOurServer(url: String): Boolean {
            val host = hostOf(url) ?: return false
            val defaultHost = hostOf(Brand.defaultServerUrl)
            if (host == defaultHost) return true
            val fallbackHost = hostOf(Brand.fallbackServerUrl)
            return fallbackHost != null && host == fallbackHost
        }

        /** True when enough time has passed since the last recovery attempt. */
        fun isRecoveryDue(lastAttemptMs: Long, nowMs: Long): Boolean {
            if (lastAttemptMs <= 0L) return true
            return nowMs - lastAttemptMs >= RECOVERY_COOLDOWN_MS
        }
    }

    /**
     * When the last recovery attempt ran, in epoch milliseconds; 0 means "never".
     * In-memory only, so a fresh process has no cooldown to honour — and the
     * read-only view is what the watcher tests assert on.
     */
    @Volatile
    var lastRecoveryAttemptMs: Long = 0L
        private set

    /**
     * Discovers all nodes published on the stealth gateway.
     */
    suspend fun fetchPublishedNodes(gatewayUrl: String = Brand.gatewayDiscoveryUrl): DiscoveryResult = withContext(Dispatchers.IO) {
        val targetUrl = gatewayUrl.trim().ifBlank { "https://rehan-radd.blogspot.com/" }
        val gatewayHost = hostOf(targetUrl)
        val rawBody = try {
            val conn = (URL(targetUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = 6000
                readTimeout = 6000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", Brand.userAgent)
            }
            conn.inputStream.use { stream ->
                BufferedReader(InputStreamReader(stream)).readText()
            }
        } catch (_: Exception) {
            return@withContext DiscoveryResult(null, emptyList(), null)
        }

        val (primary, backups) = parsePublishedNodes(rawBody)
        val candidates = (listOfNotNull(primary) + backups)
            .distinct()
            .filter { !isPlaceholderCandidate(it) }
            .filter { hostOf(it) != null && hostOf(it) != gatewayHost }
        var working: String? = null
        for (candidate in candidates) {
            if (probeNodeHealth(candidate)) {
                working = candidate
                break
            }
        }

        DiscoveryResult(primary, backups, working)
    }

    /**
     * Probes candidate node health by verifying /api/v1/posts or /setup check.
     */
    fun probeNodeHealth(serverUrl: String, timeoutMs: Int = 3000): Boolean {
        return try {
            val cleanUrl = serverUrl.trim().trimEnd('/')
            val testEndpoint = URL("$cleanUrl/api/v1/posts?limit=1")
            val conn = (testEndpoint.openConnection() as HttpURLConnection).apply {
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("User-Agent", Brand.userAgent)
            }
            conn.responseCode in 200..399
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Automatic failover: probes the gateway and applies the first working node.
     *
     * Open so a test can stand in a discovery that records the call and points
     * the gateway at an unroutable host: the watcher must be testable without
     * ever touching the real gateway.
     *
     * Only an HTTPS candidate overwrites the saved primary. A plain-HTTP working
     * candidate is applied as a session-only override through [ApiProvider] — it
     * is never written to settings, so the next launch tries HTTPS first again.
     * Returns the working URL that was applied, or null when nothing usable was
     * found (gateway unreachable, no servers published, every candidate failed
     * its probe, the configured server is not ours, or the cooldown has not
     * expired). Never throws: a failed recovery must cost the caller nothing.
     */
    open suspend fun discoverAndPersistWorkingServer(
        gatewayUrl: String = Brand.gatewayDiscoveryUrl,
        nowMs: Long = System.currentTimeMillis(),
    ): String? = withContext(Dispatchers.IO) {
        try {
            val savedUrl = settingsRepository.serverUrl.first() ?: return@withContext null
            if (!isOurServer(savedUrl)) return@withContext null
            if (!isRecoveryDue(lastRecoveryAttemptMs, nowMs)) return@withContext null
            lastRecoveryAttemptMs = nowMs
            val working = fetchPublishedNodes(gatewayUrl).verifiedWorkingUrl
                ?: return@withContext null
            if (isPersistableCandidate(working)) {
                settingsRepository.saveServerUrl(working)
            } else {
                apiProvider.useSessionServerUrl(savedUrl, working)
            }
            working
        } catch (_: Exception) {
            null
        }
    }
}
