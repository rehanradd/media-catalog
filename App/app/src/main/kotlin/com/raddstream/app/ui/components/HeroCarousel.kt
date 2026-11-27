package com.raddstream.app.ui.components

import android.content.Context
import android.os.SystemClock
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clipToBounds
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
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.vectorResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.raddstream.app.R
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.WireText
import com.raddstream.app.ui.theme.RaddHeroPlaceholderHigh
import com.raddstream.app.ui.theme.RaddHeroPlaceholderLow
import com.raddstream.app.ui.theme.RaddMotion
import com.raddstream.app.ui.theme.RaddStreamMark
import com.raddstream.app.ui.theme.RaddTokens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The banner rail on Home: [heroHeight] tall, a full-bleed banner
 * edge-to-edge (P1-5, Nov 2026 — the inset card is gone), one work at a
 * time, advancing on its own. The ambient glow still tints the canvas the
 * banner blends into at the bottom.
 *
 * Layout of one slide, back to front:
 *  - the ambient glow ([HeroAmbientGlow]): two low-alpha radial blooms tinted from the
 *    theme, drifting to a new hue as the active page changes — light spilling into the
 *    canvas around the banner, which is what gives it depth instead of a pasted-on
 *    rectangle;
 *  - the widest artwork the row carries, cropped to fill — and nothing else.
 *    The hero is CLEAN (user direction, 21:51 PKT 27 Nov 2026): no title
 *    text, no meta line, no Watch Now / My List buttons, no scrim, no
 *    pause control. The poster attracts, not text covering the screen —
 *    everybody already knows tapping a poster opens it, so the whole
 *    slide is the tap target (it opens the title's details page). The
 *    title, rating, year, genres and languages survive only in the
 *    slide's spoken sentence ([heroTitle] + the a11y line), so TalkBack
 *    still names the work.
 *  - page dots, which double as the manual affordance — tapping a dot jumps to that slide.
 *    They ride ON the art, top-end in a contrast pill (P1-5): they used to sit
 *    below the card, centred and bare — and before that the bottom strip, where their 48dp
 *    hit boxes (declared after the pager, so on top in z-order) covered the Watch Now
 *    button's centre on a 6-dot hero — a centred tap hit a dot, which just re-selected
 *    the slide, so Watch Now looked dead while the banner tap (above the strip) worked.
 *    The top end is free art now that nothing else sits on it, so on-art dots can cover
 *    no control — the band math is gone with them. The dots are the hero's only
 *    visible chrome: a simple animated indicator (the active dot stretches
 *    into a bar), which is all the carousel needs.
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
 * the page away again.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HeroCarousel(
    posts: List<Post>,
    onPostClick: (Post) -> Unit,
    modifier: Modifier = Modifier,
    backdropFor: (Post) -> String? = { it.heroArtUrl },
    /** M5 (home review, Nov 2026): the hero is manual-swipe unless the
     *  operator opts into rotation — a rotating banner yanked the slide
     *  away under the viewer's finger and pushed the first content row out
     *  of reach. False pauses the auto-advance; the interval is in ms. */
    autoplay: Boolean = false,
    intervalMs: Long = AUTO_ADVANCE_MS,
    /**
     * Round 6 (motion): the shared-element scopes, passed down from the
     * NavHost's SharedTransitionLayout by the infra branch. Both null by
     * default — dormant until infra wires them.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    if (posts.isEmpty()) return
    val reduceMotion = rememberReduceMotion()
    // Round 6 (motion): the hero enters with a fade + short rise on the
    // expressive springs, following the rail pattern — in inspection mode
    // it is already visible, so a Paparazzi golden shows the settled hero.
    val inInspectionMode = LocalInspectionMode.current
    var heroVisible by remember { mutableStateOf(inInspectionMode) }
    LaunchedEffect(Unit) {
        if (inInspectionMode) return@LaunchedEffect
        heroVisible = true
    }
    val pagerState = rememberPagerState(pageCount = { posts.size })
    // Video-audit #1 (Nov 2026): the hero list can shrink under a restored
    // pager — return from Live TV with a refreshed, shorter catalog — and
    // leave currentPage past the last page. A stale page means the pager
    // reads a slide that no longer exists when the auto-advance tick fires,
    // so clamp back to the last valid page the moment the list changes.
    LaunchedEffect(posts.size) {
        if (posts.isNotEmpty() && pagerState.currentPage >= posts.size) {
            pagerState.scrollToPage(clampHeroPage(pagerState.currentPage, posts.size))
        }
    }
    val scrollScope = rememberCoroutineScope()
    // Samsung RTL (v1.1.2, Issue 3): the banner rotated every 5 s and a
    // manual swipe or dot tap did not restart the clock, so the slide a
    // viewer had just chosen could be yanked away a beat later. The
    // advance loop below reads this timestamp per tick: any page change
    // (auto or manual), any drag, and any dot tap restarts the full
    // interval, so a viewer always gets the whole interval on the slide
    // they are looking at.
    var lastChangeMs by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    // A drag that snaps back to the same page changes no page, so the
    // page effect below would not see it — the scroll flag is the record
    // that the viewer is handling the banner right now.
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (pagerState.isScrollInProgress) lastChangeMs = SystemClock.uptimeMillis()
    }
    LaunchedEffect(pagerState.currentPage) {
        lastChangeMs = SystemClock.uptimeMillis()
    }
    // Video-audit #8 (Nov 2026): the pager index must not outlive the list
    // it was paging. The hero list is re-dealt when the hero section
    // changes (a stale panel hero loses the slot to "Recently added", a
    // refresh re-picks the rails), and the remembered pager kept its page —
    // so the slide in view sat at the old list's position, not the new
    // list's item. That is the shape the video's Ranma slide takes: art
    // and text that do not belong to the same item. A new list identity
    // restarts the carousel at its first item, so the slide in view is
    // always the new list's own item; the clock restarts too, so the new
    // first slide gets its full interval. The key is the ids, not the list
    // instance: a recomposition that hands the same titles back does not
    // restart the carousel.
    val heroIds = posts.map { it.id }
    LaunchedEffect(heroIds) {
        if (pagerState.currentPage != 0) pagerState.scrollToPage(0)
        lastChangeMs = SystemClock.uptimeMillis()
    }

    LaunchedEffect(posts.size, reduceMotion, autoplay, intervalMs) {
        if (!autoplay || posts.size < 2 || reduceMotion) return@LaunchedEffect
        while (true) {
            // Tick, don't sleep the whole interval: a drag, or a manual
            // page change part-way through the wait must hold the advance,
            // and a single long delay could not notice any of them until it
            // had already fired. The tick is the only cost; the decision —
            // and its test — lives in [shouldAdvanceHero].
            delay(HERO_ADVANCE_TICK_MS)
            if (!shouldAdvanceHero(
                    nowMs = SystemClock.uptimeMillis(),
                    lastChangeMs = lastChangeMs,
                    intervalMs = intervalMs,
                    isScrolling = pagerState.isScrollInProgress,
                )
            ) continue
            lastChangeMs = SystemClock.uptimeMillis()
            // Video-audit #1: re-check the page is still in range at this
            // instant — the list can shrink between the tick and the
            // advance (a refresh landing while the loop was waiting), and a
            // stale currentPage would advance from a slide that no longer
            // exists. Skip the tick instead of advancing.
            if (pagerState.currentPage >= posts.size) continue
            pagerState.animateScrollToPage(
                nextHeroPage(pagerState.currentPage, posts.size),
                animationSpec = RaddMotion.defaultSpatial(),
            )
        }
    }

    // P1-5 (Nov 2026): full-bleed — the dots ride ON the art, top-end in
    // the contrast pill. The top end is free art: nothing else sits on it
    // (the hero is clean — no title, no buttons, no pause control), so
    // on-art dots can cover no control — the overlap that once ate Watch
    // Now taps is structural now, not a band calculation
    // ([heroIndicatorOverlapsActionRow] is gone with it).
    Column(modifier.fillMaxWidth()) {
    AnimatedVisibility(
        visible = heroVisible,
        // Video-audit #7 (Nov 2026): clip the hero's entrance to its own
        // bounds, like the rails — the slide starts 1/4-height down and
        // would otherwise paint over the filter chips below it while
        // entering. Settled frames are unchanged.
        modifier = Modifier.clipToBounds(),
        enter = fadeIn(
            animationSpec = if (reduceMotion) snap() else RaddMotion.defaultEffects(),
        ) + if (reduceMotion) {
            EnterTransition.None
        } else {
            slideInVertically(
                initialOffsetY = { it / 4 },
                animationSpec = RaddMotion.defaultSpatial(),
            )
        },
    ) {
    Box(Modifier.fillMaxWidth().height(heroHeight())) {
        HeroAmbientGlow(
            currentPage = pagerState.currentPage,
            modifier = Modifier.fillMaxSize(),
        )

        // P1-5: edge-to-edge — no inset, square corners. The pager used
        // to be sized to the inset card (16dp margins, 16dp corners);
        // the next slide is cut exactly at the screen edge now, and the
        // scrim's bottom fade blends the art into the page canvas.
        HorizontalPager(
            state = pagerState,
            // F28 (Batch B): the pager composes only the current page by
            // default, so every auto-advance started a fresh image load and
            // the hero read as empty mid-load (the Batch 0/1 empty-hero
            // shots). One page of lookahead keeps the next slide's art
            // warm, so an advance lands on a painted slide.
            beyondViewportPageCount = 1,
            modifier = Modifier.fillMaxSize().clipToBounds(),
        ) { page ->
            val post = posts.getOrNull(page) ?: return@HorizontalPager
            HeroSlide(
                post = post,
                backdropUrl = backdropFor(post),
                onClick = { onPostClick(post) },
                sharedTransitionScope = sharedTransitionScope,
                animatedVisibilityScope = animatedVisibilityScope,
            )
        }

        if (posts.size > 1) {
            // The dots ride top-end, 48dp tall, on the art. The hero is
            // clean (user direction, 21:51 PKT 27 Nov 2026) — the dots are
            // its only visible chrome, and there is no pause control: a
            // dot tap restarts the clock, so the slide just chosen is
            // never yanked away a beat later (Samsung RTL v1.1.2, Issue 3).
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = RaddTokens.Space.Md, end = RaddTokens.Space.Md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HeroIndicator(
                    count = posts.size,
                    current = pagerState.currentPage,
                    titles = posts.map { it.displayTitle },
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
                    // On the art: the pill is contrast over bright artwork.
                    // The dots row itself stays 48dp (the tap floor, pinned
                    // by HeroIndicatorTest).
                    background = true,
                )
            }
        }
    }
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
 * The title the hero speaks: the same clean-title treatment Detail uses
 * ([UserWords.detailTitle] — server display title, then clean title, then
 * raw title, each stripped of the year/language/type/season suffixes), so
 * the hero and Detail can never disagree about a title's name. Pure, so
 * the shape is assertable without a pager. The clean hero (user direction,
 * 21:51 PKT 27 Nov 2026) paints no visible title — this feeds the slide's
 * spoken sentence only.
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
 * the capped hero list (HERO_LIMIT = 6), never the whole rail. The work lives
 * in [prefetchPosterArt] now; this keeps the hero's contract (widest artwork
 * per slide, via [heroArtUrls]) and the call site untouched.
 */
internal suspend fun prefetchHeroArt(context: Context, posts: List<Post>) {
    prefetchPosterArt(context, heroArtUrls(posts))
}

/**
 * The banner's own placeholder while the first rail is still in flight. It keeps the real
 * hero's footprint ([heroHeight]) so the page does not jump when artwork lands, and the
 * shimmer + the dots stand in for the clean hero (user direction, 21:51 PKT 27 Nov
 * 2026: the hero carries no words and no buttons, so its skeleton carries no
 * bars for them).
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
    // P1-5 (Nov 2026): the skeleton mirrors the real hero — full-bleed
    // card, dots ON the art at the top end, like the real indicator.
    Column(
        modifier.fillMaxWidth().semantics {
            contentDescription = "Loading home"
            liveRegion = LiveRegionMode.Polite
        },
    ) {
    Box(Modifier.fillMaxWidth().height(heroHeight())) {
        Box(Modifier.fillMaxSize()) {
            ShimmerBox(Modifier.fillMaxSize(), highlightAlpha = HERO_SKELETON_SHIMMER_ALPHA)
            // H4: no mark in the skeleton — the mark's two open arcs read as
            // a loading spinner in the hero centre. Pure shimmer instead.
            // The clean hero (user direction, 21:51 PKT 27 Nov 2026) carries
            // no words and no buttons, so the skeleton carries no bars for
            // them either — shimmer + the dots below are the whole stand-in.
            // P1-5: the page dots on the art, top-end — six 48dp cells, each
            // with a small painted dot centred in it, the same footprint and
            // position as the real indicator. Six dots is the hero's default
            // count; a smaller hero shows no dots at all. The painted dots
            // match the real ones (Audit #10): the first is the 24×6dp active
            // dash, the rest 6dp circles.
            Row(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = RaddTokens.Space.Md, end = RaddTokens.Space.Md)
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
                                .width(if (index == 0) 24.dp else 6.dp) // token-exempt: dot geometry, not spacing
                                .height(6.dp), // token-exempt: dot geometry, not spacing
                            shape = RaddTokens.Shape.Pill,
                            highlightAlpha = HERO_SKELETON_SHIMMER_ALPHA,
                        )
                    }
                }
            }
        }
    }
    }
}

/**
 * Two soft radial blooms behind the banner, tinted from the theme's accent trio and
 * cross-fading to the next hue as the pager moves. The banner is full-bleed now (P1-5),
 * so the blooms show through the scrim's bottom blend into the canvas, not around a
 * card's margins — the depth without a single extra image.
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
    modifier: Modifier = Modifier,
    /**
     * Round 6 (motion): the shared-element scopes — see [HeroCarousel].
     * Both null by default; dormant until infra wires them.
     */
    sharedTransitionScope: SharedTransitionScope? = null,
    animatedVisibilityScope: AnimatedVisibilityScope? = null,
) {
    val title = heroTitle(post)
    // The spoken line still names the languages and the rating — the
    // visible pills and meta line are gone (clean hero, user direction
    // 21:51 PKT 27 Nov 2026), but a screen reader is not a layout: the
    // title, rating, year, genres and languages all live in this one
    // sentence, read straight from the wire text, filtered by the same
    // known-language rule, no pill cap or length cut.
    val languages = remember(post.language) {
        WireText.listOf(post.language).map { it.trim() }.filter { isKnownLanguage(it) }
    }
    val genres = remember(post.genres) { post.genreList.take(2) }
    val rating = remember(post.imdbRating) { UserWords.displayRating(post.imdbRating) }
    // The slide's only words are this spoken sentence: there is no
    // visible title, meta line or button left to announce, so the
    // sentence names the work and the tap target in one line.
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
        // 1 — artwork, or the branded fallback when there is nothing to paint.
        // The clean hero (user direction, 21:51 PKT 27 Nov 2026) carries no
        // words at all: the art-less slide is the gradient + the mark only —
        // no title printed in the middle, no headline below, nothing for
        // the mark to collide with.
        if (backdropUrl.isNullOrBlank()) {
            Box(
                Modifier.fillMaxSize()
                    .then(sharedPosterModifier(post, sharedTransitionScope, animatedVisibilityScope))
                    .background(
                    Brush.verticalGradient(
                        listOf(RaddHeroPlaceholderHigh, RaddHeroPlaceholderLow),
                    ),
                ),
            ) {
                // P1-5: the mark keeps its 48dp top. The on-art indicator row
                // is TopEnd — it occupies only the right edge, and the centred
                // 48dp mark clears it horizontally.
                RaddStreamMark(
                    diameter = 48.dp, // token-exempt: fallback-mark size, not spacing
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 48.dp),
                )
            }
        } else {
            CatalogImage(
                url = backdropUrl,
                contentDescription = null, // the merged description above already tells it
                modifier = Modifier.fillMaxSize()
                    .then(sharedPosterModifier(post, sharedTransitionScope, animatedVisibilityScope)),
                contentScale = ContentScale.Crop,
                // Home review (Nov 2026, M1): no fallback label here. When this
                // image fails or is gated, a printed title in the middle of
                // the slide would be the only words on a hero that carries
                // none — the fallback is mark + gradient only, like the
                // art-less slide above.
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
    }
}

/**
 * Page dots that are also buttons: the active one stretches into a short bar, so the state
 * reads at a glance, and each dot jumps the pager to its slide.
 *
 * The painted dot is 6dp tall (review redesign, Nov 2026: active 16×4dp pill,
 * inactive 4dp circles) and used to be the only thing a finger could aim at. Each one now
 * sits inside the [DOT_TOUCH_WIDTH] by [DOT_TOUCH_HEIGHT] box — 48dp square, the WCAG 2.2 AA
 * minimum in both directions (the 24dp width it used to have failed Robo's 48dp touch-target
 * floor). Audit #10 (Nov 2026): the 16×4dp dash and 4dp dots at 50% were nearly invisible
 * on black — the active dash is 24×6dp in the accent now, the inactive dots 6dp at 85% (H7).
 * P1-5 (Nov 2026): the row rides ON the art, top-end, in the contrast pill — it used to
 * ride below the card, centred and bare, where its hit boxes could cover no control but
 * the row read as generic chrome; on the art the pill stays, because the dots need
 * contrast over bright artwork. The painted dots shrank twice — Batch B (active 18dp → 12dp, 7dp → 5dp
 * tall) and the review redesign (16×4dp pill, 4dp circles) — because the old capsules
 * read oversized next to the badges; audit #10 grew them back to 24×6dp because the
 * small ones were unreadable. The hit boxes stay 48dp: the review asked for a
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
    // H5: the slide titles — a dot announces "Slide N of 6, <title>".
    titles: List<String> = emptyList(),
    modifier: Modifier = Modifier,
    /**
     * P1-5 (Nov 2026): on the art the dots sit over bright artwork, so the
     * contrast pill stays — true keeps the pill (the on-art position);
     * false draws bare dots (the old below-card position, where there was
     * no artwork behind them).
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
                        val pillHeight = 18.dp.toPx().coerceAtMost(size.height) // token-exempt: dot-pill height, fixed component geometry
                        val insetX = 12.dp.toPx().coerceAtMost(size.width * 0.15f) // token-exempt: dot-pill inset, fixed component geometry
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
                            style = Stroke(width = 0.75.dp.toPx()), // token-exempt: hairline stroke, not spacing
                        )
                    }
                } else Modifier
            ),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val active = index == current
            // Audit #10: the dots were nearly invisible — a 16×4dp dash
            // and 4dp dots at 50% on black. The active dash stretches to
            // 24×6dp in the accent, and the inactive dots are 6dp at 85%
            // (H7: 70% still read as a smudge on black), so the row reads
            // as a control, not a smudge. The 48dp hit boxes are untouched
            // (HeroIndicatorTest pins them).
            val dotWidth by animateDpAsState(
                targetValue = if (active) 24.dp else 6.dp, // token-exempt: dot geometry, not spacing
                animationSpec = if (reduceMotion) snap() else RaddMotion.fastSpatial(),
                label = "heroDotWidth",
            )
            val dotColor by animateColorAsState(
                targetValue = if (active) scheme.primary else scheme.onSurface.copy(alpha = 0.85f),
                animationSpec = if (reduceMotion) snap() else RaddMotion.defaultEffects(),
                label = "heroDotColor",
            )
            Box(
                modifier = Modifier
                    .size(width = DOT_TOUCH_WIDTH, height = DOT_TOUCH_HEIGHT)
                    .clickable(onClickLabel = "Show banner ${index + 1} of $count") { onSelect(index) }
                    .semantics {
                        contentDescription = titles.getOrNull(index)?.let { title ->
                            "Slide ${index + 1} of $count, $title"
                        } ?: "Banner ${index + 1} of $count"
                        selected = active
                    },
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    Modifier
                        .size(width = dotWidth, height = 6.dp) // token-exempt: dot geometry, not spacing
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
 * instead of each site re-deciding what counts as unknown. The rule lives in
 * [WireText.isKnownLanguage]; this is only the hero's short name for it.
 */
internal fun isKnownLanguage(value: String): Boolean = WireText.isKnownLanguage(value)

/**
 * The auto-advance step, pure so the wrap-around is testable: the next page, or page 0
 * again after the last one. A degenerate count advances nowhere.
 */
internal fun nextHeroPage(currentPage: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else (currentPage + 1) % pageCount

/**
 * Video-audit #1 (Nov 2026): the page a restored pager holds when the hero
 * list shrinks — clamp it to the last valid page, so the pager never reads
 * a slide that no longer exists. A degenerate count clamps to 0, and a
 * negative page (never expected, never trusted) clamps up to 0.
 */
internal fun clampHeroPage(currentPage: Int, pageCount: Int): Int =
    if (pageCount <= 0) 0 else currentPage.coerceIn(0, pageCount - 1)

/**
 * Whether the auto-advance loop may take the next slide, pure so the timing
 * is testable without a pager. A drag in progress holds the tick, and a
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
): Boolean {
    if (isScrolling) return false
    if (intervalMs <= 0L) return false
    return nowMs - lastChangeMs >= intervalMs
}

private const val DOT_TOUCH_HEIGHT_DP = 48f

/**
 * Samsung RTL (v1.1.2, Issue 3): 5 s per slide yanked the slide away while
 * the viewer was still reading it. 8 s is a full read plus a comfortable
 * tap, and the operator can still retune it from the panel
 * (`hero_interval_s`); this is only the default when no theme has been
 * published. HomeScreen, PanelTheme and the theme mirror in
 * ServerSettingsRepository carry the same 8 s, so no layer silently
 * reverts it to 5.
 */
private const val AUTO_ADVANCE_MS = 8_000L

/** The auto-advance loop's tick: short enough to notice a drag or a
 *  manual page change promptly, long enough to cost nothing. */
private const val HERO_ADVANCE_TICK_MS = 500L

/** The skeleton's shimmer band runs stronger than the shared default
 *  (ShimmerBox), so the first-load hero reads as loading, not blank grey. */
private const val HERO_SKELETON_SHIMMER_ALPHA = 0.30f

/**
 * The page dot's hit box: 48dp square, the WCAG 2.2 AA minimum in both directions.
 * The painted dot inside it is 6dp tall (24×6dp active pill, 6dp circles — audit #10).
 */
private val DOT_TOUCH_HEIGHT: Dp = DOT_TOUCH_HEIGHT_DP.dp
private val DOT_TOUCH_WIDTH: Dp = 48.dp

// H1 (2027 Home fix): the 16:9 floor/cap for heroHeight() below — kept
// with the other hero geometry consts, NOT above the function: the
// @Composable annotation between the two KDocs belongs to heroHeight(),
// and a const placed there steals it (CI, 24 Nov 2026).
// Clean hero (user direction, 21:51 PKT 27 Nov 2026): the floor is 220dp
// again — the 272dp floor was the bottom text column's (reason + title +
// meta + buttons) plus the dots band, and that column is gone with the
// words. The hero is 16:9 art now, 202.5dp on a 360dp phone, floored at
// 220dp so the first rail still peeks below it.
private const val HERO_HEIGHT_MIN_DP = 220f
private const val HERO_HEIGHT_MAX_DP = 360f

/**
 * The banner's height. A full-bleed hero wants ~16:9 of a phone screen; 400dp keeps the first rail's posters peeking over the fold, so the page still
 * reads as a catalog and not as a single poster. UI-fix P1-H2: on a narrow phone
 * (360dp class) 400dp is 62% of the screen for one title — the hero caps at 320dp
 * there, so the first rail still shows below it.
 */
@Composable
/**
 * H1 (2027 Home fix): 16:9-derived — the hero is a 16:9 backdrop, so its
 * height follows the screen width (220dp floor, 360dp cap). A tablet
 * (expanded width, 840dp+) caps at 360dp. [HeroSkeleton] reads this same
 * height, so the loading footprint shrinks with it.
 */
private fun heroHeight(): Dp {
    val width = LocalConfiguration.current.screenWidthDp
    return (width * 9f / 16f).dp.coerceIn(HERO_HEIGHT_MIN_DP.dp, HERO_HEIGHT_MAX_DP.dp)
}

// P1-5 (Nov 2026): the inset card is gone — the hero is full-bleed,
// edge-to-edge with square corners, so there is no inset or corner
// value left to own here.

