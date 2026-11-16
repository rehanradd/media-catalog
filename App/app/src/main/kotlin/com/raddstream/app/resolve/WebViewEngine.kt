package com.raddstream.app.resolve

import android.content.Context
import android.net.TrafficStats
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.webkit.CookieManager
import android.webkit.DownloadListener
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import java.io.ByteArrayInputStream
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Engine 2 — headless WebView (real Chromium JS, non-visible), for the
 * 1fichier free flow and the NEEDS-JS control hosts.
 *
 * Isolation, per plan §3.2 — all mandatory: never attached to a window,
 * never composited, never shown; one WebView per attempt, destroyed after;
 * third-party cookies off; first-party cookies wiped before AND after;
 * no geolocation, no autofill, no password saving, no
 * `addJavascriptInterface` (extraction via `evaluateJavascript` only,
 * page → app, one direction); a no-op DownloadListener that captures the
 * URL and aborts; same-tab navigation is followed across hosts (run-1 fix:
 * bridges navigate cross-host normally — the old same-host guard aborted
 * rpmplay 5/5 with `other_host`).
 *
 * Mint detection, run-1 fix: the old detector only recognised URLs ending
 * in a media extension, so a page that was already streaming extensionless
 * playlist/stream URLs (filemoon: ~1.5 MB of video per attempt, 0 minted)
 * never produced a candidate. The engine now records every subresource URL
 * the page requests, reads the player's `currentSrc` and the performance
 * resource list, ranks the candidates (download URL, media extensions,
 * media markers, other non-static URLs — never .ts/.m4s segments) and
 * Range-probes them inside the 30 s wall cap; the first ACTIVE probe mints.
 *
 * Deviation from the plan (recorded, harness-only): the plan reuses one
 * WebView per session and wipes between tasks; the harness creates one
 * per attempt and destroys it. Simpler, and strictly more isolated.
 */
