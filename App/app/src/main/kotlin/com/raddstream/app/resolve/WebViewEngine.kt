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
 * URL and aborts; a navigation guard that allows the assigned bridge URL
 * and same-host redirects only.
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

        internal fun hostOf(url: String): String? {
            return try {
                android.net.Uri.parse(url).host?.lowercase()
            } catch (_: Exception) {
                null
            }
        }

        internal fun isMediaUrl(url: String): Boolean {
            val path = url.substringBefore('?').substringBefore('#').lowercase()
            return MEDIA_EXTENSIONS.any { path.endsWith(it) }
        }

        private const val EXTRACT_JS = """(function(){var vids=[];document.querySelectorAll('video,source').forEach(function(e){var s=e.src||e.getAttribute('src');if(s)vids.push(s);});var anchors=[];document.querySelectorAll('a[href]').forEach(function(a){var h=a.href;if(h&&/\.(mp4|mkv|webm|m3u8|mpd)(\?|#|$)/i.test(h))anchors.push(h);});var txt=(document.body?document.body.innerText:'').slice(0,4000);return JSON.stringify({videos:vids,anchors:anchors,title:document.title||'',text:txt});})()"""

        private const val CLICK_DOWNLOAD_JS = """(function(){var els=document.querySelectorAll('input[type=submit],button[type=submit],button');for(var i=0;i<els.length;i++){var el=els[i];var label=(el.value||el.innerText||'').toLowerCase();if(label.indexOf('download')!==-1&&!el.disabled){el.click();return 'clicked';}}return 'none';})()"""
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
                if (state.candidates.isNotEmpty()) break
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
            }
            if (state.aborted != null) {
                return Result.Failed(state.aborted!!, challengeOf(state, lastChallenge), null)
            }
            if (state.candidates.isEmpty() && state.downloadUrl == null) {
                return Result.Failed("wall_cap", challengeOf(state, lastChallenge), null)
            }
            val candidates = ArrayList<String>()
            state.downloadUrl?.let { candidates.add(it) }
            candidates.addAll(state.candidates)
            return probeCandidates(candidates, challengeOf(state, lastChallenge))
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

    private suspend fun probeCandidates(candidates: List<String>, challenge: Challenge): Result {
        var firstProbe: ProbeResult? = null
        for (candidate in candidates.distinct()) {
            val probe = withContext(Dispatchers.IO) { runRangeProbe(transport, candidate) }
            if (firstProbe == null) firstProbe = probe
            if (probe.verdict == ProbeVerdict.ACTIVE) {
                return Result.Minted(candidate, probe, challenge)
            }
        }
        return Result.Failed("no_active_candidate", challenge, firstProbe)
    }

    private fun challengeOf(state: AttemptState, observed: Challenge): Challenge {
        if (state.aborted == "other_host") return Challenge.OTHER
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
                val host = hostOf(url)
                if (host == null || host.equals(state.bridgeHost, ignoreCase = true)) return false
                state.aborted = "other_host"
                return true
            }

            override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                val url = request?.url?.toString() ?: return null
                val host = hostOf(url) ?: return null
                if (state.blocklistOn && state.blocklist.any { domain ->
                        host.equals(domain, ignoreCase = true) || host.endsWith(".$domain", ignoreCase = true)
                    }) {
                    return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
                }
                if (isMediaUrl(url)) state.candidates.add(url)
                return null
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                state.pageFinished = true
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
        val outer = MiniJson.parse(extractedJson) as? JsonValue.Str ?: return Challenge.NONE
        val inner = MiniJson.parse(outer.value) as? JsonValue.Obj ?: return Challenge.NONE
        val title = (inner.map["title"] as? JsonValue.Str)?.value ?: ""
        val text = (inner.map["text"] as? JsonValue.Str)?.value ?: ""
        val haystack = "$title\n$text".lowercase()
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
        var downloadUrl: String? = null
        var pageFinished: Boolean = false
        var aborted: String? = null

        fun addExtracted(extractedJson: String) {
            val outer = MiniJson.parse(extractedJson) as? JsonValue.Str ?: return
            val inner = MiniJson.parse(outer.value) as? JsonValue.Obj ?: return
            val videos = (inner.map["videos"] as? JsonValue.Arr)?.items
                ?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()
            val anchors = (inner.map["anchors"] as? JsonValue.Arr)?.items
                ?.mapNotNull { (it as? JsonValue.Str)?.value } ?: emptyList()
            for (url in videos + anchors) {
                if (WebViewEngine.isMediaUrl(url)) candidates.add(url)
            }
        }
    }
}
