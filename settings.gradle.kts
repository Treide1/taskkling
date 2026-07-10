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

// Auto-provisions the JDK 21 compile toolchain (foojay/Adoptium discovery) when
// no matching local JDK exists — contributors only need *some* JDK to boot the
// Gradle launcher (t-zbvo). In CI this is a no-op: setup-java pre-satisfies it.
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
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
