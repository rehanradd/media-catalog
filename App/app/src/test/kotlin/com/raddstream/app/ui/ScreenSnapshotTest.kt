package com.raddstream.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.raddstream.app.network.models.LiveTvChannel
import com.raddstream.app.network.models.Post
import com.raddstream.app.network.models.ReleaseDetail
import com.raddstream.app.ui.components.AudioBadge
import com.raddstream.app.ui.components.HdrBadge
import com.raddstream.app.ui.components.HeroCarousel
import com.raddstream.app.ui.components.PosterCard
import com.raddstream.app.ui.components.PrintQualityBadge
import com.raddstream.app.ui.components.PrintSourceBadge
import com.raddstream.app.ui.components.RaddChip
import com.raddstream.app.ui.components.SubtitleBadge
import com.raddstream.app.ui.components.raddFilterChipColors
import com.raddstream.app.ui.screens.LiveTvChannelCard
import com.raddstream.app.ui.screens.MaintenanceScreen
import com.raddstream.app.ui.screens.MethodSection
import com.raddstream.app.ui.screens.PaymentSection
import com.raddstream.app.ui.screens.PricingSection
import com.raddstream.app.ui.theme.RaddInk950
import com.raddstream.app.ui.theme.RaddStreamTheme
import com.raddstream.app.viewmodel.ReleaseLayoutBuilder
import com.raddstream.app.viewmodel.VipMethod
import com.raddstream.app.viewmodel.VipPlan
import com.raddstream.app.viewmodel.VipUiState
import java.io.File
import java.io.FileOutputStream
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.View
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Off-screen pixel snapshots of the surfaces that are hardest to eyeball without a phone.
 *
 * Every capture renders the REAL composable the app ships — never a hand-written stand-in —
 * so a snapshot that looks right is evidence about the app rather than about this file. Where
 * a screen is driven by server state (payment handles, the maintenance escape) both honest
 * states are captured.
 *
 * PNGs land in `App/app/build/reports/screenshots/`, inside the `ci-reports` artifact ci.yml
 * already uploads. Robolectric's native graphics runtime has no Linux/aarch64 build
 * (robolectric/robolectric#9166) and it dies while the runner boots, so this class is excluded
 * on ARM hosts in `app/build.gradle.kts`: it compiles here, it renders on x86_64 CI.
 *
 * **The bitmap comes from a software draw of the view hosting the composition, because
 * `captureToImage()` cannot ever produce one here.** That call path (androidx ui-test 1.6.8
 * `WindowCapture.captureRegionToImage`) asks
 * for a fresh frame by `view.handler.post { … }` and then blocks the calling thread in
 * `waitUntil(mainClock, 2000ms)` until the redraw lands. Under Robolectric the test thread *is*
 * the main looper's thread, so the posted frame can never run while that wait holds it, and every
 * capture died at the same 2000 ms `ComposeTimeoutException` — robolectric/robolectric#8071 calls
 * this out as unsupported rather than as a timing bug, so no lengthening of the timeout and no
 * idle-wait would ever fix it. `view.draw(Canvas(bitmap))` runs the same display list through a
 * software canvas with no frame scheduling at all, which is also what makes these snapshots
 * independent of wall-clock: the only waiting left is `waitForIdle()`, i.e. composition and
 * layout having finished, not a duration.
 */
/**
 * A4-F1: the per-region bar for a fallback tile — a flat void plus two
 * anti-aliased text lines counts well under it, the branded fallback
 * (gradient + mark + title/monogram) counts well over it.
 */
private const val FALLBACK_MIN_DISTINCT_COLOURS = 52

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenSnapshotTest {

    @get:Rule
    val compose = createComposeRule()

    private val outputDir = File("build/reports/screenshots").absoluteFile

    /** The view hosting the last [shot] composition — the thing a snapshot is taken of. */
    private var renderSurface: View? = null

    private fun capture(name: String): Bitmap {
        compose.waitForIdle()
        val view = renderSurface ?: error("capture($name) ran before any shot() composed a view")
        assertTrue(
            "$name laid out a ${view.width}x${view.height} surface — nothing to snapshot",
            view.width > 0 && view.height > 0,
        )
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        outputDir.mkdirs()
        val file = File(outputDir, "$name.png")
        FileOutputStream(file).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        assertTrue("${file.name} was written but holds no bytes", file.length() > 0)
        // A blank canvas compresses to real bytes, so "the file is non-empty" proves only that
        // the write worked. Two different paints inside the frame is what proves the tree drew.
        assertTrue("$name is a flat bitmap — nothing painted itself", bitmap.paintedSomething())
        return bitmap
    }

    /**
     * Sampled, not exhaustive: a step of 4 keeps this a couple of thousand reads on a
     * window-sized frame, and any two distinct paints answer the question just as well as a
     * full scan would.
     */
    private fun Bitmap.paintedSomething(): Boolean {
        val first = getPixel(0, 0)
        var y = 0
        while (y < height) {
            var x = 0
            while (x < width) {
                if (getPixel(x, y) != first) return true
                x += 4
            }
            y += 4
        }
        return false
    }

    /**
     * A4-F1: how many distinct paints a region holds, sampled every 2px.
     * A flat void — one surfaceVariant, one ink — counts in single figures,
     * while the branded fallback (gradient + mark + title/monogram) counts
     * in the dozens. The region is the card's own bounds, so the page
     * background around it cannot inflate the count for a blank tile —
     * though the tile's own text rows sit inside it, a flat poster box
     * plus two anti-aliased text lines still counts well under the bar.
     */
    private fun Bitmap.distinctColourCount(region: Rect): Int {
        val colours = HashSet<Int>()
        val left = region.left.toInt().coerceIn(0, width - 1)
        val top = region.top.toInt().coerceIn(0, height - 1)
        val right = region.right.toInt().coerceIn(left + 1, width)
        val bottom = region.bottom.toInt().coerceIn(top + 1, height)
        var y = top
        while (y < bottom) {
            var x = left
            while (x < right) {
                colours.add(getPixel(x, y))
                x += 2
            }
            y += 2
        }
        return colours.size
    }

    private fun shot(content: @Composable () -> Unit) {
        renderSurface = null
        compose.setContent {
            val view = LocalView.current
            SideEffect { renderSurface = view }
            RaddStreamTheme(darkTheme = true) {
                Surface(modifier = Modifier.fillMaxSize(), color = RaddInk950) {
                    content()
                }
            }
        }
    }

    // ── Maintenance ──────────────────────────────────────────────────────────

    @Test
    fun maintenanceLockedNotice() {
        shot {
            Box(Modifier.fillMaxSize()) { MaintenanceScreen() }
        }
        assertTrue(
            "the notice without an escape still offered a catalog switch",
            compose.onAllNodes(hasText("Use a different catalog", substring = true))
                .fetchSemanticsNodes().isEmpty(),
        )
        capture("01_maintenance_locked")
    }

    @Test
    fun maintenanceWithServerChangeButton() {
        shot {
            Box(Modifier.fillMaxSize()) { MaintenanceScreen(onChangeServer = {}) }
        }
        assertTrue(
            "the escape hatch a wrongly-flagged deployment depends on is gone",
            compose.onAllNodes(hasText("Use a different catalog", substring = true))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        capture("02_maintenance_server_switch")
    }

    @Test
    fun maintenanceRetryRechecksTheFlag() {
        var retried = false
        shot {
            Box(Modifier.fillMaxSize()) {
                MaintenanceScreen(onChangeServer = {}, onRetry = { retried = true })
            }
        }
        assertTrue(
            "the maintenance notice offers no Retry — a wrongly-flagged server traps the viewer until the watcher's five-minute tick",
            compose.onAllNodes(hasText("Retry", substring = true))
                .fetchSemanticsNodes().isNotEmpty(),
        )
        compose.onNode(hasText("Retry", substring = true)).performClick()
        assertTrue("tapping Retry did not call the re-check", retried)
        capture("03_maintenance_retry")
    }

    // ── Poster cards ─────────────────────────────────────────────────────────

    @Test
    fun posterCards() {
        val cleaned = Post(
            id = 101,
            title = "PagalMovies · Jawan (2023) Hindi 1080p WebRip",
            cleanTitle = "Jawan",
            year = 2023,
            contentType = "movie",
            imdbRating = "7.9",
            language = "Hindi + Tamil + Telugu",
        )
        val noArtwork = Post(
            id = 102,
            title = "Oppenheimer",
            cleanTitle = "Oppenheimer",
            year = 2023,
            contentType = "movie",
            posterUrl = null,
            imdbRating = "8.9",
        )
        val longTitle = Post(
            id = 103,
            title = "Baahubali",
            cleanTitle = "Baahubali: The Beginning — The Final Chapter (Extended Director's Cut)",
            year = 2015,
            contentType = "series",
            posterUrl = null,
        )

        shot {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Trending Releases", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    // Three tiles, three shapes the app can actually build. A "#N" overlay used to
                    // be here too; no screen ever passed a rank, because the list route sends no
                    // ordering field to rank from, so the renderer was deleted rather than
                    // snapshotted. A ranked rail needs a server-supplied rank first.
                    PosterCard(post = cleaned, modifier = Modifier.width(180.dp))
                    PosterCard(post = noArtwork, modifier = Modifier.width(180.dp))
                }
                PosterCard(post = longTitle, modifier = Modifier.width(372.dp))
            }
        }
        capture("03_poster_cards")
    }

    // ── Quality badges ───────────────────────────────────────────────────────

    @OptIn(ExperimentalLayoutApi::class)
    @Test
    fun qualityBadgeStrip() {
        // The detail screen's own path: wire release rows in, ranked prints out. Building a
        // PrintDescriptor by hand would snapshot a print the server could never send.
        val prints = ReleaseLayoutBuilder.build(
            contentType = "movie",
            releases = listOf(
                ReleaseDetail(
                    id = 1,
                    releaseTitle = "Inception (2010) 2160p UHD BluRay x265 HDR10+ Dolby Vision " +
                        "[Hindi English] AC3 Subtitles",
                    size = "15.2GB",
                ),
                ReleaseDetail(
                    id = 2,
                    releaseTitle = "Inception (2010) 1080p WEB-DL AAC [English]",
                    quality = "1080p",
                    size = "4.1GB",
                ),
            ),
        ).workPrints
        assertTrue("the label grammar produced no print, so the badge strip would be blank", prints.isNotEmpty())

        shot {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                prints.forEach { print ->
                    Text(print.rawTitle.orEmpty(), style = MaterialTheme.typography.titleSmall)
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PrintQualityBadge(print = print)
                        HdrBadge(print = print)
                        PrintSourceBadge(print = print)
                        AudioBadge(print = print)
                        SubtitleBadge(print = print)
                    }
                }
            }
        }
        capture("04_quality_badges")
    }

    // ── VIP upgrade ──────────────────────────────────────────────────────────

    private fun vipState(
        till: String = "",
        mobile: String = "",
        easyPaisa: String = "",
        raast: String = "",
    ) = VipUiState(
        isLoading = false,
        currency = "PKR",
        // What a server that HAS published prices looks like on screen. These are render input, not
        // defaults: this client ships no figure of its own, and the price of record belongs to the
        // operator — see VipViewModel.NO_PRICE_PUBLISHED and docs/MONETIZATION_AND_FLEET_PLAN.md.
        monthlyPkr = 250,
        yearlyPkr = 1800,
        jazzCashTillId = till,
        jazzCashMobileAccount = mobile,
        easyPaisaMobile = easyPaisa,
        raastInstantId = raast,
        selectedPlan = VipPlan.MONTHLY,
        selectedMethod = VipMethod.JAZZCASH,
    )

    @Test
    fun vipPaymentInstructions() {
        val state = vipState(
            till = "7019882",
            mobile = "0300-1234567",
            easyPaisa = "0345-7654321",
            raast = "raddstream@hbl",
        )
        shot {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PricingSection(state = state) {}
                PaymentSection(state = state) { _, _ -> }
                MethodSection(state = state) {}
            }
        }
        assertTrue(
            "a published JazzCash till never reached the screen",
            compose.onAllNodes(hasText("7019882", substring = false)).fetchSemanticsNodes().isNotEmpty(),
        )
        capture("05_vip_payment_instructions")
    }

    @Test
    fun vipPaymentNotConfigured() {
        val state = vipState()
        shot {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                PricingSection(state = state) {}
                PaymentSection(state = state) { _, _ -> }
            }
        }
        capture("06_vip_payment_unconfigured")
    }

    // ── F25 (Batch B): chip states + hero pills ──────────────────────────────
    //
    // CI does not catch visual regressions — the chip colour, the placeholder
    // tile and the "Unknown" pill all regressed silently. These two captures
    // pin the states a screenshot review can actually see: a selected chip
    // beside an unselected one, and a hero slide whose language pills hide
    // the server's "Unknown" (F2). The placeholder tile itself is already
    // captured by posterCards above (the noArtwork tile, posterUrl = null).

    @Test
    fun filterChipStates() {
        shot {
            Column(
                Modifier.fillMaxWidth().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Filter chips", style = MaterialTheme.typography.titleLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // P2-chip2027: rendered through RaddChip, so the
                    // capture pins the 8dp shape and the selected check.
                    RaddChip(
                        selected = true,
                        onClick = {},
                        label = { Text("Hindi") },
                        colors = raddFilterChipColors(),
                    )
                    RaddChip(
                        selected = false,
                        onClick = {},
                        label = { Text("English") },
                        colors = raddFilterChipColors(),
                    )
                }
            }
        }
        // The selected chip carries the selected state and the unselected one
        // does not — the colour difference alone would not fail a test, and
        // a chip that looks selected but is not (or the reverse) is the
        // regression these pixels exist to catch.
        compose.onNode(hasText("Hindi")).assertIsSelected()
        compose.onNode(hasText("English")).assertIsNotSelected()
        capture("07_filter_chip_states")
    }

    @Test
    fun heroIsCleanArtworkWithDots() {
        val post = Post(
            id = 104,
            title = "Jawan",
            cleanTitle = "Jawan",
            year = 2023,
            contentType = "movie",
            posterUrl = "https://image.tmdb.org/t/p/w500/abc123.jpg",
            genres = "Action",
            language = "Hindi + English + Unknown",
        )
        val second = post.copy(id = 105, title = "Pathaan", cleanTitle = "Pathaan")
        shot {
            Box(Modifier.fillMaxWidth()) {
                HeroCarousel(
                    posts = listOf(post, second),
                    onPostClick = {},
                    autoplay = false,
                )
            }
        }
        // Clean hero (user direction, 21:51 PKT 27 Nov 2026): the hero is
        // artwork + a dot indicator, nothing else — no title text, no meta
        // line, no buttons. The poster URL never loads under Robolectric
        // (no network), but the words would render regardless of the load
        // state, so their absence here is the assertion, not the network.
        compose.onNodeWithText("Jawan").assertDoesNotExist()
        compose.onNodeWithText("2023 · Action").assertDoesNotExist()
        compose.onNodeWithText("Watch now").assertDoesNotExist()
        compose.onNodeWithText("My list").assertDoesNotExist()
        // The dots are the hero's only visible chrome: two slides, so the
        // indicator renders, and the first dot names its slide.
        compose.onNodeWithContentDescription("Slide 1 of 2, Jawan").assertExists()
        // F2: the server's "Unknown" is a missing value, not a language — it
        // must never reach a visible node, while the real languages do (in
        // the slide's spoken sentence).
        assertTrue(
            "the hero still paints an \"Unknown\" pill",
            compose.onAllNodes(hasText("Unknown")).fetchSemanticsNodes().isEmpty(),
        )
        compose.onNode(hasContentDescription("languages Hindi, English", substring = true)).assertExists()
        capture("08_hero_clean")
    }

    // ── A4-F1: image fallback never blank ────────────────────────────────────
    //
    // The Robo captures showed Fan Letter / Kesariya@100 plates uniform
    // #20282A — the loading shimmer's base with the band off-frame — and
    // the Aaj Tak HD logo plate empty. A failing URL must still paint the
    // branded tile (gradient + mark + title / monogram), never a flat box.
    // The bar is 52 distinct colours per card region: a flat void plus two
    // anti-aliased text lines counts well under it, the fallback counts
    // well over it, so the number — not an eyeball — is the regression
    // tripwire. The URLs never load under Robolectric (no network), so by
    // idle time both cards sit in their error fallback.

    @Test
    fun imageFallbackNeverBlank() {
        compose.mainClock.autoAdvance = false
        val failingPoster = Post(
            id = 201,
            title = "One Piece Fan Letter",
            cleanTitle = "One Piece Fan Letter",
            year = 2024,
            contentType = "movie",
            posterUrl = "https://image.tmdb.org/t/p/w500/deadbeef.jpg",
        )
        val failingChannel = LiveTvChannel(
            id = 1,
            name = "Aaj Tak HD",
            category = "News",
            country = "India",
            language = "Hindi",
            logoUrl = "https://example.invalid/logo.png",
            streamId = "deadbeef",
        )
        shot {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                PosterCard(post = failingPoster, modifier = Modifier.width(180.dp).testTag("failingPoster"))
                LiveTvChannelCard(channel = failingChannel, onClick = {}, modifier = Modifier.width(180.dp).testTag("failingChannel"))
            }
        }
        val bitmap = capture("09_image_fallback_never_blank")
        val posterColours = bitmap.distinctColourCount(
            compose.onNodeWithTag("failingPoster").fetchSemanticsNode().boundsInRoot,
        )
        assertTrue(
            "failing poster card rendered (near-)blank: $posterColours distinct colours in its region",
            posterColours >= FALLBACK_MIN_DISTINCT_COLOURS,
        )
        val channelColours = bitmap.distinctColourCount(
            compose.onNodeWithTag("failingChannel").fetchSemanticsNode().boundsInRoot,
        )
        assertTrue(
            "failing channel card rendered (near-)blank: $channelColours distinct colours in its region",
            channelColours >= FALLBACK_MIN_DISTINCT_COLOURS,
        )
    }
}
