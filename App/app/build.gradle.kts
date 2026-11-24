import java.io.ByteArrayInputStream
import java.io.File
import java.net.URI
import java.security.KeyStore
import java.util.Base64
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.kotlin.kapt)
    // Order matters: google-services after the Android plugin (it reads the
    // application id), crashlytics after google-services (it reads the
    // Firebase config the google-services plugin generates from
    // google-services.json). Both are apply-only here; versions live in the
    // catalog (libs.versions.toml).
    alias(libs.plugins.google.services)
    alias(libs.plugins.crashlytics)
    // Paparazzi 2.0.0-alpha02 (2026-11-23): JVM screenshot tests — renders real
    // Compose UI via Layoutlib in CI, no emulator. Test-only wiring; no main
    // source changes. Snapshots live in src/test/snapshots (recorded by
    // :app:recordPaparazziDebug, verified by :app:verifyPaparazziDebug).
    alias(libs.plugins.paparazzi)
    // Feel-instant S7: Baseline Profiles — the plugin that generates them
    // (the generator lives in :baselineprofile). Applied here so the
    // `baselineProfile` dependency configuration exists and the generated
    // profile ships inside the app.
    alias(libs.plugins.androidx.baselineprofile)
}

// ═════════════════════════════════════════════════════════════════════════════
//  BRAND — one file re-skins this app: <repo root>/brand.properties
// ═════════════════════════════════════════════════════════════════════════════
//  Read with java.util.Properties (no plugin, no dependency) and fanned out to:
//    • BuildConfig.BRAND_<KEY with '.' -> '_'>  (String, every key)
//    • resValue("color",  ...)  for every resources.colors.* key
//    • resValue("string", ...)  for the launcher label / wordmark strings
//    • manifestPlaceholders["brandScheme"] for the deep-link <data android:scheme>
//    • applicationId / namespace / versionName / versionCode / debug suffix
//    • build/generated/assets/brand/brand.json, merged into assets at build time
//      (Brand.kt reads that JSON at startup; it is generated, never hand-edited,
//       and lives under build/ so it can never be committed by accident)
//  The same file is read by Fast_Fetch/config.py with configparser. `[section]`
//  header lines load as empty-valued keys whose name starts with '[' — skipped.
val brandPropsFile = File(rootDir.parentFile, "brand.properties")
if (!brandPropsFile.exists()) {
    throw GradleException("brand.properties missing at $brandPropsFile — the app cannot be branded.")
}
val brandProps = Properties().apply { brandPropsFile.inputStream().use { load(it) } }
val brandKeys = brandProps.stringPropertyNames().filter { !it.startsWith("[") }.sorted()

fun brand(key: String, fallback: String): String =
    (brandProps.getProperty(key) ?: "").trim().ifEmpty { fallback }

fun brandInt(key: String, fallback: Int): Int = brand(key, "").toIntOrNull() ?: fallback

fun brandField(key: String): String = "BRAND_" + key.uppercase().replace('.', '_').replace('-', '_')

fun brandJsonEscape(value: String): String = buildString {
    for (ch in value) when (ch) {
        '\\' -> append("\\\\")
        '"' -> append("\\\"")
        '\n' -> append("\\n")
        '\r' -> append("\\r")
        '\t' -> append("\\t")
        else -> if (ch < ' ') append("\\u%04x".format(ch.code)) else append(ch)
    }
}

// Fail fast: a typo'd hex must break the build, not silently ship the old brand.
val brandHexPattern = Regex("^#([0-9A-Fa-f]{6}|[0-9A-Fa-f]{8})$")
brandKeys.filter { it.startsWith("colors.") || it.startsWith("resources.colors.") }.forEach { key ->
    val hex = brand(key, "")
    if (!brandHexPattern.matches(hex)) {
        throw GradleException("brand.properties $key='$hex' is not #RRGGBB or #AARRGGBB")
    }
}

