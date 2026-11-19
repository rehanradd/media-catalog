// Feel-instant S7 — Baseline Profile generator module.
//
// A `com.android.test` module: it builds a test APK that profiles `:app`
// (see `targetProjectPath` below) instead of shipping code. Its only job
// is `BaselineProfileGenerator` — the Startup Profile covering
// Home → Search → Detail.
//
// The plugin needs AGP 8.0+; this project is on 8.5.2. No Gradle-managed
// device is configured: generation runs on a connected device (rooted, or
// API 33+) the operator provides — see the generator's KDoc for the
// prerequisites and the exact command. CI never runs the generator
// (instrumented tests need a device); CI compiles + packages it via
// `:baselineprofile:assemble` in `.github/workflows/ci.yml`, which
// is the gate that keeps a typo in the generator from landing silently.
plugins {
    id("com.android.test")
    alias(libs.plugins.androidx.baselineprofile)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.raddstream.app.baselineprofile"
    compileSdk = 35

    defaultConfig {
        // minSdk 23, not the app's 21: benchmark-macro-junit4 1.4.1
        // declares minSdk 23, and the manifest merger refuses a module
        // lower than its library (proven by Android CI 44426842277).
        // This module never ships in the app — it is a test APK that runs
        // on the generation device only — so its floor does not move the
        // app's 21, and generation already needs a rooted device or
        // API 33+ anyway.
        minSdk = 23
        targetSdk = 35
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Point to the app module, the module the profile is generated for.
    targetProjectPath = ":app"
}

dependencies {
    implementation(libs.androidx.test.ext.junit)
    implementation(libs.androidx.test.runner)
    implementation(libs.uiautomator)
    implementation(libs.benchmark.macro.junit4)
}
