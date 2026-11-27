package com.raddstream.app.ui.components

import android.content.Context
import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil3.asDrawable
import coil3.compose.SubcomposeAsyncImage
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.raddstream.app.R
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.images.ArtUrlPolicy
import com.raddstream.app.images.ImageSizing
import com.raddstream.app.images.ImageSizingHolder
import com.raddstream.app.images.ImageSizingPolicy
import com.raddstream.app.images.PosterUrlPolicy
import com.raddstream.app.ui.theme.RaddHeroPlaceholderHigh
import com.raddstream.app.ui.theme.RaddHeroPlaceholderLow
import com.raddstream.app.ui.theme.RaddPlaceholderHigh
import com.raddstream.app.ui.theme.RaddPlaceholderLow
import com.raddstream.app.ui.theme.RaddStreamMark
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay

/**
 * The ONE place a catalog image turns a stored preference into a Coil request. Home, Browse, Search,
 * My List (all via [PosterCard]) and the detail header render posters through here, so the Settings
 * "Image quality" row and the mobile-data switch cannot be honoured on one screen and quietly ignored
 * on another — the drift this whole file exists to prevent.
 *
 * [onBitmap] is the artwork-accent hook: a caller that wants the poster's own colour (the detail
 * header's border) gets the decoded bitmap on success, and nothing when the load is gated or fails.
 * It is optional and defaults to off, so the grid cells that never need a bitmap pay nothing.
 *
 * It reads [ImageSizingHolder], an Application-owned singleton, through a Hilt [EntryPoint] off the
 * ambient [LocalContext], because a poster lives deep inside a recycled grid cell where no screen
 * composes a ViewModel. Handing Coil an explicit [ImageRequest] size is the whole point: left to
 * itself Coil decodes to the view box, so "Battery saver" would still pull a full poster and the
 * setting would be a decoration. Passing the request as `model` (not a bare URL) is how the width
 * actually reaches the decoder in Coil 3.
 *
 * When the load is gated off — metered connection and no mobile-data permission — `model` is null, so
 * Coil makes no request at all and whatever the surrounding card already paints behind the image (the
 * surfaceVariant placeholder, or the initials tile when there is no URL) is exactly what the viewer
 * sees. What is NOT protected: metered-ness is sampled once per composition, so an image already on
 * screen stays up if the network flips to metered while you scroll; only the next request obeys it.
 * That is the deliberate cost of not parking a live ConnectivityListener on every list.
 *
 * Phase 7 (M4): the URL itself is gated too, by [ArtUrlPolicy]. The server already
 * vets catalog art through `/api/v1/art`, and this is the client-side second lock:
 * a URL that is not our server, a public image CDN, or a neutral upload host never
 * reaches Coil — the fallback card paints instead, so no upstream domain ever
 * learns a viewer opened a title. [allowAnyHost] is the one documented exception:
 * live-TV channel logos come from the operator-curated channel registry, not the
 * catalog, and their hosts are the registry's own — the caller opts out by name.
 *
 * A *failed* load is different from a *gated* one and gets its own art. Nothing about a
 * dead poster URL is the caller's fault — the server sent a link, the CDN did not answer —
 * so instead of leaving a bare surfaceVariant rectangle (which reads as "this title has no
 * artwork", a claim about the content rather than about the network) the tile paints
 * [ImageFallbackCard]: a dark branded panel carrying the source-ripple mark and, when the
 * caller supplied one, the title itself. [fallbackLabel] is optional precisely because
 * not every call site has a title to hand; the mark alone is a complete answer.
 *
 * A failed load is retried, not filed away. Samsung RTL (v1.1.2, Issue 5):
 * Salaar's poster URL is alive (HEAD 200 from the Oracle VM, 0.19 s) yet the
 * phone showed the dark "SA" fallback — one transient fetch failure, and
 * Coil's single attempt left the tile failed for the whole visit, with no
 * way back. A failure now re-requests at once, up to [MAX_IMAGE_AUTO_RETRIES]
 * times ([shouldAutoRetryImage] is the bound). Once those are spent the tile
 * stays on the fallback until the composition is recreated — there is
 * deliberately no tap-to-retry on the error slot (A3 Proof 3: a full-size
 * retry clickable there swallowed the parent card's tap, so a tap on a
 * failed-image tile belongs to the parent card). The retries are immediate
 * on purpose: a delayed retry would park a coroutine on the composition
 * clock, which the frozen-clock Robolectric tests cannot advance past; a
 * bounded immediate re-request settles the same way a single attempt does.
 */
