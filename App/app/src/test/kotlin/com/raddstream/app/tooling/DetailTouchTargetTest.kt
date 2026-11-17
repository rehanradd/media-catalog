package com.raddstream.app.tooling

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Detail review (Nov 2026, P1): every tappable TextButton on the detail
 * page carries the 48dp touch-target floor — the five expanders ("Read
 * more", "and N more", "Show all files" ×2, "Start over") and the
 * report/cancel buttons that already had it. A TextButton's own min
 * height is below the floor, so the floor is a modifier, not a default —
 * and a new TextButton added without it would silently ship short. A
 * text scan, like [SkeletonParityTest]: each TextButton call must carry
 * heightIn(min = 48.dp) within its own argument list.
 */
class DetailTouchTargetTest {

    private fun detailSource(): String =
        File("src/main/kotlin/com/raddstream/app/ui/screens/DetailScreen.kt").readText()

    @Test
    fun `every detail TextButton carries the 48dp floor`() {
        val detail = detailSource()
        val callSites = Regex("""TextButton\(""").findAll(detail).toList()
        assertTrue("DetailScreen must still have its TextButtons", callSites.isNotEmpty())
        val short = callSites.filter { match ->
            // The movie files expander carries a long comment between
            // onClick and its modifier, so the window is generous — but
            // still far shorter than the gap to the next TextButton.
            val args = detail.substring(match.range.first, minOf(detail.length, match.range.first + 600))
            !args.contains("heightIn(min = 48.dp)")
        }
        assertTrue(
            "every TextButton must carry heightIn(min = 48.dp), missing at: " +
                short.joinToString { detail.substring(it.range.first, minOf(detail.length, it.range.first + 80)).replace("\n", " ") },
            short.isEmpty(),
        )
    }

    @Test
    fun `top-bar targets are 48dp circles and packed-banner buttons carry the floor`() {
        val detail = detailSource()
        // The top bar is two 48dp circles, not a heightIn — the size is
        // the floor there, and the 40dp circle it replaces is gone.
        assertTrue("the 40dp top-bar circle must be gone", !detail.contains(".size(40.dp)"))
        // The packed banner's Play/Download buttons are Material 3
        // buttons (40dp own min), so they carry the floor explicitly.
        val bannerStart = detail.indexOf("private fun PackedSeasonBanner(")
        assertTrue("must find PackedSeasonBanner", bannerStart >= 0)
        val banner = detail.substring(bannerStart, minOf(detail.length, bannerStart + 2_000))
        assertEquals(
            "both packed-banner buttons must carry heightIn(min = 48.dp)",
            2,
            Regex("""heightIn\(min = 48\.dp\)""").findAll(banner).count(),
        )
    }
}
