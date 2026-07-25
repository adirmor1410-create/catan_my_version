pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CatanMultiplayer"

include(":core")
include(":server")

// The :app module needs an Android SDK to configure. Headless CI without one can still build
// and test :core and :server, which is where every game rule lives.
//
// Android Studio writes local.properties on first open, but running Gradle from the command
// line before ever opening the project leaves no trace of the SDK at all. So as a last resort
// look in the place each platform installs it by default, rather than silently skipping :app.
val conventionalSdkPath: File? = when {
    System.getProperty("os.name").startsWith("Windows") ->
        File(System.getenv("LOCALAPPDATA") ?: "", "Android/Sdk")

    System.getProperty("os.name").contains("Mac") ->
        File(System.getProperty("user.home"), "Library/Android/sdk")

    else -> File(System.getProperty("user.home"), "Android/Sdk")
}.takeIf { it.isDirectory }

val sdkFromEnvironment = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
val sdkFromProperties = file("local.properties")
    .takeIf { it.exists() }
    ?.readLines()
    ?.firstOrNull { it.startsWith("sdk.dir") }

when {
    sdkFromEnvironment != null || sdkFromProperties != null -> include(":app")

    conventionalSdkPath != null -> {
        // Point Gradle at it for this build. Writing local.properties is still worth doing
        // (Android Studio expects it), but the build should not fail for want of it.
        System.setProperty("android.home", conventionalSdkPath.absolutePath)
        System.setProperty("sdk.dir", conventionalSdkPath.absolutePath)
        logger.lifecycle("Using the Android SDK found at $conventionalSdkPath.")
        include(":app")
    }

    else -> logger.lifecycle(
        """
        No Android SDK found - skipping :app, building :core and :server only.
        To build the app, either open this project in Android Studio, or create a
        local.properties file next to this one containing:
            sdk.dir=C:/Users/<you>/AppData/Local/Android/Sdk    (Windows)
            sdk.dir=/Users/<you>/Library/Android/sdk            (macOS)
            sdk.dir=/home/<you>/Android/Sdk                     (Linux)
        """.trimIndent(),
    )
}
