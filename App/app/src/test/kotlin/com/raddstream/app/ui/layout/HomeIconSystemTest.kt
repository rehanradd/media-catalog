package com.raddstream.app.ui.layout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * HomeIconSystemTest — the S8 gate for the 2027 Home + Shared fix.
 *
 * Plain JUnit4, no Robolectric: like [CardGeometryTest] it reads the
 * sources from disk, so it runs anywhere a JDK + junit4 exist.
 *
 * Rule: the Home scope paints no `Icons.Default.*` glyph. Every icon is a
 * house drawable (24dp viewport, stroke 2, round caps — or a Material
 * filled path for the filled variants), loaded with `painterResource` or
 * `ImageVector.vectorResource`, so the icon system is one style, not a
 * mix of Material glyphs and house vectors. The test also proves every
 * `R.drawable.ic_*` the scope references exists on disk — a renamed
 * drawable fails here, not at runtime.
 *
 * See `App/docs/ICON_SYSTEM.md` for the inventory and the rules.
 */
class HomeIconSystemTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "App/app/src/main").isDirectory) return@lazy dir
            dir = dir.parentFile
        }
        error("App/app/src/main not found above ${File("").absolutePath}")
    }

    private val scopeFiles = listOf(
        "App/app/src/main/kotlin/com/raddstream/app/ui/screens/HomeScreen.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/components/HeroCarousel.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/components/PosterCard.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/components/ScrollingChipRow.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/navigation/NavGraph.kt",
        "App/app/src/main/kotlin/com/raddstream/app/ui/components/CommonUi.kt",
    )

    /** The 12 drawables this run adds (S8). */
    private val newDrawables = listOf(
        "ic_action_close",
        "ic_action_refresh",
        "ic_action_info",
        "ic_action_history",
        "ic_action_lock",
        "ic_action_cloud_off",
        "ic_action_update",
        "ic_action_favorite_filled",
        "ic_tab_home_filled",
        "ic_tab_search_filled",
        "ic_tab_settings_filled",
        "ic_tab_live_tv_filled",
    )

    private fun String.withoutComments(): String =
        Regex("""/\*[\s\S]*?\*/""").replace(Regex("""//[^\n]*""").replace(this, ""), "")

    @Test
    fun homeScopeUsesNoMaterialIcons() {
        val offenders = mutableListOf<String>()
        for (path in scopeFiles) {
            val file = File(repoRoot, path)
            assertTrue("scope file missing: $path", file.isFile)
            val hits = Regex("""Icons\.Default\.[A-Za-z]+""")
                .findAll(file.readText().withoutComments())
                .map { it.value }
                .toSet()
            if (hits.isNotEmpty()) offenders += "$path: ${hits.joinToString()}"
        }
        assertEquals(
            "a Home-scope file still paints a Material icon — use a house drawable instead (see App/docs/ICON_SYSTEM.md)",
            emptyList<String>(),
            offenders,
        )
    }

    @Test
    fun everyReferencedIconDrawableExists() {
        val drawableDir = File(repoRoot, "App/app/src/main/res/drawable")
        assertTrue("drawable dir missing", drawableDir.isDirectory)
        val referenced = mutableSetOf<String>()
        for (path in scopeFiles) {
            val file = File(repoRoot, path)
            assertTrue("scope file missing: $path", file.isFile)
            Regex("""R\.drawable\.(ic_[A-Za-z0-9_]+)""")
                .findAll(file.readText().withoutComments())
                .forEach { referenced += it.groupValues[1] }
        }
        val missing = (referenced + newDrawables)
            .filter { !File(drawableDir, "$it.xml").isFile }
            .sorted()
        assertEquals(
            "a Home-scope icon drawable is referenced but missing from res/drawable",
            emptyList<String>(),
            missing,
        )
    }
}
