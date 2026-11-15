package com.raddstream.app.ui.components

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.imageLoader
import coil.request.ImageRequest
import com.raddstream.app.R
import com.raddstream.app.brand.Brand
import com.raddstream.app.images.ArtUrlPolicy
import com.raddstream.app.images.ImageSizingPolicy
import com.raddstream.app.images.PosterUrlPolicy
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WireText
import com.raddstream.app.ui.theme.PressHaptics
import com.raddstream.app.ui.theme.RaddEmphasized
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddStreamMark
import com.raddstream.app.ui.theme.RaddTokens
import com.raddstream.app.ui.theme.pressScale
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * The banner rail on Home: [heroHeight] tall, a rounded card floating on a soft
 * ambient glow, one work at a time, advancing on its own.
 *
 * Layout of one slide, back to front:
 *  - the ambient glow ([HeroAmbientGlow]): two low-alpha radial blooms tinted from the
 *    theme, drifting to a new hue as the active page changes — light spilling into the
 *    inset around the card, which is what gives the banner depth instead of a pasted-on
 *    rectangle;
 *  - the widest artwork the row carries, cropped to fill;
 *  - a vertical scrim that goes from clear at the top to the page background at the
 *    bottom, so the title and buttons stay readable over any poster;
 *  - the title and one meta line (Season 1–2 · Reality · 2026);
 *  - page dots, which double as the manual affordance — tapping a dot jumps to that slide.
 *    They sit BELOW the card now, centred and bare (mockup, Nov 2026): they used to ride
 *    the top end in a contrast pill, and before that the bottom strip, where their 48dp
 *    hit boxes (declared after the pager, so on top in z-order) covered the Watch Now
 *    button's centre on a 6-dot hero — a centred tap hit a dot, which just re-selected
 *    the slide, so Watch Now looked dead while the banner tap (above the strip) worked.
 *    Below the card no dot can cover any control, so the band math is gone with them.
 *  - a prominent Watch Now button, which starts the title (the slide's own tap target
 *    only opens the details — the button is the part that says "play"); beside it, a
 *    My List toggle, so saving a title does not need a trip to its details page;
 *
 * WHAT THE HERO PAINTS (App/AGENTS.md §5): `GET /api/v1/posts` projects a
 * `backdrop_urls` column alongside `poster_url` (`Fast_Fetch/viewer.py` `_art_pair()`),
 * and that is the wide TMDB artwork this banner is built for — so the default image is the
 * first backdrop, and the poster is only the fallback for a row that has no backdrop, or a
 * server whose list projection predates the column. The hero never invents a URL from
 * `tmdb_id`: a TMDB image path is not derivable from the id, and guessing one would be a
 * 404 on every tile. [backdropFor] is the seam if that policy ever changes.
 *
 * Auto-advance is motion, so it respects the same system switch as the skeletons
 * ([rememberReduceMotion]): with animations off the banner still paginates by hand, it just
 * never moves on its own, and the glow cuts instead of cross-fading. A drag also gets the
 * pager back immediately — the loop waits for `isScrollInProgress` to clear before it takes
 * the page away again. The same guard covers a finger already down on Watch Now: an
 * advance that STARTS mid-press slides the button out from under it, and the tap
 * detector cancels a press whose target moved away — so the loop skips its tick while
 * the button reports pressed (`watchNowPressed`) instead of stealing the tap.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCarousel(
    posts: List<Post>,
    onPostClick: (Post) -> Unit,
    modifier: Modifier = Modifier,
    backdropFor: (Post) -> String? = { it.heroArtUrl },
    /** M5 (home review, Nov 2026): the hero is manual-swipe unless the
     *  operator opts into rotation — a rotating banner made Watch Now a
     *  moving target and pushed the first content row out of reach. False
     *  pauses the auto-advance; the interval is in ms. */
    autoplay: Boolean = false,
    intervalMs: Long = AUTO_ADVANCE_MS,
    /** Session 121 (P-26): Watch Now starts the title instead of only opening it. */
    onWatchNow: (Post) -> Unit = onPostClick,
    isFavorite: (Post) -> Boolean = { false },
    onToggleFavorite: (Post) -> Unit = {},
) {
    if (posts.isEmpty()) return
    val reduceMotion = rememberReduceMotion()
    val pagerState = rememberPagerState(pageCount = { posts.size })
    val scrollScope = rememberCoroutineScope()
    // True while a finger is down on the visible slide's Watch Now button, reported
    // up by HeroSlide. The advance loop reads it per tick (it is deliberately NOT
    // a LaunchedEffect key: restarting the effect would cancel an in-flight
    // animateScrollToPage and strand the pager between pages).
    var watchNowPressed by remember { mutableStateOf(false) }
    // Samsung RTL (v1.1.2, Issue 3): the banner rotated every 5 s and a
    // manual swipe or dot tap did not restart the clock, so the slide a
    // viewer had just chosen could be yanked away a beat later — and
    // Watch Now was a moving target. The advance loop below reads this
    // timestamp per tick: any page change (auto or manual), any drag, and
    // any dot tap restarts the full interval, so a viewer always gets the
    // whole interval on the slide they are looking at.
    var lastChangeMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    // A drag that snaps back to the same page changes no page, so the
    // page effect below would not see it — the scroll flag is the record
    // that the viewer is handling the banner right now.
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) lastChangeMs = SystemClock.uptimeMillis()
    }
    // Self-heal for the press flag: if the pressed slide is ever disposed mid-press
    // (a two-finger drag paging away under the finger), its release callback never
    // fires and the flag would stick true, parking auto-advance forever. A page
    // change means the pressed button is gone, so the flag is stale by definition.
    // A normal press can never trip this — the loop below holds its tick while the
    // flag is true, and a dot tap needs the finger to be on a dot, not the button.
    LaunchedEffect(pagerState.currentPage) {
        watchNowPressed = false
        lastChangeMs = SystemClock.uptimeMillis()
    }

    LaunchedEffect(posts.size, reduceMotion, autoplay, intervalMs) {
        if (!autoplay || posts.size < 2 || reduceMotion) return@LaunchedEffect
        while (true) {
            // Tick, don't sleep the whole interval: a press, a drag, or a
            // manual page change part-way through the wait must hold the
            // advance, and a single long delay could not notice any of them
            // until it had already fired. The tick is the only cost; the
            // decision — and its test — lives in [shouldAdvanceHero].
            delay(HERO_ADVANCE_TICK_MS)
            if (!shouldAdvanceHero(
                    nowMs = SystemClock.uptimeMillis(),
                    lastChangeMs = lastChangeMs,
                    intervalMs = intervalMs,
                    isScrolling = pagerState.isScrollInProgress,
                    watchNowPressed = watchNowPressed,
                )
            ) continue
            lastChangeMs = SystemClock.uptimeMillis()
            pagerState.animateScrollToPage(
                nextHeroPage(pagerState.currentPage, posts.size),
                animationSpec = RaddMotion.defaultSpatial(),
            )
        }
    }

    // Mockup (Nov 2026): the dots live BELOW the card, not on it — centred,
    // bare, no pill. They cannot cover any control down here: the overlap
    // that once ate Watch Now taps is structural now, not a band calculation
    // ([heroIndicatorOverlapsActionRow] is gone with it).
    Column(modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().height(heroHeight())) {
        HeroAmbientGlow(
            currentPage = pagerState.currentPage,
            modifier = Modifier.fillMaxSize(),
        )

        HorizontalPager(
            state = pagerState,
            // F28 (Batch B): the pager composes only the current page by
            // default, so every auto-advance started a fresh image load and
            // the hero read as empty mid-load (the Batch 0/1 empty-hero
            // shots). One page of lookahead keeps the next slide's art
            // warm, so an advance lands on a painted slide.
            beyondBoundsPageCount = 1,
            modifier = Modifier.fillMaxSize().padding(heroInset),
        ) { page ->
            val post = posts.getOrNull(page) ?: return@HorizontalPager
            HeroSlide(
                post = post,
                backdropUrl = backdropFor(post),
                onClick = { onPostClick(post) },
                onWatchNow = { onWatchNow(post) },
                onWatchNowPressChange = { watchNowPressed = it },
                isFavorite = isFavorite(post),
                onToggleFavorite = { onToggleFavorite(post) },
                modifier = Modifier.clip(RoundedCornerShape(HERO_CORNER)),
            )
        }
    }

    if (posts.size > 1) {
        HeroIndicator(
            count = posts.size,
            current = pagerState.currentPage,
            // Scrolling is a suspending pager call, so the dot's click launches it on the
            // composition's scope instead of pretending the tap can wait for it inline.
            // The tap also restarts the auto-advance clock outright: tapping the
            // current dot changes no page, so the page effect would not see it,
            // and without this the slide just chosen could still be yanked away
            // a beat later (Samsung RTL v1.1.2, Issue 3).
            onSelect = { index ->
                lastChangeMs = SystemClock.uptimeMillis()
                scrollScope.launch {
                    pagerState.animateScrollToPage(index, animationSpec = RaddMotion.defaultSpatial())
                }
            },
            // Below the card, on the page background: no pill behind the
            // dots — the pill was contrast over bright artwork, and down
            // here there is no artwork behind them.
            background = false,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp),
        )
    }
    }
}

