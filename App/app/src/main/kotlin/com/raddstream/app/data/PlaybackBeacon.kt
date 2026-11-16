package com.raddstream.app.data

import android.content.Context
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken

/**
 * One finished playback session, as the phone reports it — the beacon the
 * host-ranking work reads.
 *
 * The five fields the ranking needs, and nothing else: the session id (a
 * random UUID, never an install id), the opaque play handle (resolved to a
 * host server-side from its own ledger, then discarded), the rung it played
 * at, what the player measured while it played, how it ended, and the coarse
 * network class. No title, no query, no position, no device.
 *
 * Two deliberate absences. There is no per-segment list: the beacon is one
 * summary per session, batched onto the next progress write, never one POST
 * per segment. And there is no `mtp` field: the server derives measured
 * throughput from `bytes` over `loadMs` — the components, not a ratio —
 * because a mean of ratios is not the ratio of sums. `br` is the rung's
 * nominal bitrate (the player's own table), validated server-side and not
 * stored today; the band already names the rung, and the ranking slice adds
 * a column when it needs measured-vs-nominal.
 *
 * `ttfbMs` is the first media load's wall time — the closest Media3 1.3.1
 * exposes to time-to-first-byte through AnalyticsListener. The true TTFB
 * (headers received) is not observable there, so the field is named for
 * what it measures and the server buckets it rather than trusting it to
 * the millisecond. Zero means no media load happened (abandoned before the
 * player moved a byte), which is the absence of a measurement, not a fast
 * load — the server keeps it out of the fast bucket.
 */
data class PlaybackBeacon(
    /** Random per-session id. Never an install id, never stored server-side. */
    @SerializedName("sid") val sid: String,
    /** The opaque play handle. Resolved server-side, never stored. */
    @SerializedName("stream_id") val streamId: String,
    /** The rung it played at: "480p", "1080p", … */
    @SerializedName("band") val band: String,
    /** The rung's nominal bitrate in bps, from the player's own table. */
    @SerializedName("br") val brBps: Long,
    /** Total media bytes the player loaded in this session. */
    @SerializedName("bytes") val bytesTotal: Long,
    /** Total wall time of those loads, in milliseconds. */
    @SerializedName("load_ms") val loadMsTotal: Long,
    /** First media load's wall time in milliseconds; 0 when none happened. */
    @SerializedName("ttfb_ms") val ttfbMs: Long,
    /** Rebuffer stalls after the first frame. */
    @SerializedName("stalls") val stalls: Int,
    /** Total milliseconds spent rebuffering after the first frame. */
    @SerializedName("rebuffer_ms") val rebufferMs: Long,
    /** "played" | "failed" | "abandoned" — see [PlaybackBeaconOutcomes]. */
    @SerializedName("outcome") val outcome: String,
    /** Closed error class when it failed, else null. See [PlaybackBeaconErrors]. */
    @SerializedName("error") val error: String?,
    /** "wifi" | "mobile" | "none" — the coarse transport, never a carrier. */
    @SerializedName("net") val net: String,
) {
    /**
     * The store's form: Gson, not the framework's `org.json` — the framework
     * classes are stubs on the JVM, so a unit test that round-trips a beacon
     * would throw before it asserted anything. Gson is a real dependency
     * here, and the same object the sync layer sends.
     */
    fun toJsonString(): String = BEACON_GSON.toJson(this)

    companion object {
        private val BEACON_GSON = Gson()

        /**
         * A stored entry that no longer decodes — or decodes to a shape
         * missing a required field — is null, never trusted. Gson builds
         * with unsafe allocation, so a `{}` decodes "successfully" to an
         * object whose non-null fields are null at runtime; the validity
         * check below is what catches that, and its null dereference is
         * caught by the same `runCatching`.
         */
        fun fromJsonString(raw: String): PlaybackBeacon? = runCatching {
            BEACON_GSON.fromJson(raw, PlaybackBeacon::class.java)?.takeIf(::isValid)
        }.getOrNull()

        fun listToJsonString(beacons: List<PlaybackBeacon>): String =
            BEACON_GSON.toJson(beacons)

        fun listFromJsonString(raw: String): List<PlaybackBeacon> = runCatching {
            val type = object : TypeToken<List<PlaybackBeacon>>() {}.type
            BEACON_GSON.fromJson<List<PlaybackBeacon>>(raw, type)
                ?.filter(::isValid) ?: emptyList()
        }.getOrDefault(emptyList())

        private fun isValid(beacon: PlaybackBeacon): Boolean =
            beacon.sid.isNotBlank() && beacon.streamId.isNotBlank() &&
                beacon.band.isNotBlank() && beacon.outcome.isNotBlank() &&
                beacon.net.isNotBlank()
    }
}

