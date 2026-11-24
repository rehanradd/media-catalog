package com.raddstream.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.raddstream.app.brand.Brand
import com.raddstream.app.data.CrashReporting
import com.raddstream.app.data.DeviceIdentity
import com.raddstream.app.data.PlaybackBeacons
import com.raddstream.app.data.RemoteConfigWatcher
import com.raddstream.app.data.ServerSettingsRepository
import com.raddstream.app.data.Telemetry
import com.raddstream.app.data.cache.CacheEngineRunner
import com.raddstream.app.data.cache.CacheSweepWorker
import com.raddstream.app.data.cache.KeepTtlHolder
import com.raddstream.app.data.follow.FollowSeriesWorker
import com.raddstream.app.work.CatalogIndexSyncWorker
import com.raddstream.app.work.CatalogWarmWorker
import com.raddstream.app.data.downloads.DeviceDownloadsRepository
import com.raddstream.app.diagnostics.Diagnostics
import com.raddstream.app.diagnostics.DiagnosticsBus
import com.raddstream.app.images.ImageSizingHolder
import com.raddstream.app.share.LanShareRunner
import com.raddstream.app.sync.SyncAccountRepository
import dagger.hilt.android.HiltAndroidApp
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Application class — Hilt entry point.
 * Referenced in AndroidManifest.xml as android:name=".RaddStreamApplication"
 */
@HiltAndroidApp
class RaddStreamApplication : Application(), Configuration.Provider, SingletonImageLoader.Factory {

    @Inject
    lateinit var cacheEngineRunner: CacheEngineRunner

    @Inject
    lateinit var lanShareRunner: LanShareRunner

    @Inject
    lateinit var keepTtlHolder: KeepTtlHolder

    @Inject
    lateinit var imageSizingHolder: ImageSizingHolder

    @Inject
    lateinit var syncAccountRepository: SyncAccountRepository

    @Inject
    lateinit var settingsRepository: ServerSettingsRepository

    @Inject
    lateinit var remoteConfigWatcher: RemoteConfigWatcher

    @Inject
    lateinit var deviceDownloadsRepository: DeviceDownloadsRepository

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    /**
     * Feel-instant S9 — Coil tuning: the Hilt-provided singleton image
     * loader ([com.raddstream.app.di.ImageLoaderModule]). Coil's singleton
     * is whatever this factory returns, so [newImageLoader] is what makes
     * every `SubcomposeAsyncImage` and the S2 prefetch's
     * `context.imageLoader` actually use the tuned caches instead of
     * Coil's defaults — a `@Provides @Singleton` alone would have left a
     * second, untuned loader serving every poster. Hilt injects this field
     * in `onCreate` before `super.onCreate()` returns, and Coil only asks
     * for the loader lazily on the first image load, so the field is set
     * long before the factory is ever called.
     */
    @Inject
    lateinit var imageLoader: ImageLoader

    override fun newImageLoader(context: PlatformContext): ImageLoader = imageLoader

