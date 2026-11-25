// Top-level build file — plugin declarations only, nothing applied here.
plugins {
    alias(libs.plugins.android.application) apply false
    // AGP 9.0 built-in Kotlin (BOM bump 2026-11-25): org.jetbrains.kotlin.android
    // removed — AGP compiles Kotlin itself now; applying it is a fatal error.
    alias(libs.plugins.kotlin.compose)      apply false
    alias(libs.plugins.hilt)                apply false
    alias(libs.plugins.legacy.kapt)         apply false
    alias(libs.plugins.google.services)     apply false
    alias(libs.plugins.crashlytics)         apply false
}
