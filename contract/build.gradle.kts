plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    jvm()

    // Native targets per PRD §6.1 / §15. mingwX64 is the host (Windows).
    mingwX64()
    macosArm64()
    macosX64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
        }

        // Pure DTO wire tests (field names + round-trip) guard the ADR-008
        // vocabulary boundary. Target-agnostic, so they live in commonTest and run
        // on every target; CI invokes the JVM variant (:contract:jvmTest), mirroring
        // :core. The kotlinx JSON runtime is on the test classpath via commonMain's
        // implementation dependency.
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}
