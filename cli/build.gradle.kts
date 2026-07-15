import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget
import org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeTest
import org.jetbrains.kotlin.konan.target.HostManager

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    // `add --batch -` (t-zsh6) DECLARES a @Serializable wire record (CliHelpers.kt), which
    // needs the compiler plugin — the kotlinx-serialization-json dependency below is only the
    // runtime, and was until now used solely to CONSUME :contract's generated serializers.
    alias(libs.plugins.kotlinSerialization)
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
            // shell32 is CommandLineToArgvW's home (Argv.mingw.kt, t-jagq) — the lossless
            // argv recovery for non-ASCII titles/text passed as literal CLI arguments.
            if (target.name == "mingwX64") {
                linkerOpts += "-ladvapi32"
                linkerOpts += "-lshell32"
            }
        }
    }

    applyDefaultHierarchyTemplate()

    sourceSets {
        // Shared no-op actual for platformArgv (Argv.kt, t-jagq): linux + macOS argv is
        // already UTF-8, so only mingwX64 needs a real correction (Argv.mingw.kt).
        // Mirrors :core's posixMain split (Lock.kt et al.).
        val posixMain by creating { dependsOn(commonMain.get()) }
        named("linuxX64Main") { dependsOn(posixMain) }
        named("macosX64Main") { dependsOn(posixMain) }
        named("macosArm64Main") { dependsOn(posixMain) }

        commonMain.dependencies {
            implementation(project(":core"))
            implementation(project(":contract"))
            implementation(libs.kotlinx.cli)
            implementation(libs.kotlinx.serialization.json)
        }
        // Test seam for the pure CLI helpers (CliHelpers.kt) — argv parsing + table/field
        // rendering. `:cli` is native-only, so these compile into a per-target native test
        // binary and run via the host `<target>Test` task (e.g. mingwX64Test on Windows);
        // mirrors the `:ui` layout-math seam (t-qkqo) with the kotlin-test runner used by :core.
        commonTest.dependencies {
            implementation(kotlin("test"))
            // Subprocess golden tests (t-hfwt) read captured stdout/stderr files with okio and
            // parse `export` JSON with serialization; both are :cli's own transitive deps but
            // must be declared for the test source set to compile against them directly.
            implementation(libs.okio)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

// Wire the black-box subprocess tests (t-hfwt) to the real binary. Each `<target>Test` task
// must (a) run only AFTER the matching debug executable is linked, and (b) know where that
// executable is — injected via TASKKLING_TEST_BIN, which the native test harness reads. KGP
// registers all four `<target>Test` tasks on every host, but each CI matrix leg invokes only
// its own host-native one (mingwX64Test on Windows, linuxX64Test on Linux, macos*Test on macOS).
kotlin.targets.withType(KotlinNativeTarget::class.java).configureEach {
    val exe = binaries.getExecutable(NativeBuildType.DEBUG)
    val userHome = layout.buildDirectory.dir("test-userhome/$name").get().asFile
    tasks.matching { it.name == "${name}Test" }.configureEach {
        dependsOn(exe.linkTaskName)
        (this as KotlinNativeTest).environment("TASKKLING_TEST_BIN", exe.outputFile.absolutePath)
        // Sandbox the user-level config/cache home (UserPaths.kt) into the build dir so the
        // `config init` test — which writes the user-level config.toml, not a workspace file —
        // never touches the real developer's home dir. Cleaned by `clean`; fresh on CI.
        environment("XDG_CONFIG_HOME", userHome.resolve("config").absolutePath)
        environment("XDG_CACHE_HOME", userHome.resolve("cache").absolutePath)
        environment("LOCALAPPDATA", userHome.resolve("localappdata").absolutePath)
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
