rootProject.name = "taskkling"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
        // Compose Multiplatform dev builds (occasionally needed for plugin artifacts)
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

include(":contract")
include(":core")
include(":cli")
include(":ui")
