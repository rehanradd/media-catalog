package com.raddstream.app.viewmodel

import com.raddstream.app.network.models.OptionGroup
import com.raddstream.app.player.LadderForScope
import com.raddstream.app.player.QualityLadder
import com.raddstream.app.player.QualityOption
import com.raddstream.app.player.StreamCategory
import com.raddstream.app.player.qualityCostPerHour

/**
 * 2-button detail page (Nov 2026): the pure decision layer behind the
 * Play / Download redesign. The detail page shows exactly two buttons —
 * ▶ Play and ⬇ Download — and every quality choice lives in one
 * [QualitySheetState] (one component, two modes). Nothing here touches
 * the network or a repository: the ViewModel computes the ladder, these
 * functions turn it into rows, labels and decisions, and the screen
 * paints them. Pure so TwoButtonDetailTest pins every branch without a
 * ViewModel.
 *
 * Rules the rows must never break (report §6.5, §8):
 * - never show a host name, site name, URL, codec, source tag, rank
 *   score, handle, or HLS/m3u8/bitrate/cache/resolve jargon;
 * - never invent a size or a download time — unknown means the line is
 *   omitted, never guessed;
 * - VIP rows are shown greyed and locked, never hidden;
 * - a dead band is greyed and not tappable, never a row that fails;
 * - exactly one Recommended row per sheet.
 */

/** Best-first band order for the sheet. Unknown bands keep server order, last. */
internal val SHEET_BAND_ORDER = listOf("2160p", "1080p", "720p", "480p", "sd", "unspecified")

/** Number + plain word, never a number alone and never a word alone. */
fun bandDisplayName(band: String): String = when (band.trim().lowercase()) {
    "2160p", "4k", "uhd" -> "4K Ultra HD"
    "1080p" -> "1080p Full HD"
    "720p" -> "720p HD"
    "480p" -> "480p"
    "sd" -> "SD"
    else -> band
}

/** What this band is good for, in plain words. Null when the band is unknown. */
fun bestForLine(band: String): String? = when (band.trim().lowercase()) {
    "2160p", "4k", "uhd" -> "Best for TV"
    "1080p" -> "Sharp on big screens"
    "720p" -> "Best for phones"
    "480p" -> "Good on slow data"
    "sd" -> "Saves the most space"
    else -> null
}

/** True when this rung's winner is an HLS playlist stream (a manifest, not a file). */
fun isHlsOption(option: QualityOption): Boolean =
    option.streamCategory?.kind == StreamCategory.Kind.INSTANT_STREAM

enum class QualitySheetMode {
    PLAY,
    DOWNLOAD,

    /**
     * Audit Detail #1 (Nov 2026): pick-only — the Video row opens the
     * sheet to CHOOSE a quality, not to start anything. Rows tap to
     * select the band (Auto included) and close; nothing plays, nothing
     * saves. The rows are the PLAY rows (Auto leads, same locked/dead
     * rules); the Recommended marker is the current selection.
     */
    BROWSE,
}

enum class SeriesDownloadKind { NEXT_EPISODE, WHOLE_SEASON }

/** One of the two top rows of a series Download sheet. */
data class SeriesDownloadRow(
    val kind: SeriesDownloadKind,
    val label: String,
    val detail: String,
)

/** One episode of a batch download: the band is chosen once, for all of them. */
data class BatchEpisodeTarget(
    val season: Int,
    val episode: Int,
)

/** One row of the season screen's "Download an episode" sheet. */
data class EpisodeDownloadRow(
    val season: Int,
    val episode: Int,
    /** The size the server named for this episode's best copy — null when it named none. */
    val sizeBytes: Long?,
    /** True when this episode's best copy is already on this device. */
    val downloaded: Boolean,
)

/**
 * Everything the Quality Sheet needs, as one state. [ladder] is the
 * scope the sheet is about — the whole work for a movie, one episode
 * for a series. [nextEpisodeRow] / [wholeSeasonRow] are the series
 * Download top rows; [batchEpisodes] is set only for a batch download,
 * where the band is picked once for every episode in the list.
 */
data class QualitySheetState(
    val mode: QualitySheetMode,
    val ladder: LadderForScope,
    /** The row the sheet opens pre-selected: AUTO in PLAY, the recommended band in DOWNLOAD. */
    val recommendedBand: String?,
    /** Series context: the episode this sheet is about. Null for a movie / whole work. */
    val season: Int? = null,
    val episode: Int? = null,
    val nextEpisodeRow: SeriesDownloadRow? = null,
    val wholeSeasonRow: SeriesDownloadRow? = null,
    val wholeSeasonPlan: SeasonKeepPlan? = null,
    val wholeSeasonNumber: Int? = null,
    val batchEpisodes: List<BatchEpisodeTarget>? = null,
    /** Batch download (Phase 1): the bands every ticked episode has a copy in, best first. */
    val batchBands: List<String>? = null,
    /**
     * The quality rows, computed when the sheet opens from the ladder,
     * the mode and the viewer's entitlement — empty for series and
     * batch sheets, which render their own rows instead.
     */
    val rows: List<QualitySheetRow> = emptyList(),
    /** The title the sheet is about, for its subtitle. */
    val title: String? = null,
)