val brandAssetsDir = File(layout.buildDirectory.get().asFile, "generated/assets/brand")
val brandResDir = File(layout.buildDirectory.get().asFile, "generated/res/brand")

// The hosts a release build may talk to over plain HTTP. Derived, never typed.
//
// Off by default: `App/AGENTS.md` §11 forbids relaxing network security to make a
// release work over HTTP, so the shipped policy is loopback (a developer's own API
// on their machine or the emulator) plus one named host: the deadman-switch
// fallback from `network.fallback_server_url`, and only when that URL is plain
// HTTP — the owner allowed cleartext for this one host (2026-11-16) so the
// session-only fallback works in a release build. One further brand key —
// `network.release_cleartext_for_default_host` — adds the default server's host,
// and only that host, when the owner decides cleartext to their own catalog is
// acceptable. Third-party media origins are never added: their URLs already
// arrive as our own redirector handles.
fun brandReleaseCleartextAllowed(): Boolean =
    brand("network.release_cleartext_for_default_host", "false").equals("true", ignoreCase = true)

// The deadman-switch fallback host, and only when its URL is plain HTTP: an
// HTTPS fallback needs no exception, so it adds none. Derived, never typed.
fun brandFallbackCleartextHost(): String? {
    val uri = runCatching { URI(brand("network.fallback_server_url", "")) }.getOrNull()
        ?: return null
    if (!uri.scheme.equals("http", ignoreCase = true)) return null
    return uri.host?.takeIf { it.isNotEmpty() }
}

fun brandCleartextHosts(): List<String> {
    val local = listOf("127.0.0.1", "localhost", "10.0.2.2")
    val fallback = listOfNotNull(brandFallbackCleartextHost())
    if (!brandReleaseCleartextAllowed()) return (local + fallback)
        .filter { it.isNotEmpty() && !it.contains('/') && !it.contains(':') }
        .distinct()
        .sorted()
    val fromUrl = runCatching { URI(brand("network.default_server_url", "")).host }.getOrNull()
    val extras = brand("network.cleartext.extra_hosts", "").split(',', ' ').map { it.trim() }
    return (local + fallback + listOfNotNull(fromUrl) + extras)
        .filter { it.isNotEmpty() && !it.contains('/') && !it.contains(':') }
        .distinct()
        .sorted()
}


// ── Version (brand.properties wins; CI env vars can still override) ──────────
val versionNameFromEnv = System.getenv("VERSION_NAME") ?: brand("identity.version.name", "0.1.0")
val versionCodeFromEnv = System.getenv("VERSION_CODE")?.toIntOrNull()
    ?: brandInt("identity.version.code", 1)

val brandApplicationId = brand("identity.application.id", "com.raddstream.app")
val brandDebugSuffix = brand("identity.application.id.debug.suffix", ".debug")

