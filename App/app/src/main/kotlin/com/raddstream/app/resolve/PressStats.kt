package com.raddstream.app.resolve

import kotlin.math.sqrt

/** The three G-10 bars, in ms. T-1's baseline is the banked 0.021 s press. */
const val PRESS_T1_MINT_TO_302_MAX_MS = 100L
const val PRESS_T2_FIRST_FRAME_MAX_MS = 2_000L
const val PRESS_T3_FIRST_BYTE_MAX_MS = 2_000L

/** A group scores a repeat run when any press bar lands here (out of 20). */
const val PRESS_REPEAT_LOW = 15
const val PRESS_REPEAT_HIGH = 17

/** Wilson 95% score interval for [passes] of [trials]; z = 1.96. Clamped to [0, 1]. */
fun wilsonInterval(passes: Int, trials: Int, z: Double = 1.96): Pair<Double, Double> {
    if (trials <= 0) return 0.0 to 0.0
    val p = passes.toDouble() / trials
    val denom = 1.0 + z * z / trials
    val centre = (p + z * z / (2.0 * trials)) / denom
    val half = z * sqrt(p * (1.0 - p) / trials + z * z / (4.0 * trials * trials)) / denom
    return (centre - half).coerceIn(0.0, 1.0) to (centre + half).coerceIn(0.0, 1.0)
}

/** One bar's pass count plus its Wilson interval, as 0–1 fractions. */
data class BarScore(
    val passes: Int,
    val trials: Int,
    val wilsonLow: Double,
    val wilsonHigh: Double,
)

data class GroupScore(
    val group: String,
    val t1: BarScore,
    val t2: BarScore,
    val t3: BarScore,
)

fun passesT1(result: PressResult): Boolean =
    result.mintTo302Ms != null && result.mintTo302Ms <= PRESS_T1_MINT_TO_302_MAX_MS

fun passesT2(result: PressResult): Boolean =
    result.pressToFirstFrameMs != null && result.pressToFirstFrameMs <= PRESS_T2_FIRST_FRAME_MAX_MS

fun passesT3(result: PressResult): Boolean =
    result.tapToFirstByteMs != null && result.tapToFirstByteMs <= PRESS_T3_FIRST_BYTE_MAX_MS

private fun barScore(results: List<PressResult>, passes: (PressResult) -> Boolean): BarScore {
    val count = results.count(passes)
    val (low, high) = wilsonInterval(count, results.size)
    return BarScore(count, results.size, low, high)
}

/** Per-group (filepress, 1fichier) plus overall scores, for one attempt kind only. */
fun scorePressResults(results: List<PressResult>, kind: AttemptKind): List<GroupScore> {
    val scoped = results.filter { it.attemptKind == kind }
    val groups = listOf("filepress", "1fichier", "overall")
    return groups.map { group ->
        val groupResults = if (group == "overall") scoped else scoped.filter { it.service == group }
        GroupScore(
            group = group,
            t1 = barScore(groupResults, ::passesT1),
            t2 = barScore(groupResults, ::passesT2),
            t3 = barScore(groupResults, ::passesT3),
        )
    }
}

/**
 * Groups whose INITIAL run scored 15–17/20 on any press bar and have not had
 * their one repeat yet. A group below 15 fails outright; 18+ passes outright;
 * only the middle band earns a second run.
 */
fun repeatEligibleGroups(results: List<PressResult>, alreadyRepeated: Set<String>): List<String> {
    return scorePressResults(results, AttemptKind.NORMAL)
        .filter { it.group != "overall" && it.group !in alreadyRepeated }
        .filter { score ->
            listOf(score.t1, score.t2, score.t3).any { it.passes in PRESS_REPEAT_LOW..PRESS_REPEAT_HIGH }
        }
        .map { it.group }
}
