package com.raddstream.app.ui.screens

import android.app.Activity
import android.app.AppOpsManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Process
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.raddstream.app.R
import com.raddstream.app.viewmodel.PlayerEvent
import com.raddstream.app.viewmodel.PlayerViewModel
import kotlin.math.roundToInt

/**
 * P5 (player fixes): the reduced-motion crossfade. The player fades ride
 * the M3 Expressive effects springs via RaddMotion; under reduced motion
 * they crossfade in this many milliseconds instead — the same 30ms the
 * token set carries ([RaddMotion.FadeWhenReduced]). One constant, owned
 * here, so the fade duration lives in one place.
 *
 * MaterialTheme.motionScheme is read at the theme level now: the 2026-11-25
 * BOM bump (material3 1.5.0) makes it public and RaddStreamTheme sets
 * motionScheme = MotionScheme.expressive(). This fade constant stays owned
 * here because it is a plain duration, not a spec.
 */
internal const val REDUCED_MOTION_FADE_MS = 30


// ── Event routing ──────────────────────────────────────────────────────────────

/**
 * Routes ViewModel events: messages become the on-screen snackbar via [onMessage] — an
 * undoable message (the player auto-delete of a finished episode) via [onMessageWithUndo],
 * which defaults to the plain route; the external-player event fires a real intent carrying
 * OUR OWN address; next-episode events are delegated to the app (the NavGraph agent wires
 * the PlayBundle-per-episode route — see HANDOFF_appplayer.md).
 */
@Composable
internal fun PlayerEventWatcher(
    vm: PlayerViewModel,
    context: Context,
    onPlayNextEpisode: (workKey: String?, postId: Int?, season: Int, episode: Int) -> Unit,
    onMessage: (String) -> Unit,
    onMessageWithUndo: (String) -> Unit = onMessage,
    /** MX #7 — Kids Lock: the unlock FAB was tapped while kids mode is on. */
    onRequestKidsPin: () -> Unit = {},
    /** REA #1: a post-play preview tile was tapped — the caller opens that title's Detail. */
    onOpenPost: (Int) -> Unit = {},
) {
    LaunchedEffect(vm) {
        vm.events.collect { event ->
            when (event) {
                is PlayerEvent.Message -> onMessage(event.text)
                is PlayerEvent.MessageWithUndo -> onMessageWithUndo(event.text)
                is PlayerEvent.PlayNextEpisode ->
                    onPlayNextEpisode(event.workKey, event.postId, event.season, event.episode)
                is PlayerEvent.OpenExternalPlayer -> openExternally(context, event.url)
                is PlayerEvent.OpenPost -> onOpenPost(event.postId)
                // SC.24: share a moment — the VM built the text (title, time,
                // deep link); the screen only opens the system chooser for it.
                is PlayerEvent.ShareMoment -> shareMoment(context, event.text)
                // MX #7 — Kids Lock: raise the PIN pad.
                is PlayerEvent.RequestKidsPin -> onRequestKidsPin()
            }
        }
    }
}

/**
 * One snackbar message. [showUndo] is only for the player auto-delete
 * of a finished episode: the Undo button re-queues it, and the bar
 * stays up longer so the viewer can reach it.
 */
internal data class PlayerSnackbar(val text: String, val showUndo: Boolean = false)

// SC.24: the share chooser for a moment. No try/catch: the chooser is a system
// activity, so unlike a video player intent it always resolves.
private fun shareMoment(context: Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, context.getString(R.string.player_share_moment)))
}