android {
    namespace   = brand("identity.namespace", brandApplicationId)
    // Library bump 2026-11-23: compileSdk 35 -> 36, forced by Media3
    // 1.11.1 + NextLib (their AAR metadata requires compiling against
    // API 36+; proven by CI 45089532958, :app:checkDebugAarMetadata).
    // targetSdk stays 35: compileSdk can be raised separately from
    // targetSdk, and 36's runtime behavior changes are untested.
    compileSdk  = 36

    sourceSets {
        getByName("main") {
            // brand.json is generated into build/ by generateBrandAssets below.
            assets.srcDir(brandAssetsDir)
            // so is res/xml/network_security_config.xml
            res.srcDir(brandResDir)
        }
    }

    signingConfigs {
        create("release") {
            val keystoreBase64 = System.getenv("KEYSTORE_BASE64") ?: ""
            val storePassword  = System.getenv("KEY_STORE_PASSWORD") ?: ""
            val keyAlias       = System.getenv("KEY_ALIAS") ?: ""
            val keyPassword    = System.getenv("KEY_PASSWORD") ?: ""

            if (keystoreBase64.isNotEmpty() && storePassword.isNotEmpty()) {
                // Why this block interrogates its input instead of just decoding it: release run
                // 41233720264 died on the decode line below with "Last unit does not have enough
                // valid bits" after 8 seconds — 8 seconds is Gradle configuration, not signing, so
                // nothing had compiled. That message describes a malformed final base64 group, i.e.
                // a corrupt stored value, and the strict decoder was right to refuse it. Measured on
                // this host: for that exact input the tolerant MIME decoder throws identically, so
                // relaxing the decoder would have fixed nothing while discarding the one check that
                // caught the fault. What the failure lacked was a sentence an operator could act on.
                //
                // These checks therefore report the *shape* of the value — length, position,
                // character code, byte count after decode — and never the value itself, which is a
                // credential and belongs in no log, no error message and no artifact.
                fun refuse(shape: String): Nothing = throw GradleException(
                    "KEYSTORE_BASE64 is not usable: $shape.\n" +
                        "  Re-export with `base64 -w 0 <keystore-file>` and paste the terminal output " +
                        "straight into Settings -> Secrets and variables -> Actions, replacing the " +
                        "stored value. Do not route it through a file, an editor or a chat message — " +
                        "that is where a single-line string gains newlines or loses its `==` padding. " +
                        "The correct length for the keystore this repo has always used is 3672 " +
                        "characters. No decoder change will help: the stored value is what is wrong, " +
                        "and this message prints its shape only, never its contents."
                )

                if (keystoreBase64.length % 4 != 0) {
                    refuse(
                        "is ${keystoreBase64.length} characters, and base64 is always a multiple of 4 " +
                            "— a tail that is not a whole group is exactly the \"Last unit does not " +
                            "have enough valid bits\" crash this replaces"
                    )
                }
                val badChar = keystoreBase64.indexOfFirst { c ->
                    !(c.isLetterOrDigit() || c == '+' || c == '/' || c == '=')
                }
                if (badChar >= 0) {
                    refuse(
                        "holds a non-base64 character at index $badChar of ${keystoreBase64.length} " +
                            "(code point 0x${keystoreBase64[badChar].code.toString(16)}) — a newline " +
                            "or a space pasted in from a wrapping display"
                    )
                }
                if (keyAlias.isEmpty()) refuse("is set but KEY_ALIAS is empty, so nothing could be selected")
                if (keyPassword.isEmpty()) refuse("is set but KEY_PASSWORD is empty")

                val keystoreBytes = Base64.getDecoder().decode(keystoreBase64)

                // Bytes that decode cleanly are not yet a keystore. Without this the operator gets
                // a structural pass and then a signing task that names neither the secret nor the
                // file; with it, garbage fails here as "not a keystore" and a wrong password fails
                // here as itself. JKS is dual-format on JDK 9+, so it reads a PKCS12 store too, and
                // PKCS12 is tried for a store that is not self-describing.
                val storeChars = storePassword.toCharArray()
                val probe = listOf("JKS", "PKCS12").firstNotNullOfOrNull { type ->
                    runCatching {
                        KeyStore.getInstance(type).apply {
                            ByteArrayInputStream(keystoreBytes).use { load(it, storeChars) }
                        }
                    }.getOrNull()
                } ?: refuse(
                    "decodes to ${keystoreBytes.size} bytes that parse as neither a JKS nor a PKCS12 " +
                        "keystore with the stored KEY_STORE_PASSWORD"
                )
                if (!probe.containsAlias(keyAlias)) {
                    refuse(
                        "the keystore holds ${probe.size()} alias(es) and none is the configured " +
                            "KEY_ALIAS (the name is not printed; list it with " +
                            "`keytool -list -keystore <file>`)"
                    )
                }
                if (!probe.isKeyEntry(keyAlias)) {
                    refuse("the configured alias is not a private-key entry, so it cannot sign an APK")
                }
                runCatching { probe.getKey(keyAlias, keyPassword.toCharArray()) }.onFailure {
                    refuse(
                        "KEY_PASSWORD does not recover the private key for the configured alias " +
                            "(${it.javaClass.simpleName}) — signing would fail on precisely this, and " +
                            "the identity a release is signed with cannot be changed after the fact"
                    )
                }

                val keystoreFile = File(layout.buildDirectory.asFile.get(), "radd-release.jks")
                keystoreFile.parentFile?.mkdirs()
                keystoreFile.writeBytes(keystoreBytes)
                println(
                    "release signing: KEYSTORE_BASE64 accepted — ${keystoreBase64.length} chars -> " +
                        "${keystoreBytes.size} bytes, private-key entry found and unlocked"
                )
                storeFile = keystoreFile
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    defaultConfig {
        applicationId = brandApplicationId
        // Library bump 2026-11-23: minSdk 21 -> 23, forced by Media3 1.11.1
        // (its AAR manifests declare minSdk 23 since 1.9.0) and NextLib.
        // Android 5.0/5.1 are dropped; the app is pre-launch, so no
        // installed base is affected.
        minSdk        = 23
        targetSdk     = 35
        versionCode   = versionCodeFromEnv
        versionName   = versionNameFromEnv

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables.useSupportLibrary = true

        // Deep-link scheme, consumed by AndroidManifest.xml as ${brandScheme}.
        manifestPlaceholders["brandScheme"] = brand("deeplink.scheme", "radd")

        // Every brand key becomes BuildConfig.BRAND_<UPPER_SNAKE> (String).
        // Brand.kt exposes typed accessors; Color.kt / Type.kt read the hexes
        // and type scale from here, so no Kotlin file carries a brand literal.
        brandKeys.forEach { key ->
            buildConfigField("String", brandField(key), "\"${brandJsonEscape(brand(key, ""))}\"")
        }

        // XML-side colours (themes.xml, launcher layers, splash) reference
        // @color/<name>; res/values/colors.xml no longer declares them.
        brandKeys.filter { it.startsWith("resources.colors.") }.forEach { key ->
            resValue("color", key.removePrefix("resources.colors."), brand(key, "#000000"))
        }

        // XML-side strings. @string/brand_app_name is the manifest label, so the
        // launcher name comes from brand.properties, not res/values/strings.xml.
        resValue("string", "brand_app_name", brand("identity.name", "RADD Stream"))
        resValue("string", "brand_app_name_short", brand("identity.name.short", brand("identity.name", "RADD Stream")))
        resValue("string", "brand_wordmark_first", brand("identity.name.wordmark.first", "Radd"))
        resValue("string", "brand_wordmark_second", brand("identity.name.wordmark.second", "Stream"))
        resValue("string", "brand_tagline", brand("identity.tagline", ""))
    }

    buildTypes {
        debug {
            applicationIdSuffix = brandDebugSuffix
            isDebuggable        = true
            isMinifyEnabled     = false
        }
        release {
            // A release build is never debuggable: the shipped APK must not
            // accept a debugger, and a debuggable flag would also let the
            // system read the app's private files. Debug keeps isDebuggable
            // true above; release states false explicitly so a future edit
            // cannot silently inherit the debug value.
            isDebuggable      = false
            isMinifyEnabled   = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val keystoreBase64 = System.getenv("KEYSTORE_BASE64") ?: ""
            if (keystoreBase64.isNotEmpty()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // MX idea #1 (2026-11-19): the NextLib FFmpeg decoder AAR requires
        // core library desugaring (its aar-metadata sets
        // coreLibraryDesugaringEnabled=true) — AGP's checkDebugAarMetadata
        // fails the build without it (proven by Android CI 44393698539).
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose     = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // Library bump 2026-11-23 (Hilt 2.58's transitive deps):
            // error_prone_annotations 2.51.0 and jspecify 1.0.0 both ship
            // META-INF/versions/9/OSGI-INF/MANIFEST.MF, and
            // mergeDebugJavaResource fails on the duplicate (CI 45092811369).
            // The file is an OSGi bundle manifest — irrelevant to the APK.
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }

    // Compose semantics assertions run on the JVM under Robolectric. This box has
    // no /dev/kvm, so an emulator is not an option; without includeAndroidResources
    // Robolectric cannot stand up the framework the composition needs.
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// ── Robolectric UI tests: x86_64 only ────────────────────────────────────────
// Robolectric's native runtime ships no Linux/aarch64 build (robolectric/robolectric#9166),
// and it fails while the runner boots — during @Rule evaluation, before any line of the test
// body — so an in-test Assume cannot catch it. Excluding here is the only place that works.
// These classes therefore run on an x86_64 CI runner, never on this ARM host.
tasks.withType<Test>().configureEach {
    // Full failure output (2026-11-23): the default SHORT exception format
    // prints only the exception type + one frame, which hid the message of
    // the Paparazzi Renderer.prepare failure for two CI runs. FULL prints
    // the message + the whole stack trace to the console log, so a failure
    // is diagnosable from the log alone without the XML/HTML report.
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
        showStackTraces = true
    }
    if (System.getProperty("os.arch")?.lowercase() in setOf("aarch64", "arm64")) {
        filter {
            excludeTestsMatching("com.raddstream.app.ui.components.PosterCardProvenanceTest")
            excludeTestsMatching("com.raddstream.app.ui.ScreenSnapshotTest")
            excludeTestsMatching("com.raddstream.app.data.local.AppDatabaseMigrationTest")
            excludeTestsMatching("com.raddstream.app.data.cache.AheadFetcherFinalizeTest")
            excludeTestsMatching("com.raddstream.app.data.cache.CacheSweepWorkerTest")
            excludeTestsMatching("com.raddstream.app.work.CatalogWarmWorkerTest")
            excludeTestsMatching("com.raddstream.app.work.CatalogIndexSyncWorkerTest")
            excludeTestsMatching("com.raddstream.app.data.CrashReportingTest")
        }
        doFirst {
            // Name every excluded class: this line is the only signal a reader of a local gate
            // gets that the suite count below is not the whole suite count.
            logger.lifecycle(
                "Skipping PosterCardProvenanceTest, ScreenSnapshotTest, AppDatabaseMigrationTest, " +
                    "AheadFetcherFinalizeTest, CacheSweepWorkerTest, CatalogWarmWorkerTest, " +
                    "CatalogIndexSyncWorkerTest and CrashReportingTest: Robolectric has no native runtime for " +
                    "${System.getProperty("os.arch")}. Run these classes on an x86_64 CI runner."
            )
        }
    }
}

// ── Paparazzi vs Robolectric: separate JVMs ──────────────────────────────────
// Paparazzi 2.x CONFLICTS with Robolectric tests in the same module
// (cashapp/paparazzi#1979): if a Robolectric test runs first in a fork, the
// Robolectric-loaded framework classes break Paparazzi's Bridge.init
// ("Failed to init Bridge."), and every Paparazzi test in that fork fails.
// testDebugUnitTest runs the whole suite in shared forks, so the
// Paparazzi-rule classes are excluded here and run ONLY via
// verifyPaparazziDebug — its own Test task, its own JVM, no Robolectric
// classes loaded. The goldens still gate on verifyPaparazziDebug (ci.yml
// runs it as a separate step). Applied via withType<Test>().configureEach
// with a name guard: tasks.named("testDebugUnitTest") fails at configuration
// time because AGP creates that task lazily ("Task with name
// 'testDebugUnitTest' not found", CI 45103299471), and an unguarded
// withType<Test> exclusion would also empty verifyPaparazziDebug.
// ScreenSnapshotTest is NOT excluded — it is a Robolectric test (no Paparazzi
// rule) and belongs in testDebugUnitTest.
tasks.withType<Test>().configureEach {
    if (name == "testDebugUnitTest") {
        filter {
            excludeTestsMatching("com.raddstream.app.ui.PaparazziPreviewTest")
            excludeTestsMatching("com.raddstream.app.ui.PlayerLandscapePaparazziTest")
            excludeTestsMatching("com.raddstream.app.ui.DownloadsPaparazziTest")
        }
    }
}

// ── Unit-test worker heap: 2g ───────────────────────────────────────────────
// No maxHeapSize was configured, so Gradle ran the test worker at its
// 512m default (proven by the worker command line in the run-474 --info
// log). After the Crashlytics wiring the full 212-class Robolectric suite
// dies at the first Robolectric class (runs 471/473/474: executor exit 1,
// no test XMLs, no failure line — a worker-process death, not a test
// failure), while CrashReportingTest passes alone. The suite passed on
// main at this heap before the Firebase jars joined the test runtime
// classpath, so raise the worker heap before bisecting classes.
tasks.withType<Test>().configureEach {
    maxHeapSize = "2048m"
}

// ── brand.json: generated asset, single runtime source for Brand.kt ──────────
// Flat JSON object of every brand key. Regenerated on every build whenever
// brand.properties changes, and merged into the APK assets via the sourceSets
// entry above, so copy strings / TTLs / flags can be read at startup.
val generateBrandAssets = tasks.register("generateBrandAssets") {
    description = "Render brand.json and the network policy into the merged resources"
    group = "branding"
    inputs.file(brandPropsFile)
    outputs.dir(brandAssetsDir)
    outputs.dir(brandResDir)
    doLast {
        brandAssetsDir.mkdirs()
        brandResDir.mkdirs()
        val json = buildString {
            appendLine("{")
            appendLine("  \"_generated\": \"by :app:generateBrandAssets from /brand.properties — DO NOT HAND-EDIT\",")
            appendLine("  \"_brandFile\": \"${brandJsonEscape(brandPropsFile.absolutePath)}\",")
            brandKeys.forEachIndexed { index, key ->
                val terminator = if (index == brandKeys.lastIndex) "" else ","
                append("  \"")
                append(brandJsonEscape(key))
                append("\": \"")
                append(brandJsonEscape(brand(key, "")))
                appendLine("\"$terminator")
            }
            appendLine("}")
        }
        File(brandAssetsDir, "brand.json").writeText(json)
        File(brandResDir, "xml").mkdirs()
        File(brandResDir, "xml/network_security_config.xml").writeText(brandNetworkPolicyXml())
        logger.lifecycle("[brand] ${brand("identity.name", "?")} v${brand("identity.version.name", "?")} " +
            "appId=${brand("identity.application.id", "?")} scheme=${brand("deeplink.scheme", "radd")} " +
            "primary=${brand("colors.dark.primary", "?")} keys=${brandKeys.size} " +
            "cleartext=${brandCleartextHosts()}")
    }
}

// The TLS pins for the default host (`network.tls_pins`): the chain, never the
// leaf. Empty when the brand ships none, and then no pin-set is emitted.
fun brandTlsPins(): List<String> =
    brand("network.tls_pins", "").split(',').map { it.trim() }.filter { it.isNotEmpty() }

fun brandDefaultHost(): String? =
    runCatching { URI(brand("network.default_server_url", "")).host }.getOrNull()?.takeIf { it.isNotEmpty() }

// The one cleartext exception, in the platform's own format. Generated so that
// changing `network.default_server_url`, `network.fallback_server_url`, or the
// release switch changes it with no second place to edit. The same file carries
// the `<pin-set>` for the default host when `network.tls_pins` is set: the app enforces the same pins in code
// (`ApiProvider`), and the platform enforces them here, so a release build
// refuses a certificate that neither the pins nor the platform trust. A
// user-chosen server is never pinned.
fun brandNetworkPolicyXml(): String {
    val hosts = brandCleartextHosts()
    val extra = hosts - setOf("127.0.0.1", "localhost", "10.0.2.2")
    val policy = when {
        brandReleaseCleartextAllowed() ->
            "cleartext is permitted for the one host this brand ships against"
        extra.isEmpty() ->
            "cleartext is permitted for loopback only, so a release build reaches a TLS catalog"
        else ->
            "cleartext is permitted for loopback plus the plain-HTTP fallback host " +
                "(${extra.joinToString(", ")}), so a release build can fall back to it " +
                "when the TLS catalog is unreachable"
    }
    return buildString {
        appendLine("<?xml version=\"1.0\" encoding=\"utf-8\"?>")
        appendLine("<!-- DO NOT HAND-EDIT: generated by :app:generateBrandAssets from /brand.properties.")
        appendLine("     Policy in force: $policy.")
        appendLine("     Debug builds replace this file with their own source-set copy, because a")
        appendLine("     developer aims the app at whatever machine they are testing. There is no")
        appendLine("     global `cleartextTrafficPermitted` here and none in release: an exception is")
        appendLine("     always a named host, never a wildcard. -->")
        appendLine("<network-security-config>")
        appendLine("    <domain-config cleartextTrafficPermitted=\"true\">")
        hosts.forEach { appendLine("        <domain includeSubdomains=\"false\">$it</domain>") }
        appendLine("    </domain-config>")
        val pins = brandTlsPins()
        val pinHost = brandDefaultHost()
        if (pins.isNotEmpty() && pinHost != null) {
            appendLine("    <domain-config>")
            appendLine("        <domain includeSubdomains=\"false\">$pinHost</domain>")
            appendLine("        <pin-set>")
            pins.forEach { appendLine("            <pin digest=\"SHA-256\">$it</pin>") }
            appendLine("        </pin-set>")
            appendLine("    </domain-config>")
        }
        appendLine("</network-security-config>")
    }
}

tasks.named("preBuild") { dependsOn(generateBrandAssets) }

dependencies {
    // ── Compose BOM (all Compose versions in sync) ────────────────────────────
    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.icons.extended)
    implementation(libs.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // ── Navigation ────────────────────────────────────────────────────────────
    implementation(libs.navigation.compose)

    // ── Media3 / ExoPlayer ────────────────────────────────────────────────────
    implementation(libs.media3.exoplayer)
    implementation(libs.media3.exoplayer.hls)

    // MX idea #1 (2026-11-18): FFmpeg software audio decoder (AC-3 / E-AC-3 /
    // DTS / TrueHD / MLP) so titles those formats fail to decode on MediaCodec
    // play instead of showing the give-up card. NextLib's prebuilt
    // nextlib-media3ext (Maven Central, Media3 1.11.1 + FFmpeg 9.0.1)
    // carries FfmpegAudioRenderer, so NextRenderersFactory finds it by the
    // same reflective lookup and FfmpegSupport.isSoftwareAudioAvailable
    // answers true. NextLib replaced Jellyfin's media3-ffmpeg-decoder in
    // the 2026-11-23 library bump (Jellyfin caps at Media3 1.9.0+1); the
    // in-CI native build it originally replaced failed 4x in CI on
    // 2026-11-19 (nasm, blank-line configure, Maven 429, AAR path).
    // License note: the NextLib artifact is GPL-3.0 (FFmpeg itself LGPL
    // 2.1+); see App/ffmpeg/NOTICE.md — the repo has no license file, so
    // this is flagged for the owner, not a blocker.
    implementation(libs.nextlib.media3ext)
    // MX idea #1: the NextLib decoder AAR requires core library desugaring
    // (see compileOptions above) — this is the library that provides it.
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.media3.exoplayer.dash)
    implementation(libs.media3.datasource)
    implementation(libs.media3.ui)
    implementation(libs.media3.session)

    // ── Networking ────────────────────────────────────────────────────────────
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // ── Hilt DI ───────────────────────────────────────────────────────────────
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // ── Room ──────────────────────────────────────────────────────────────────
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    kapt(libs.room.compiler)

    // ── Paging 3 (feel-instant S6) ────────────────────────────────────────
    // Network-only PagingSource for browse/search lists; RemoteMediator +
    // Room as source of truth comes after S3c, not this slice.
    implementation(libs.paging.runtime)
    implementation(libs.paging.compose)

    // ── Image loading (Coil 3 since the 2026-11-23 library bump) ─────────────
    // Coil 3 core no longer loads network images: coil-network-okhttp is
    // the network fetcher (auto-registered via service loader).
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

    // ── Lifecycle + Coroutines ────────────────────────────────────────────────
    implementation(libs.lifecycle.viewmodel)
    implementation(libs.coroutines.android)

    // ── DataStore (server URL preference) ────────────────────────────────────
    implementation(libs.datastore.preferences)

    // ── WorkManager (the closed-app sweep — CacheSweepWorker) ────────────────
    implementation(libs.workmanager)
    implementation(libs.hilt.work)
    // The processor that generates CacheSweepWorker_AssistedFactory +
    // CacheSweepWorker_HiltModule from @HiltWorker. The runtime alone is NOT
    // enough: without this kapt line the worker compiles but WorkManager cannot
    // instantiate it (NoSuchMethodException on the S6 device, Session 160) —
    // HiltWorkerFactory's map is empty and the reflection fallback needs a
    // (Context, WorkerParameters) constructor the worker does not have.
    kapt(libs.hilt.work.compiler)

    // ── Core ──────────────────────────────────────────────────────────────────
    implementation(libs.core.ktx)

    // ── Firebase (Crashlytics crash reporting + Analytics) ─────────────────
    // The BOM pins every Firebase library to one tested set (33.7.0:
    // crashlytics 19.3.0, analytics 22.1.2 — both minSdk 21, below the app's 23). Crashlytics
    // uploads the release mapping file automatically via its Gradle plugin,
    // so release stack traces de-obfuscate without a manual step.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // ── Play Core in-app updates ──────────────────────────────────────────
    // The force-update card tries Play first (flexible for a soft update,
    // immediate for a forced one) and falls back to the download page when
    // Play has no update for this install — which is always, for a build
    // sideloaded from the download page rather than installed from Play.
    implementation(libs.play.app.update)
    implementation(libs.play.app.update.ktx)

    // ── Google Cast sender (E2 slice 3 spike) ─────────────────────────────
    // CastContext / Default Media Receiver wiring only — no cast button UI
    // yet (see App/docs/CAST_SPIKE_20261118.md). 21.4.0 stays pinned;
    // the app's minSdk is 23 since the 2026-11-23 library bump.
    implementation(libs.play.services.cast.framework)
    // MX #9 Cast: the real feature — media3-cast 1.11.1 (CastPlayer.Builder
    // + RemoteCastPlayer.Builder) for the remote player, mediarouter for
    // the route-picker button.
    implementation(libs.media3.cast)
    implementation(libs.androidx.mediarouter)

    // ── Tests ─────────────────────────────────────────────────────────────────
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.paging.testing)
    testImplementation(composeBom)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.work.testing)
    testImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)

    // Device proof (library bump 2026-11-23): the five-codec decode test
    // (FfmpegDecodersTest) runs on a device (Firebase Test Lab / phone),
    // never on the JVM. CI compile-checks it via
    // :app:compileDebugAndroidTestKotlin.
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.androidx.test.core)

    // Feel-instant S7: the generated Baseline Profile ships with the app.
    // The `:baselineprofile` module produces it; without this line the
    // profile is generated and then thrown away.
    baselineProfile(project(":baselineprofile"))
}