class WebViewEngine(
    private val appContext: Context,
    private val transport: ResolveHttpTransport,
) {

    sealed interface Result {
        data class Minted(val mintedUrl: String, val probe: ProbeResult, val challenge: Challenge) : Result
        data class Failed(val note: String, val challenge: Challenge, val probe: ProbeResult?) : Result
    }

    companion object {
        /** Wall cap per attempt (plan §3.3): 30 s, aborted as `unknown`. */
        const val WALL_CAP_MS = 30_000L

        /** Data cap per resolve (plan §3.3): 5 MB of UID traffic, aborted over it. */
        const val DATA_CAP_BYTES = 5_000_000L

        private val suffixAttempted = AtomicBoolean(false)

        /** True only when `setDataDirectorySuffix` actually applied this process run. */
        @Volatile
        var suffixApplied: Boolean = false
            private set

        /**
         * Own WebView data directory for the harness. Only call from the
         * harness entry point, before the first WebView exists in this
         * process run. Below API 28 the API does not exist; when a WebView
         * already exists (the viewer opened the visible page first) it
         * throws — then the suffix simply did not apply and the per-task
         * wipe is the isolation that remains.
         */
        fun ensureDataDirectorySuffix(): Boolean {
            if (!suffixAttempted.compareAndSet(false, true)) return suffixApplied
            suffixApplied = try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    WebView.setDataDirectorySuffix("resolve_test")
                    true
                } else {
                    false
                }
            } catch (_: Exception) {
                false
            }
            return suffixApplied
        }

        private val WAIT_SECONDS_REGEX = Regex("\\d+\\s*(seconds?|secs?)\\b")
        private val MEDIA_EXTENSIONS = listOf(".mp4", ".mkv", ".webm", ".m3u8", ".mpd")

        /** Segments are never a mint: probing one would "mint" a 10-second slice. */
        private val SEGMENT_EXTENSIONS = listOf(".ts", ".m4s")

        /** Paths that are plainly static assets, never media — skipped when ranking. */
        private val STATIC_EXTENSIONS = listOf(
            ".css", ".js", ".mjs", ".png", ".jpg", ".jpeg", ".gif", ".svg", ".ico",
            ".webp", ".avif", ".woff", ".woff2", ".ttf", ".eot", ".otf", ".map",
            ".json", ".xml", ".html", ".htm", ".txt",
        )

        /** Markers that make an extensionless URL worth probing for media. */
        private val MEDIA_MARKERS = listOf(
            "m3u8", "mp4", "/hls/", "/stream", "/video", "/media/", "download",
            "get_video", "/file/", "googlevideo",
        )

        /** Max ranked candidates probed per attempt — bounds time and data. */
        const val MAX_PROBE_CANDIDATES = 24

        /** Max subresource URLs recorded per attempt — bounds memory. */
        const val MAX_RECORDED_URLS = 300

        /** Candidates probed per poll round inside the 30 s wall cap. */
        const val PROBE_BATCH = 4

        internal fun hostOf(url: String): String? {
            return try {
                android.net.Uri.parse(url).host?.lowercase()
            } catch (_: Exception) {
                null
            }
        }

        /** Pure-JDK host parse, so the ranking logic is unit-testable without Robolectric. */
        internal fun hostOfUrl(url: String): String? {
            return try {
                java.net.URI(url).host?.lowercase()
            } catch (_: Exception) {
                null
            }
        }

        internal fun isMediaUrl(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return MEDIA_EXTENSIONS.any { path.endsWith(it) }
        }

        private fun isSegmentUrl(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return SEGMENT_EXTENSIONS.any { path.endsWith(it) }
        }

        private fun isStaticAsset(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return STATIC_EXTENSIONS.any { path.endsWith(it) }
        }

        internal fun isProbableMediaResource(url: String): Boolean {
            val low = url.lowercase()
            return MEDIA_MARKERS.any { it in low }
        }

        /**
         * Run 1 fix: rpmplay bridges navigate the SAME tab across hosts as a
         * normal part of the flow, and the old guard aborted the attempt with
         * `other_host` (5/5 rpmplay). Cross-host main-frame navigation is now
         * always followed; only non-http(s) schemes (tel:, intent:, …) are
         * refused, so the WebView never tries to open another app.
         */
        internal fun navigationAllowed(url: String): Boolean {
            val scheme = url.substringBefore(':').lowercase()
            return scheme == "http" || scheme == "https"
        }

        /** What one `evaluateJavascript(EXTRACT_JS)` round returns, parsed. */
        data class ExtractedData(
            val videos: List<String>,
            val anchors: List<String>,
            val resources: List<String>,
            val title: String,
            val text: String,
        )

        internal fun parseExtracted(extractedJson: String): ExtractedData? {
            val outer = MiniJson.parse(extractedJson) as? JsonValue.Str ?: return null
            val inner = MiniJson.parse(outer.value) as? JsonValue.Obj ?: return null
            fun strings(key: String): List<String> {
                return (inner.map[key] as? JsonValue.Arr)?.items
                    ?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()
            }
            return ExtractedData(
                videos = strings("videos"),
                anchors = strings("anchors"),
                resources = strings("resources"),
                title = (inner.map["title"] as? JsonValue.Str)?.value ?: "",
                text = (inner.map["text"] as? JsonValue.Str)?.value ?: "",
            )
        }

        /**
         * The ranked probe list for one attempt, best first. Run 1 proved the
         * old detector blind: filemoon downloaded ~1.5 MB of video per attempt
         * yet minted nothing, because the only URLs it recognised were ones
         * ending in a media extension — and these players serve extensionless
         * playlist/stream URLs while the bytes arrive as .ts segments.
         *
         * Order: the DownloadListener URL, then explicit media-extension
         * URLs, then extensionless URLs carrying a media marker, then every
         * other recorded URL that is not a static asset. Segments (.ts/.m4s)
         * are never candidates; the bridge URL itself, blocklisted hosts and
         * non-http(s) schemes are never candidates. Deduped, capped.
         */
        internal fun rankProbeCandidates(
            recorded: Collection<String>,
            extracted: Collection<String>,
            downloadUrl: String?,
            bridgeUrl: String,
            blocklist: List<String>,
        ): List<String> {
            val seen = LinkedHashSet<String>()
            val out = ArrayList<String>()
            fun blockedHost(url: String): Boolean {
                val host = hostOfUrl(url) ?: return true
                return blocklist.any { domain ->
                    host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
                }
            }
            fun accept(url: String): Boolean {
                if (url.isBlank()) return false
                val scheme = url.substringBefore(':').lowercase()
                if (scheme != "http" && scheme != "https") return false
                if (url == bridgeUrl) return false
                if (isSegmentUrl(url)) return false
                if (blockedHost(url)) return false
                return seen.add(url)
            }
            downloadUrl?.let { if (accept(it)) out.add(it) }
            for (url in extracted + recorded) {
                if (isMediaUrl(url) && accept(url)) out.add(url)
            }
            for (url in extracted + recorded) {
                if (isProbableMediaResource(url) && accept(url)) out.add(url)
            }
            for (url in recorded) {
                if (!isStaticAsset(url) && accept(url)) out.add(url)
            }
            return out.take(MAX_PROBE_CANDIDATES)
        }

        // `currentSrc` (not just the `src` attribute) is the URL the player
        // actually loaded — hls.js/MediaSource players leave `src` empty and
        // put a blob: URL in `currentSrc`. `performance` resource entries are
        // the network truth: every XHR/fetch/media request the page made,
        // including the extensionless playlist and segment URLs the old
        // detector never saw.
        private const val EXTRACT_JS = """(function(){var vids=[];document.querySelectorAll('video,source').forEach(function(e){var s=e.currentSrc||e.src||e.getAttribute('src');if(s)vids.push(s);});var anchors=[];document.querySelectorAll('a[href]').forEach(function(a){var h=a.href;if(h&&/\.(mp4|mkv|webm|m3u8|mpd)(\?|#|$)/i.test(h))anchors.push(h);});var resources=[];try{performance.getEntriesByType('resource').forEach(function(r){if(r.name)resources.push(r.name);});}catch(e){}var txt=(document.body?document.body.innerText:'').slice(0,4000);return JSON.stringify({videos:vids,anchors:anchors,resources:resources,title:document.title||'',text:txt});})()"""

        // 1fichier is French: its button reads "Télécharger", which the old
        // English-only matcher never clicked. Anchors included: some flows
        // render the download action as a link, not a button.
        private const val CLICK_DOWNLOAD_JS = """(function(){var els=document.querySelectorAll('input[type=submit],button[type=submit],button,a');for(var i=0;i<els.length;i++){var el=els[i];var label=((el.value||el.innerText||el.textContent||'')+' '+(el.getAttribute('aria-label')||'')).toLowerCase();if((label.indexOf('download')!==-1||label.indexOf('télécharger')!==-1||label.indexOf('telecharger')!==-1)&&!el.disabled){el.click();return 'clicked';}}return 'none';})()"""
    }

    suspend fun resolve(sample: ResolveSample, blocklist: List<String>): Result {
        return withContext(Dispatchers.Main) { runAttempt(sample, blocklist) }
    }

    private suspend fun runAttempt(sample: ResolveSample, blocklist: List<String>): Result {
        val bridgeHost = hostOf(sample.bridgeUrl) ?: return Result.Failed("bad_bridge_url", Challenge.NONE, null)
        wipeState()
        val state = AttemptState(bridgeHost, sample.blocklist, blocklist)
        val webView = WebView(appContext)
        try {
            webView.clearCache(true)
            configure(webView, state)
            webView.setDownloadListener(DownloadListener { url, _, _, _, _ ->
                state.downloadUrl = url
                state.aborted = "download_triggered"
            })
            val trafficBefore = uidTrafficBytes()
            val started = SystemClock.elapsedRealtime()
            webView.loadUrl(sample.bridgeUrl)
            var lastChallenge = Challenge.NONE
            var clickedDownload = false
            while (SystemClock.elapsedRealtime() - started < WALL_CAP_MS) {
                if (state.aborted != null) break
                val traffic = uidTrafficBytes()
                if (trafficBefore != null && traffic != null && traffic - trafficBefore > DATA_CAP_BYTES) {
                    state.aborted = "data_cap"
                    break
                }
                delay(500)
                if (state.aborted != null) break
                if (state.pageFinished && !clickedDownload && state.candidates.isEmpty()) {
                    if (sample.service == "1fichier") {
                        clickedDownload = webView.evalJs(CLICK_DOWNLOAD_JS)?.contains("clicked") == true
                    } else {
                        clickedDownload = true
                    }
                }
                val extracted = webView.evalJs(EXTRACT_JS)
                val challenge = extracted?.let { observeChallenge(it) } ?: Challenge.NONE
                if (challenge != Challenge.NONE) lastChallenge = challenge
                extracted?.let { state.addExtracted(it) }
                // Run 1 waited out the whole 30 s cap blind: the loop only
                // looked at <video>/anchor URLs, so a page that was already
                // streaming never minted. Probe newly ranked candidates every
                // round instead — the first ACTIVE probe mints at once.
                if (state.pageFinished) {
                    val minted = probeNewCandidates(state, sample, challengeOf(state, lastChallenge))
                    if (minted != null) return minted
                }
            }
            if (state.aborted != null) {
                return Result.Failed(state.aborted!!, challengeOf(state, lastChallenge), null)
            }
            // Final sweep: whatever the loop never got to probe.
            val ranked = rankProbeCandidates(
                state.recordedUrls,
                state.candidates,
                state.downloadUrl,
                sample.bridgeUrl,
                state.blocklist,
            )
            val remaining = ranked.filter { it !in state.probedUrls }
            if (remaining.isEmpty()) {
                return Result.Failed(
                    if (ranked.isNotEmpty()) "no_active_candidate" else "wall_cap",
                    challengeOf(state, lastChallenge),
                    state.firstProbe,
                )
            }
            return probeCandidates(remaining, challengeOf(state, lastChallenge), state)
        } finally {
            try {
                webView.stopLoading()
            } catch (_: Exception) {
            }
            try {
                webView.clearCache(true)
            } catch (_: Exception) {
            }
            try {
                webView.destroy()
            } catch (_: Exception) {
            }
            wipeState()
        }
    }

    /**
     * Probes up to [PROBE_BATCH] ranked candidates that have not been probed
     * yet this attempt. Returns the Minted result on the first ACTIVE probe,
     * else null — the caller keeps watching the page.
     */
    private suspend fun probeNewCandidates(state: AttemptState, sample: ResolveSample, challenge: Challenge): Result? {
        val ranked = rankProbeCandidates(
            state.recordedUrls,
            state.candidates,
            state.downloadUrl,
            sample.bridgeUrl,
            state.blocklist,
        )
        val fresh = ranked.filter { state.probedUrls.add(it) }.take(PROBE_BATCH)
        return probeBatch(fresh, challenge, state)
    }

    /** One batch of Range probes; the first ACTIVE probe mints, else null. */
    private suspend fun probeBatch(candidates: List<String>, challenge: Challenge, state: AttemptState): Result? {
        for (candidate in candidates.distinct()) {
            val probe = withContext(Dispatchers.IO) { runRangeProbe(transport, candidate) }
            if (state.firstProbe == null) state.firstProbe = probe
            if (probe.verdict == ProbeVerdict.ACTIVE) {
                return Result.Minted(candidate, probe, challenge)
            }
        }
        return null
    }

    private suspend fun probeCandidates(candidates: List<String>, challenge: Challenge, state: AttemptState): Result {
        return probeBatch(candidates, challenge, state)
            ?: Result.Failed("no_active_candidate", challenge, state.firstProbe)
    }

    private fun challengeOf(state: AttemptState, observed: Challenge): Challenge {
        return observed
    }

    private fun configure(webView: WebView, state: AttemptState) {
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            allowFileAccess = false
            allowContentAccess = false
            loadsImagesAutomatically = false
            cacheMode = WebSettings.LOAD_DEFAULT
        }
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, false)
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                val url = request?.url?.toString() ?: return true
                if (request.isForMainFrame != true) return false
                // Run 1: rpmplay navigates the same tab across hosts as part
                // of its normal flow; aborting that killed 5/5 attempts. The
                // tab now follows every http(s) navigation and keeps watching
                // for the media URL; the current host is tracked, not fenced.
                if (!navigationAllowed(url)) return true
                hostOf(url)?.let { state.currentHost = it }
                return false
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val host = hostOf(url) ?: return null
                if (state.blocklistOn && state.blocklist.any { domain ->
                        host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
                    }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                // Record every subresource URL: the mint detector ranks these
                // and probes them. Run 1 recorded only extension-suffixed
                // URLs and missed the extensionless playlist/stream URLs
                // these players actually use.
                val scheme = url.substringBefore(':').lowercase()
                if (scheme == "http" || scheme == "https") {
                    if (state.recordedUrls.size < MAX_RECORDED_URLS) state.recordedUrls.add(url)
                }
                if (isMediaUrl(url)) state.candidates.add(url)
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                state.pageFinished = true
                url?.let { hostOf(it)?.let { host -> state.currentHost = host } }
            }
        }
    }

    private fun wipeState() {
        try {
            CookieManager.getInstance().removeAllCookies(null)
        } catch (_: Exception) {
        }
        try {
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
        try {
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {
        }
    }

    private fun uidTrafficBytes(): Long? {
        val uid = Process.myUid()
        val rx = TrafficStats.getUidRxBytes(uid)
        val tx = TrafficStats.getUidTxBytes(uid)
        if (rx == TrafficStats.UNSUPPORTED.toLong() || tx == TrafficStats.UNSUPPORTED.toLong()) return null
        return rx + tx
    }

    private suspend fun WebView.evalJs(script: String): String? {
        return suspendCancellableCoroutine { cont ->
            try {
                evaluateJavascript(script) { value ->
                    if (cont.isActive) cont.resume(value)
                }
            } catch (_: Exception) {
                if (cont.isActive) cont.resume(null)
            }
        }
    }

    /** The challenge the page text shows: turnstile / captcha / wait timer / other. */
    private fun observeChallenge(extractedJson: String): Challenge {
        val data = parseExtracted(extractedJson) ?: return Challenge.NONE
        val haystack = "${data.title}\n${data.text}".lowercase()
        return when {
            "turnstile" in haystack -> Challenge.TURNSTILE
            "captcha" in haystack -> Challenge.CAPTCHA
            "please wait" in haystack || "countdown" in haystack ||
                WAIT_SECONDS_REGEX.containsMatchIn(haystack) -> Challenge.WAIT_TIMER
            "cloudflare" in haystack -> Challenge.OTHER
            else -> Challenge.NONE
        }
    }

    private class AttemptState(
        val bridgeHost: String,
        val blocklistOn: Boolean,
        val blocklist: List<String>,
    ) {
        val candidates: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        /** Every subresource URL the page requested, in arrival order. */
        val recordedUrls: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        /** Ranked candidates already Range-probed this attempt. */
        val probedUrls: MutableSet<String> = Collections.synchronizedSet(LinkedHashSet())
        /** First probe result, kept for the failure record. */
        var firstProbe: ProbeResult? = null
        var downloadUrl: String? = null
        var pageFinished: Boolean = false
        var aborted: String? = null
        /** The host the tab is on now — bridges navigate across hosts. */
        @Volatile
        var currentHost: String = bridgeHost

        fun addExtracted(extractedJson: String) {
            val data = parseExtracted(extractedJson) ?: return
            for (url in data.videos + data.anchors) {
                if (WebViewEngine.isMediaUrl(url)) candidates.add(url)
            }
            // Performance entries are real network URLs the page fetched:
            // the extensionless playlist/stream URLs the old detector missed.
            for (url in data.resources) {
                if (recordedUrls.size < MAX_RECORDED_URLS) recordedUrls.add(url)
            }
        }
    }
}
