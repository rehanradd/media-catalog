package com.raddstream.app.ui

import com.raddstream.app.player.PlayerUiState
import com.raddstream.app.ui.screens.subtitleStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The player control lane names every one of its buttons — and used to throw every name away:
 * the composable took a `contentDescription` it never read, each Icon inside passed `null`, and
 * the slots were forced to 42 dp and 38 dp. Nothing about that fails a compile, so this is a
 * drift test over the source itself, in the `BrandDriftTest` pattern, plus the one rule that IS
 * ordinary logic: what the subtitles button says.
 */
class PlayerControlSemanticsTest {

    /**
     * A word the helper has never heard of. `subtitleStatusLabel` takes the off-word as a
     * parameter instead of owning it, so the screen can speak the resource value while this test
     * proves the helper hardcodes nothing: a sentinel the helper could not possibly invent, echoed
     * back, is the whole assertion.
     */
    private val OFF_WORD = "no text track"

    /** The 11 files the PlayerScreen split (APP-10) produced, in split order. */
    private val playerScreenFiles: List<String> = listOf(
        "PlayerScreen.kt", "PlayerGestures.kt", "PlayerHud.kt",
        "PlayerChrome.kt", "PlayerControls.kt", "PlayerCards.kt",
        "PlayerNotices.kt", "PlayerSubtitles.kt", "PlayerDialogs.kt",
        "PlayerSheets.kt", "PlayerScreenUtils.kt",
    )