/** PiP needs Android 8+; older devices get an honest toast. */
@Composable
internal fun rememberPipLauncher(aspectRatio: Float? = null, sourceRect: androidx.compose.ui.geometry.Rect? = null): () -> Unit {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    // Library bump 2026-11-23 (Compose 1.9 lint LocalContextGetResourceValueCall):
    // the toasts fire inside the returned lambda, where stringResource()
    // cannot run — resolve them here, at composition time.
    val pipRefused = stringResource(R.string.player_pip_refused)
    val pipUnsupported = stringResource(R.string.player_pip_unsupported)
    return {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            try {
                // PL-10: the PiP window follows the video's own shape, and the
                // source rect is the surface's own bounds — the window grows
                // out of the picture, not out of a corner.
                val params = android.app.PictureInPictureParams.Builder().apply {
                    if (aspectRatio != null && aspectRatio > 0f) {
                        val width = 1000
                        val height = (1000f / aspectRatio).roundToInt().coerceAtLeast(1)
                        setAspectRatio(android.util.Rational(width, height))
                    }
                    sourceRect?.let {
                        setSourceRectHint(
                            android.graphics.Rect(
                                it.left.roundToInt(),
                                it.top.roundToInt(),
                                it.right.roundToInt(),
                                it.bottom.roundToInt(),
                            ),
                        )
                    }
                }.build()
                // F-05: a false return (PiP disallowed for this activity,
                // or the auto-PiP call racing ON_PAUSE) used to be
                // swallowed — the viewer tapped PiP and nothing happened
                // with no message. The refusal toast is the same one the
                // exception path already shows.
                if (!activity.enterPictureInPictureMode(params)) {
                    Toast.makeText(context, pipRefused, Toast.LENGTH_SHORT).show()
                }
            } catch (t: Throwable) {
                // Dev-cleanup (Nov 2026, E2): the toast is one fixed line — the
                // exception's own words are logcat-only, never viewer copy.
                // Single-token log message: PlayerChromeCopyTest forbids prose
                // literals in this file, and a log line is not viewer copy.
                Log.w("PlayerScreen", "enterPictureInPictureMode_failed", t)
                Toast.makeText(context, pipRefused, Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(context, pipUnsupported, Toast.LENGTH_SHORT).show()
        }
    }
}

/**
 * P2-5 (B-23): whether the SYSTEM lets this app enter PiP right now — the
 * per-app picture-in-picture toggle in the system settings, plus the
 * device actually having PiP. This is the setting auto-PiP respects: a
 * viewer who turned PiP off for Radd Stream never gets a window they did
 * not ask for, and neither does a device without the feature. Below
 * Android 8 there is no PiP at all. The AppOps mode is DEFAULT when the
 * viewer never touched the toggle — the system's default is allowed — and
 * IGNORED or ERRORED once they turn it off.
 */
internal fun isPipAllowedBySystem(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
    if (!context.packageManager.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)) return false
    val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
    return try {
        when (
            appOps.checkOpNoThrow(
                AppOpsManager.OPSTR_PICTURE_IN_PICTURE,
                Process.myUid(),
                context.packageName,
            )
        ) {
            AppOpsManager.MODE_ALLOWED, AppOpsManager.MODE_DEFAULT -> true
            else -> false
        }
    } catch (_: Exception) {
        false
    }
}

private fun openExternally(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(Uri.parse(url), "video/*")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(Intent.createChooser(intent, context.getString(R.string.player_chooser)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(context, context.getString(R.string.player_no_external_player), Toast.LENGTH_SHORT).show()
    }
}

internal fun queryDisplayName(context: Context, uri: Uri): String? {
    return try {
        context.contentResolver.query(
            uri,
            arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    } catch (_: Exception) {
        null
    }
}

internal fun Context.findActivity(): Activity? {
    var currentContext: Context = this
    while (currentContext is ContextWrapper) {
        if (currentContext is Activity) return currentContext
        currentContext = currentContext.baseContext
    }
    return null
}

/**
 * Borderless press — the overlay standard for the chrome in this file.
 *
 * It used to be a raw tap detector, which is invisible to an accessibility service: nothing
 * downstream of `pointerInput` publishes a click action, so every sheet row, quality rung,
 * keep status and play-again button in this screen could be seen by TalkBack but not pressed.
 * This is a real clickable with a Button role and no indication, so the borderless look is
 * exactly what it was and the press is now an action a screen reader can take.
 */
@Composable
internal fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.then(
        Modifier.clickable(
            interactionSource = remember { MutableInteractionSource() },
            indication = null,
            role = Role.Button,
            onClick = onClick,
        ),
    )
