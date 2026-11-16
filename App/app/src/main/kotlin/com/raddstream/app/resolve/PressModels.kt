package com.raddstream.app.resolve

/** The press-test run header: the mint run's header shape, plus the phase and the run kind. */
data class PressRunHeader(
    val deviceModel: String,
    val androidVersion: String,
    val appVersion: String,
    val runStartedEpochMs: Long,
    /** wifi / mobile / other / none — never an SSID. */
    val networkType: String,
    /** Always "press": this header belongs to the G-10 press/download run, not the mint run. */
    val phase: String = "press",
    /** "initial" for the first run of a group, "repeat" for the one repeat run a 15–17/20 group gets. */
    val runKind: String = "initial",
)

/**
 * Everything the press test records for one banked link.
 *
 * The two epoch timestamps per press ([pressStartedAtMs] for the tap and
 * [mintStartedAtMs] for the redirector probe) plus [postId] and [linkId] are
 * what the parent needs to check the server log afterwards: this phone
 * cannot see the server log, so "no resolve log line for this press" is
 * verified on the VM by matching those timestamps, not on the phone.
 */
data class PressResult(
    val linkId: Long,
    val postId: Long,
    val service: String,
    val attemptKind: AttemptKind,
    /** Epoch ms of the Play tap — the press the server log is checked against. */
    val pressStartedAtMs: Long,
    /** Epoch ms the redirector probe started — the mint call the log is checked against. */
    val mintStartedAtMs: Long,
    val mintFinishedAtMs: Long?,
    /** Probe duration: starting the play request to the 302 arriving. Null when no 302 arrived. */
    val mintTo302Ms: Long?,
    val mintHttpStatus: Int?,
    /** The 302 Location: the banked direct link, in full — same practice as the mint export. */
    val mintedUrl: String?,
    /** Tap to first rendered frame. Null when no frame rendered before the timeout. */
    val pressToFirstFrameMs: Long?,
    /** BUFFERING entries after playback first reached READY — initial buffering is startup, not a rebuffer. */
    val rebufferCount: Int,
    val droppedFrames: Int,
    /** Bytes the player read (sum of finished-load bytes). */
    val bytesRead: Long,
    /** The PlaybackStatsListener's own totals; null when it never delivered a session. */
    val statsTotalPlayTimeMs: Long?,
    val statsRebufferCount: Int?,
    /** Short machine-readable note for a failed press (error vocabulary, verbatim). */
    val playError: String?,
    /** Epoch ms of the Download tap. */
    val downloadTapStartedAtMs: Long?,
    /** Tap until the first byte was written (first progress with bytes > 0). Null when none arrived. */
    val tapToFirstByteMs: Long?,
    /** True when the cancel after the first byte took the row out of the active states. */
    val downloadCancelled: Boolean?,
    val downloadError: String?,
)

/** One press's playback counters, as the harness player measured them. */
data class PressPlaybackSnapshot(
    val firstFrameElapsedMs: Long?,
    val rebufferCount: Int,
    val droppedFrames: Int,
    val bytesRead: Long,
    val statsTotalPlayTimeMs: Long?,
    val statsRebufferCount: Int?,
)
