package com.raddstream.app.data

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.sessions.FirebaseSessions

/**
 * Applies the viewer's analytics-consent answer to Firebase collection.
 *
 * WHY THIS EXISTS (H-2, 2026-11-24): the first-run dialog promises "nothing
 * is counted, nothing is sent" on a decline, but collection was never gated
 * by that answer — Firebase auto-initialises from `google-services.json` and
 * collects from the first launch. The gate has two halves, and neither works
 * without the other:
 * - the manifest defaults collection OFF (`firebase_analytics_collection_enabled`,
 *   `firebase_crashlytics_collection_enabled` and `firebase_sessions_collection_enabled`,
 *   all `false`), so nothing is collected before the app has read the answer —
 *   including on a first launch, and including the Crashlytics instance
 *   [CrashReporting.install] forces into existence;
 * - [apply] turns collection on only for a viewer who said yes, and off again
 *   the moment the answer changes. It is called from [Telemetry.refresh], which
 *   runs at startup and again right after the consent dialog or the Settings
 *   switch is answered — so the Firebase switches follow the same answer at the
 *   same moment the local snapshot does, without a restart.
 *
 * Sessions rides the same answer: its data is collection too, and a declined
 * viewer sends none of it. There is no other Firebase collection path in the
 * app — no `logEvent`, no `recordException`, no `setUserId`, no Performance
 * or Messaging dependency — so these three switches are the whole gate.
 *
 * Never throws: collection is observability, not a launch dependency. A
 * missing Firebase config (no `google-services.json` in a local build) just
 * means nothing is collected, and a consent change still lands in the local
 * snapshot even if Firebase cannot be reached.
 *
 * The [applier] seam exists so a test can record the calls without Firebase:
 * [Telemetry.refresh] is the only production caller, and it goes through
 * [apply]. Tests that swap it must restore it (see FirebaseConsentTest).
 */
object FirebaseConsent {

    /**
     * The real application. Internal so tests can stand in a recorder; the
     * production value is the three Firebase switches, each guarded on its
     * own so one SDK failing cannot block the other two.
     */
    @Volatile
    internal var applier: (Context, Boolean) -> Unit = { context, consented ->
        runCatching {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(consented)
        }
        runCatching {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(consented)
        }
        runCatching {
            FirebaseSessions.getInstance().isSessionCollectionEnabled = consented
        }
    }

    /**
     * Applies [consented] to every Firebase collection switch. Safe to call
     * before Firebase has initialised (the manifest default holds until then)
     * and safe to call twice with the same answer. Never throws.
     */
    fun apply(context: Context, consented: Boolean) {
        runCatching { applier(context, consented) }
    }
}
