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
 * - Hero title: the skeleton's title bar must be the hero title's own line
 *   height (32dp — the 28sp copy, not displaySmall's 44dp token), not a
 *   guessed number.
 */
class SkeletonParityTest {

    private fun mainSource(relativePath: String): String =
        File("src/main/kotlin/$relativePath").readText()

    private fun bodyOf(source: String, signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("must find $signature", start >= 0)
        // The next top-level composable starts the following block; the
        // skeleton bodies here are short, so 8_000 chars is ample.
        return source.substring(start, minOf(source.length, start + 8_000))
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
    fun `hero skeleton title bar is the hero title line height`() {
        val hero = mainSource("com/raddstream/app/ui/components/HeroCarousel.kt")
        // The real title is displaySmall copied to 28sp/32sp (Gemini home
        // review, Nov 2026) — the skeleton must match the copy, not the token.
        // The sizes ride brandSp (BrandDriftTest bans raw .sp literals).
        val slide = bodyOf(hero, "if (hasArt) {")
        val lineHeight = Regex("""lineHeight = brandSp\((\d+)\)""")
            .find(slide)?.groupValues?.get(1)
        assertEquals("the hero title must carry its 32sp line height", "32", lineHeight)

        val skeleton = bodyOf(hero, "fun HeroSkeleton(")
        val titleHeight = Regex("""\.height\((\d+)\.dp\)\.testTag\("heroTitleSkeleton"\)""")
            .find(skeleton)?.groupValues?.get(1)
        assertEquals(
            "the skeleton title bar must be the hero title's line height",
            lineHeight,
            titleHeight,
        )
    }

    @Test
    fun `detail skeleton sections follow the real page order`() {
        // Detail review (Nov 2026, P1): the skeleton reordered to the
        // redesigned page — synopsis before rungs, the utility row's
        // shape instead of the removed three-button row, the audio line
        // between them. The section tags below are the skeleton's own
        // testTags; the order is DetailContent's item order (hero, Play,
        // synopsis, utility row, audio line, quality, cast, print
        // summary, similar), minus the lists the skeleton cannot know at
        // load time — episodes/files need movie-vs-series, which only the
        // load itself answers. The quality section is the wrapped
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
            "detailSkeletonAudioLine",
            "detailSkeletonQuality",
            "detailSkeletonCast",
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