/**
 * The branded frame the hero paints while its artwork is still in flight
 * ([CatalogImage]'s loading slot): the [R.drawable.hero_fallback] vector —
 * dark surface, jade mark — full-bleed under the slide's own scrim, so the
 * wait reads as a designed brand surface, never a black void. No title text
 * here on purpose: the slide's headline already prints the title in big type
 * (displaySmall) through the whole load, and printing it on the frame too
 * duplicates it (M1, Appetize-proven). The no-URL branch is different — there
 * the headline is dropped, so the fallback card carries the title instead.
 */
@Composable
internal fun HeroFallbackFrame(modifier: Modifier = Modifier) {
    Image(
        imageVector = ImageVector.vectorResource(R.drawable.hero_fallback),
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier.fillMaxSize().testTag("heroFallbackFrame"),
    )
}

/**
 * The hero's art URLs, in slide order: the widest artwork each row carries
 * (first backdrop, else poster), blanks dropped, duplicates dropped. Pure,
 * so the selection is testable without Coil.
 */
internal fun heroArtUrls(posts: List<Post>): List<String> =
    posts.mapNotNull { it.heroArtUrl?.takeIf { url -> url.isNotBlank() } }.distinct()

/**
 * The hero's one meta line (review redesign, Nov 2026): seasons, first genre, year —
 * "Season 1–2 · Reality · 2026". The seasons text is normalised by
 * [heroSeasonsLabel] (the server sends "2", "S01–S03" and "[1, 2]" for the same
 * fact — a raw list leaked onto the card as "[1, 2]"), the genre reads
 * capitalised, and the parts join with " · ". Null when the title carries none
 * of the three, so the slide prints no empty line. Pure, so the line is
 * testable without a pager.
 */