@Composable
fun CatalogImage(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    fallbackLabel: String? = null,
    onBitmap: ((Bitmap?) -> Unit)? = null,
    /**
     * Phase 7 (M4): true only for operator-curated art whose hosts are the
     * registry's own (live-TV channel logos). Catalog art never passes this.
     */
    allowAnyHost: Boolean = false,
    /**
     * B1 (home review, Nov 2026): the hero's failed backdrop paints the
     * [FallbackTone.Hero] gradient instead of the tile one, so a missing
     * backdrop reads as a designed brand surface, not a black void.
     */
    fallbackTone: FallbackTone = FallbackTone.Tile,
    /**
     * Live TV review (Nov 2026): a channel logo plate is a wide, short
     * surface — the standard 30dp fallback mark reads as an empty box
     * there. A caller on such a plate passes true for a 48dp mark at full
     * strength and a larger wordmark. Defaults off: poster tiles keep the
     * small mark.
     */
    fallbackLarge: Boolean = false,
    /**
     * Hero (Nov 2026): what the loading slot paints instead of the bare
     * shimmer. Only the hero passes one — a full-bleed slide's shimmer reads
     * as a black void on first paint, while a poster's does not. Null keeps
     * the shimmer everywhere else.
     */
    loadingContent: (@Composable () -> Unit)? = null,
    /**
     * A4-F1 (Nov 2026): the short monogram ("DP") a wide, short plate
     * paints instead of the full label — a channel name plus a 48dp mark
     * never fit the 60%-of-16:9 logo box, so the Robo capture showed the
     * mark with no text at all. Null keeps the full label (posters); see
     * [channelMonogram] for the derivation. Only the logo plate passes one.
     */
    monogram: String? = null,
    /**
     * S5 (feel-instant): the poster's own accent, sampled by the caller
     * from a bitmap it already holds ([PosterCard], the detail poster
     * header). While this image loads, the slot paints in this colour at
     * low alpha with the shimmer band over it — the artwork arriving in
     * its own palette instead of a grey box. Null keeps the branded
     * fallback card + band (every other caller: hero backdrops, channel
     * logos, and any tile whose poster has never loaded).
     */
    accentTint: Color? = null,
    /**
     * Audit #7 (Nov 2026): bottom clearance for the fallback card, in
     * the caller's units — a ranked [PosterCard] clears its numeral's
     * bottom-left zone, so a centred fallback title never runs into it.
     * Defaults to none: every other caller keeps its centred fallback.
     */
    fallbackBottomClearance: Dp = 0.dp,
    /**
     * Video audit issue 10 (Nov 2026): skip the wsrv.nl resize proxy
     * for this load and fetch the sized direct URL instead. Live-TV
     * channel logos pass true — imgur rate-limits wsrv's fetcher (429),
     * so a proxied logo paid a ~10 s dead wait before the fallback
     * interceptor retried it direct. Defaults off: catalog posters keep
     * the proxy (and its WebP saving).
     */
    bypassResizeProxy: Boolean = false,
    /**
     * Video audit issue 27 (Nov 2026): the aspect ratio of the slot
     * this image paints into (2/3 for a poster card), used with
     * [fitWideArt] to spot source art that is not a poster at all.
     * Null keeps the old behaviour — the caller does not want the
     * aspect check.
     */
    slotAspect: Float? = null,
    /**
     * Video audit issue 27 (Nov 2026): when the decoded bitmap is
     * meaningfully wider (or taller) than [slotAspect], render it
     * [ContentScale.Fit] — letterboxed over the jade gradient — instead
     * of centre-cropping it. A 16:9 backdrop filed as a poster used to
     * crop to a fragment ("HALL RULE WORLD", a giant "a"); a fitted
     * image is never a fragment. Defaults off: only poster cards pass
     * true. The switch happens on the bitmap already in memory, so no
     * second fetch runs.
     */
    fitWideArt: Boolean = false,
) {
    val context = LocalContext.current
    // P1 goldens (Nov 2026): Paparazzi/Layoutlib (and @Preview) have no
    // Application, so the Hilt entry point throws there and every
    // CatalogImage caller — LiveTvChannelCard's logo plate included —
    // crashes before painting anything. Fall back to the holder's own
    // seed values (the same values a first frame gets before DataStore
    // answers), so the tile still renders its fallback art. On a real
    // device there is always an Application and this branch never runs;
    // on-device behaviour is unchanged.
    val holder = remember(context) { imageSizingHolderOrNull(context) }
    val sizing = if (holder != null) {
        holder.sizing.collectAsState().value
    } else {
        ImageSizing(
            posterMaxWidthPx = ImageSizingPolicy.widthPxFor(
                ServerSettingsRepository.DEFAULT_IMAGE_QUALITY,
                false,
            ),
            imagesAllowedOnMobileData = false,
        )
    }
    val metered = holder?.isMetered() ?: false
    val settingsRepository = remember(context) { serverSettingsRepositoryOrNull(context) }
    val serverUrl = if (settingsRepository != null) {
        settingsRepository.serverUrl.collectAsState(initial = null).value
    } else {
        null
    }
    val serverHost = remember(serverUrl) { ArtUrlPolicy.hostOf(serverUrl) }
    // S5: a loaded poster fades in over its tinted slot instead of
    // popping — 200ms, or a snap for reduced motion (the 30ms crossfade
    // the motion system already uses for everything else).
    val crossfadeMs = if (rememberReduceMotion()) 0 else 200

    // The load attempt this URL is on: 0 is the first request, and each
    // failure below bumps it (bounded) while a tap on the failed tile bumps
    // it without a bound. It is part of the request's key, so a bump
    // re-issues the fetch instead of re-showing the same failure.
    var attempt by remember(url) { mutableIntStateOf(0) }

    val request = remember(url, sizing, metered, serverHost, allowAnyHost, attempt, crossfadeMs) {
        val decision = ImageSizingPolicy.decide(
            widthPx = sizing.posterMaxWidthPx,
            imagesAllowedOnMobileData = sizing.imagesAllowedOnMobileData,
            metered = metered,
        )
        // B2 (home review, 2026-11-15): a TMDB /original/ poster is a
        // full-size file Coil downloads whole and downsamples locally —
        // rewrite it to the smallest TMDB width covering this tile before
        // the request is built, so the bytes match the pixels on screen.
        // wsrv (2026-11-20): the same call routes the sized poster
        // through the wsrv.nl resize proxy at that width as WebP
        // ([PosterUrlPolicy.displayUrl]) — 46.8% fewer bytes measured on
        // 100 real catalog posters; if the proxy fails, the loader's
        // fallback interceptor retries the direct URL once.
        val clean = url?.takeIf { it.isNotBlank() }
            ?.let { PosterUrlPolicy.displayUrl(it, decision.widthPx, bypassProxy = bypassResizeProxy) }
        val allowed = allowAnyHost || ArtUrlPolicy.isAllowed(clean, serverHost)
        if (clean != null && decision.shouldLoad && allowed) {
            ImageRequest.Builder(context)
                .data(clean)
                .size(decision.widthPx, decision.widthPx)
                .crossfade(crossfadeMs)
                .build()
        } else {
            null
        }
    }

    // A request that was never made — no URL, a gated load (metered
    // connection with no mobile-data permission), or a disallowed host —
    // must not shimmer forever: the shimmer is a loading state, and nothing
    // is loading. The branded fallback says "no artwork" honestly, with the
    // mark and the initials to look at. Robo run 6: a gated Detail poster
    // showed as a flat dark surfaceVariant rectangle (image contrast 1.33)
    // because the null model left the box blank behind the shimmer.
    if (request == null) {
        ImageFallbackCard(
            label = fallbackLabel,
            monogram = monogram,
            modifier = modifier,
            tone = fallbackTone,
            large = fallbackLarge,
            bottomClearance = fallbackBottomClearance,
        )
        return
    }

    // Robo run 8: until this request has produced a bitmap, the box holds the
    // loading shimmer, not an image — a flat dark rectangle the checker scores
    // as a picture (McLintock! detail poster, 1.63:1 — the sampled colours are
    // the poster's own surfaceVariant box and outlineVariant frame, no artwork
    // at all). The image node stays out of the accessibility tree until there
    // is an image to describe (the run-7 ShimmerBox pattern); on success it
    // appears with its content description, and on error the fallback card
    // speaks instead. Keyed on the request, so a new URL starts hidden again.
    var loaded by remember(request) { mutableStateOf(false) }

    // Video audit issue 22 (Nov 2026): the delayed-retry budget. The
    // immediate retries above all fire inside the same bad moment, so a
    // flaky connection spends them and the tile is stuck on the fallback
    // for the whole visit even after the network recovers. Once they are
    // spent the tile is marked failed, and the effect below re-issues one
    // request 20 s later — long enough for the network to recover,
    // bounded to one, so a permanently dead URL never re-requests
    // forever. Keyed on the URL, so a new URL gets a fresh budget. A
    // frozen-clock Robolectric test never advances the 20 s, so it still
    // observes the fallback state — the delay parks the effect, it never
    // fires, and no test hangs on it.
    var delayedAttempts by remember(url) { mutableIntStateOf(0) }
    var failed by remember(request) { mutableStateOf(false) }
    LaunchedEffect(failed, url, delayedAttempts) {
        if (!failed || !shouldDelayedRetryImage(delayedAttempts)) return@LaunchedEffect
        delay(DELAYED_IMAGE_RETRY_MS)
        delayedAttempts++
        attempt++
    }

    // Video audit issue 27 (Nov 2026): whether the decoded bitmap is the
    // wrong shape for this slot. Set on success from the bitmap's own
    // aspect — the image already in memory re-renders fitted, no refetch.
    // Keyed on the URL, so a new URL re-checks its own art.
    var wideArt by remember(url) { mutableStateOf(false) }

    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier
            .then(if (loaded) Modifier else Modifier.clearAndSetSemantics { })
            .then(
                // Video audit issue 27: the jade gradient behind a fitted
                // wide image — the letterbox bars read as brand, not void.
                if (wideArt) {
                    Modifier.background(
                        Brush.verticalGradient(
                            listOf(RaddHeroPlaceholderHigh, RaddHeroPlaceholderLow),
                        ),
                    )
                } else Modifier
            ),
        // Video audit issue 27: a wrongly-shaped bitmap renders fitted,
        // never centre-cropped into a fragment.
        contentScale = if (wideArt) ContentScale.Fit else contentScale,
        // A real request in flight paints the branded fallback tile first and
        // sweeps the band-only shimmer over it — or the caller's branded
        // frame, when it passed one (the hero). Loading, error and null-URL
        // all show the same tile, so a slow or hung /api/v1/art proxy fetch
        // never leaves a naked void: the old bare shimmer read as a flat
        // #20282A plate (Robo: Fan Letter, Kesariya@100) whenever the band
        // was off-frame. A gated or blank URL never reaches this slot, so
        // the surface behind the card stays the truth in that case.
        // S5: when the caller sampled the poster's own accent, the slot
        // paints in that colour at low alpha instead of the fallback
        // card — the title already sits in the text row below the poster,
        // so the slot carries no card here, just the tint and the band.
        loading = {
            loadingContent?.invoke() ?: Box(Modifier.fillMaxSize()) {
                if (accentTint != null) {
                    Box(
                        Modifier.fillMaxSize()
                            .background(accentTint.copy(alpha = ACCENT_TINT_ALPHA)),
                    )
                } else {
                    // Video audit issue 5 (Nov 2026): the loading slot is
                    // the welcome poster — jade gradient + mark, never a
                    // monogram and never a title. A tile whose poster is
                    // still arriving reads as brand, not as a dark box
                    // with two letters in it.
                    WelcomePoster(Modifier.fillMaxSize())
                }
                ShimmerBox(Modifier.fillMaxSize(), bandOnly = true)
            }
        },
        // A failed load shows the branded fallback (A3 Proof 3: the slot
        // itself has no full-size tap-to-retry — a full-size retry
        // clickable there swallowed the parent card's tap, so a tap on a
        // failed-image tile belongs to the parent card; see
        // [CatalogImageErrorSlot]). Video audit issue 22: the slot DOES
        // carry a small corner retry button (40dp, TopEnd, in a surface
        // disc like the Live TV star), not a full-size clickable — so a
        // centre tap still reaches the parent. Logo plates are too small
        // for it; those get the delayed retry only.
        error = {
            CatalogImageErrorSlot(
                fallbackLabel = fallbackLabel,
                monogram = monogram,
                tone = fallbackTone,
                large = fallbackLarge,
                bottomClearance = fallbackBottomClearance,
                onRetry = if (fallbackLarge) null else ({ attempt++ }),
            )
        },
        // The artwork-accent hook: only a caller that passed onBitmap pays the
        // decode-to-bitmap, and only on a real success — a gated or failed load
        // reports nothing, so the caller keeps its fallback colour.
        onSuccess = { state ->
            loaded = true
            failed = false
            val bitmap = state.result.image.asDrawable(context.resources).toBitmap()
            onBitmap?.invoke(bitmap)
            // Video audit issue 27: the bitmap's own aspect decides the
            // fit. A poster slot is 2:3; art more than 20% wider or taller
            // than the slot is a backdrop or a banner filed as a poster,
            // and centre-cropping it would ship a fragment.
            if (fitWideArt && slotAspect != null && slotAspect > 0f && bitmap.height > 0) {
                val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                wideArt = aspect > slotAspect * WIDE_ART_FIT_THRESHOLD || aspect < slotAspect / WIDE_ART_FIT_THRESHOLD
            }
        },
        // A transient failure re-requests at once, bounded by
        // [shouldAutoRetryImage]: one failed fetch must not leave a live
        // poster as a dark initials tile for the whole visit. Once the
        // bound is spent the error slot stays — and the delayed retry
        // (issue 22, above) takes over: the tile is marked failed, and
        // one request goes out 20 s later. The slot has no full-size
        // tap-to-retry (A3 Proof 3), so a tap on the tile belongs to the
        // parent card, and the image is re-requested when the composition
        // is recreated.
        onError = {
            if (shouldAutoRetryImage(attempt)) attempt++ else {
                loaded = false
                failed = true
            }
        },
    )
}