    /**
     * Feel-instant S7 — Startup diet: the scope the fallback timer below
     * runs on. Deliberately separate from the ad-hoc scope `Telemetry.refresh`
     * uses: the fallback is process-lifetime work, not one launch's refresh.
     */
    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * Feel-instant S7 — Startup diet: the half of `onCreate` that may wait
     * until after the first frame. The lambdas read the injected fields at
     * call time, not at construction time, so building this list here is
     * safe even though the fields are `lateinit` until Hilt injects them
     * (injection happens before `onCreate` runs).
     *
     * What waits, and why it may:
     * - `cacheEngineRunner`: publishes the offline engine and pumps the
     *   scheduler; its first tick, first sweep and playhead collection all
     *   run on its own scope, so nothing here blocks the frame — but the
     *   work itself competes for CPU and disk while the frame composes.
     * - `lanShareRunner`: binds the LAN socket once the opt-in reads on;
     *   the bind and the catalog refresh happen right after it starts.
     *   It reads its preference, never the UI, so this is the only place
     *   the share server is ever constructed — and a fresh install never
     *   exposes a share, because the opt-in is off until the viewer turns
     *   it on.
     * - `startEntitlementWatch` + `ensureDeviceTrialAtLaunch`: the
     *   membership read — a network round-trip on a signed-in device, and
     *   a trial mint on a fresh one. Nothing on the first frame reads the
     *   membership, so it can wait for the frame.
     * - `autoResumeInterrupted`: reads the download ledger from disk on the
     *   calling thread and may start the download service — synchronous
     *   disk I/O in `onCreate` before this slice, and the one item on this
     *   list the spec did not name (found by audit; same class of problem).
     *
     * What stays on the critical path, and why: `Brand.load` + `CrashReporting`
     * (the frame cannot paint without the brand, and a crash before the
     * handler installs is a crash nobody counts); `Telemetry` (already
     * backgrounded — its refresh reads DataStore on a background scope);
     * `keepTtlHolder` + `imageSizingHolder` (the first frame reads the
     * values they seed — the TTL the sweep obeys, the poster size every
     * `AsyncImage` asks for); `remoteConfigWatcher` (maintenance mode must
     * cover the whole app from the first paint, and its first refresh is a
     * background network call, not main-thread work); the WorkManager
     * enqueues (cheap registrations, no work runs inline).
     */
    private val deferredStartup = DeferredStartup(
        listOf(
            { cacheEngineRunner.start() },
            { lanShareRunner.start() },
            { syncAccountRepository.startEntitlementWatch() },
            { syncAccountRepository.ensureDeviceTrialAtLaunch { DeviceIdentity.trialDeviceId(this) } },
            { deviceDownloadsRepository.autoResumeInterrupted() },
        ),
    )

    /**
     * Feel-instant S7: starts the deferred half of `onCreate` — see
     * [deferredStartup]. Called by `MainActivity` once its first frame is
     * drawn, and by the fallback timer at the end of [onCreate] when no
     * frame ever arrives. Exactly-once either way: the second call is a
     * no-op, so the two triggers can race freely.
     */
    fun startDeferredComponents() {
        deferredStartup.start()
    }

