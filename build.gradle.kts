// Intentionally empty.
//
// Each module declares the plugins it needs. That keeps the Android Gradle Plugin out of the
// build graph entirely when :app is not included, so :core and :server can be built and tested
// on a machine with no Android SDK.

tasks.register<Delete>("clean") {
    delete(rootProject.layout.buildDirectory)
}