/**
 * A3 Proof 3 (Nov 2026): the content of [CatalogImage]'s error slot,
 * extracted so its tap path is testable without Coil. A failed load shows
 * the branded fallback — and the slot deliberately has NO full-size
 * clickable of its own: a full-size retry clickable there swallowed the
 * parent card's tap (Discover Pakistan's logo 429s, Robo tapped the card
 * centre twice, and nothing navigated), so a tap on a failed-image tile
 * belongs to the parent card. The bounded auto-retry ([shouldAutoRetryImage])
 * is the recovery for a transient failure; once it is spent the tile stays
 * on the fallback until the composition is recreated. [CatalogImageErrorTapTest]
 * pins this: a centre tap on the slot must reach the parent onClick.
 *
 * Video audit issue 22 (Nov 2026): [onRetry] is the exception that proves
 * the rule — a SMALL corner button (40dp, TopEnd, in a surface disc like
 * the Live TV star), not a full-size clickable. Tapping it re-issues the
 * load; a centre tap still falls through to the parent. Null means no
 * button: logo plates are too small for it ([CatalogImage] passes null
 * when `fallbackLarge`), and those get the delayed retry only.
 */
@Composable
internal fun CatalogImageErrorSlot(
    fallbackLabel: String?,
    monogram: String?,
    tone: FallbackTone,
    large: Boolean,
    modifier: Modifier = Modifier,
    bottomClearance: Dp = 0.dp,
    onRetry: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize()) {
        ImageFallbackCard(
            label = fallbackLabel,
            monogram = monogram,
            modifier = Modifier.fillMaxSize(),
            tone = tone,
            large = large,
            bottomClearance = bottomClearance,
        )
        if (onRetry != null && !large) {
            IconButton(
                onClick = onRetry,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(40.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
                        CircleShape,
                    ),
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_action_refresh),
                    contentDescription = "Retry loading image",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(20.dp), // token-exempt: icon size, not spacing
                )
            }
        }
    }
}

