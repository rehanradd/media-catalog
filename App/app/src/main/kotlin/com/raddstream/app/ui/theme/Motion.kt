@file:OptIn(ExperimentalSharedTransitionApi::class)
package com.raddstream.app.ui.theme

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.rememberSharedContentState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.raddstream.app.network.models.Post

// ═════════════════════════════════════════════════════════════════════════════
//  ROUND 6 MOTION INFRA — shared-element keys + MotionScheme accessors
//  The one contract every round-6 screen branch follows. The NavHost in
//  NavGraph.kt is wrapped in a SharedTransitionLayout and hands the two
//  scopes to Home, Search, My List and Detail; the screens thread them
//  into their sharedElement() calls. Nothing here renders on its own, so
//  this file changes no golden: with null scopes the poster helper is a
//  no-op (Paparazzi previews, tests), and the spec accessors are only
//  read by the screen branches' animations.
// ═════════════════════════════════════════════════════════════════════════════

/**
 * THE poster→hero shared-element key: "poster-<workId>", where <workId> is
 * the work's stable ID — the same ID the Detail route carries. A v1 row
 * has no work id, so the post id stands in (exactly as navigateToPost
 * routes it: workId when present, else post id). One key per work, shared by source and destination — never a
 * per-screen key, or the element has nothing to match on the other side.
 */
fun posterSharedElementKey(workId: Long?, postId: Int): String =
    "poster-${workId ?: postId.toLong()}"

/** The poster key for a list [Post] — its work id when present, else its post id. */
fun posterSharedElementKey(post: Post): String =
    posterSharedElementKey(post.workId, post.id)

/**
 * The poster key from a Detail route's arguments: the route's work id when
 * it is not the -1 "no work id" default, else the post id. Matches
 * [posterSharedElementKey] for the same work.
 */
fun posterSharedElementKeyForDetail(workId: Long, postId: Int): String =
    "poster-${if (workId != -1L) workId else postId.toLong()}"

/**
 * Applies sharedElement() for a poster/hero pair under [key]. A no-op when
 * either scope is null — the poster renders exactly as before outside
 * navigation (Paparazzi previews, tests), and the screen branches need no
 * null-check at the call site.
 */
@Composable
fun Modifier.posterSharedElement(
    key: String,
    sharedTransitionScope: SharedTransitionScope?,
    animatedVisibilityScope: AnimatedVisibilityScope?,
): Modifier {
    if (sharedTransitionScope == null || animatedVisibilityScope == null) return this
    return with(sharedTransitionScope) {
        sharedElement(
            state = rememberSharedContentState(key = key),
            animatedVisibilityScope = animatedVisibilityScope,
        )
    }
}

// ── MOTIONSCHEME ACCESSORS ───────────────────────────────────────────────────
// The expressive specs the theme already applies (RaddStreamTheme sets
// motionScheme = MotionScheme.expressive()), read live from
// MaterialTheme.motionScheme so M3 components and custom animations share
// one source. RaddMotion carries the same token values directly for call
// sites outside a MaterialTheme scope; these accessors are the ones the
// screen branches use. Spatial = container/positional movement (may
// overshoot); effects = fade/scale for content appearance (critically
// damped, never overshoots). No per-screen spring constants, no invented
// easing — a screen that needs a different feel uses fast/slow, not a
// hand-rolled spring.

/** Default spatial spec: rails arriving, cards settling, pager pages. */
@Composable
fun <T> raddSpatialSpec(): FiniteAnimationSpec<T> =
    MaterialTheme.motionScheme.defaultSpatialSpec()

/** Fast spatial spec: small shape/bounds changes. */
@Composable
fun <T> raddFastSpatialSpec(): FiniteAnimationSpec<T> =
    MaterialTheme.motionScheme.fastSpatialSpec()

/** Slow spatial spec: hero glow, large containers. */
@Composable
fun <T> raddSlowSpatialSpec(): FiniteAnimationSpec<T> =
    MaterialTheme.motionScheme.slowSpatialSpec()

/** Default effects spec: press scale, HUD hiding, selection. */
@Composable
fun <T> raddEffectsSpec(): FiniteAnimationSpec<T> =
    MaterialTheme.motionScheme.defaultEffectsSpec()

/** Fast effects spec: HUD showing, chip state. */
@Composable
fun <T> raddFastEffectsSpec(): FiniteAnimationSpec<T> =
    MaterialTheme.motionScheme.fastEffectsSpec()

/** Slow effects spec: glow crossfades, large fades. */
@Composable
fun <T> raddSlowEffectsSpec(): FiniteAnimationSpec<T> =
    MaterialTheme.motionScheme.slowEffectsSpec()
