package com.raddstream.app.brand

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.net.URI
import java.util.Base64
import java.util.Properties

/**
 * CleartextFallbackTest — guards the one named-host cleartext exception in the
 * release network policy, read straight from /brand.properties the way
 * TlsPinsTest does: plain JUnit4 on the JVM, no Robolectric, no Android
 * runtime, no generated classes.
 *
 * The generated `network_security_config.xml` is produced by
 * `brandNetworkPolicyXml()` in `App/app/build.gradle.kts` from exactly these
 * keys, so pinning the keys pins the policy: the fallback host is the
 * plain-HTTP server IP and nothing else, the release switch for the default
 * host stays off, and the HTTPS default plus its pins are unchanged. The
 * generator's own output is also printed by CI on the `[brand]` log line
 * (`cleartext=[...]`), which is the second check on the real XML.
 *
 * It fails when:
 *  1. the fallback address stops being the plain-HTTP server IP (the
 *     deadman switch hands this address to the app, and the release policy
 *     must permit exactly it);
 *  2. the release cleartext switch is turned on for the default host, or any
 *     extra cleartext host is added — either would widen the exception past
 *     the one host the owner allowed;
 *  3. the shipped default server stops being the HTTPS catalog, or the pin
 *     list changes.
 */
class CleartextFallbackTest {

    private val repoRoot: File by lazy {
        var dir: File? = File("").absoluteFile
        while (dir != null) {
            if (File(dir, "brand.properties").isFile) return@lazy dir
            dir = dir.parentFile
        }
        error("brand.properties not found above ${File("").absolutePath}")
    }

    private val brandProps: Properties by lazy {
        Properties().apply { FileInputStream(File(repoRoot, "brand.properties")).use { load(it) } }
    }

    private fun brandValue(key: String): String = (brandProps.getProperty(key) ?: "").trim()

    @Test
    fun `the fallback address is the plain-HTTP server IP`() {
        assertEquals(
            "http://92.4.95.252",
            brandValue("network.fallback_server_url"),
        )
        val uri = URI(brandValue("network.fallback_server_url"))
        assertEquals("http", uri.scheme?.lowercase())
        assertEquals("92.4.95.252", uri.host)
        assertTrue(
            "the fallback host must be a bare IPv4 address — no port, path, or wildcard",
            uri.host.matches(Regex("\\d+\\.\\d+\\.\\d+\\.\\d+")),
        )
    }

    @Test
    fun `release cleartext stays off for every host but the fallback IP`() {
        assertEquals(
            "false",
            brandValue("network.release_cleartext_for_default_host").lowercase(),
        )
        val defaultHost = URI(brandValue("network.default_server_url")).host
        assertTrue(
            "the default host must never be the fallback host — cleartext is " +
                "for the fallback IP only",
            defaultHost != "92.4.95.252",
        )
        assertEquals(
            "no extra cleartext hosts: the exception is the fallback IP only",
            "",
            brandValue("network.cleartext.extra_hosts"),
        )
    }

    @Test
    fun `the shipped default server is still the HTTPS catalog with its pins`() {
        assertEquals(
            "https://raddstream.duckdns.org",
            brandValue("network.default_server_url"),
        )
        val pins = brandValue("network.tls_pins").split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }
        assertEquals(3, pins.size)
        pins.forEach { pin ->
            assertEquals(
                "a pin that is not a 32-byte SHA-256 digest pins nothing: $pin",
                32, Base64.getDecoder().decode(pin).size,
            )
        }
    }
}