/**
 * S5: the accent tint's strength in a loading slot — the poster's own
 * colour, lifted to clear 4.5:1 by [rememberArtworkAccent], at a low
 * alpha so the slot reads as a tint, not a solid.
 */
internal const val ACCENT_TINT_ALPHA = 0.18f

/**
 * Auto-retries for a failed catalog image: the first load plus
 * [MAX_IMAGE_AUTO_RETRIES] immediate re-requests, then the error slot
 * stays until the composition is recreated — there is deliberately no
 * tap-to-retry (A3 Proof 3: it swallowed the parent card's tap). The
 * bound is what keeps a permanently dead URL from re-requesting
 * forever. Pure so the bound is testable without Coil.
 */
internal const val MAX_IMAGE_AUTO_RETRIES = 2

internal fun shouldAutoRetryImage(attempt: Int, maxAutoRetries: Int = MAX_IMAGE_AUTO_RETRIES): Boolean =
    attempt < maxAutoRetries

/**
 * Video audit issue 22 (Nov 2026): the delayed retry — one re-request 20 s
 * after the immediate retries are spent, so a tile that failed in a bad
 * moment gets a second chance once the network has had time to recover.
 * Pure so the bound is testable without Coil.
 */
internal const val DELAYED_IMAGE_RETRY_MS: Long = 20_000L