internal fun heroMetaLine(post: Post): String? {
    val parts = listOfNotNull(
        heroSeasonsLabel(post.seasons),
        post.genreList.firstOrNull()?.lowercase()?.replaceFirstChar { it.uppercase() }?.takeIf { it.isNotBlank() },
        post.yearLabel,
    )
    return parts.joinToString(" · ").takeIf { it.isNotBlank() }
}

/**
 * The seasons half of [heroMetaLine], normalised from the server's free text.
 * A bare integer is a count of seasons ("2" -> "Season 1–2"); anything else
 * lists explicit season numbers ("[1, 2]", "S01–S03" -> "Season 1–2" and
 * "Season 1–3"). A single season reads "Season 1"; text with no numbers
 * carries no seasons at all. Pure, so the shapes are assertable without a pager.
 */
internal fun heroSeasonsLabel(raw: String?): String? {
    val text = raw?.trim().orEmpty()
    if (text.isBlank()) return null
    val numbers = Regex("""\d+""").findAll(text).mapNotNull { it.value.toIntOrNull() }.toList()
    if (numbers.isEmpty()) return null
    if (Regex("""^\d+$""").matches(text)) {
        val count = numbers.first()
        return if (count <= 1) "Season 1" else "Season 1–$count"
    }
    val first = numbers.min()
    val last = numbers.max()
    return if (first == last) "Season $first" else "Season $first–$last"
}

/**
 * The title the hero paints: the same clean-title treatment Detail uses
 * ([UserWords.detailTitle] — server display title, then clean title, then
 * raw title, each stripped of the year/language/type/season suffixes the
 * meta line already states), so the hero and Detail can never disagree
 * about a title's name. Pure, so the shape is assertable without a pager.
 */
internal fun heroTitle(post: Post): String =
    UserWords.detailTitle(
        displayTitle = post.displayTitleWire,
        cleanTitle = post.cleanTitle,
        title = post.title,
        year = post.year,
        contentType = post.contentType,
        languages = WireText.listOf(post.language),
    )

