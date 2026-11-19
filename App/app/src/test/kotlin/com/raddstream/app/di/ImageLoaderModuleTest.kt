package com.raddstream.app.di

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import androidx.test.core.app.ApplicationProvider
import coil.annotation.ExperimentalCoilApi
import coil.imageLoader
import coil.memory.MemoryCache
import com.raddstream.app.RaddStreamApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import kotlin.math.roundToInt

/**
 * Feel-instant S9 — Coil tuning: the loader's caches are the spec's
 * numbers, and the app's singleton IS the tuned loader.
 *
 * The numbers are pinned against the module's own constants, not restated
 * literals, so a deliberate retune updates the test by updating the one
 * place the number lives. The memory percent is pinned two ways: the exact
 * byte value Coil's formula gives for the pinned memory class, and a
 * relational check against a 10% cache, so the test fails if the percent
 * is ever ignored rather than merely miscomputed.
 *
 * Runs on the JVM under Robolectric: building a loader touches
 * ActivityManager (memory class) and the cache dir, both of which the
 * shadows back with real host files. No network is involved — nothing here
 * executes a request.
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

    @Before
    fun pinMemoryClass() {
        // Coil sizes the memory cache from ActivityManager: memoryClass, or
        // largeMemoryClass when the manifest sets largeHeap (it does — see
        // AndroidManifest.xml). Pin memoryClass so the percent math below
        // has one known input; the large class is read live in the test,
        // because this Robolectric version's shadow has no setter for it.
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        shadowOf(activityManager).setMemoryClass(256)
    }

    @Test
    fun `disk cache is capped at 250 MB in the default directory`() {
        val loader = ImageLoaderModule.buildImageLoader(context)
        val diskCache = loader.diskCache
        assertTrue(diskCache != null)
        assertEquals(ImageLoaderModule.IMAGE_DISK_CACHE_MAX_BYTES, diskCache!!.maxSize)
        assertEquals(ImageLoaderModule.IMAGE_DISK_CACHE_DIR_NAME, diskCache.directory.name)
    }

    @Test
    fun `memory cache honours the twenty-five percent cap`() {
        val loader = ImageLoaderModule.buildImageLoader(context)
        val quarter = loader.memoryCache?.maxSize ?: 0
        // Mirror Coil's own sizing read — largeMemoryClass when largeHeap
        // is on, else memoryClass — so the test pins the 25% against
        // whatever this device reports, not a hardcoded class: a different
        // percent in the module fails the exact check either way.
        val activityManager =
            context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val isLargeHeap =
            (context.applicationInfo.flags and ApplicationInfo.FLAG_LARGE_HEAP) != 0
        val memoryClassMb =
            if (isLargeHeap) activityManager.largeMemoryClass else activityManager.memoryClass
        assertEquals((0.25 * memoryClassMb * 1024 * 1024).roundToInt(), quarter)
        val tenth = MemoryCache.Builder(context).maxSizePercent(0.10).build().maxSize
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
