// Declares every Kotlin plugin once, without applying any of them here.
//
// Subprojects each apply what they need. Declaring them at the root loads the Kotlin plugin a
// single time; without this, applying it in both :core and :app loads it twice and Gradle warns
// that the build may break.
//
// The Android plugin is deliberately NOT declared here. Resolving it would drag the Android
// Gradle Plugin into every build, and :core and :server are meant to build and test on a machine
// with no Android SDK at all.
plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.kotlin.compose) apply false
}

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
