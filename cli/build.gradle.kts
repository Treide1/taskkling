plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    // CLI is native-only (PRD §6.1 / §6.2): no JVM target. mingwX64 is the host.
    val nativeTargets = listOf(
        mingwX64(),
        macosArm64(),
        macosX64(),
        linuxX64(),
    )

    nativeTargets.forEach { target ->
        target.binaries.executable {
            baseName = "taskkling"
            entryPoint = "io.taskkling.cli.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":contract"))
            implementation(libs.kotlinx.cli)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}
