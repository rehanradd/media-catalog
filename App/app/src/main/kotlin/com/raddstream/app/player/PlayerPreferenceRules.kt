package com.raddstream.app.player

/**
 * P6 (player fixes) — the preference-persistence rules, defined in one
 * place and pinned by [PlayerPreferenceRulesTest]. This is a RULES gap,
 * not a proven defect: the DataStore infra already exists (F-03 debounced
 * saves), and the behaviour below is what the code already does — written
 * down so a later change can be caught by a test instead of a viewer.
 *
 * The rules:
 *  - Speed resets to 1x per title, unless a per-title override exists.
 *    Today no per-title speed override is ever stored, so the reset is
 *    unconditional in practice — PlayerViewModel.loadNow resets the
 *    controller to 1.0 whenever the title changes, and a retry or a quality
 *    switch on the SAME title keeps the choice. The override parameter
 *    exists so the day a per-title override store lands, this rule already
 *    says what it must do, and the test already pins it.
 *  - The global default quality is 480p ([DEFAULT_START_BAND], owner order
 *    07:01 PKT 16 Nov; pinned by PlayerDefaultsTest). Auto stays on, so the
 *    switcher may climb from 480p once the network proves it can.
 *  - Subtitle, audio and quality persist per title; speed does not. A
 *    viewer who picked a subtitle track or a rung on a title gets it back
 *    on that title; a viewer who picked 1.5x does not get 1.5x on the next
 *    title unless they pick it again.
 *  - The current state is always visible, without opening a sheet: speed
 *    on the bottom-row pill (only while != 1x), subtitles on the button's
 *    label + jade tint, audio on the button's label (the track in play),
 *    quality on the pill's rung. [visibleIndicator] names the surface each
 *    kind uses, so a kind with no surface is a test failure, not a shrug.
 *
 * No new infra, and the F-03 debounce behaviour is untouched — this file
 * defines rules, it does not move a save.
 */
object PlayerPreferenceRules {

    /** Speed with no override and no memory: the reset value. */
    const val DEFAULT_SPEED: Float = 1f

    /** The four preferences the player carries across titles. */
    enum class PreferenceKind {
        SPEED,
        SUBTITLE,
        AUDIO,
        QUALITY,
    }

    /** Where the viewer reads the current value without opening a sheet. */
    enum class VisibleIndicator {
        SPEED_PILL,
        SUBTITLE_BUTTON,
        AUDIO_BUTTON,
        QUALITY_PILL,
    }

    /**
     * Whether [kind] survives a title change. Speed is the only one that
     * does not — a speed is a mood for this viewing, not a taste.
     */
    fun persistsPerTitle(kind: PreferenceKind): Boolean = when (kind) {
        PreferenceKind.SPEED -> false
        PreferenceKind.SUBTITLE,
        PreferenceKind.AUDIO,
        PreferenceKind.QUALITY -> true
    }

    /**
     * The speed a new title starts at. A per-title override wins; without
     * one the title starts at [DEFAULT_SPEED]. `null` is not "no speed" —
     * it is "no override", and the answer is still 1x.
     */
    fun effectiveSpeedForNewTitle(perTitleOverride: Float?): Float =
        perTitleOverride ?: DEFAULT_SPEED

    /**
     * Whether opening a new title resets the speed. True exactly when no
     * per-title override exists — the override is the only thing that can
     * carry a speed across titles.
     */
    fun speedResetsOnTitleChange(perTitleOverride: Float?): Boolean =
        perTitleOverride == null

    /** The global default quality band: 480p, with Auto on. */
    fun defaultStartBand(): String = DEFAULT_START_BAND

    /**
     * The surface that shows [kind]'s current value. Every kind has one —
     * a preference with no visible indicator is a preference the viewer
     * cannot check, and this function is the test's way of noticing.
     */
    fun visibleIndicator(kind: PreferenceKind): VisibleIndicator = when (kind) {
        PreferenceKind.SPEED -> VisibleIndicator.SPEED_PILL
        PreferenceKind.SUBTITLE -> VisibleIndicator.SUBTITLE_BUTTON
        PreferenceKind.AUDIO -> VisibleIndicator.AUDIO_BUTTON
        PreferenceKind.QUALITY -> VisibleIndicator.QUALITY_PILL
    }
}
