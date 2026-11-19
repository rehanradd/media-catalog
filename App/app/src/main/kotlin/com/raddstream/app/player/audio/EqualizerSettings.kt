package com.raddstream.app.player.audio

/**
 * The equalizer's bands, presets and stored state — pure rules, no Android,
 * no Media3, unit-testable on the JVM.
 *
 * Five bands, the shape every phone EQ uses: a low shelf for the bass, three
 * peaking bands for the body and the presence, a high shelf for the air.
 * Gains are dB, −12..+12; a preset is just a named set of five gains, and
 * any slider move off a preset makes the state Custom.
 */
enum class EqBandType { LOW_SHELF, PEAKING, HIGH_SHELF }

data class EqBand(val frequencyHz: Int, val type: EqBandType)

val EQ_BANDS: List<EqBand> = listOf(
    EqBand(60, EqBandType.LOW_SHELF),
    EqBand(230, EqBandType.PEAKING),
    EqBand(910, EqBandType.PEAKING),
    EqBand(3600, EqBandType.PEAKING),
    EqBand(14000, EqBandType.HIGH_SHELF),
)

const val EQ_BAND_COUNT: Int = 5

/** Peaking Q, the cookbook's EE definition (see [BiquadDesign.peaking]). */
const val PEAKING_Q: Float = 1.0f

const val EQ_GAIN_MIN_DB: Float = -12f
const val EQ_GAIN_MAX_DB: Float = 12f

fun clampEqGain(db: Float): Float =
    if (!db.isFinite()) 0f else db.coerceIn(EQ_GAIN_MIN_DB, EQ_GAIN_MAX_DB)

/** A named set of five band gains, in [EQ_BANDS] order. */
data class EqPreset(val id: String, val gainsDb: List<Float>) {
    init {
        require(gainsDb.size == EQ_BAND_COUNT) { "a preset holds $EQ_BAND_COUNT gains" }
    }
}

object EqPresets {
    val FLAT = EqPreset("flat", listOf(0f, 0f, 0f, 0f, 0f))
    val NORMAL = EqPreset("normal", listOf(1f, 0f, 0f, 0f, 1f))
    val BASS_BOOST = EqPreset("bass", listOf(6f, 4f, 1f, 0f, 0f))
    val TREBLE_BOOST = EqPreset("treble", listOf(0f, 0f, 1f, 4f, 6f))
    val VOCAL = EqPreset("vocal", listOf(-3f, 0f, 4f, 3f, -2f))

    val ALL: List<EqPreset> = listOf(FLAT, NORMAL, BASS_BOOST, TREBLE_BOOST, VOCAL)

    fun byId(id: String): EqPreset? = ALL.firstOrNull { it.id == id }

    /** The preset these gains are, or null when no preset matches them. */
    fun matching(gainsDb: List<Float>): EqPreset? =
        ALL.firstOrNull { it.gainsDb == gainsDb }
}

/**
 * The whole equalizer state: on/off, which preset the gains came from, and
 * the five gains themselves. `presetId` is "custom" whenever the gains match
 * no preset — the sheet shows that honestly instead of naming a preset the
 * sliders no longer describe.
 */
data class EqState(
    val enabled: Boolean = false,
    val presetId: String = EqPresets.FLAT.id,
    val gainsDb: List<Float> = EqPresets.FLAT.gainsDb,
) {
    init {
        require(gainsDb.size == EQ_BAND_COUNT) { "EQ state holds $EQ_BAND_COUNT gains" }
    }

    fun withPreset(preset: EqPreset): EqState =
        copy(enabled = true, presetId = preset.id, gainsDb = preset.gainsDb)

    fun withBandGain(index: Int, gainDb: Float): EqState {
        require(index in 0 until EQ_BAND_COUNT) { "band $index is outside 0..${EQ_BAND_COUNT - 1}" }
        val next = gainsDb.toMutableList()
        next[index] = clampEqGain(gainDb)
        val preset = EqPresets.matching(next)
        return copy(presetId = preset?.id ?: CUSTOM_ID, gainsDb = next)
    }

    /** One DataStore string: `on|off:presetId:g1,g2,g3,g4,g5`. */
    fun serialize(): String {
        val onOff = if (enabled) "on" else "off"
        return "$onOff:$presetId:${gainsDb.joinToString(",")}"
    }

    companion object {
        const val CUSTOM_ID: String = "custom"

        val DEFAULT: EqState = EqState()

        /**
         * Tolerant read: anything unparseable (a null, a half-written value,
         * an unknown preset) falls back to [DEFAULT] rather than crashing a
         * settings load or inventing a mix.
         */
        fun parse(raw: String?): EqState {
            if (raw.isNullOrBlank()) return DEFAULT
            val parts = raw.split(":")
            if (parts.size != 3) return DEFAULT
            val enabled = when (parts[0]) {
                "on" -> true
                "off" -> false
                else -> return DEFAULT
            }
            val gains = parts[2].split(",").mapNotNull { it.toFloatOrNull() }
            if (gains.size != EQ_BAND_COUNT) return DEFAULT
            val clamped = gains.map { clampEqGain(it) }
            // The gains are the truth: a stored id the app never wrote (or a
            // preset the gains happen to equal) resolves to what the mix
            // actually is, so serialize(parse(x)) == x for every state the
            // app itself writes.
            val presetId = when {
                parts[1] == CUSTOM_ID -> CUSTOM_ID
                EqPresets.byId(parts[1]) != null -> parts[1]
                else -> EqPresets.matching(clamped)?.id ?: CUSTOM_ID
            }
            return EqState(enabled, presetId, clamped)
        }
    }
}

/**
 * The split of one requested total level (0..2) into the two handles the
 * platform actually offers: [playerGain] is the value legal for
 * `Player.setVolume` (always 0..1); [dspGain] is the software excess the
 * enhancement processor applies (always 1..2). The product is exactly the
 * requested total — a viewer at 60% who asks for 150% gets 90% from the
 * player and the remaining 1.5× from the processor, never a clamped lie.
 */
data class GainSplit(val playerGain: Float, val dspGain: Float)

fun splitTotalGain(total: Float): GainSplit {
    val safe = if (!total.isFinite()) 1f else total.coerceIn(0f, 2f)
    if (safe == 0f) return GainSplit(0f, 1f)
    val playerGain = safe.coerceAtMost(1f)
    return GainSplit(playerGain, safe / playerGain)
}
