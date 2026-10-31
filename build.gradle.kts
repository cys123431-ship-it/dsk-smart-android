// Top-level build file. Module level configuration lives in app/build.gradle.kts
// AGP 9+ has built-in Kotlin support, so the kotlin.android plugin is intentionally absent.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}
