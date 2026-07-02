import org.jetbrains.kotlin.konan.target.HostManager

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
            // `uninstall`'s Windows PATH de-entry (ADR-004) is this binary's first
            // registry write (RegOpenKeyExW/RegSetValueExW, advapi32) — windows.def's
            // default linkerOpts cover kernel32/user32 (already used by Lock/ExePath/
            // Update's mingw actuals) but not advapi32, so add it explicitly here.
            if (target.name == "mingwX64") {
                linkerOpts += "-ladvapi32"
            }
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

// Dev dogfooding: build the host debug binary and drop it into the repo's own
// .taskkling/bin/ so the tracked ./taskkling[.cmd] wrappers run the fresh build.
// Windows host only (the one that must work); the same is achievable elsewhere
// via the built `init --local-bin`.
if (HostManager.hostIsMingw) {
    tasks.register<Copy>("installLocalBinDev") {
        group = "taskkling"
        description = "Build the debug mingwX64 binary and install it into <repo>/.taskkling/bin for dogfooding."
        dependsOn("linkDebugExecutableMingwX64")
        from(layout.buildDirectory.file("bin/mingwX64/debugExecutable/taskkling.exe"))
        into(rootProject.layout.projectDirectory.dir(".taskkling/bin"))
    }
}
