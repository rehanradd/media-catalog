package com.raddstream.app.network

import com.google.gson.Gson
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.Telemetry
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import okhttp3.Cache
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory

/**
 * The v1 detail path — GET /api/v1/posts/{numeric-id} — whose 55-89 KB
 * body gets the longer read budget in [ApiProvider]'s timeout
 * interceptor (screenshot-fix 2026-11-16). The regex form keeps the
 * list/search prefix location (/api/v1/posts) on the default budget.
 */
internal val DETAIL_PATH_REGEX: Regex = Regex("^/api/v1/posts/[0-9]+$")

/** The shared client's read timeout — the budget every call gets unless [readTimeoutSecondsForPath] says longer. Int, not Long: this project's OkHttp `Chain.withReadTimeout` / `withConnectTimeout` take Int seconds, and a Long budget failed `compileDebugKotlin` (CI 43930521368). */
internal const val DEFAULT_READ_TIMEOUT_SECONDS: Int = 15

/**
 * The read timeout, in seconds, for one catalog path (screenshot-fix
 * 2026-11-16). The big bodies get longer budgets — the v2 works detail
 * is a 441 KB payload (60 s), the Live TV channel list is 167 KB
 * (45 s), a v1 detail is 55-89 KB (30 s) — because at the 2-25 KB/s
 * the owner's phone showed, the 15 s default killed every attempt
 * mid-body. Everything else keeps the 15 s default. Pure, so
 * [ApiProviderTimeoutTest] pins the mapping without a network call.
 */
internal fun readTimeoutSecondsForPath(path: String): Int = when {
    path.startsWith("/api/v2/works/") -> 60
    path == "/api/v1/live-tv/channels" -> 45
    DETAIL_PATH_REGEX.matches(path) -> 30
    else -> DEFAULT_READ_TIMEOUT_SECONDS
}

/**
 * Builds (and caches) the Retrofit client for the server the user picked.
 *
 * WHAT IS DELIBERATELY NOT INSTALLED: no `HttpLoggingInterceptor`. Even with provenance gone
 * from every response, a media URL is a bearer token for a live stream and the redirect chain
 * leaves it in OkHttp's BODY-level logs, and therefore into bug reports and `adb bugreport`
 * (App/AGENTS.md §3). If a future build adds a logger, it must be a redacting one, not the
 * stock interceptor.
 *
 * There is also no `If-None-Match` / ETag handling: the v1 server sends no ETag, and the v2
 * contract's ETag/304 support is not served yet — see `models/EndpointGaps.kt`.
 *
 * Feel-instant S1 (Nov 2026): the client DOES carry a 50 MB disk cache
 * ([httpCache], provided by [HttpCacheModule]) with two interceptors — a
 * network interceptor that forces `Cache-Control: public, max-age=…` onto
 * cacheable GET responses the server left unannotated, and an application
 * interceptor that serves those GETs stale while offline. Both are
 * allowlisted to the catalog read paths; POST `/api/v2/play`, auth and panel
 * calls never touch the cache. The two constructor parameters default to
 * null so plain-JVM tests keep constructing `ApiProvider(settings)` — a
 * null cache means no disk cache, exactly as before.
 *
 * Open, with [current] open, so a test can stand in a provider that throws
 * without a server (Session 157): the detail path must answer null on a
 * provider failure, and that contract is only pinnable through a fake.
 */