    // The manifest removes WorkManager's default initializer, so this is the
    // only factory that can build a worker — and the only one that can build
    // a @HiltWorker at all.
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        super.onCreate()
        // APP-13 (review B-2): install the debug implementation into the
        // process-wide bus first, before anything can record. The
        // implementation lives in the debug source set, which src/main
        // cannot name (it is not compiled into release), so it is loaded
        // reflectively — and only inside BuildConfig.DEBUG, so release
        // keeps the NoOp and never performs the lookup. Any failure
        // leaves the safe NoOp in place. Until this runs, the bus holds
        // the NoOp, so an early record call is a no-op, never a crash.
        if (BuildConfig.DEBUG) {
            runCatching {
                val debugClass =
                    Class.forName("com.raddstream.app.diagnostics.debug.DebugDiagnostics")
                val instance = debugClass.getField("INSTANCE").get(null) as Diagnostics
                DiagnosticsBus.install(instance)
            }
        }
        // Swap in the runtime brand snapshot before any screen composes. Until this runs,
        // every Brand getter falls back to its compiled BuildConfig constant, so this call
        // can only make the brand *more* current, never blank it.
        Brand.load(this)
        // Crashlytics first, Telemetry second: CrashReporting installs
        // Firebase's uncaught-exception handler, and Telemetry's handler
        // (below) captures it as its `previous` and chains to it — a crash
        // is counted locally AND reported to Crashlytics. See CrashReporting.
        CrashReporting.install(this)
        // Tracking A: the counters exist from the first lines of the process,
        // and the crash handler with them — a crash before this point is a
        // crash before the app did anything worth counting. The snapshot
        // refresh reads the viewer's answers from DataStore on a background
        // scope, so a slow store never holds up the first frame; a store
        // that throws simply leaves the snapshot at "not consented".
        Telemetry.init(this)
        Telemetry.installCrashHandler()
        PlaybackBeacons.init(this)
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { Telemetry.refresh(settingsRepository) }
        }
        // Seed the retention window before anything can read it: the sweep's first pass is seconds
        // away, and until this collector's first emission the answer is the 3-hour default rather
        // than the viewer's saved choice. Started here — not lazily by the sweep — because a
        // preference a background component must obey is collected once, at the top, exactly like
        // the LAN share opt-in below.
        keepTtlHolder.start()
        // Seed the image-sizing decision before the first poster composes. The Settings "Image
        // quality" row and the mobile-data switch both write DataStore that nothing else reads; this
        // holder is that reader, so the collectors here are the wire between those two controls and
        // every AsyncImage. Until the first emission the holder answers its own AUTO / Wi-Fi default
        // (640px, nothing blocked), so a cold install renders exactly what it did before this existed.
        imageSizingHolder.start()
        // Feel-instant S7: the deferred half of this method — cache engine,
        // LAN share, membership read, download resume — no longer starts
        // here. `MainActivity` starts it once its first frame is drawn (see
        // `startDeferredComponents`); the fallback timer at the end of this
        // method covers a process that never draws a frame.
        // The operator's switches are process state, not a screen's private fetch: this is the
        // only reader of `/remote-config` that runs before any UI, and it is what makes
        // maintenance mode cover the whole app instead of only a screen someone opened.
        remoteConfigWatcher.start()
        // Feel-instant S7: the download resume moved with the rest of the
        // deferred half — see `startDeferredComponents`. It used to run here,
        // reading the download ledger from disk on the calling thread.
        // The closed-app sweep: the same TTL auto-delete + low-storage eviction
        // the in-process runner runs every 5 minutes, once a day while the app
        // is closed. KEEP means a reinstall of this line never duplicates the
        // work; a failure never retries, the next day or the next launch
        // covers it.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CacheSweepWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<CacheSweepWorker>(1, TimeUnit.DAYS).build(),
        )
        // E2 slice 2: the daily new-episode check for followed series.
        // KEEP means a reinstall of this line never duplicates the work;
        // a failure never retries, the next day covers it. Battery-not-low
        // is the only constraint — Wi-Fi is deliberately not required.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            FollowSeriesWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            FollowSeriesWorker.request(),
        )
        // Feel-instant S8: the overnight catalog warm + index sync — the
        // client-side twin of the server's 21:00 top-100 pre-warm. KEEP
        // means a reinstall of these lines never duplicates the work; a
        // failure never retries, the next day covers it. Both run in the
        // overnight window (Wi-Fi + charging + idle, never on a low
        // battery), ≤ 1/day.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CatalogWarmWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            CatalogWarmWorker.request(),
        )
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            CatalogIndexSyncWorker.UNIQUE_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            CatalogIndexSyncWorker.request(),
        )
        // Feel-instant S7 fallback: a process that never draws a frame —
        // started for `PlaybackService`, a worker, or an activity that dies
        // before its first paint — still gets the deferred half, a few
        // seconds in. Five seconds is long enough that any device drawing
        // its first frame in the normal window wins this race, and short
        // enough that a service-only process is never left without its
        // cache engine, LAN socket, or membership read for long. A player
        // that starts before the engine is published is safe either way:
        // every `CacheEngineHolder.engine` reader null-checks it and plays
        // without the cache affordance until the engine lands.
        applicationScope.launch {
            delay(DEFERRED_STARTUP_FALLBACK_DELAY_MILLIS)
            startDeferredComponents()
        }
    }

    companion object {
        /**
         * Feel-instant S7: the fallback window — see the timer at the end of
         * [onCreate]. Long enough for a slow device's first frame to win the
         * race, short enough for a service-only process to come up promptly.
         */
        private const val DEFERRED_STARTUP_FALLBACK_DELAY_MILLIS = 5_000L
    }
}
