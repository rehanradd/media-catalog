package com.raddstream.app.data

import android.content.Context
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

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
 * Sessions is gated by the manifest default only: its runtime toggle
 * (`FirebaseSessions.getInstance().isSessionCollectionEnabled`) is internal
 * to the firebase-sessions library and not callable from app code — Android
 * CI 45433573772 proved it (`Cannot access 'class FirebaseSessions': it is
 * internal in file`). So a consenting viewer does not re-enable Sessions;
 * it stays off, which is more privacy than the dialog promises, not less.
 * A declined viewer sends none of it either way. There is no other Firebase
 * collection path in the app — no `logEvent`, no `recordException`, no
 * `setUserId`, no Performance or Messaging dependency — so these two runtime
 * switches plus the manifest default are the whole gate.
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
     * production value is the two Firebase switches, each guarded on its
     * own so one SDK failing cannot block the other. Sessions has no runtime
     * switch here — its toggle is internal to the SDK, so the manifest
     * default-off is its whole gate (see the class KDoc).
     */
    @Volatile
    internal var applier: (Context, Boolean) -> Unit = { context, consented ->
        runCatching {
            FirebaseAnalytics.getInstance(context).setAnalyticsCollectionEnabled(consented)
        }
        runCatching {
            FirebaseCrashlytics.getInstance().setCrashlyticsCollectionEnabled(consented)
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
