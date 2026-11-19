package com.raddstream.app.player

/**
 * MX idea #3 (2026-11-19): which video decoder the player is built with —
 * MX's top-right HW/SW switch, one tap in the player chrome.
 *
 * [HARDWARE] is the shipped default: the device's own MediaCodec decoders,
 * which is what Media3 picks on its own. [SOFTWARE] rebuilds the player
 * with a MediaCodecSelector that only offers software decoders
 * (`OMX.google.*` / `c2.android.*`) — the fix for a title the hardware
 * decoder shows as green blocks, a black screen, or stutter.
 *
 * There is no HW+ mode: MX's HW+ is its own decoder framework, and Media3
 * exposes no equivalent — inventing a third mode would be a label with no
 * decoder behind it.
 *
 * The FFmpeg audio extension stays PREFER in both modes: it is audio-only
 * (AC-3 / E-AC-3 / DTS / TrueHD / MLP), and the automatic software retry
 * from MX idea #1 depends on it. This switch moves the VIDEO decoder path;
 * the audio path is the same in both modes. That is also why the switch is
 * always visible: a software MediaCodec video decoder exists on every
 * certified device, so SW is meaningful even on a build without the FFmpeg
 * AAR.
 */
enum class DecoderMode {
    HARDWARE,
    SOFTWARE,
    ;

    /** The other mode — the failover target, and what the chip toggles to. */
    fun other(): DecoderMode = if (this == HARDWARE) SOFTWARE else HARDWARE

    companion object {
        /** Storage contract: an unknown or absent name is the mode nobody opted into. */
        fun fromName(name: String?): DecoderMode =
            entries.firstOrNull { it.name == name } ?: HARDWARE
    }
}
