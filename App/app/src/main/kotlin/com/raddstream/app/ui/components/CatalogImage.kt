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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import coil.compose.SubcomposeAsyncImage
import coil.request.ImageRequest
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.images.ArtUrlPolicy
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
 * actually reaches the decoder in Coil 2.x.
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
) {
    val context = LocalContext.current
    val holder = remember(context) { imageSizingHolder(context) }
    val sizing by holder.sizing.collectAsState()
    val metered = holder.isMetered()
    val settingsRepository = remember(context) { serverSettingsRepository(context) }
    val serverUrl by settingsRepository.serverUrl.collectAsState(initial = null)
    val serverHost = remember(serverUrl) { ArtUrlPolicy.hostOf(serverUrl) }

    // The load attempt this URL is on: 0 is the first request, and each
    // failure below bumps it (bounded) while a tap on the failed tile bumps
    // it without a bound. It is part of the request's key, so a bump
    // re-issues the fetch instead of re-showing the same failure.
    var attempt by remember(url) { mutableIntStateOf(0) }

    val request = remember(url, sizing, metered, serverHost, allowAnyHost, attempt) {
        val decision = ImageSizingPolicy.decide(
            widthPx = sizing.posterMaxWidthPx,
            imagesAllowedOnMobileData = sizing.imagesAllowedOnMobileData,
            metered = metered,
        )
        // B2 (home review, 2026-11-15): a TMDB /original/ poster is a
        // full-size file Coil downloads whole and downsamples locally —
        // rewrite it to the smallest TMDB width covering this tile before
        // the request is built, so the bytes match the pixels on screen.
        val clean = url?.takeIf { it.isNotBlank() }
            ?.let { PosterUrlPolicy.sizedForTile(it, decision.widthPx) }
        val allowed = allowAnyHost || ArtUrlPolicy.isAllowed(clean, serverHost)
        if (clean != null && decision.shouldLoad && allowed) {
            ImageRequest.Builder(context)
                .data(clean)
                .size(decision.widthPx, decision.widthPx)
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

    SubcomposeAsyncImage(
        model = request,
        contentDescription = contentDescription,
        modifier = modifier.then(if (loaded) Modifier else Modifier.clearAndSetSemantics { }),
        contentScale = contentScale,
        // A real request in flight paints the branded fallback tile first and
        // sweeps the band-only shimmer over it — or the caller's branded
        // frame, when it passed one (the hero). Loading, error and null-URL
        // all show the same tile, so a slow or hung /api/v1/art proxy fetch
        // never leaves a naked void: the old bare shimmer read as a flat
        // #20282A plate (Robo: Fan Letter, Kesariya@100) whenever the band
        // was off-frame. A gated or blank URL never reaches this slot, so
        // the surface behind the card stays the truth in that case.
        loading = {
            loadingContent?.invoke() ?: Box(Modifier.fillMaxSize()) {
                ImageFallbackCard(
                    label = fallbackLabel,
                    monogram = monogram,
                    modifier = Modifier.fillMaxSize(),
                    tone = fallbackTone,
                    large = fallbackLarge,
                )
                ShimmerBox(Modifier.fillMaxSize(), bandOnly = true)
            }
        },
        // A failed load shows the branded fallback (A3 Proof 3: the slot
        // itself has no tap-to-retry — a full-size retry clickable there
        // swallowed the parent card's tap, so a tap on a failed-image
        // tile belongs to the parent card; see [CatalogImageErrorSlot]).
        error = {
            CatalogImageErrorSlot(
                fallbackLabel = fallbackLabel,
                monogram = monogram,
                tone = fallbackTone,
                large = fallbackLarge,
            )
        },
        // The artwork-accent hook: only a caller that passed onBitmap pays the
        // decode-to-bitmap, and only on a real success — a gated or failed load
        // reports nothing, so the caller keeps its fallback colour.
        onSuccess = { state ->
            loaded = true
            onBitmap?.invoke(state.result.drawable.toBitmap())
        },
        // A transient failure re-requests at once, bounded by
        // [shouldAutoRetryImage]: one failed fetch must not leave a live
        // poster as a dark initials tile for the whole visit. Once the
        // bound is spent the error slot stays — the slot has no
        // tap-to-retry (A3 Proof 3), so a tap on the tile belongs to the
        // parent card, and the image is re-requested when the composition
        // is recreated.
        onError = {
            if (shouldAutoRetryImage(attempt)) attempt++ else loaded = false
        },
    )
}

/**
 * A3 Proof 3 (Nov 2026): the content of [CatalogImage]'s error slot,
 * extracted so its tap path is testable without Coil. A failed load shows
 * the branded fallback — and the slot deliberately has NO clickable of
 * its own: a full-size tap-to-retry here swallowed the parent card's tap
 * (Discover Pakistan's logo 429s, Robo tapped the card centre twice, and
 * nothing navigated), so a tap on a failed-image tile belongs to the
 * parent card. The bounded auto-retry ([shouldAutoRetryImage]) is the
 * recovery for a transient failure; once it is spent the tile stays on
 * the fallback until the composition is recreated. [CatalogImageErrorTapTest]
 * pins this: a centre tap on the slot must reach the parent onClick.
 */
@Composable
internal fun CatalogImageErrorSlot(
    fallbackLabel: String?,
    monogram: String?,
    tone: FallbackTone,
    large: Boolean,
    modifier: Modifier = Modifier,
) {
    Box(modifier.fillMaxSize()) {
        ImageFallbackCard(
            label = fallbackLabel,
            monogram = monogram,
            modifier = Modifier.fillMaxSize(),
            tone = tone,
            large = large,
        )
    }
}

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
            modifier = Modifier.padding(8.dp),
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

/** Phase 7 (M4): the server settings, read the same way — the art policy needs the configured host. */
internal fun serverSettingsRepository(context: Context): ServerSettingsRepository =
    EntryPointAccessors.fromApplication(
        context.applicationContext,
        ImageSizingEntryPoint::class.java,
    ).serverSettingsRepository()

@EntryPoint
@InstallIn(SingletonComponent::class)
interface ImageSizingEntryPoint {
    fun imageSizingHolder(): ImageSizingHolder
    fun serverSettingsRepository(): ServerSettingsRepository
}
