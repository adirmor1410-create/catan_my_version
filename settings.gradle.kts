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

// The :app module needs an Android SDK to configure. Android Studio always provides one,
// so it is included there. Headless CI without an SDK can still build and test :core and
// :server, which is where every game rule lives.
val hasAndroidSdk = System.getenv("ANDROID_HOME") != null ||
    System.getenv("ANDROID_SDK_ROOT") != null ||
    file("local.properties").let { it.exists() && it.readText().contains("sdk.dir") }

if (hasAndroidSdk) {
    include(":app")
} else {
    logger.lifecycle("No Android SDK detected - skipping :app. Building :core and :server only.")
}