/** The outcomes a session may end in. "played" means a first frame landed. */
object PlaybackBeaconOutcomes {
    const val PLAYED = "played"
    const val FAILED = "failed"
    const val ABANDONED = "abandoned"
}

/** The closed error classes a beacon may carry. Only network/timeout blame the host. */
object PlaybackBeaconErrors {
    const val NETWORK = "network"
    const val TIMEOUT = "timeout"
    const val DECODE = "decode"
    const val DRM = "drm"
    const val UNKNOWN = "unknown"
}

/** The network classes a beacon may name. */
object PlaybackBeaconNetworks {
    const val WIFI = "wifi"
    const val MOBILE = "mobile"
    const val NONE = "none"
}

/**
 * The phone-side half of the beacon: finished sessions wait here for the
 * next progress write, exactly like the usage counters ride it.
 *
 * A queue, not a counter: one entry per session, capped at [MAX_PENDING] —
 * the server caps a submission at 20, so a longer queue would only ever
 * shed its oldest entries on arrival. Recording is gated on the viewer's
 * analytics consent, the same gate the counters use: a declined or
 * unanswered consent records nothing, so a later "yes" never flushes
 * sessions from before the answer.
 *
 * Storage is SharedPreferences, not DataStore, for the same reason the
 * counters use it: the player calls [record] from threads that cannot
 * suspend, and the write is a synchronous commit. The values are one small
 * JSON array, never identifiers beyond the random session id, so the
 * simpler store costs nothing in privacy. [acknowledge] removes exactly the
 * sessions a report carried, so a session recorded between the snapshot and
 * the acknowledgement survives instead of being eaten.
 */
class PlaybackBeaconQueue(
    private val store: PlaybackBeaconStore,
    private val gate: () -> Boolean,
) {
    companion object {
        const val MAX_PENDING = 20
    }

    fun record(beacon: PlaybackBeacon) {
        if (!gate()) return
        store.enqueue(beacon)
    }

    fun takePending(): List<PlaybackBeacon> = store.snapshot()

    fun acknowledge(sent: List<PlaybackBeacon>) {
        if (sent.isNotEmpty()) store.acknowledge(sent)
    }
}

/** Synchronous beacon storage: enqueue, read, remove-by-session. */
interface PlaybackBeaconStore {
    fun enqueue(beacon: PlaybackBeacon)
    fun snapshot(): List<PlaybackBeacon>
    fun acknowledge(sent: List<PlaybackBeacon>)
}

/**
 * The pending beacons, one JSON array in a private preferences file. Every
 * write is a synchronous `commit()`: the player records from threads that
 * cannot suspend, and a beacon is small enough that the disk write is not
 * a jank risk. A malformed entry on read is dropped, never allowed to wedge
 * the queue — a corrupt row costs one session, not the whole report.
 */
class SharedPreferencesPlaybackBeaconStore(context: Context) : PlaybackBeaconStore {
    private val prefs = context.getSharedPreferences(
        "radd_playback_beacons",
        Context.MODE_PRIVATE,
    )

    override fun enqueue(beacon: PlaybackBeacon) {
        val kept = (snapshot() + beacon)
            .takeLast(PlaybackBeaconQueue.MAX_PENDING)
        putAll(kept)
    }

    override fun snapshot(): List<PlaybackBeacon> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return PlaybackBeacon.listFromJsonString(raw)
    }

    override fun acknowledge(sent: List<PlaybackBeacon>) {
        val sentIds = sent.map { it.sid }.toSet()
        putAll(snapshot().filterNot { it.sid in sentIds })
    }

    private fun putAll(beacons: List<PlaybackBeacon>) {
        prefs.edit().putString(KEY, PlaybackBeacon.listToJsonString(beacons)).commit()
    }

    companion object {
        private const val KEY = "pending"
    }
}

/**
 * The process-wide holder for the beacon queue, mirroring [Telemetry]: one
 * volatile store, built once, read from the player service and the sync
 * repository. Nothing is recorded before [init] — and nothing is recorded
 * at all until the viewer said yes to analytics, because the queue's gate
 * reads [Telemetry.consentAccepted].
 */
object PlaybackBeacons {
    private var queue: PlaybackBeaconQueue? = null

    fun init(context: Context) {
        if (queue == null) {
            queue = PlaybackBeaconQueue(
                SharedPreferencesPlaybackBeaconStore(context),
                gate = { Telemetry.consentAccepted },
            )
        }
    }

    fun record(beacon: PlaybackBeacon) {
        queue?.record(beacon)
    }

    /** The pending beacons to ride the next progress report, if any. */
    fun takePending(): List<PlaybackBeacon> = queue?.takePending() ?: emptyList()

    /** Forget exactly the beacons a progress report carried, once it landed. */
    fun acknowledge(sent: List<PlaybackBeacon>) {
        queue?.acknowledge(sent)
    }
}
