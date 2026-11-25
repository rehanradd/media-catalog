package com.raddstream.app.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The Firebase consent gate (H-2, 2026-11-24), pinned in two halves:
 *
 * - the manifest defaults collection OFF, so nothing is collected before the
 *   app has read the viewer's answer — including on a first launch;
 * - [Telemetry.refresh] hands the answer to [FirebaseConsent], so a changed
 *   answer takes effect at the same moment the local snapshot does.
 *
 * The wiring test records the calls through [FirebaseConsent.applier] instead
 * of touching Firebase itself: the real switches are two SDK calls (Sessions
 * rides the manifest default only — its runtime toggle is internal to the
 * SDK), and this test pins that the consent answer reaches them — true then
 * false, in order — not what the SDKs do with it. The applier is restored
 * afterwards; the test ends on a decline, so the shared [Telemetry] snapshot
 * is left at its default (not consented).
 *
 * Prod-leak audit (2026-11-20) pattern: the manifest test reads the file
 * from the repo root the way CleartextFallbackTest reads brand.properties —
 * plain JUnit, no Android runtime — and the wiring test uses the same
 * unroutable-free Robolectric + DataStore shape as
 * ApiProviderSessionFallbackTest. No endpoint is ever called here.
 */
@RunWith(RobolectricTestRunner::class)
class FirebaseConsentTest {

    private val manifestFile: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            val candidate = File(dir, "App/app/src/main/AndroidManifest.xml")
            if (candidate.isFile) return@lazy candidate
            dir = dir.parentFile
        }
        error("AndroidManifest.xml not found above ${File("").absolutePath}")
    }

    @Test
    fun `firebase collection defaults off in the manifest`() {
        val manifest = manifestFile.readText()
        for (name in listOf(
            "firebase_analytics_collection_enabled",
            "firebase_crashlytics_collection_enabled",
            "firebase_sessions_collection_enabled",
        )) {
            assertTrue(
                "the manifest must default $name to false, so nothing is " +
                    "collected before the viewer answers",
                Regex(
                    "<meta-data\\s+android:name=\"$name\"\\s+android:value=\"false\"\\s*/>",
                ).containsMatchIn(manifest),
            )
        }
    }

    @Test
    fun `refresh hands the consent answer to firebase, true then false`() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        Telemetry.init(context)
        val settings = ServerSettingsRepository(context)
        val seen = mutableListOf<Boolean>()
        val previous = FirebaseConsent.applier
        FirebaseConsent.applier = { _, consented -> seen.add(consented) }
        try {
            // Flush first: RaddStreamApplication's onCreate launches its own
            // Telemetry.refresh on a background scope, applying the initial,
            // unanswered consent (false) exactly once — but on its own scope,
            // unsynchronised with this test. Usually it lands during this
            // flush (both refreshes await the same DataStore load); under
            // load it may land after the clear, or between the two refreshes
            // below. The value is the same either way and the apply is
            // idempotent, so the flush only narrows the window — the
            // assertion below is written to tolerate the stray false
            // wherever it lands, rather than assuming the flush caught it.
            Telemetry.refresh(settings)
            seen.clear()
            settings.saveAnalyticsConsent(true)
            Telemetry.refresh(settings)
            settings.saveAnalyticsConsent(false)
            Telemetry.refresh(settings)
        } finally {
            FirebaseConsent.applier = previous
        }
        // The application's background apply (see the flush comment) may add
        // one stray `false` — before the `true`, between the two refreshes,
        // or after them — so the exact list is timing-dependent (run
        // 45527500354 attempt 3: [false, true, false]). What the test pins
        // is what matters: exactly one `true` (the first refresh), a `false`
        // after it (the second refresh), and at most two `false`s in total
        // (the second refresh plus the one background apply).
        assertEquals(
            "exactly one apply of true: the first refresh",
            listOf(true),
            seen.filter { it },
        )
        assertTrue(
            "a false must follow the true: the second refresh",
            seen.lastIndexOf(false) > seen.indexOf(true),
        )
        assertTrue(
            "at most two falses: the second refresh plus the one background apply",
            seen.count { !it } <= 2,
        )
    }
}
