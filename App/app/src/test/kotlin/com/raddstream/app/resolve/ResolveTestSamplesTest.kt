package com.raddstream.app.resolve

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The baked sample asset: 20 filepress + 20 1fichier + 10 control, the
 * blocklist split 10/10 + 10/10 + 5/5, no duplicate link ids.
 */
class ResolveTestSamplesTest {

    private val samplesFile: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) return@lazy File(dir, "App/app/src/debug/assets/resolve_test_samples.json")
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val set: ResolveSampleSet by lazy {
        assertTrue("sample asset missing at ${samplesFile.path}", samplesFile.isFile)
        val parsed = ResolveSamples.parse(samplesFile.readText())
        assertNotNull("sample asset does not parse", parsed)
        parsed!!
    }

    @Test
    fun `counts are 20 filepress 20 1fichier 10 control`() {
        assertEquals(20, set.filepress.size)
        assertEquals(20, set.fichier.size)
        assertEquals(10, set.control.size)
        assertEquals(50, set.ordered().size)
    }

    @Test
    fun `run order is filepress then 1fichier then control`() {
        val ordered = set.ordered()
        assertTrue(ordered.take(20).all { it.service == "filepress" })
        assertTrue(ordered.drop(20).take(20).all { it.service == "1fichier" })
        assertTrue(ordered.drop(40).all { it.service == "filemoon" || it.service == "rpmplay" })
    }

    @Test
    fun `bridge urls match their service host`() {
        for (sample in set.filepress) {
            val host = hostOf(sample.bridgeUrl)
            assertTrue("filepress $host", host == "filebee.xyz" || host?.endsWith("filepress.lat") == true)
        }
        for (sample in set.fichier) {
            assertEquals("1fichier.com", hostOf(sample.bridgeUrl))
        }
        for (sample in set.control) {
            val host = hostOf(sample.bridgeUrl)
            assertTrue("control $host", host == "filemoon.sx" || host == "movieshub.rpmplay.xyz")
        }
    }

    @Test
    fun `no duplicate link ids`() {
        val ids = set.ordered().map { it.linkId }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `blocklist split is 10-10 plus 10-10 plus 5-5`() {
        assertEquals(10, set.filepress.count { it.blocklist })
        assertEquals(10, set.filepress.count { !it.blocklist })
        assertEquals(10, set.fichier.count { it.blocklist })
        assertEquals(10, set.fichier.count { !it.blocklist })
        assertEquals(5, set.control.count { it.blocklist })
        assertEquals(5, set.control.count { !it.blocklist })
    }

    @Test
    fun `baked blocklist has 20 domains`() {
        assertEquals(20, set.blocklist.size)
        assertTrue(set.blocklist.all { it.isNotBlank() && !it.contains("://") })
    }

    private fun hostOf(url: String): String? {
        return try {
            java.net.URI(url).host?.lowercase()
        } catch (_: Exception) {
            null
        }
    }
}