@Singleton
open class ApiProvider @Inject constructor(
    private val settingsRepository: ServerSettingsRepository,
    private val httpCache: Cache? = null,
    private val offlineCacheInterceptor: OfflineCacheInterceptor? = null,
) {
    @Volatile
    private var cachedBaseUrl: String? = null

    @Volatile
    private var cachedApi: RaddStreamApi? = null

    /**
     * The address THIS PROCESS is using instead of the saved one, and the saved
     * value the choice was made from.
     *
     * Session-only by design: a fallback to a plain-HTTP address is a decision
     * about the network right now, not a preference. It lives only in memory,
     * so a process death ends it and the next launch tries the saved address
     * first again — and it is never written to settings.
     *
     * Keying it to the saved value is what keeps that cache honest. A viewer
     * who changes server mid-session must land on the new one immediately, not
     * stay frozen on a fallback decided from the old one: [resolveSessionUrl]
     * drops the override the moment the saved value moves.
     */
    @Volatile
    private var sessionDecision: SessionDecision? = null

    private data class SessionDecision(val savedUrl: String, val sessionUrl: String)

    /**
     * Use [sessionUrl] for the rest of this process instead of [savedUrl], without
     * persisting anything. Only the deadman switch calls this, with a candidate
     * it already probed; both URLs are normalized here so the key comparison in
     * [resolveSessionUrl] is exact.
     */
    fun useSessionServerUrl(savedUrl: String, sessionUrl: String) {
        sessionDecision = SessionDecision(
            savedUrl = normalizeServerUrl(savedUrl),
            sessionUrl = normalizeServerUrl(sessionUrl),
        )
    }

    /** Drop the session override, if any — the saved address is live again. */
    fun clearSessionServerUrl() {
        sessionDecision = null
        // The cached client was built for the overridden address; drop it so
        // the next read answers from the saved address instead of a cleared
        // fallback.
        cachedBaseUrl = null
        cachedApi = null
    }

    /**
     * The address this process actually talks to for [savedUrl]: the session
     * override when one was decided from exactly this saved value, the saved
     * value itself otherwise. A saved value that moved since the decision
     * clears the override, so a mid-session server change is never shadowed
     * by a stale fallback.
     */
    fun resolveSessionUrl(savedUrl: String): String {
        // Normalize eagerly: a malformed saved URL must still throw here, the
        // way currentBaseUrl() always did (ApiProviderBaseUrlTest pins it) —
        // swallowing it would turn a broken setting into a silent wrong one.
        val saved = normalizeServerUrl(savedUrl)
        val decision = sessionDecision ?: return saved
        if (decision.savedUrl != saved) {
            sessionDecision = null
            return saved
        }
        return decision.sessionUrl
    }

    fun forServer(serverUrl: String): RaddStreamApi {
        val baseUrl = normalizeServerUrl(serverUrl)
        cachedApi?.let { api ->
            if (cachedBaseUrl == baseUrl) return api
        }

        synchronized(this) {
            cachedApi?.let { api ->
                if (cachedBaseUrl == baseUrl) return api
            }

            val defaultHost = runCatching { URI(Brand.defaultServerUrl).host }.getOrNull()
            val clientBuilder = OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                // Feel-instant S1: the 50 MB disk cache for catalog GETs, plus
                // the offline interceptor (added first, so a stale serve
                // short-circuits before the timeout/UA interceptors touch the
                // request) and the network interceptor that forces a max-age
                // onto unannotated catalog responses. A null cache (tests, or
                // a build without the module) means no disk cache, as before.
                .cache(httpCache)
                .addNetworkInterceptor(CacheControlNetworkInterceptor())
                .apply { offlineCacheInterceptor?.let { addInterceptor(it) } }
                // Screenshot-fix (2026-11-16): the 15 s read timeout is a
                // per-read silence budget, and the big catalog bodies do
                // not fit it on a slow connection — GET /api/v2/works/{id}
                // is a 441 KB payload (measured 2026-11-16) and the Live TV
                // channel list is 167 KB, so at the 2-25 KB/s the owner's
                // phone showed, every attempt died mid-body and the detail
                // retry burned all 4 attempts before showing
                // "Couldn't load this title" — a load that was merely slow
                // read as a failure. The big bodies get a longer read
                // budget (see [readTimeoutSecondsForPath]); the default
                // stays 15 s for everything else, so a dead small call
                // still fails fast. Per-request, not a second client: the
                // timeout rides the call, and the retry policy in
                // DetailViewModel still bounds the attempts.
                .addInterceptor { chain ->
                    val path = chain.request().url.encodedPath
                    val readSeconds = readTimeoutSecondsForPath(path)
                    val timeoutChain = if (readSeconds != DEFAULT_READ_TIMEOUT_SECONDS) {
                        chain.withReadTimeout(readSeconds, TimeUnit.SECONDS)
                            .withConnectTimeout(15, TimeUnit.SECONDS)
                    } else {
                        chain
                    }
                    timeoutChain.proceed(timeoutChain.request())
                }
                // Every request carries the app's own User-Agent, including its
                // version name: the config fetch is how the server learns
                // which build is asking, and the discovery probes already
                // send this same string. No header, no version on the wire.
                //
                // Tracking A adds the install headers on top, and only once
                // the viewer said yes to anonymous totals: `X-Install-Id` (a
                // random id minted on accept, never derived from the phone),
                // `X-App-Version`, `X-Device-Class` (low/mid/high from RAM),
                // `X-Region` (the viewer's region card), the two language
                // choices, and `Accept-Language` (the server reads only its
                // region subtag). A viewer who declined or was never asked
                // sends exactly what this client sent before — the User-Agent
                // alone — and the server falls back to the credential digest,
                // as it always did.
                .addInterceptor { chain ->
                    val builder = chain.request().newBuilder()
                        .header("User-Agent", Brand.userAgent)
                    if (Telemetry.consentAccepted) {
                        Telemetry.installId?.let { builder.header("X-Install-Id", it) }
                        builder.header("X-App-Version", Telemetry.appVersion)
                        builder.header("X-Device-Class", Telemetry.deviceClass)
                        Telemetry.region?.let { builder.header("X-Region", it) }
                        Telemetry.audioLanguage?.takeIf { it.isNotBlank() }
                            ?.let { builder.header("X-Audio-Lang", it) }
                        Telemetry.subtitleLanguage?.takeIf { it.isNotBlank() }
                            ?.let { builder.header("X-Subtitle-Lang", it) }
                        builder.header("Accept-Language", Telemetry.acceptLanguage)
                    }
                    chain.proceed(builder.build())
                }
            // Certificate pinning, default host only: `certificatePinnerFor`
            // returns null for any other server (or no pins), so a user-chosen
            // catalog is never pinned and the platform trust store decides.
            certificatePinnerFor(baseUrl, defaultHost, Brand.tlsPins)?.let {
                clientBuilder.certificatePinner(it)
            }
            val client = clientBuilder.build()
            // MUST be buildRaddGson(), not GsonBuilder().create(): the v1 wire sends
            // `is_temporary` as 1/0 on /api/v1/posts/{id} and as true/false on
            // /api/v1/stream, and keeps INTEGER-affinity columns as TEXT when the scraped
            // value did not look numeric. The stock config throws on both. See RaddGson.kt.
            val gson: Gson = buildRaddGson()
            val api = Retrofit.Builder()
                .baseUrl(baseUrl)
                .client(client)
                .addConverterFactory(GsonConverterFactory.create(gson))
                .build()
                .create(RaddStreamApi::class.java)

            cachedBaseUrl = baseUrl
            cachedApi = api
            return api
        }
    }

    open suspend fun current(): RaddStreamApi {
        val savedUrl = settingsRepository.serverUrl.first()
            ?: throw IllegalStateException("Connect to a catalog server first.")
        return forServer(resolveSessionUrl(savedUrl))
    }

    /**
     * The normalized origin of the server the user connected to. Two jobs: build our own
     * redirector address with [streamRedirectUrl], and be the reference origin every hand-off
     * is checked against with [isSameOriginAsServer].
     *
     * Batch B detail regression (2026-11-14): this used to re-read DataStore and
     * re-normalize on every call, so a failure of that read AFTER a successful detail
     * response killed the whole load — the base-URL read sits after the network call in
     * `CatalogRepository.getWorkDetailV2`, and its narrow catch let the throw escape to
     * the detail error screen ("Failed to load details. Check connection.") even though
     * the API had answered 200. The Retrofit client is already built from this same URL
     * and cached, so a populated cache is the answer: return it without touching
     * storage. Only a cold start (no call yet) reads DataStore.
     *
     * Open for tests: a fake provider answers `current()` with a stub API and
     * `currentBaseUrl()` with a failing read, to pin the repository's fallback.
     */
    open suspend fun currentBaseUrl(): String {
        cachedBaseUrl?.let { cached ->
            // A session fallback decided from this exact saved value still
            // applies; any other cached value is the live answer already.
            val decision = sessionDecision
            if (decision != null && cached == decision.savedUrl) return decision.sessionUrl
            return cached
        }
        val savedUrl = settingsRepository.serverUrl.first()
            ?: throw IllegalStateException("Connect to a catalog server first.")
        return resolveSessionUrl(savedUrl)
    }
}