/** One row of the Quality Sheet, already decided — the screen only paints it. */
data class QualitySheetRow(
    val band: String,
    val title: String,
    /** DOWNLOAD only: the measured size, or null when the server never named one. */
    val sizeLine: String?,
    /** PLAY only: the app's own guide cost per hour, or null when the table has none. */
    val dataPerHour: String?,
    val bestFor: String?,
    /** "· 2 more copies if this one fails" — the safety net, never a count to pick from. */
    val copiesNote: String?,
    val isAuto: Boolean,
    val isRecommended: Boolean,
    val isLocked: Boolean,
    /** No winner at all: greyed, "No working link", not tappable. */
    val isDead: Boolean,
    val isHls: Boolean,
    val isProviderPage: Boolean,
    /** DOWNLOAD only, HLS bands: "Save for offline" instead of a size. */
    val downloadNote: String?,
    val option: QualityOption?,
)

/**
 * The rows of one Quality Sheet, best first. Auto leads in PLAY and
 * BROWSE; DOWNLOAD has no Auto row — a download is one fixed file. A
 * locked row is shown greyed (never hidden); a dead row is shown greyed
 * and not tappable; a provider-page row opens the provider's page.
 * In BROWSE the Recommended marker is the current selection
 * ([selectedBand], AUTO when nothing is picked); in PLAY it is Auto,
 * in DOWNLOAD the recommended band.
 */
fun sheetRowsFor(
    ladder: LadderForScope,
    mode: QualitySheetMode,
    premiumAccess: Boolean,
    freeQualityCap: String,
    selectedBand: String? = null,
): List<QualitySheetRow> {
    val recommended = recommendedBandFor(ladder, mode, premiumAccess, freeQualityCap, selectedBand)
    val rows = mutableListOf<QualitySheetRow>()
    if ((mode == QualitySheetMode.PLAY || mode == QualitySheetMode.BROWSE) && ladder.autoOption != null) {
        rows += QualitySheetRow(
            band = LadderForScope.AUTO,
            title = "Auto",
            sizeLine = null,
            dataPerHour = null,
            bestFor = "Best picture your connection can handle. Changes by itself.",
            copiesNote = null,
            isAuto = true,
            isRecommended = recommended == LadderForScope.AUTO,
            isLocked = false,
            isDead = false,
            isHls = false,
            isProviderPage = false,
            downloadNote = null,
            option = null,
        )
    }
    val ordered = ladder.options.sortedWith(
        compareBy { option ->
            val index = SHEET_BAND_ORDER.indexOf(option.band.trim().lowercase())
            if (index < 0) Int.MAX_VALUE else index
        },
    )
    for (option in ordered) {
        val locked = !VipQualityGate.allowsBand(option.band, premiumAccess, freeQualityCap) ||
            (!premiumAccess && VipQualityGate.isPremiumLabel(option.title, freeQualityCap))
        val dead = option.streamId.isBlank()
        val hls = isHlsOption(option)
        rows += QualitySheetRow(
            band = option.band,
            title = bandDisplayName(option.band),
            sizeLine = if (mode == QualitySheetMode.DOWNLOAD && !hls) option.sizeLine else null,
            dataPerHour = if (mode == QualitySheetMode.PLAY || mode == QualitySheetMode.BROWSE) qualityCostPerHour(option.band) else null,
            bestFor = bestForLine(option.band),
            copiesNote = if (option.copies > 1) {
                val extra = option.copies - 1
                "· $extra more ${if (extra == 1) "copy" else "copies"} if this one fails"
            } else {
                null
            },
            isAuto = false,
            isRecommended = (mode == QualitySheetMode.DOWNLOAD || mode == QualitySheetMode.BROWSE) && option.band == recommended,
            isLocked = locked,
            isDead = dead,
            isHls = hls,
            isProviderPage = option.isProviderPage,
            downloadNote = if (mode == QualitySheetMode.DOWNLOAD && hls) "Save for offline" else null,
            option = option,
        )
    }
    return rows
}

/**
 * The row the sheet opens pre-selected. PLAY: Auto (the ladder's own
 * pointer, or its best playable rung). DOWNLOAD: 720p when a free copy
 * exists at that band, else the best free band that does. BROWSE: the
 * current selection when it still names a rung in this scope, else
 * Auto. Null when the ladder has nothing at all.
 */
fun recommendedBandFor(
    ladder: LadderForScope,
    mode: QualitySheetMode,
    premiumAccess: Boolean,
    freeQualityCap: String,
    selectedBand: String? = null,
): String? {
    if (ladder.isEmpty) return null
    if (mode == QualitySheetMode.PLAY) return LadderForScope.AUTO
    if (mode == QualitySheetMode.BROWSE) {
        return selectedBand?.takeIf { band -> ladder.options.any { it.band == band } }
            ?: LadderForScope.AUTO
    }
    val free = ladder.options.filter { option ->
        VipQualityGate.allowsBand(option.band, premiumAccess, freeQualityCap) &&
            !( !premiumAccess && VipQualityGate.isPremiumLabel(option.title, freeQualityCap) )
    }
    val pool = free.ifEmpty { ladder.options }
    return pool.firstOrNull { it.band.trim().lowercase() == "720p" }?.band
        ?: pool.firstOrNull { it.playsHere }?.band
        ?: pool.firstOrNull()?.band
}