    private val screensDir: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                return@lazy File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/ui/screens",
                )
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val playerViewModel: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                return@lazy File(
                    dir,
                    "App/app/src/main/kotlin/com/raddstream/app/viewmodel/PlayerViewModel.kt",
                )
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    /**
     * Hate-audit fixes, pinned so a later mockup pass cannot silently undo
     * them: live stays the mark only, no HLS jargon, the sheet switches
     * sources, gestures are guarded, and the scrubber is 48dp.
     */
    @Test
    fun `hate-audit fixes stay in the player`() {
        // H16: live is the LIVE mark only — no seek bar, no elapsed counter.
        // The mockup's live bar is not copied; the regression (073e454e) stays fixed.
        assertTrue("the live mark is missing", source.contains("fun LiveMark("))
        assertFalse(
            "the live progress row is back — the mockup's seek bar must not be copied",
            source.contains("LiveProgressRow"),
        )
        // H17: no HLS jargon anywhere on screen, and the sheet is Switch version.
        assertFalse("an HLS tag is still printed", source.contains("player_tag_hls"))
        assertFalse(
            "the sheet still says Versions & failover",
            stringsSource.contains("Versions &amp; failover"),
        )
        assertTrue("the sheet must be Switch version", stringsSource.contains("Switch version"))
        assertFalse("an HLS string still exists", stringsSource.contains(">HLS<"))
        // H12: the print sheet's rows switch the source.
        assertTrue("rendition rows must call the switch", source.contains("vm.switchRendition(r)"))
        // H5: the edge dead zone gates vertical gestures in Lane C.
        assertTrue("the edge dead zone is missing from Lane C", source.contains("isInEdgeDeadZone("))
        // H6: the scrubber's touch height is 48dp (24dp track + 12dp each side).
        val scrubber = functionBody("fun Scrubber(")
        assertTrue("the scrubber must pad 12dp vertically", scrubber.contains("vertical = 12.dp"))
        // H14/H5: the centre button's double-tap guard — one toggle per double-tap.
        assertTrue("the centre double-tap guard is missing", source.contains("lastCentreToggleNs"))
        // H25: one Audio & Subtitles sheet, audio switch one tap from the primary.
        assertTrue("the combined sheet title is missing", source.contains("player_audio_subtitles_title"))
        // H21: the top bar clears the status bar when the bars are shown.
        assertTrue("statusBarsPadding is missing from the chrome", source.contains("statusBarsPadding()"))
    }

    /**
     * The Stitch popups restyle, pinned so a later pass cannot silently
     * undo it: the up-next card is a bottom card with a ring, the resume
     * prompt is a centred card, the finding card is jade with a bold
     * headline, the speed and sleep sheets are vertical rows in the
     * mockup's order, and the switch-source sheet rows carry the size
     * and the Playing pill. The sheets are private and take the
     * ViewModel, so this is a source test — the one row that can be
     * composed (SwitchSourceRow) is pinned in PlayerMockupTest instead.
     */
    @Test
    fun `popups match the Stitch sheets`() {
        // Up-next: a bottom card with a jade countdown ring, "Up next:
        // S2:E5" bold, an X, and Cancel + Play now pills. The old
        // centred pill + text buttons are gone.
        val upNext = functionBody("fun AutoplayCountdownCard(")
        assertTrue("the up-next card must sit at the bottom", upNext.contains("Alignment.BottomCenter"))
        assertTrue("the ring must read the countdown total", upNext.contains("AUTOPLAY_COUNTDOWN_SECONDS"))
        assertTrue("the headline needs the season and episode", upNext.contains("player_season_episode"))
        assertTrue("the card needs its X", upNext.contains("ic_action_close"))
        assertTrue("the card needs the Play now pill", upNext.contains("player_autoplay_play_now"))
        assertFalse("the old countdown sentence is back", upNext.contains("player_autoplay_countdown"))
        assertFalse("the old centred layout is back", upNext.contains("CenterHorizontally"))
        // Resume: a centred card with a jade Resume pill and a dark Start
        // over pill. The old bottom chip and its X are gone.
        val resume = functionBody("fun ResumeChip(")
        assertTrue("the resume prompt must be a centred card", resume.contains("Alignment.Center"))
        assertTrue("the resume prompt needs its Resume pill", resume.contains("R.string.player_resume)"))
        assertTrue("the resume prompt needs Start over", resume.contains("player_start_over"))
        assertFalse("the resume chip's X is back", resume.contains("ic_action_close"))
        assertFalse("the resume chip is back at the bottom", resume.contains("Alignment.BottomCenter"))
        assertTrue("the resume title needs its question mark", stringsSource.contains("Resume from %1\$s?"))
        // Finding: a jade spinner ring, a bold headline, the "Trying the
        // best version first" line while finding, a dark Try-another pill,
        // and no logo mark on the card.
        val finding = functionBody("fun StartNoticeCard(")
        assertTrue("the finding card needs its subtitle", finding.contains("player_finding_note"))
        assertTrue("the finding headline must be bold", finding.contains("FontWeight.Bold"))
        assertTrue("Try another must be the dark pill", finding.contains("RaddHudPillIdle"))
        assertFalse("the logo mark is back on the finding card", finding.contains("ic_logo_mark"))
        assertTrue("the finding card must be rounder", finding.contains("RoundedCornerShape(24.dp)"))
        val spinner = functionBody("fun PlayerLoadingIndicator(")
        assertTrue("the spinner ring must be jade", spinner.contains("RaddCurrent500"))
        assertFalse("the spinner is grey again", spinner.contains("onSurfaceVariant"))
        // Speed: vertical rows over the mockup's six speeds, with the
        // boost note kept; the horizontal chip strip is gone.
        val speed = functionBody("fun SpeedSheet(")
        assertTrue("the speed sheet must read its six rows", speed.contains("GestureMapper.SHEET_PRESETS"))
        assertTrue("the speed rows must be vertical", speed.contains("SheetRow("))
        assertFalse("the horizontal chip strip is back", speed.contains("horizontalScroll"))
        assertTrue("the boost note must stay", speed.contains("player_boost_note"))
        // Sleep: Off first, then the four presets, then End of episode;
        // the fading line and the fade note stay.
        val sleep = functionBody("fun SleepSheet(")
        val offAt = sleep.indexOf("player_off_label")
        val presetsAt = sleep.indexOf("SHEET_PRESETS_MS.forEach")
        val endAt = sleep.indexOf("player_sleep_at_end")
        assertTrue("the sleep sheet must put Off first", offAt >= 0 && offAt < presetsAt && presetsAt < endAt)
        assertTrue("the sleep sheet must read its four presets", sleep.contains("SHEET_PRESETS_MS"))
        assertTrue("the sleep note must stay", sleep.contains("player_sleep_note"))
        // Switch version: rows with size + Playing pill, a footer that
        // switching keeps the viewer's place, and an X on the title.
        val switch = functionBody("fun PrintLogSheet(")
        assertTrue("the source rows must be SwitchSourceRows", switch.contains("SwitchSourceRow("))
        assertTrue("the sheet needs its footer", switch.contains("player_switch_keeps_place"))
        assertFalse("the old header note is back", switch.contains("player_prints_note"))
        assertFalse("the old plain rendition rows are back", switch.contains("SheetRow(label = rowText"))
        val title = functionBody("fun SheetTitle(")
        assertTrue("sheet titles must take an X", title.contains("onDismiss"))
        assertTrue("the X must be drawn", title.contains("ic_action_close"))
        // Strings: the two dropped notes are gone from the file, and the
        // trimmed titles read as the mockups do.
        assertFalse("the prints note string is back", stringsSource.contains("player_prints_note"))
        assertFalse("the autoplay countdown string is back", stringsSource.contains("player_autoplay_countdown"))
        assertTrue("the speed title must be plain", stringsSource.contains(">Playback speed<"))
        assertTrue("the finding headline needs its ellipsis", stringsSource.contains("Finding your video…"))
        assertTrue("the finding subtitle is missing", stringsSource.contains("Trying the best version first"))
        assertTrue("the switch footer is missing", stringsSource.contains("Switching keeps your place."))
    }

    private val stringsSource: String by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) {
                val f = File(dir, "App/app/src/main/res/values/strings.xml")
                assertTrue("strings.xml is missing at ${f.path}", f.isFile)
                return@lazy f.readText()
            }
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val source: String by lazy {
        // The drift asserts read the split files concatenated, in split
        // order, so a declaration's neighbours are the same as when the
        // screen was one file.
        playerScreenFiles.joinToString("\n") { name ->
            val file = File(screensDir, name)
            assertTrue("Player split file $name is missing at ${file.path}", file.isFile)
            file.readText()
        }
    }

    /**
     * The body of one top-level function, from its signature to the end of its declaration.
     * A block body is brace-counted to its matching close, so blank lines inside a long
     * function (GestureOverlay) do not cut the body short — the first version stopped at
     * the first blank line and never reached Lane C. An expression body (the press
     * helper's `= this.then(...)`) ends at the next blank line. The scan skips string
     * literals and comments, so a brace inside either cannot throw the count off.
     */
    private fun functionBody(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("PlayerScreen.kt no longer declares $signature", start >= 0)
        val blank = source.indexOf("\n\n", start)
        val openParen = source.indexOf("(", start)
        if (openParen < 0) {
            val end = if (blank < 0) source.length else blank
            return source.substring(start, end)
        }
        // End of the parameter list: the `)` matching the opening `(`.
        var i = openParen
        var depth = 0
        var closeParen = -1
        while (i < source.length) {
            i = skipStringOrComment(i)
            if (i >= source.length) break
            when (source[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) {
                        closeParen = i
                        break
                    }
                }
            }
            i++
        }
        if (closeParen < 0) {
            val end = if (blank < 0) source.length else blank
            return source.substring(start, end)
        }
        // After the parameter list (and any return type) a `{` means a block
        // body and an `=` means an expression body — whichever comes first.
        val openBrace = source.indexOf("{", closeParen)
        val equals = source.indexOf("=", closeParen)
        val isBlock = openBrace >= 0 && (equals < 0 || openBrace < equals)
        if (!isBlock) {
            val end = if (blank < 0) source.length else blank
            return source.substring(start, end)
        }
        var braceDepth = 0
        i = openBrace
        while (i < source.length) {
            i = skipStringOrComment(i)
            if (i >= source.length) break
            when (source[i]) {
                '{' -> braceDepth++
                '}' -> {
                    braceDepth--
                    if (braceDepth == 0) return source.substring(start, i + 1)
                }
            }
            i++
        }
        return source.substring(start)
    }

    /** Index just past a string literal or comment starting at [i], or [i] itself. */
    private fun skipStringOrComment(i: Int): Int {
        if (source.startsWith("//", i)) {
            val nl = source.indexOf("\n", i)
            return if (nl < 0) source.length else nl
        }
        if (source.startsWith("/*", i)) {
            val end = source.indexOf("*/", i + 2)
            return if (end < 0) source.length else end + 2
        }
        if (source[i] == '"') {
            var j = i + 1
            while (j < source.length && source[j] != '"') {
                if (source[j] == '\\') j++
                j++
            }
            return minOf(j + 1, source.length)
        }
        return i
    }

    @Test
    fun `the minimum touch target is the Android minimum`() {
        assertTrue(
            "every player press must be at least 48 dp",
            source.contains("val MinControlTarget = 48.dp"),
        )
    }

    @Test
    fun `no control keeps a slot smaller than the minimum`() {
        listOf("Modifier.size(38.dp)", "Modifier.size(42.dp)").forEach { retired ->
            assertFalse(
                "PlayerScreen still forces a control into $retired — that is under the " +
                    "48 dp a fingertip is entitled to",
                source.contains(retired),
            )
        }
    }

    @Test
    fun `the press a control uses gives its label to a screen reader`() {
        val body = functionBody("fun ControlTarget(")
        assertTrue(
            "ControlTarget declares a label and never publishes it, which is how sixteen " +
                "named controls became invisible",
            body.contains("contentDescription ="),
        )
        assertTrue("ControlTarget must be a real press", body.contains(".clickable("))
        assertTrue("ControlTarget must be announced as a button", body.contains("role = Role.Button"))
        assertTrue(
            "ControlTarget must be at least the minimum target",
            body.contains(".size(MinControlTarget)"),
        )
    }

    @Test
    fun `the lane button hands its label down instead of dropping it`() {
        val body = functionBody("fun PlayerControlButton(")
        assertTrue(
            "a contentDescription parameter nobody reads is the bug this test exists for",
            !body.contains("contentDescription: String"),
        )
        assertTrue("PlayerControlButton must route through ControlTarget", body.contains("ControlTarget("))
        assertTrue("PlayerControlButton must pass its label on", body.contains("label = label"))
    }

    @Test
    fun `a borderless press is still a press an accessibility service can take`() {
        // The lookup drops the visibility word: clickableNoRipple went private -> internal
        // in the redesign fixup 2 so PlayerSheet.kt can use it, and the rule this test pins
        // is the body (a real clickable with a Button role), not the visibility.
        val body = functionBody("fun Modifier.clickableNoRipple(")
        assertTrue(
            "a raw tap detector publishes no click action, so sheet rows and chips cannot be " +
                "activated by a screen reader",
            body.contains("Modifier.clickable("),
        )
        assertTrue("the press must be announced as a button", body.contains("role = Role.Button"))
        assertFalse("the borderless chrome look must stay borderless", body.contains("detectTapGestures"))
    }

    @Test
    fun `volume and fullscreen are real buttons, not gestures alone`() {
        assertTrue("the bottom row needs a Volume button", source.contains("vm.toggleMute()"))
        assertTrue("the bottom row needs a Fullscreen button", source.contains("vm.toggleFullscreen()"))
        assertTrue("the Volume button must say its level", source.contains("R.string.player_volume"))
        assertTrue("a muted player must say it is muted", source.contains("R.string.player_volume_muted"))
        assertTrue("the Fullscreen button must be named", source.contains("R.string.player_fullscreen"))
        assertTrue("leaving fullscreen must be named too", source.contains("R.string.player_exit_fullscreen"))
    }

    @Test
    fun `a long press on volume opens the level slider`() {
        // P1-15 (audit B-18): the long-press opens the inline vertical
        // slider floating above the volume button — no AlertDialog, so the
        // video stays visible while the level is adjusted.
        assertTrue(
            "the Volume button must offer its level on a long press",
            source.contains("onLongClick = { showVolumeSlider = true }"),
        )
        assertTrue(
            "the slider must write the same volume the gesture writes",
            source.contains("onValueChange = { onSetUserVolume(it) }"),
        )
        val row = bottomControlsBody()
        assertFalse("the volume dialog is back", row.contains("AlertDialog("))
        assertFalse("the boost slider is back in the player", row.contains("player_volume_boost"))
        assertFalse("the dialog title is back", row.contains("player_volume_title"))
    }

    /**
     * The bottom row, from its signature to the next top-level function — the shared
     * `functionBody` helper would stop at the first blank line inside this long body.
     */
    private fun bottomControlsBody(): String {
        val start = source.indexOf("internal fun BottomControls(")
        assertTrue("PlayerScreen.kt no longer declares BottomControls", start >= 0)
        val end = source.indexOf("\nprivate fun subtitleActive(", start)
        assertTrue("cannot find the end of BottomControls", end >= 0)
        return source.substring(start, end)
    }

    /** The More sheet, from its signature to the next sheet's opt-in annotation. */
    private fun moreSheetBody(): String {
        val start = source.indexOf("fun MoreSheet(")
        assertTrue("PlayerScreen.kt no longer declares MoreSheet", start >= 0)
        // The next sheet is PrintLogSheet, whose opt-in also covers the
        // ExperimentalFoundationApi its long-press title needs — the marker
        // tracks the real annotation, not a shorter one.
        val end = source.indexOf("\n@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)", start)
        assertTrue("cannot find the end of MoreSheet", end >= 0)
        return source.substring(start, end)
    }

    @Test
    fun `the bottom row is exactly the seven fixed controls`() {
        // P1-15 (audit B-17): fixed positions — Play/Pause, Back 10,
        // Forward 10, Volume, Captions/Audio, Fullscreen, More, always in
        // that order. The flexible slots are gone; Speed, Quality and Next
        // Episode live in More.
        val row = bottomControlsBody()
        listOf(
            "onTogglePlayPause()" to "Play/Pause",
            "onSeekBy(-10_000)" to "Back 10",
            "onSeekBy(10_000)" to "Forward 10",
            "onToggleMute()" to "Volume",
            "onShowSubtitleSheet()" to "Captions/Audio",
            "onToggleFullscreen()" to "Fullscreen",
            "onShowMoreSheet()" to "More",
        ).forEach { (call, name) ->
            assertTrue("the bottom row lost its $name control", row.contains(call))
        }
        assertTrue("the More button must be named", row.contains("R.string.player_more"))
        assertFalse(
            "the bottom row must not scroll — seven fixed buttons fit the " +
                "landscape player, and a hidden eighth would be a button that lies",
            row.contains("horizontalScroll"),
        )
    }

    @Test
    fun `nothing else lives in the bottom row`() {
        val row = bottomControlsBody()
        listOf(
            "vm.showSubtitleOffsetSheet()", "vm.showTracksSheet()", "vm.showSpeedSheet()",
            "vm.setLocked(true)", "vm.seekBy(", "vm.stepFrame(", "vm.requestNextEpisode()",
            "vm.showSleepSheet()", "vm.cycleAspect()", "vm.toggleOrientation()",
            "vm.toggleAudioOnly()", "vm.toggleStereoOnly()", "vm.toggleViewMode()",
            "PlayerControlButton(onEnterPip",
        ).forEach { moved ->
            assertFalse(
                "the bottom row still carries $moved — it belongs in the More sheet",
                row.contains(moved),
            )
        }
    }

    @Test
    fun `the More sheet carries every moved control, grouped`() {
        val sheet = moreSheetBody()
        listOf(
            // Playback
            "vm.showSpeedSheet()", "vm.showQualitySheet()", "vm.showSleepSheet()", "vm.stepFrame(-1)", "vm.stepFrame(1)",
            "vm.seekBy(-10_000)", "vm.seekBy(10_000)", "vm.cycleAspect()", "vm.toggleOrientation()",
            "vm.toggleViewMode()",
            // Subtitle
            "vm.showSubtitleSheet()", "vm.showSubtitleOffsetSheet()",
            // Audio
            "vm.showTracksSheet()", "vm.cycleAudioTrack()", "vm.toggleAudioOnly()", "vm.toggleStereoOnly()",
            // Tools
            "vm.requestNextEpisode()", "vm.showPlayFromDialog()", "vm.openCalibration()",
            "vm.showPrintSheet()", "vm.retryCurrentCandidates()", "vm.openWithExternalPlayer()",
            "onEnterPip()",
        ).forEach { moved ->
            assertTrue("the More sheet lost $moved", sheet.contains(moved))
        }
        listOf(
            "R.string.player_more_playback", "R.string.player_subtitles_title",
            "R.string.player_audio_title", "R.string.player_more_tools",
        ).forEach { title ->
            assertTrue("the More sheet lost its $title group", sheet.contains(title))
        }
    }

    @Test
    fun `a slow start earns a hint and, later, actions`() {
        val card = functionBody("fun StartNoticeCard(")
        assertTrue(
            "the notice must say the wait is normal once it passes two seconds",
            card.contains("R.string.player_start_slow"),
        )
        assertTrue(
            "the notice must name the rung being found",
            card.contains("R.string.player_quality_label"),
        )
        assertTrue(
            "the notice must offer a retry once it passes eight seconds",
            card.contains("R.string.player_retry"),
        )
        assertTrue(
            "the notice must offer another print once it passes eight seconds",
            card.contains("R.string.player_start_try_another"),
        )
        assertTrue(
            "the notice must be driven by the deadline stage, not a fixed timer",
            source.contains("state.startDeadlineStage"),
        )
        assertTrue(
            "the actions must call the manual failover, not just a retry",
            source.contains("vm.tryNextPrint()"),
        )
    }

    @Test
    fun `the thirty-second give-up is its own reason`() {
        val card = functionBody("fun GiveUpCard(")
        assertTrue(
            "the give-up card must know the timeout reason",
            card.contains("HandoffWhy.START_TIMEOUT"),
        )
        assertTrue(
            "the timeout must have its own title, not the no-candidate one",
            card.contains("R.string.player_giveup_timeout"),
        )
        assertTrue(
            "the timeout must have its own body, not the no-candidate one",
            card.contains("R.string.player_giveup_timeout_body"),
        )
        assertTrue(
            "the timeout card must offer another print",
            card.contains("R.string.player_start_try_another"),
        )
        assertTrue(
            "the deadline card must dismiss into a fresh thirty seconds",
            source.contains("vm.dismissDeadlineGiveUp()"),
        )
    }

    @Test
    fun `the start timing is a debug line, not viewer copy`() {
        val start = source.indexOf("fun PrintLogSheet(")
        assertTrue("PlayerScreen.kt no longer declares PrintLogSheet", start >= 0)
        val end = source.indexOf("\n@Composable", start)
        assertTrue("cannot find the end of PrintLogSheet", end >= 0)
        val sheet = source.substring(start, end)
        val frameAt = sheet.indexOf("R.string.player_first_frame")
        assertTrue("the print sheet lost its first-frame line", frameAt >= 0)
        // Dev-cleanup (Nov 2026, F1): the measured first-frame time stays in the
        // sheet, but only behind the long-press debug gate — a start-timing stat
        // is an owner diagnostic, not viewer copy.
        val gateAt = sheet.lastIndexOf("if (debugVisible)", frameAt)
        assertTrue("the first-frame line must sit behind the debug gate", gateAt >= 0)
        assertTrue(
            "the timing must come from the ViewModel's measurement, not a guess",
            sheet.contains("state.lastFirstFrameMs"),
        )
    }

    @Test
    fun `nothing is announced while no text track is loaded`() {
        assertEquals(OFF_WORD, subtitleStatusLabel(PlayerUiState(), OFF_WORD))
    }

    @Test
    fun `an external file is named by its own label`() {
        val state = PlayerUiState(customSubtitleLoaded = "subtitles.srt")
        assertEquals("subtitles.srt", subtitleStatusLabel(state, OFF_WORD))
    }

    @Test
    fun `a selected track the player no longer reports is not claimed as loaded`() {
        // The handle survives a rendition swap while the track list does not, and saying
        // "English [CC]" about a track that is gone would be the button lying. Building a real
        // Media3 TrackUiOption needs a device-side class, so this covers the empty-report case,
        // which is the one that can strand a stale id.
        assertEquals(OFF_WORD, subtitleStatusLabel(PlayerUiState(selectedSubtitleId = "9"), OFF_WORD))
    }

    @Test
    fun `the volume gesture reads a fresh base, not the first frame's`() {
        // Volume-gesture fix (Nov 2026): onVolume adds each frame's delta
        // to state.userVolume, a snapshot — inside Lane C's
        // pointerInput(Unit) loop the first composition's closure kept
        // adding to the ORIGINAL volume, so a full swipe moved it ~1%.
        // The lane must call the rememberUpdatedState wrapper, never the
        // bare lambda; same for brightness, which only worked by accident.
        val overlay = functionBody("fun GestureOverlay(")
        assertTrue(
            "GestureOverlay must wrap onVolume in rememberUpdatedState",
            overlay.contains("val latestOnVolume by rememberUpdatedState(onVolume)"),
        )
        assertTrue(
            "GestureOverlay must wrap onBrightness in rememberUpdatedState",
            overlay.contains("val latestOnBrightness by rememberUpdatedState(onBrightness)"),
        )
        assertTrue(
            "Lane C must call latestOnVolume, not the stale onVolume closure",
            overlay.contains("latestOnVolume(cmd.fractionPerSwipe)"),
        )
        assertTrue(
            "Lane C must call latestOnBrightness, not the stale onBrightness closure",
            overlay.contains("latestOnBrightness(cmd.fractionPerSwipe)"),
        )
        // "Volume(cmd.fractionPerSwipe)" matches both the latest-prefixed call
        // and a bare onVolume call: camelCase puts a capital O in
        // latestOnVolume, so splitting on lowercase "onVolume(...)" matches
        // nothing at all. Exactly one occurrence means Lane C calls it once,
        // via latestOnVolume.
        assertEquals(
            "Lane C must call onVolume exactly once, via latestOnVolume",
            1,
            overlay.split("Volume(cmd.fractionPerSwipe)").size - 1,
        )
        assertEquals(
            "Lane C must call onBrightness exactly once, via latestOnBrightness",
            1,
            overlay.split("Brightness(cmd.fractionPerSwipe)").size - 1,
        )
    }

    @Test
    fun `the top bar carries the lock action and the gesture coach is gone`() {
        val topBarStart = source.indexOf("internal fun TopBar(")
        val topBarEnd = source.indexOf("\n@Composable\ninternal fun CenterSpinner(", topBarStart)
        assertTrue("TopBar bounds missing", topBarStart >= 0 && topBarEnd > topBarStart)
        val topBar = source.substring(topBarStart, topBarEnd)
        // onLock is passed as a function reference (onClick = onLock), not
        // invoked in the body, so the check names the wiring, not a call.
        assertTrue("TopBar must offer the Lock control", topBar.contains("onClick = onLock"))
        assertTrue("TopBar Lock control must announce player_lock", topBar.contains("R.string.player_lock"))

        // Mockup (Stitch player redesign): the first-run gesture coach is
        // deleted — the gestures stay, the popup never shows again. Nothing
        // in the screen may reference it, its strings, or its ViewModel half.
        listOf(
            "PlayerCoachOverlay", "CoachHintRow", "player_coach_", "showCoach",
            "markCoachSeen", "coachVisible", "coachDismissed",
        ).forEach { gone ->
            assertFalse(
                "PlayerScreen still carries $gone — the gesture coach was removed",
                source.contains(gone),
            )
        }
        // The mockup's centre transport exists, and the top bar does NOT badge
        // live (review fix, 16 Nov): the single live mark rides the bottom
        // row, so the top bar carries no live pill and no broadcast subtitle.
        // (The subtitle branch still reads state.isLive — to return empty —
        // so only the painted strings are asserted here.)
        assertTrue("the centre play/pause button is missing", source.contains("CenterPlayPause("))
        assertFalse("TopBar still says Live — the mark lives in the bottom row", topBar.contains("R.string.player_live"))
        assertFalse("TopBar still prints a broadcast subtitle", topBar.contains("player_live_broadcast"))
    }

    @Test
    fun `player review fixes stay in the player`() {
        // Player review (Nov 2026), items 1–3 + 5: the boost slider must not
        // offer the dead zone, volume/brightness drags must reset the
        // auto-hide timer, the chrome must fade instead of cutting, and the
        // speed pill must open the speed sheet.
        // D2, moved by P1-15 (audit B-18): the boost slider left the player
        // entirely — the volume dialog's and the TracksSheet's sliders are
        // both gone. The one boost slider lives in Settings → Audio, and it
        // still stops at the offered ceiling. AudioBoost.MAX (200%) must not
        // appear as any slider's range: a thumb the viewer can drag into a
        // range nothing can act on is a promise broken on every drag.
        assertEquals(
            "the player must carry no boost slider anymore",
            0,
            source.split("valueRange = 0f..AudioBoostUi.MAX_OFFERED_LEVEL").size - 1,
        )
        val settings = File(screensDir, "SettingsScreen.kt").readText()
        assertEquals(
            "the one boost slider, in Settings Audio, must stop at the offered ceiling",
            1,
            settings.split("valueRange = 0f..AudioBoostUi.MAX_OFFERED_LEVEL").size - 1,
        )
        assertFalse(
            "a boost slider still offers the dead zone past the ceiling",
            source.contains("valueRange = 0f..AudioBoost.MAX"),
        )
        assertFalse(
            "the Settings boost slider still offers the dead zone past the ceiling",
            settings.contains("valueRange = 0f..AudioBoost.MAX"),
        )
        // D6: the volume and brightness drags bump interactTick, exactly like
        // the tap and the scrub callbacks do — otherwise the chrome hides
        // mid-gesture.
        val brightnessStart = source.indexOf("onBrightness = { frac ->")
        val volumeStart = source.indexOf("onVolume = { frac ->", brightnessStart)
        val scrubStart = source.indexOf("onScrubPreview", volumeStart)
        assertTrue(
            "GestureOverlay callbacks missing",
            brightnessStart >= 0 && volumeStart > brightnessStart && scrubStart > volumeStart,
        )
        val brightness = source.substring(brightnessStart, volumeStart)
        val volume = source.substring(volumeStart, scrubStart)
        assertTrue(
            "a brightness drag must reset the auto-hide timer",
            brightness.contains("interactTick = System.nanoTime()"),
        )
        assertTrue(
            "a volume drag must reset the auto-hide timer",
            volume.contains("interactTick = System.nanoTime()"),
        )
        // D1: the chrome fades. The section between the chrome comment and
        // the narration cards must wrap the top bar and the bottom group in
        // AnimatedVisibility with the ripple's fades — and must not keep the
        // old plain-conditional gate that removed the bars in one frame.
        val chromeStart = source.indexOf("// 5 — chrome.")
        val narrationStart = source.indexOf("// 6 — narration cards", chromeStart)
        assertTrue("chrome section bounds missing", chromeStart >= 0 && narrationStart > chromeStart)
        val chrome = source.substring(chromeStart, narrationStart)
        assertTrue("the top bar must fade in and out", chrome.contains("AnimatedVisibility("))
        assertTrue("the top bar must fade on chromeVisible", chrome.contains("visible = chromeVisible"))
        assertTrue("the bottom group must fade on controlsVisible", chrome.contains("visible = controlsVisible"))
        assertTrue("the chrome fade must use fadeIn", chrome.contains("fadeIn("))
        assertTrue("the chrome fade must use fadeOut", chrome.contains("fadeOut("))
        assertFalse(
            "the chrome still cuts on a plain conditional",
            chrome.contains("if (chromeVisible && !state.locked)"),
        )
        // D5, moved by P1 (player fixes) and again by P1-15: the speed pill
        // left the TopBar, then left the bottom row with the flexible slots
        // — the More sheet's Speed row is the single owner and the single
        // control now. The TopBar takes no speed callback and paints no
        // pill; neither does the row.
        val topBarStart = source.indexOf("internal fun TopBar(")
        val topBarEnd = source.indexOf("\n@Composable\ninternal fun CenterSpinner(", topBarStart)
        assertTrue("TopBar bounds missing", topBarStart >= 0 && topBarEnd > topBarStart)
        val topBar = source.substring(topBarStart, topBarEnd)
        assertFalse("the TopBar must not own speed anymore", topBar.contains("onShowSpeedSheet"))
        assertFalse("the TopBar must not paint a speed pill", topBar.contains("player_speed_short"))
        val bottomStart = source.indexOf("internal fun BottomControls(")
        assertTrue("BottomControls bounds missing", bottomStart >= 0)
        val bottom = source.substring(bottomStart)
        assertFalse("the row must not take the speed-sheet callback anymore", bottom.contains("onShowSpeedSheet"))
        assertFalse("the speed pill must be gone from the row", bottom.contains("SpeedPill("))
        assertFalse("the quality pill must be gone from the row", bottom.contains("QualityPill("))
        assertTrue(
            "the More sheet must own speed now",
            moreSheetBody().contains("vm.showSpeedSheet()"),
        )
    }

    @Test
    fun `a switch resets the keep request so the next title stays silent`() {
        // Player review D3: keepStatusVisible gates the Keeping status on
        // keepRequested (P1-16: the gate moved with the status into the
        // time row), and refreshKeepState is what keeps that flag honest — a new title,
        // episode or rendition has had no tap yet, so a stale true from the
        // previous title must not un-silence its automatic ahead-window keep.
        val vmSource = playerViewModel.readText()
        val refreshStart = vmSource.indexOf("fun refreshKeepState()")
        assertTrue("refreshKeepState is missing", refreshStart >= 0)
        val keepCurrentStart = vmSource.indexOf("fun keepCurrent()", refreshStart)
        assertTrue("keepCurrent must follow refreshKeepState", keepCurrentStart > refreshStart)
        val refresh = vmSource.substring(refreshStart, keepCurrentStart)
        assertTrue(
            "refreshKeepState must reset keepRequested before re-attaching",
            refresh.contains("it.copy(keepRequested = false)"),
        )
    }
}
