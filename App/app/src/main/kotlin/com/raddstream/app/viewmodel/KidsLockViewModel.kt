package com.raddstream.app.viewmodel

import com.raddstream.app.data.ServerSettingsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.Flow

/**
 * MX idea #7 — Kids Lock: the one place that owns the PIN.
 *
 * Settings, the Home banner and the player all ask this ViewModel —
 * never the repository directly — so the attempt throttle lives in
 * exactly one place and a wrong PIN in one screen counts against the
 * pad everywhere. The flows are the repository's own (collected with
 * `collectAsState` by the screens, the way MainActivity collects every
 * other settings flow); the functions are suspending so a screen calls
 * them from its own coroutine scope and a test calls them from
 * `runTest` with no dispatcher to fake.
 *
 * The throttle is in-memory: five wrong guesses lock the pad for
 * 30 seconds ([KidsLock.LOCKOUT_MS]). A process death resets it —
 * the PIN itself is still the only way in, and a lockout that survived
 * a reboot would punish the parent who mistyped, not the child who
 * guessed. [lockoutRemainingMs] is the live answer for the pad's
 * "try again in …" line.
 */
@HiltViewModel
class KidsLockViewModel @Inject constructor(
    private val settingsRepository: ServerSettingsRepository,
) : androidx.lifecycle.ViewModel() {

    /** True once a PIN exists — the setup flow's "already set up" answer. */
    val pinSet: Flow<Boolean> = settingsRepository.kidsLockPinSet

    /** Whether Kids Mode is on right now. */
    val modeActive: Flow<Boolean> = settingsRepository.kidsModeActive

    private var failedAttempts = 0
    private var lastFailedAtMs: Long? = null

    /** Milliseconds left on a lockout, or 0 when the pad is open. */
    fun lockoutRemainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        KidsLock.lockoutRemainingMs(failedAttempts, lastFailedAtMs, nowMs)

    private fun noteFailure(nowMs: Long = System.currentTimeMillis()) {
        failedAttempts++
        lastFailedAtMs = nowMs
    }

    private fun noteSuccess() {
        failedAttempts = 0
        lastFailedAtMs = null
    }

    /**
     * One guess against the stored PIN, with the throttle in front of
     * it. A success clears the attempt count; a failure counts, and the
     * fifth failure locks the pad for [KidsLock.LOCKOUT_MS].
     */
    suspend fun verifyPin(pin: String): KidsPinOutcome {
        val now = System.currentTimeMillis()
        if (KidsLock.isPinLockedOut(failedAttempts, lastFailedAtMs, now)) {
            return KidsPinOutcome.LockedOut
        }
        if (!KidsLock.isValidPinFormat(pin)) return KidsPinOutcome.InvalidFormat
        return if (settingsRepository.verifyKidsPin(pin)) {
            noteSuccess()
            KidsPinOutcome.Success
        } else {
            noteFailure(now)
            KidsPinOutcome.WrongPin
        }
    }

    /**
     * Set the first PIN (or replace one after [changePin] proved the
     * old one). Both entries must be four digits and must agree.
     */
    suspend fun setupPin(pin: String, confirm: String): KidsPinOutcome {
        if (!KidsLock.isValidPinFormat(pin) || !KidsLock.isValidPinFormat(confirm)) {
            return KidsPinOutcome.InvalidFormat
        }
        if (pin != confirm) return KidsPinOutcome.Mismatch
        settingsRepository.saveKidsLockPin(pin)
        noteSuccess()
        return KidsPinOutcome.Success
    }

    /** Enter Kids Mode: the PIN, then the mode flips on. */
    suspend fun enterKidsMode(pin: String): KidsPinOutcome {
        return when (val checked = verifyPin(pin)) {
            KidsPinOutcome.Success -> {
                settingsRepository.setKidsModeActive(true)
                KidsPinOutcome.Success
            }
            else -> checked
        }
    }

    /** Exit Kids Mode: the PIN, then the mode flips off. */
    suspend fun exitKidsMode(pin: String): KidsPinOutcome {
        return when (val checked = verifyPin(pin)) {
            KidsPinOutcome.Success -> {
                settingsRepository.setKidsModeActive(false)
                KidsPinOutcome.Success
            }
            else -> checked
        }
    }

    /** Change the PIN: prove the old one, then set the new one twice. */
    suspend fun changePin(current: String, next: String, confirm: String): KidsPinOutcome {
        return when (val checked = verifyPin(current)) {
            KidsPinOutcome.Success -> setupPin(next, confirm)
            else -> checked
        }
    }

    /** Remove Kids Lock entirely: the PIN, then PIN + mode are gone. */
    suspend fun removeKidsLock(pin: String): KidsPinOutcome {
        return when (val checked = verifyPin(pin)) {
            KidsPinOutcome.Success -> {
                settingsRepository.clearKidsLockPin()
                KidsPinOutcome.Success
            }
            else -> checked
        }
    }
}

/** What one PIN interaction decided. The screens render each one. */
sealed interface KidsPinOutcome {
    /** The PIN was right (and the mode, if one was asked for, flipped). */
    data object Success : KidsPinOutcome

    /** The PIN was wrong. */
    data object WrongPin : KidsPinOutcome

    /** Not four digits — never sent to the store. */
    data object InvalidFormat : KidsPinOutcome

    /** Setup's two entries disagreed. */
    data object Mismatch : KidsPinOutcome

    /** Five wrong guesses: the pad refuses for [KidsLock.LOCKOUT_MS]. */
    data object LockedOut : KidsPinOutcome
}