/**
 * Audit Detail #1 (Nov 2026): the Video row's value for one scope's
 * ladder — the band the viewer picked, or what Auto reaches when
 * nothing is picked. Null when the scope has nothing playable to
 * choose from, so the row hides instead of offering a choice that
 * cannot play. A picked band that no longer plays here falls back to
 * Auto rather than stranding the row on a dead rung.
 */
fun videoQualityTextFor(ladder: LadderForScope, selectedBand: String?): String? {
    val playable = ladder.options.filter { it.playsHere && it.streamId.isNotBlank() && !it.isProviderPage }
    if (playable.isEmpty()) return null
    val picked = selectedBand?.takeIf { it != LadderForScope.AUTO }
        ?.let { band -> playable.firstOrNull { it.band == band } }
    if (picked != null) return picked.band
    val best = ladder.effectiveAutoBand ?: playable.first().band
    return "Auto · up to $best"
}

/**
 * Audit Detail #2 (Nov 2026): the Download button's label — the cost
 * before the tap, never after. The band is the viewer's pick when one
 * is set, else the sheet's recommended band; the size is the server's
 * own size line, omitted when it named none — never invented. A series
 * names its episode, so the tap's target is unambiguous.
 */
fun downloadLabelFor(
    band: String?,
    sizeLine: String?,
    season: Int?,
    episode: Int?,
    downloaded: Boolean,
): String {
    if (downloaded) return "✓ Downloaded"
    return buildString {
        append("Download")
        if (season != null && episode != null) append(" S$season:E$episode")
        if (band != null) append(" · $band")
        if (sizeLine != null) append(" · $sizeLine")
    }
}

/**
 * The ladder for one episode's scope, gated for this viewer. This is
 * the P1 fix (report §2, P1): the detail screen builds its ladder as
 * the whole work's scope, which is EMPTY for a multi-episode series —
 * so a series episode staged no ladder at all and the player offered
 * no quality switch. Resolving the scope per episode is what arms it.
 */
fun ladderForEpisodeScope(
    optionGroups: List<OptionGroup>,
    season: Int,
    episode: Int,
    premiumAccess: Boolean,
    freeQualityCap: String,
): LadderForScope = VipQualityGate.gate(
    QualityLadder.forNumberedScope(optionGroups, season, episode),
    premiumAccess,
    freeQualityCap,
)

/**
 * The next episode to watch: the first playable, unwatched episode in
 * season/episode order. When every episode is watched, the first
 * playable episode (a re-watch from the start). Null when nothing in
 * the work can play. [season] narrows the search to one season (the
 * season screen's Play Season); null searches the whole work.
 */
fun nextUnwatchedEpisode(
    layout: ReleaseLayout?,
    progress: Map<Pair<Int, Int>, EpisodeProgress>,
    season: Int? = null,
): Pair<Int, Int>? {
    if (layout == null) return null
    val seasons = if (season != null) {
        layout.seasons.filter { it.number == season }
    } else {
        layout.seasons
    }
    var firstPlayable: Pair<Int, Int>? = null
    for (seasonGroup in seasons) {
        for (episode in seasonGroup.episodes) {
            val playable = episode.prints.any { it.playableHere || it.opensProviderPage }
            if (!playable) continue
            val scope = seasonGroup.number to episode.numberWithinSeason
            if (firstPlayable == null) firstPlayable = scope
            if (progress[scope]?.watched != true) return scope
        }
    }
    return firstPlayable
}

/**
 * The free-space verdict for a device-file download. The file needs its
 * own size plus a 500 MB floor; when the size is unknown, only the
 * floor is required. The check is the gate — the message names the
 * file's size (or the floor), never the floor-adjusted total.
 */
const val DOWNLOAD_SPACE_FLOOR_BYTES: Long = 500L * 1024L * 1024L

sealed interface FreeSpaceVerdict {
    data object Ok : FreeSpaceVerdict
    data class NotEnough(val neededBytes: Long, val freeBytes: Long) : FreeSpaceVerdict
}

fun freeSpaceVerdictFor(freeBytes: Long, sizeBytes: Long?): FreeSpaceVerdict {
    val size = sizeBytes?.takeIf { it > 0 }
    val required = (size ?: 0L) + DOWNLOAD_SPACE_FLOOR_BYTES
    if (freeBytes >= required) return FreeSpaceVerdict.Ok
    return FreeSpaceVerdict.NotEnough(size ?: DOWNLOAD_SPACE_FLOOR_BYTES, freeBytes)
}

/** "Not enough space. This needs 1.2 GB, you have 800 MB free." — plain words, real numbers. */
fun notEnoughSpaceMessage(neededBytes: Long, freeBytes: Long): String =
    "Not enough space. This needs ${QualityOption.formatBytes(neededBytes)}, you have ${QualityOption.formatBytes(freeBytes)} free."