/**
 * Hero art prefetch (Nov 2026): when Home lands, warm the hero items' art so
 * first paint has art, not placeholders. Hero items only — the caller passes
 * the capped hero list (HERO_LIMIT = 6), never the whole rail. The request is
 * built exactly like the slide's own ([CatalogImage]): the same TMDB sizing
 * rewrite, the same host allowlist, the same metered gate — so the prefetched
 * bytes are the bytes the slide shows, and a metered connection the viewer
 * never granted image loading to fetches nothing. Coil dedupes an in-flight
 * request for the same URL, so this never double-fetches the slide that is
 * already loading. Never throws: a prefetch is an optimisation, and a failed
 * one must leave Home exactly as it was.
 */
internal suspend fun prefetchHeroArt(context: Context, posts: List<Post>) {
    val urls = heroArtUrls(posts)
    if (urls.isEmpty()) return
    runCatching {
        val holder = imageSizingHolder(context)
        val sizing = holder.sizing.value
        if (!ImageSizingPolicy.shouldLoad(sizing.imagesAllowedOnMobileData, holder.isMetered())) return
        val serverHost = ArtUrlPolicy.hostOf(serverSettingsRepository(context).serverUrl.first())
        val loader = context.imageLoader
        urls.forEach { url ->
            val clean = PosterUrlPolicy.sizedForTile(url, sizing.posterMaxWidthPx)
            if (!ArtUrlPolicy.isAllowed(clean, serverHost)) return@forEach
            loader.enqueue(
                ImageRequest.Builder(context)
                    .data(clean)
                    .size(sizing.posterMaxWidthPx, sizing.posterMaxWidthPx)
                    .build(),
            )
        }
    }
}

/**
 * The banner's own placeholder while the first rail is still in flight. It keeps the real
 * hero's footprint ([heroHeight]) so the page does not jump when artwork lands, and a few
 * shimmering bars stand in for the title, meta line and Watch Now button.
 *
 * Samsung RTL (v1.1.2, Issue 3): the first load read as a blank grey box for ~10 s.
 * The skeleton was already here — but its shimmer band (0.16f) is near-invisible
 * over the surfaceVariant base, so on a phone it read as flat grey, not as
 * loading. The band runs stronger here ([HERO_SKELETON_SHIMMER_ALPHA]) and the
 * brand mark sits in the middle, so the wait reads as "featured titles are
 * loading" instead of an empty slot. The mark is decorative canvas, like the
 * fallback card's, and stays out of the accessibility tree with the bars.
 */
@Composable
fun HeroSkeleton(modifier: Modifier = Modifier) {
    // Mockup (Nov 2026): the skeleton mirrors the real hero — inset card,
    // then the dots BELOW it, bare. The dots used to shimmer inside the card
    // at the top end; they ride under it now, like the real indicator.
    Column(modifier.fillMaxWidth()) {
    Box(Modifier.fillMaxWidth().height(heroHeight())) {
        Box(Modifier.fillMaxSize().padding(heroInset).clip(RoundedCornerShape(HERO_CORNER))) {
            ShimmerBox(Modifier.fillMaxSize(), highlightAlpha = HERO_SKELETON_SHIMMER_ALPHA)
            RaddStreamMark(
                diameter = 56.dp,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                modifier = Modifier.align(Alignment.Center),
            )
            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 20.dp),
                // The real slide's text column is 8dp-spaced, not 10dp.
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Title: displaySmall, one 44dp line. The real title allows
                // two lines, but a one-line title is the common case — a
                // second bar would over-reserve every short title.
                ShimmerBox(
                    Modifier.fillMaxWidth(0.55f).height(44.dp).testTag("heroTitleSkeleton"),
                    shape = RoundedCornerShape(8.dp),
                )
                // Meta line: one bodyMedium line (16dp) — the real meta line
                // is a single 20dp line, not the 22dp badge pills it replaced.
                ShimmerBox(
                    Modifier.width(180.dp).height(20.dp),
                    shape = RoundedCornerShape(8.dp),
                )
                // Action row: Watch Now AND My List, each 40dp min, 8dp
                // apart, 28dp corners — the old skeleton drew one bar and
                // the second button appeared out of nowhere on load.
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ShimmerBox(
                        Modifier.width(150.dp).height(HERO_BUTTON_HEIGHT_DP.dp),
                        shape = RoundedCornerShape(28.dp),
                    )
                    ShimmerBox(
                        Modifier.width(130.dp).height(HERO_BUTTON_HEIGHT_DP.dp),
                        shape = RoundedCornerShape(28.dp),
                    )
                }
            }
        }
    }
        // The page dots below the card: six 48dp cells, each with a small
        // painted dot centred in it — the same footprint as the real
        // indicator. Six dots is the hero's default count; a smaller hero
        // shows no dots at all. The painted dots match the real ones
        // (review redesign, Nov 2026): the first is the 16×4dp active
        // pill, the rest 4dp circles.
        Row(
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(top = 10.dp)
                .height(48.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            repeat(6) { index ->
                Box(
                    modifier = Modifier.size(48.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    ShimmerBox(
                        Modifier
                            .width(if (index == 0) 16.dp else 4.dp)
                            .height(4.dp),
                        shape = RaddTokens.Shape.Pill,
                        highlightAlpha = HERO_SKELETON_SHIMMER_ALPHA,
                    )
                }
            }
        }
    }
}