internal const val MAX_DELAYED_IMAGE_RETRIES = 1

internal fun shouldDelayedRetryImage(delayedAttempts: Int): Boolean =
    delayedAttempts < MAX_DELAYED_IMAGE_RETRIES

/**
 * Video audit issue 27 (Nov 2026): how far a decoded bitmap's aspect may
 * stray from its slot before the slot fits it instead of cropping it —
 * 20% either way. A 2:3 slot fits art from 0.56 to 0.80 as a crop; outside
 * that band the art is a different class (a 16:9 backdrop, a tall banner)
 * and cropping it ships a fragment.
 */
internal const val WIDE_ART_FIT_THRESHOLD = 1.2f

/**
 * A4-F1: the short monogram a wide, short logo plate paints instead of the
 * full channel name — "Discover Pakistan" -> "DP", "Aaj Tak HD" -> "AT".
 * Two or more words give the first letter of each of the first two; one
 * word gives its first two letters; a name with no letter or digit in it
 * gives null, so the plate falls back to the mark alone. Pure so the
 * derivation is testable without Compose.
 */
fun channelMonogram(name: String?): String? {
    if (name.isNullOrBlank()) return null
    val words = name.trim().split(Regex("\\s+"))
        .map { word -> word.filter { it.isLetterOrDigit() } }
        .filter { it.isNotEmpty() }
    if (words.isEmpty()) return null
    return if (words.size == 1) {
        words[0].take(2).uppercase()
    } else {
        words.take(2).joinToString("") { it.first().toString() }.uppercase()
    }
}

