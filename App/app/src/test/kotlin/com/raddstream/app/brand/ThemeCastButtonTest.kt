package com.raddstream.app.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.util.Properties

/**
 * ThemeCastButtonTest — pins the theme precondition that MediaRouteButton needs.
 *
 * Robo v11 crashed the app 3 times on the detail screen's Cast button:
 * `IllegalArgumentException: background can not be translucent: #0` at
 * `ColorUtils.calculateContrast`, reached from
 * `MediaRouterThemeHelper.getControllerColor`, which resolves the AppCompat
 * attribute `colorPrimary` from the activity theme. Theme.RaddStream (parent
 * `android:Theme.Material.NoActionBar`) defined no AppCompat `colorPrimary` /
 * `colorPrimaryDark` / `colorAccent` — the platform parent defines the
 * namespaced `android:colorPrimary`, not the AppCompat one — so the
 * resolution returned 0 (transparent) and the contrast call threw. The crash
 * fires deterministically whenever the Cast button composes with a receiver
 * discoverable, i.e. whenever `DetailViewModel.castAvailable` is true.
 *
 * Plain JUnit4 on the JVM, same style as BrandDriftTest: it parses the three
 * themes.xml files from disk and resolves `@color` references through
 * brand.properties, so it runs with no Robolectric and no Android runtime.
 * It fails when any of the three theme files (values, values-v23, values-v27
 * — each replaces the others wholesale) drops an AppCompat color, or when
 * the resolved value is transparent or unresolvable.
 */
class ThemeCastButtonTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val brandProps: Properties by lazy {
        Properties().apply { FileInputStream(File(repoRoot, "brand.properties")).use { load(it) } }
    }

    private val themeFiles = listOf(
        "App/app/src/main/res/values/themes.xml",
        "App/app/src/main/res/values-v23/themes.xml",
        "App/app/src/main/res/values-v27/themes.xml",
    )

    /** The AppCompat color items MediaRouteButton (and AppCompat widgets generally) resolve. */
    private val requiredItems = listOf("colorPrimary", "colorPrimaryDark", "colorAccent")

    private fun themeText(relativePath: String): String {
        val file = File(repoRoot, relativePath)
        assertTrue("$relativePath must exist", file.isFile)
        return file.readText()
    }

    private fun styleBlock(text: String, relativePath: String): String {
        val styles = Regex("""<style\s+name="Theme\.RaddStream"[^>]*>([\s\S]*?)</style>""")
            .findAll(text).map { it.groupValues[1] }.toList()
        assertEquals("$relativePath must define exactly one Theme.RaddStream style", 1, styles.size)
        return styles.single()
    }

    private fun itemValue(block: String, name: String): String? =
        Regex("""<item\s+name="$name">([^<]+)</item>""").find(block)?.groupValues?.get(1)?.trim()

    /** Resolves `@color/<name>` through brand.properties, or passes a literal hex through. */
    private fun resolveToHex(raw: String, relativePath: String): String {
        if (raw.startsWith("@color/")) {
            val key = "resources.colors." + raw.removePrefix("@color/")
            val hex = brandProps.getProperty(key)?.trim()
            assertTrue("$relativePath: $raw has no $key in brand.properties", !hex.isNullOrBlank())
            return hex!!
        }
        return raw
    }

    private fun assertOpaqueHex(hex: String, label: String) {
        assertTrue(
            "$label resolves to '$hex', which is not #RRGGBB or #AARRGGBB",
            Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$").matches(hex),
        )
        if (hex.length == 9) {
            assertTrue(
                "$label resolves to '$hex' — alpha 00 is transparent, and a transparent " +
                    "colorPrimary is exactly what crashed MediaRouteButton in Robo v11",
                !hex.substring(1, 3).equals("00", ignoreCase = true),
            )
        }
    }

    @Test
    fun everyThemeFileDefinesOpaqueAppCompatColors() {
        themeFiles.forEach { path ->
            val block = styleBlock(themeText(path), path)
            requiredItems.forEach { name ->
                val raw = itemValue(block, name)
                assertTrue("$path: Theme.RaddStream must define $name", !raw.isNullOrBlank())
                val hex = resolveToHex(raw!!, path)
                assertOpaqueHex(hex, "$path: $name ($raw)")
            }
        }
    }

    @Test
    fun versionedThemesRepeatTheBaseColors() {
        // values-v23 and values-v27 replace values/themes.xml wholesale, so a
        // color added only to the base file still leaves API 23+ devices crashing.
        val values = themeFiles.map { path ->
            val block = styleBlock(themeText(path), path)
            path to requiredItems.associateWith { itemValue(block, it) }
        }.toMap()
        val base = values.getValue(themeFiles[0])
        themeFiles.drop(1).forEach { path ->
            assertEquals(
                "$path must repeat the base theme's AppCompat colors (qualified styles " +
                    "replace, never merge)",
                base,
                values.getValue(path),
            )
        }
    }
}
