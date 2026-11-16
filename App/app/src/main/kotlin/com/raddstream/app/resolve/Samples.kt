package com.raddstream.app.resolve

import android.content.Context
import com.raddstream.app.resolve.MiniJson.array
import com.raddstream.app.resolve.MiniJson.bool
import com.raddstream.app.resolve.MiniJson.long
import com.raddstream.app.resolve.MiniJson.obj
import com.raddstream.app.resolve.MiniJson.string
import java.io.InputStream

/** The parsed sample asset, grouped as the asset groups it. */
data class ResolveSampleSet(
    val filepress: List<ResolveSample>,
    val fichier: List<ResolveSample>,
    val control: List<ResolveSample>,
    val blocklist: List<String>,
) {
    /** Run order: filepress first, then 1fichier, then control — never parallel. */
    fun ordered(): List<ResolveSample> = filepress + fichier + control

    fun groupOf(sample: ResolveSample): String = when {
        filepress.contains(sample) -> "filepress"
        fichier.contains(sample) -> "1fichier"
        else -> "control"
    }
}

object ResolveSamples {

    const val ASSET_NAME = "resolve_test_samples.json"

    /** Loads the debug-asset sample set. Null when the asset is missing or malformed. */
    fun loadFromAssets(context: Context): ResolveSampleSet? {
        return try {
            context.assets.open(ASSET_NAME).use { load(it) }
        } catch (_: Exception) {
            null
        }
    }

    fun load(stream: InputStream): ResolveSampleSet? {
        val text = try {
            stream.bufferedReader(Charsets.UTF_8).readText()
        } catch (_: Exception) {
            return null
        }
        return parse(text)
    }

    fun parse(text: String): ResolveSampleSet? {
        val root = MiniJson.parse(text) as? JsonValue.Obj ?: return null
        val filepress = root.array("filepress")?.mapNotNull { parseSample(it) } ?: return null
        val fichier = root.array("1fichier")?.mapNotNull { parseSample(it) } ?: return null
        val control = root.array("control")?.mapNotNull { parseSample(it) } ?: return null
        val blocklist = root.array("blocklist")
            ?.mapNotNull { (it as? JsonValue.Str)?.value }
            ?: return null
        return ResolveSampleSet(filepress, fichier, control, blocklist)
    }

    private fun parseSample(value: JsonValue): ResolveSample? {
        val linkId = value.long("link_id") ?: return null
        val bridgeUrl = value.string("bridge_url") ?: return null
        val service = value.string("service") ?: return null
        val title = value.string("title") ?: return null
        val quality = value.string("quality") ?: return null
        val postId = value.long("post_id") ?: return null
        val blocklist = value.bool("blocklist") ?: return null
        val year = when (val yearValue = value.obj("year")) {
            is JsonValue.Num -> yearValue.raw.toIntOrNull()
            else -> null
        }
        if (bridgeUrl.isBlank() || service.isBlank()) return null
        return ResolveSample(linkId, bridgeUrl, service, title, year, quality, postId, blocklist)
    }
}