/**
 * Which surface the fallback paints for. A poster tile is small, so the
 * near-black tile gradient reads as a designed tile; the full-bleed hero
 * is the one surface big enough that the same gradient reads as an empty
 * black void (B1, home review Nov 2026), so the hero gets a jade-tinted
 * gradient and a full-strength mark instead.
 */
enum class FallbackTone { Tile, Hero }

/**
 * The dark placeholder every failed catalog image falls back to. Colours come only from
 * the theme, never a literal, so a re-brand from `brand.properties` restyles this with the
 * rest of the app.
 */
@Composable
fun ImageFallbackCard(
    modifier: Modifier = Modifier,
    label: String? = null,
    tone: FallbackTone = FallbackTone.Tile,
    large: Boolean = false,
    /**
     * A4-F1 (Nov 2026): the short monogram ("DP") a wide, short plate
     * paints beside the mark instead of the full label — a channel name
     * plus a 48dp mark never fit the 60%-of-16:9 logo box, so the Robo
     * capture showed the mark with no text at all. Null keeps the mark +
     * full label column (posters); see [channelMonogram] for the
     * derivation. Only the logo plate passes one.
     */
    monogram: String? = null,
    /**
     * Audit #7 (Nov 2026): bottom clearance for the centred mark + label
     * column — a ranked [PosterCard] passes its numeral's height, so the
     * column's centre shifts up by half the clearance and a two- or
     * three-line title never reaches the numeral's bottom-left zone.
     * Defaults to none: every other caller keeps its centred fallback.
     */
    bottomClearance: Dp = 0.dp,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(
                // F6 (Batch A): the placeholder is a branded gradient, not a flat
                // surface — a failed or missing poster still reads as a designed
                // tile, and the title under the mark says which title it is.
                Brush.verticalGradient(
                    if (tone == FallbackTone.Hero) {
                        listOf(RaddHeroPlaceholderHigh, RaddHeroPlaceholderLow)
                    } else {
                        listOf(RaddPlaceholderHigh, RaddPlaceholderLow)
                    },
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        // A4-F1: on a wide, short plate the monogram sits beside the mark
        // in a Row — the full name under a 48dp mark overflowed the
        // ~64dp-tall plate, so the label never painted at all. The card
        // below already prints the channel name; the Row only needs the
        // two letters. Everywhere else the mark + label column stands.
        if (large && monogram != null) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(8.dp),
            ) {
                RaddStreamMark(
                    diameter = 48.dp,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = monogram,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            return@Box
        }
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            // Audit #7: the bottom clearance lifts the whole centred
            // column — the Box centres what is left, so the mark + title
            // sit half a clearance above centre, clear of a rank numeral.
            modifier = Modifier.padding(8.dp).padding(bottom = bottomClearance),
        ) {
            // Screenshot-fix D4 (2026-11-16): the tile fallback read as a
            // broken dark box — a 30dp mark and a two-line titleSmall that
            // truncated mid-word ("The Gentlemen (Season 1 - 2) Du...").
            // The mark is bigger and the full title gets three lines, so a
            // failed poster still names its title instead of looking empty.
            RaddStreamMark(
                diameter = if (tone == FallbackTone.Hero || large) 48.dp else 40.dp,
                color = MaterialTheme.colorScheme.primary.copy(
                    alpha = if (tone == FallbackTone.Hero || large) 1f else 0.55f,
                ),
            )
            // Session 121 (P-15): the label is the title itself, never punctuation.
            // A label with no letter or digit in it — a "?" from a mistyped title, or
            // a stray symbol — renders as the mark alone instead of a stray glyph.
            // F6: the full title, up to three lines — the initials ("AL") told a
            // viewer nothing about which title failed to load.
            val safeLabel = label?.takeIf { it.any { ch -> ch.isLetterOrDigit() } }
            if (!safeLabel.isNullOrBlank()) {
                Text(
                    text = safeLabel,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    style = if (large) MaterialTheme.typography.titleMedium
                    else MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

/** Reaches the Application-owned sizing singleton from any composable — the [com.raddstream.app.brand.Brand]
 *  "read it anywhere" convenience, but for a DI object that must observe DataStore rather than BuildConfig. */
internal fun imageSizingHolder(context: Context): ImageSizingHolder =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ImageSizingEntryPoint::class.java,
    ).imageSizingHolder()

/**
 * P1 goldens (Nov 2026): the nullable form of [imageSizingHolder].
 * [EntryPointAccessors.fromApplication] throws IllegalStateException
 * when there is no Application in the context — Paparazzi/Layoutlib
 * and @Preview — so a caller that can render without the live holder
 * (CatalogImage, whose seed values are the same as a first frame's)
 * takes null here instead of crashing. Callers that cannot work
 * without the holder (PosterPrefetch) keep the throwing form.
 */
internal fun imageSizingHolderOrNull(context: Context): ImageSizingHolder? =
    try {
        imageSizingHolder(context)
    } catch (_: IllegalStateException) {
        null
    }

/** Phase 7 (M4): the server settings, read the same way — the art policy needs the configured host. */
internal fun serverSettingsRepository(context: Context): ServerSettingsRepository =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ImageSizingEntryPoint::class.java,
    ).serverSettingsRepository()

/** P1 goldens (Nov 2026): the nullable form of [serverSettingsRepository], same reason as [imageSizingHolderOrNull]. */
internal fun serverSettingsRepositoryOrNull(context: Context): ServerSettingsRepository? =
    try {
        serverSettingsRepository(context)
    } catch (_: IllegalStateException) {
        null
    }

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImageSizingEntryPoint {
    fun imageSizingHolder(): ImageSizingHolder
    fun serverSettingsRepository(): ServerSettingsRepository
}
