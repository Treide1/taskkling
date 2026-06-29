plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    mingwX64()
    macosArm64()
    macosX64()
    linuxX64()

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Shared actual for the POSIX file lock (`flock`): linux + macOS. Windows
        // (mingw) uses LockFileEx in its own leaf source set; JVM uses FileChannel.
        val posixMain by creating { dependsOn(commonMain.get()) }
        named("linuxX64Main") { dependsOn(posixMain) }
        named("macosX64Main") { dependsOn(posixMain) }
        named("macosArm64Main") { dependsOn(posixMain) }

        commonMain.dependencies {
            api(project(":contract"))
            implementation(libs.okio)
            implementation(libs.kotlinx.datetime)
        }

        // Pure, target-agnostic unit tests (frontmatter, compute, slug, datetime,
        // id) run on every target; CI invokes the JVM variant (:core:jvmTest).
        // Workspace/golden tests use a JVM temp dir, so they live in jvmTest and
        // inherit the same kotlin-test runner.
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.serialization.json) // golden tests encode the export DTO
        }
    }
}
