// Feel-instant S7 — Baseline Profile generator module.
//
// A `com.android.test` module: it builds a test APK that profiles `:app`
// (see `targetProjectPath` below) instead of shipping code. Its only job
// is `BaselineProfileGenerator` — the Startup Profile covering
// Home → Search → Detail.
//
// The plugin needs AGP 8.0+; this project is on 9.1.1 (BOM bump 2026-11-25). No Gradle-managed
// device is configured: generation runs on a connected device (rooted, or
// API 33+) the operator provides — see the generator's KDoc for the
// prerequisites and the exact command. CI never runs the generator
// (instrumented tests need a device); CI compiles + packages it via
// `:baselineprofile:assembleBenchmarkRelease` +
// `:baselineprofile:assembleNonMinifiedRelease` in
// `.github/workflows/ci.yml`, which is the gate that keeps a typo in the
// generator from landing silently. Note the per-variant tasks, not the
// `assemble` lifecycle task: `assemble` pulls in
// `connectedNonMinifiedReleaseAndroidTest`, which fails on a deviceless
// runner with "No connected devices!" (Android CI 44432484757).
plugins {
    id("com.android.test")
    alias(libs.plugins.androidx.baselineprofile)
    // AGP 9.0 built-in Kotlin (BOM bump 2026-11-25): org.jetbrains.kotlin.android
    // removed — AGP compiles Kotlin itself now; applying it is a fatal error.
}

android {
    namespace = "com.raddstream.app.baselineprofile"
    compileSdk = 37 // BOM bump 2026-11-25: tracks the app module (was 35).

    defaultConfig {
        // minSdk 23, the same as the app's since the 2026-11-23 library
        // bump: benchmark-macro-junit4 1.4.1 declares minSdk 23, and the
        // manifest merger refuses a module lower than its library
        // (proven by Android CI 44426842277). This module never ships in
        // the app — it is a test APK that runs on the generation device
        // only — and generation already needs a rooted device or
        // API 33+ anyway.
        minSdk = 23
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Mirror the app module's JVM target (17): without this, Java
    // defaults to 1.8 while Kotlin defaults to the running JDK (17)
    // and the Kotlin plugin's JVM-target validation fails the build
    // (proven by Android CI 44428960230:
    // "Inconsistent JVM-target compatibility ... JavaWithJavac (1.8)
    // and ...Kotlin (17)").
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // AGP 9.0 built-in Kotlin: `kotlinOptions` is gone — the Kotlin jvmTarget
    // now defaults to compileOptions.targetCompatibility (17) above, so the
    // old JVM-target mismatch guard (CI 44428960230) is satisfied by
    // compileOptions alone.

    // Point to the app module, the module the profile is generated for.
    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
