package com.raddstream.app.tooling

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A skeleton that is not the size of the thing it stands in for is a page
 * that jumps when the data lands — and the numbers drift apart silently,
 * because the skeleton and the real layout live in different files. These
 * are text scans, like [CatalogGridRuleTest]: they read the two numbers
 * from the sources and refuse to let them disagree.
 *
 * - Detail Play: the skeleton's hero button must be the same 48dp min the
 *   real [PrimaryActions] Play button carries (`heightIn(min = 48.dp)`).
 *   The old skeleton drew 44dp — a 4dp jump on every detail load.
 * - Hero title: gone with the clean hero (user direction, 21:51 PKT 27 Nov
 *   2026) — the hero paints no title, so its skeleton carries no title
 *   bar to parity-check.
 */
class SkeletonParityTest {

    private fun mainSource(relativePath: String): String =
        File("src/main/kotlin/$relativePath").readText()

    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("must find $signature", start >= 0)
        // The next top-level composable starts the following block; the
        // bodies here are short, so 10_000 chars is ample. (Round 6: the
        // hero title's lineHeight sits ~8.4k chars into HeroSlide, past
        // the old 8_000 window, once the shared-element params land —
        // the window must cover the whole slide body, not just its head.)
        return source.substring(start, minOf(source.length, start + 10_000))
    }

    @Test
    fun `detail skeleton Play button is the same height as the real Play button`() {
        val detail = mainSource("com/raddstream/app/ui/screens/DetailScreen.kt")
        val primaryActions = bodyOf(detail, "private fun PrimaryActions(")
        val realHeight = Regex("""heightIn\(min = (\d+)\.dp\)""")
            .find(primaryActions)?.groupValues?.get(1)
        assertEquals("the real Play button must carry its 48dp min", "48", realHeight)

        val commonUi = mainSource("com/raddstream/app/ui/components/CommonUi.kt")
        val skeleton = bodyOf(commonUi, "fun DetailSkeleton(")
        val skeletonHeight = Regex("""\.height\((\d+)\.dp\)\.testTag\("detailPlaySkeleton"\)""")
            .find(skeleton)?.groupValues?.get(1)
        assertEquals(
            "the skeleton Play bar must be the real Play button's height",
            realHeight,
            skeletonHeight,
        )
    }

    @Test
    fun `detail skeleton sections follow the real page order`() {
        // Detail review (Nov 2026, P1): the skeleton reordered to the
        // redesigned page — synopsis before rungs, the utility row's
        // shape instead of the removed three-button row. P1-8 (Nov
        // 2026): the audio line moved into the Details block after
        // Cast, so its skeleton tag moves with it. The section tags
        // below are the skeleton's own testTags; the order is
        // DetailContent's item order (hero, Play, synopsis, utility
        // row, quality, cast, Details, print summary, similar),
        // minus the lists the skeleton cannot know at load time —
        // episodes/files need movie-vs-series, which only the load
        // itself answers. The quality section is the wrapped
        // QualityRungsSkeleton() call — its tag sits at the call site,
        // not inside the helper (the helper is defined after
        // DetailSkeleton in the file, so its inner tag would read after
        // "similar" in a raw scan).
        val commonUi = mainSource("com/raddstream/app/ui/components/CommonUi.kt")
        val skeleton = bodyOf(commonUi, "fun DetailSkeleton(")
        val order = listOf(
            "detailSkeletonHero",
            "detailPlaySkeleton",
            "detailSkeletonSynopsis",
            "detailSkeletonUtilityRow",
            "detailSkeletonQuality",
            "detailSkeletonCast",
            "detailSkeletonAudioLine",
            "detailSkeletonPrintSummary",
            "detailSkeletonSimilar",
        )
        val positions = order.map { tag ->
            val index = skeleton.indexOf("\"$tag\"")
            assertTrue("DetailSkeleton must carry the $tag section", index >= 0)
            index
        }
        assertEquals(
            "DetailSkeleton sections must read in page order",
            positions.sorted(),
            positions,
        )
    }
}