/**
 * Two soft radial blooms behind the banner, tinted from the theme's accent trio and
 * cross-fading to the next hue as the pager moves. The card is inset by [heroInset], so
 * this light spills out around it — depth without a single extra image.
 */
@Composable
private fun HeroAmbientGlow(currentPage: Int, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val accents = remember(scheme.primary, scheme.secondary, scheme.tertiary) {
        listOf(scheme.primary, scheme.secondary, scheme.tertiary)
    }
    val reduceMotion = rememberReduceMotion()
    val fade = if (reduceMotion) snap<Color>() else RaddMotion.slowEffects()
    val leading by animateColorAsState(accents[currentPage % accents.size], fade, label = "heroGlowLeading")
    val trailing by animateColorAsState(accents[(currentPage + 1) % accents.size], fade, label = "heroGlowTrailing")

    Box(
        modifier.drawBehind {
            drawRect(
                Brush.radialGradient(
                    colors = listOf(leading.copy(alpha = 0.34f), leading.copy(alpha = 0f)),
                    center = Offset(size.width * 0.30f, size.height * 0.20f),
                    radius = size.maxDimension * 0.72f,
                ),
            )
            drawRect(
                Brush.radialGradient(
                    colors = listOf(trailing.copy(alpha = 0.24f), trailing.copy(alpha = 0f)),
                    center = Offset(size.width * 0.84f, size.height * 0.66f),
                    radius = size.maxDimension * 0.58f,
                ),
            )
        },
    )
}

