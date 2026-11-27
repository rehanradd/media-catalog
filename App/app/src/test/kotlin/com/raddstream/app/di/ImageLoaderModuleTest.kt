package com.raddstream.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import coil3.annotation.ExperimentalCoilApi
import coil3.imageLoader
import coil3.memory.MemoryCache
import coil3.request.CachePolicy
import com.raddstream.app.RaddStreamApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Feel-instant S9 — Coil tuning: the loader's caches are the spec's
 * numbers, and the app's singleton IS the tuned loader.
 *
 * The numbers are pinned against the module's own constants, not restated
 * literals, so a deliberate retune updates the test by updating the one
 * place the number lives. The memory percent is pinned two ways: the exact
 * value Coil itself computes for the pinned percent, and a relational check
 * against a 10% cache, so the test fails if the percent is ever ignored
 * rather than merely miscomputed. (Coil 3 sizes the memory cache from the
 * app's total available memory, not ActivityManager.memoryClass — the old
 * memoryClass mirror is wrong under Coil 3 and is not used here.)
 *
 * Runs on the JVM under Robolectric: building a loader touches the cache
 * dir and the app's memory info, both of which the shadows back with real
 * host files. No network is involved — nothing here executes a request.
 */
@OptIn(ExperimentalCoilApi::class)
@RunWith(RobolectricTestRunner::class)
class ImageLoaderModuleTest {

    // Deferred getter, not a field initializer: an ApplicationProvider call
    // at class-instantiation time runs before the sandbox is ready (the
    // Session 131 trap — see the dev-cleanup fixup entry in
    // SESSION_HISTORY.md).
    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    @Test
    fun `disk cache is capped at 250 MB in the default directory`() {
        val loader = ImageLoaderModule.buildImageLoader(context)
        val diskCache = loader.diskCache
        assertTrue(diskCache != null)
        assertEquals(ImageLoaderModule.IMAGE_DISK_CACHE_MAX_BYTES, diskCache!!.maxSize)
        assertEquals(ImageLoaderModule.IMAGE_DISK_CACHE_DIR_NAME, diskCache.directory.name)
    }

    @Test
    fun `disk cache policy is enabled so second launch is instant`() {
        // Video audit issue 5 (Nov 2026): the 250 MB disk cache is only
        // worth anything when the loader actually reads it — a disabled
        // policy would re-fetch every poster on every launch.
        val loader = ImageLoaderModule.buildImageLoader(context)
        assertEquals(CachePolicy.ENABLED, loader.diskCachePolicy)
    }

    @Test
    fun `memory cache honours the twenty-five percent cap`() {
        val loader = ImageLoaderModule.buildImageLoader(context)
        val quarter = loader.memoryCache?.maxSize ?: 0
        // Coil 3 sizes the memory cache from the app's total available
        // memory (totalAvailableMemoryBytes), not ActivityManager.memoryClass
        // — so pin the percent, not the byte basis: the module's cache must
        // equal what Coil itself computes for 25%, and a 10% cache must be
        // smaller, so a different percent in the module fails either way.
        val expected = MemoryCache.Builder()
            .maxSizePercent(context, ImageLoaderModule.IMAGE_MEMORY_CACHE_PERCENT)
            .build().maxSize
        assertEquals(expected, quarter)
        val tenth = MemoryCache.Builder().maxSizePercent(context, 0.10).build().maxSize
        assertTrue(quarter > tenth)
    }

    @Test
    fun `the application singleton is the hilt provided loader`() {
        val app = ApplicationProvider.getApplicationContext<RaddStreamApplication>()
        assertSame(app.imageLoader, context.imageLoader)
        assertEquals(
            ImageLoaderModule.IMAGE_DISK_CACHE_MAX_BYTES,
            app.imageLoader.diskCache?.maxSize,
        )
    }
}