/**
 * The VIP pass as a request header (Session 126): `X-Radd-Vip-Pass: <pass>`.
 * The same verification as `?vip=` on the server side (`redact.VIP_PASS_HEADER`),
 * but the pass never enters a URL — so it never lands in WebView history, a
 * back-stack argument, or a bug report. Only the Embed WebView uses this form:
 * it loads our own redirector directly, so the header rides the one request
 * that needs it. The player and the download engine keep [withVipPass], because
 * they follow the 302 outside this process and a header cannot ride along.
 */
const val VIP_PASS_HEADER: String = "X-Radd-Vip-Pass"

/**
 * Our own redirector address for an opaque stream handle: `<server>/api/v1/stream?id=<handle>`.
 * This is the ONLY playable address this app ever builds, hands to an external player, or writes
 * into a download task. The server answers it with a 302, so no upstream host ever appears here.
 */
fun streamRedirectUrl(baseUrl: String, streamId: String): String {
    val origin = baseUrl.trim().run { if (endsWith("/")) dropLast(1) else this }
    val handle = URLEncoder.encode(streamId.trim(), StandardCharsets.UTF_8.name())
    return "$origin/api/v1/stream?id=$handle"
}

/**
 * Our own download address for an opaque stream handle: `<server>/api/v1/download?id=<handle>`.
 * The in-app device-download path fetches through this, never through the
 * play redirector and never through a browser: the server answers it with
 * a 302 to the file, exactly like `/api/v1/stream`, and the download
 * client follows it with Range resume. (The HLS keep engine still fetches
 * its playlist through the play redirector — a segmented keep, not a file
 * download.)
 */