@Composable
private fun HeroSlide(
    post: Post,
    backdropUrl: String?,
    onClick: () -> Unit,
    onWatchNow: () -> Unit = onClick,
    /** Reports the Watch Now button's press state up, so the auto-advance loop can
     *  hold its tick while a finger is down on it instead of sliding the button
     *  away mid-tap. Default no-op: the slide is still fully usable without it. */
    onWatchNowPressChange: (Boolean) -> Unit = {},
    isFavorite: Boolean = false,
    onToggleFavorite: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val title = heroTitle(post)
    // The spoken line still names the languages (the meta line replaced the
    // visible pills, not the words) — read straight from the wire text,
    // filtered by the same known-language rule, no pill cap or length cut:
    // a screen reader is not a layout.
    val languages = remember(post.language) {
        WireText.listOf(post.language).map { it.trim() }.filter { isKnownLanguage(it) }
    }
    val genres = remember(post.genres) { post.genreList.take(2) }
    val rating = remember(post.imdbRating) { UserWords.displayRating(post.imdbRating) }
    // The slide merges its children, so TalkBack reads this line and then the Watch Now button
    // inside it. Saying "watch now" here as well would announce the same action twice.
    val a11y = buildString {
        append(title)
        rating?.let { append(", rating ").append(it) }
        post.yearLabel?.let { append(", ").append(it) }
        if (genres.isNotEmpty()) append(", ").append(genres.joinToString())
        if (languages.isNotEmpty()) append(", languages ").append(languages.joinToString())
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = a11y }
            .clickable(onClick = onClick),
    ) {
        // 1 — artwork, or the branded fallback card when there is nothing to paint.
        // F6 (Batch A): the blank-backdrop branch used to paint a labelless card —
        // a bare gradient with no title at all. It now names the title, like the
        // failed-load fallback below it.
        if (backdropUrl.isNullOrBlank()) {
            ImageFallbackCard(label = title, tone = FallbackTone.Hero)
        } else {
            CatalogImage(
                url = backdropUrl,
                contentDescription = null, // the merged description above already tells it
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                // Home review (Nov 2026, M1): no fallback label here. When this
                // image fails or is gated, the fallback card would print the
                // title in the middle of the slide while the headline below
                // prints it again — the duplicate the Appetize screenshots
                // showed ("The Stranger" twice). The headline below always
                // carries the title on this branch (hasArt), so the fallback
                // is mark + gradient only.
                fallbackLabel = null,
                // B1 (home review, Nov 2026): a failed backdrop paints the
                // hero-tinted gradient, so the slide reads as a designed
                // brand surface instead of a black void.
                fallbackTone = FallbackTone.Hero,
                // Hero (Nov 2026): while the art is in flight the slide shows
                // the branded frame, not the bare shimmer — the shimmer is
                // what read as a black void on first paint.
                loadingContent = { HeroFallbackFrame() },
            )
        }

        // 2 — vertical scrim: a multi-stop bottom fade into the page
        // background (review redesign, Nov 2026). The old gradient faded
        // too late and too softly, so baked-in poster text bled behind
        // the title and buttons; this one is clear through the top half
        // (nothing sits there now — the dots ride below the card) and
        // 0.75 at 75% before landing on the background at the bottom.
        // Session 121 (P-20): the scrim protects the title/badges against
        // bright art and blends smoothly into the page canvas at the
        // bottom of the full-bleed hero.
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.45f to MaterialTheme.colorScheme.background.copy(alpha = 0f),
                        0.75f to MaterialTheme.colorScheme.background.copy(alpha = 0.75f),
                        1f to MaterialTheme.colorScheme.background,
                    )
                ),
        )

        // 3 — the words. With art they sit at the bottom, over the scrim.
        // Without art (F8c, Batch B) the slide is a branded fallback card
        // that already names the title in its centre — repeating the headline
        // at the bottom left an enormous empty area with the buttons sitting
        // very low, reading as a missing background. So the art-less slide
        // centres its badges + buttons instead, and drops the repeated title.
        val hasArt = !backdropUrl.isNullOrBlank()
        Column(
            modifier = Modifier
                .align(if (hasArt) Alignment.BottomStart else Alignment.CenterStart)
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .padding(bottom = if (hasArt) HERO_ACTION_BOTTOM_DP.dp else 0.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasArt) {
                Text(
                    text = title,
                    style = RaddEmphasized.displaySmall,
                    color = MaterialTheme.colorScheme.onBackground,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                // Mockup (Nov 2026): one meta line under the title —
                // "S1-S2 | reality | 2026" — not four separate pills. The
                // rating, year and type badges are gone with the pills; the
                // meta line is the only words under the title.
                heroMetaLine(post)?.let { meta ->
                    Text(
                        text = meta,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onBackground,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // UI-fix P1-X1: press scale + haptic on the hero's primary action.
                val watchNowSource = remember { MutableInteractionSource() }
                PressHaptics(watchNowSource)
                // The same press state the haptic reads, reported up: the carousel
                // holds its auto-advance tick while this is true. Collected here,
                // not in the carousel, because the source belongs to this slide's
                // button — the carousel only ever sees the visible slide's press.
                val watchNowPressed by watchNowSource.collectIsPressedAsState()
                LaunchedEffect(watchNowPressed) { onWatchNowPressChange(watchNowPressed) }
                Button(
                    onClick = onWatchNow,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    ),
                    interactionSource = watchNowSource,
                    // Mockup (Nov 2026): the two buttons share the slide's
                    // width equally and read in ALL CAPS — "WATCH NOW" /
                    // "MY LIST" — instead of wrapping their labels. The
                    // inset card leaves each button ~144dp wide, and the
                    // default 24dp content padding + 22dp icon pushed
                    // "WATCH NOW" onto two lines — so both buttons take
                    // 12dp of content padding and an 18dp icon, which fits
                    // the longest label on one line with room to spare.
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    modifier = Modifier.weight(1f).heightIn(min = HERO_BUTTON_HEIGHT_DP.dp).pressScale(watchNowSource),
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(Brand.copy("hero_watch_now", "Watch Now").uppercase(), fontWeight = FontWeight.Bold)
                }
                // Session 121 (P-26): My List on the hero, so saving a title does not
                // need a trip to its details page first. The filled heart says saved;
                // the treatment matches the year/type badges above it.
                Button(
                    onClick = onToggleFavorite,
                    shape = RoundedCornerShape(28.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f),
                        contentColor = MaterialTheme.colorScheme.onBackground,
                    ),
                    // Same 12dp content padding as Watch Now (see above):
                    // the two buttons share the width, so they share the
                    // padding that keeps their labels on one line.
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                    // CL-6: the button's visible text is "My List", the same words
                    // as the nav tab — so it announces what the tap does instead:
                    // "Save to My List", or "Saved to My List" once saved.
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = HERO_BUTTON_HEIGHT_DP.dp)
                        .semantics { contentDescription = if (isFavorite) "Saved to My List" else "Save to My List" },
                ) {
                    Icon(
                        imageVector = if (isFavorite) Icons.Default.Favorite else Icons.Default.FavoriteBorder,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text((if (isFavorite) "Saved" else "My List").uppercase(), fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

/**
 * Page dots that are also buttons: the active one stretches into a short bar, so the state
 * reads at a glance, and each dot jumps the pager to its slide.
 *
 * The painted dot is 4dp tall (review redesign, Nov 2026: active 16×4dp pill,
 * inactive 4dp circles) and used to be the only thing a finger could aim at. Each one now
 * sits inside the [DOT_TOUCH_WIDTH] by [DOT_TOUCH_HEIGHT] box — 48dp square, the WCAG 2.2 AA
 * minimum in both directions (the 24dp width it used to have failed Robo's 48dp touch-target
 * floor). Mockup (Nov 2026): the row rides BELOW the card, centred and bare — it used to
 * ride the top end in a contrast pill, and before that the bottom strip, where its hit
 * boxes covered the Watch Now button's centre. Below the card no dot can cover any
 * control, and the pill is gone with the artwork behind it ([background] = false);
 * the pill stays for an overlay position, where the dots still need contrast over
 * bright artwork. The painted dots shrank twice — Batch B (active 18dp → 12dp, 7dp → 5dp
 * tall) and the review redesign (16×4dp pill, 4dp circles) — because the old capsules
 * read oversized next to the badges. The hit boxes stay 48dp: the review asked for a
 * ~20dp-tall dots row, but the 48dp boxes are pinned by HeroIndicatorTest and by the
 * Robo touch-target floor, so the row's footprint stays 48dp and only the paint got
 * smaller. Each dot also says what it is in words and carries a `selected` state,
 * because the visual stretch was the only thing that used to show which slide is up.
 */
@Composable
internal fun HeroIndicator(
    count: Int,
    current: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * Mockup (Nov 2026): below the card the dots sit on the page
     * background, bare — the contrast pill behind them was for bright
     * artwork, and there is none behind them down here. True keeps the
     * pill (an overlay position); false draws bare dots.
     */
    background: Boolean = true,
) {
    if (count <= 1) return
    val scheme = MaterialTheme.colorScheme
    val reduceMotion = rememberReduceMotion()
    Row(
        modifier = modifier
            .then(
                if (background) {
                    Modifier.drawBehind {
                        // Visual capsule is a compact 18dp-tall pill centered over the
                        // dot strip while every dot retains its full 48x48dp touch target.
                        val pillHeight = 18.dp.toPx().coerceAtMost(size.height)
                        val insetX = 12.dp.toPx().coerceAtMost(size.width * 0.15f)
                        val pillWidth = (size.width - insetX * 2f).coerceAtLeast(pillHeight)
                        val top = (size.height - pillHeight) / 2f
                        val corner = CornerRadius(pillHeight / 2f, pillHeight / 2f)
                        drawRoundRect(
                            color = scheme.scrim.copy(alpha = 0.56f),
                            topLeft = Offset(insetX, top),
                            size = Size(pillWidth, pillHeight),
                            cornerRadius = corner,
                        )
                        drawRoundRect(
                            color = scheme.onSurface.copy(alpha = 0.14f),
                            topLeft = Offset(insetX, top),
                            size = Size(pillWidth, pillHeight),
                            cornerRadius = corner,
                            style = Stroke(width = 0.75.dp.toPx()),
                        )
                    }
                } else Modifier
            ),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            val dotWidth by animateDpAsState(
                targetValue = if (active) 16.dp else 4.dp,
                animationSpec = if (reduceMotion) snap() else RaddMotion.fastSpatial(),
                label = "heroDotWidth",
            )
            val dotColor by animateColorAsState(
                targetValue = if (active) scheme.primary else scheme.onSurface.copy(alpha = 0.50f),
                animationSpec = if (reduceMotion) snap() else RaddMotion.defaultEffects(),
                label = "heroDotColor",
            )
            Box(
                modifier = Modifier
                    .size(width = DOT_TOUCH_WIDTH, height = DOT_TOUCH_HEIGHT)
                    .clickable(onClickLabel = "Show banner ${index + 1} of $count") { onSelect(index) }
                    .semantics {
                        contentDescription = "Banner ${index + 1} of $count"
                        selected = active
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = dotWidth, height = 4.dp)
                        .clip(RaddTokens.Shape.Pill)
                        .background(dotColor),
                )
            }
        }
    }
}

/**
 * A language value the server actually knows. The catalog sends the literal words
 * "Unknown" and "N/A" for titles whose language was never recorded — a missing value,
 * not a language — so every place that draws a language name drops those (F2, Batch A),
 * instead of each site re-deciding what counts as unknown.
 */
internal fun isKnownLanguage(value: String): Boolean =
    value.isNotBlank() && !value.equals("unknown", ignoreCase = true) && !value.equals("n/a", ignoreCase = true)

/**
 * The auto-advance step, pure so the wrap-around is testable: the next page, or page 0
 * again after the last one. A degenerate count advances nowhere.
 */
internal fun nextHeroPage(currentPage: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else (currentPage + 1) % pageCount

/**
 * Whether the auto-advance loop may take the next slide, pure so the timing
 * is testable without a pager. A drag in progress, or a press on Watch Now,
 * holds the tick — an advance starting under a finger moves the button away
 * before tap-up, and the tap is then cancelled instead of navigating. And a
 * full [intervalMs] must have passed since the last page change or user
 * interaction ([lastChangeMs]): a manual swipe or dot tap restarts the clock,
 * so the slide just chosen is never yanked away a beat later (Samsung RTL
 * v1.1.2, Issue 3). A non-positive interval never advances on its own.
 */
internal fun shouldAdvanceHero(
    nowMs: Long,
    lastChangeMs: Long,
    intervalMs: Long,
    isScrolling: Boolean,
    watchNowPressed: Boolean,
): Boolean {
    if (isScrolling || watchNowPressed) return false
    if (intervalMs <= 0L) return false
    return nowMs - lastChangeMs >= intervalMs
}

/**
 * Density (2026-11-15): the hero action row was 56dp tall — M3's large button
 * on a banner that already eats 320–400dp of the first viewport. 40dp is M3's
 * small button and the size Netflix's Play button ships at; the button still
 * spans most of the hero's width, so the target stays large. The skeleton
 * placeholder reads this same constant.
 */
internal const val HERO_BUTTON_HEIGHT_DP = 40f
internal const val HERO_ACTION_BOTTOM_DP = 20f
private const val DOT_TOUCH_HEIGHT_DP = 48f

/**
 * Samsung RTL (v1.1.2, Issue 3): 5 s per slide made Watch Now a moving
 * target — a viewer reading the slide and then reaching for the button got
 * the slide yanked away. 8 s is a full read plus a comfortable tap, and the
 * operator can still retune it from the panel (`hero_interval_s`); this is
 * only the default when no theme has been published. HomeScreen, PanelTheme
 * and the theme mirror in ServerSettingsRepository carry the same 8 s, so no
 * layer silently reverts it to 5.
 */
private const val AUTO_ADVANCE_MS = 8_000L

/** The auto-advance loop's tick: short enough to notice a press, a drag, or a
 *  manual page change promptly, long enough to cost nothing. */
private const val HERO_ADVANCE_TICK_MS = 500L

/** The skeleton's shimmer band runs stronger than the shared default
 *  (ShimmerBox), so the first-load hero reads as loading, not blank grey. */
private const val HERO_SKELETON_SHIMMER_ALPHA = 0.30f

/**
 * The page dot's hit box: 48dp square, the WCAG 2.2 AA minimum in both directions.
 * The painted dot inside it is 4dp tall (16×4dp active pill, 4dp circles).
 */
private val DOT_TOUCH_HEIGHT: Dp = DOT_TOUCH_HEIGHT_DP.dp
private val DOT_TOUCH_WIDTH: Dp = 48.dp

/**
 * The banner's height. A rounded, inset hero wants ~16:9 of a phone screen plus the text
 * block; 400dp keeps the first rail's posters peeking over the fold, so the page still
 * reads as a catalog and not as a single poster. UI-fix P1-H2: on a narrow phone
 * (360dp class) 400dp is 62% of the screen for one title — the hero caps at 320dp
 * there, so the first rail still shows below it.
 */
@Composable
private fun heroHeight(): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    return if (width < 400) 320.dp else 400.dp
}

/** Mockup (Nov 2026): the hero is a rounded inset card, 16dp in from the
 *  screen edges — not the full-bleed cinematic banner it used to be. The
 *  page background shows on both sides of the card. */
private val heroInset = PaddingValues(horizontal = 16.dp)

private val HERO_CORNER: Dp = 16.dp
