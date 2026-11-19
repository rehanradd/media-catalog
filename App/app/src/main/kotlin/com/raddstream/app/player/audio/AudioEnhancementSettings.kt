package com.raddstream.app.player.audio

/**
 * Everything the enhancement processor needs, as one immutable value: the
 * software gain for the boost excess (1.0 when the level is at or below
 * unity — the processor is then a pass-through) and the equalizer state.
 * This is what [com.raddstream.app.player.PlayerSessionStore] carries
 * between the ViewModel (which owns the UI state and the persisted level)
 * and the [com.raddstream.app.player.PlaybackService] (which owns the
 * processor). One value, one flow, no second channel to drift.
 */
data class AudioEnhancementSettings(
    val dspGain: Float = 1f,
    val eq: EqState = EqState.DEFAULT,
) {
    companion object {
        val DEFAULT: AudioEnhancementSettings = AudioEnhancementSettings()
    }
}
