package com.raddstream.app.di

import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import coil.request.CachePolicy
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Feel-instant S9 — Coil tuning: the image loader's caches, made explicit.
 *
 * Until this slice the app ran Coil 2's defaults, which happened to be
 * close to the spec's numbers — memory 25% of the heap, disk 2% of storage
 * clamped to [10 MB, 250 MB] — but nothing pinned them, so a Coil upgrade
 * or a different device's storage size could quietly move either. The
 * numbers below are the same ones the spec asked for, now deliberate:
 *
 * - memory: 25% of the app's available memory ([IMAGE_MEMORY_CACHE_PERCENT]).
 * - disk: a flat 250 MB cap ([IMAGE_DISK_CACHE_MAX_BYTES]), not 2% of
 *   whatever storage the device happens to have free — a phone with a
 *   nearly-full 128 GB disk and one with a fresh 512 GB disk now keep the
 *   same poster budget.
 * - disk reads/writes are explicitly enabled ([CachePolicy.ENABLED]) — the
 *   default today, pinned so a request-level override somewhere cannot
 *   silently turn the disk cache off for the whole app.
 *
 * The disk directory is Coil 2's own default (`image_cache` under the app
 * cache dir), named explicitly so the posters already on disk stay cached
 * across this change — a different directory would have wiped the cache
 * and re-fetched every poster on the next cold start.
 *
 * What this does NOT change, deliberately:
 * - The network client stays Coil's own default. The S1-warmed OkHttp
 *   cache belongs to the API client; the image disk cache is Coil's, and
 *   merging the two is a different slice's decision.
 * - Per-tile sizing stays where it already lives: [com.raddstream.app.images.PosterUrlPolicy]
 *   rewrites TMDB `/original/` to the smallest covering width, and
 *   [com.raddstream.app.ui.components.CatalogImage] + the S2 prefetch pass
 *   an explicit `.size(width, width)` on every request — the spec's
 *   "size() resolvers" already exist, verified, not re-added.
 * - No Coil 3 migration: everything here exists in the pinned 2.6.0.
 */
@Module
@InstallIn(SingletonComponent::class)
object ImageLoaderModule {

    /** Memory cache: 25% of the app's available memory. */
    const val IMAGE_MEMORY_CACHE_PERCENT = 0.25

    /** Disk cache: a flat 250 MB cap, independent of free storage. */
    const val IMAGE_DISK_CACHE_MAX_BYTES = 250L * 1024 * 1024

    /** Coil 2's default disk-cache directory, named so it survives this change. */
    const val IMAGE_DISK_CACHE_DIR_NAME = "image_cache"

    @Provides
    @Singleton
    fun provideImageLoader(@ApplicationContext context: Context): ImageLoader =
        buildImageLoader(context)

    /**
     * The loader itself, as a plain function so a test can build one
     * without Hilt — the numbers it pins are the module's, and the test
     * should assert on them, not on the DI wiring alone.
     */
    fun buildImageLoader(context: Context): ImageLoader =
        ImageLoader.Builder(context)
            .memoryCache {
                MemoryCache.Builder(context)
                    .maxSizePercent(IMAGE_MEMORY_CACHE_PERCENT)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(context.cacheDir.resolve(IMAGE_DISK_CACHE_DIR_NAME))
                    .maxSizeBytes(IMAGE_DISK_CACHE_MAX_BYTES)
                    .build()
            }
            .diskCachePolicy(CachePolicy.ENABLED)
            .build()
}