fun downloadRedirectUrl(baseUrl: String, streamId: String): String {
    val origin = baseUrl.trim().run { if (endsWith("/")) dropLast(1) else this }
    val handle = URLEncoder.encode(streamId.trim(), StandardCharsets.UTF_8.name())
    return "$origin/api/v1/download?id=$handle"
}

/**
 * Appends a signed VIP pass to one of our own redirector/download URLs
 * (Session 123): `…?vip=<pass>`. A null or blank pass leaves the URL
 * untouched, so guests and free accounts send exactly what they sent
 * before. The pass — not the sync key — is what travels here, because the
 * player and the download engine follow the 302 outside this process and a
 * header cannot ride along, while a pass carries no user id and cannot be
 * pointed at a person. Callers fetch it once per URL build from
 * `SyncAccountRepository.currentVipPass()`; the value is cached there, so
 * this never costs a network call at play time.
 */
fun withVipPass(url: String, pass: String?): String {
    if (pass.isNullOrBlank()) return url
    val encoded = URLEncoder.encode(pass.trim(), StandardCharsets.UTF_8.name())
    return url + if ("?" in url) "&vip=$encoded" else "?vip=$encoded"
}

/**
 * True only when [playUrl] is an absolute http(s) address on the SAME host+port as [baseUrl] —
 * the origin the user configured. Every hand-off in the app (external player, download, share)
 * checks this first, so an address belonging to anyone else is refused even if one somehow
 * reached a model.
 */
fun isSameOriginAsServer(playUrl: String?, baseUrl: String): Boolean {
    val target = playUrl?.trim()?.takeIf { it.isNotEmpty() } ?: return false
    val base = baseUrl.trim().takeIf { it.isNotEmpty() } ?: return false
    val targetUri = try { URI(target) } catch (_: Exception) { return false }
    val baseUri = try { URI(base) } catch (_: Exception) { return false }
    val scheme = targetUri.scheme?.lowercase(Locale.ROOT) ?: return false
    if (scheme != "http" && scheme != "https") return false
    val host = targetUri.host?.lowercase(Locale.ROOT) ?: return false
    val baseHost = baseUri.host?.lowercase(Locale.ROOT) ?: return false
    if (host != baseHost) return false
    val basePort = if (baseUri.port >= 0) baseUri.port else defaultPort(baseUri.scheme)
    val targetPort = if (targetUri.port >= 0) targetUri.port else defaultPort(targetUri.scheme)
    return basePort == targetPort
}

private fun defaultPort(scheme: String?): Int = when (scheme?.lowercase(Locale.ROOT)) {
    "https" -> 443
    "http" -> 80
    else -> -1
}

/**
 * Validate and normalize a user-entered server origin before passing it to Retrofit.
 * Credentials, query strings, and fragments are rejected rather than persisted.
 */
fun normalizeServerUrl(input: String): String {
    val value = input.trim()
    require(value.isNotEmpty()) { "Enter a server URL." }

    val uri = try {
        URI(value)
    } catch (_: Exception) {
        throw IllegalArgumentException("Enter a valid server URL.")
    }

    val scheme = uri.scheme?.lowercase(Locale.ROOT)
    require(scheme == "http" || scheme == "https") {
        "The server URL must start with http:// or https://."
    }
    require(!uri.host.isNullOrBlank()) { "The server URL must include a hostname." }
    require(uri.rawUserInfo == null && uri.rawQuery == null && uri.rawFragment == null) {
        "Do not include credentials, a query, or a fragment in the server URL."
    }

    val normalizedScheme = value.replaceFirst(
        Regex("^[A-Za-z][A-Za-z0-9+.-]*://"),
        "$scheme://",
    )
    return if (normalizedScheme.endsWith('/')) normalizedScheme else "$normalizedScheme/"
}

/**
 * The certificate pinner for one server, or null when pinning does not apply.
 *
 * Pins are enforced ONLY for the shipped default host: a user-chosen server is
 * never pinned, and an empty pin list means the platform trust store decides,
 * exactly as before. `defaultHost` is the host of `Brand.defaultServerUrl` —
 * never a literal in this file, so a re-skin moves the pins with the brand.
 * A pin that fails to parse throws here, at client build time: a bad pin must
 * fail loudly, not silently pin nothing.
 */
fun certificatePinnerFor(baseUrl: String, defaultHost: String?, pins: List<String>): CertificatePinner? {
    if (pins.isEmpty()) return null
    val host = runCatching { URI(normalizeServerUrl(baseUrl)).host }.getOrNull() ?: return null
    if (defaultHost.isNullOrBlank() || !host.equals(defaultHost, ignoreCase = true)) return null
    return CertificatePinner.Builder().apply {
        pins.forEach { add(defaultHost, "sha256/$it") }
    }.build()
}
