import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.composeMultiplatform)
}

dependencies {
    // UI links ONLY :contract (the DTOs) — never :core. This structurally
    // guarantees the single-write-path principle (PRD §6.1). The UI is a pure
    // CLI client; it cannot parse or write task files.
    implementation(project(":contract"))
    implementation(compose.desktop.currentOs)
    // The DTOs in :contract carry generated serializers; the UI needs the JSON
    // runtime to decode `taskkling export` output (PRD §6.3, §12).
    implementation(libs.kotlinx.serialization.json)
}

compose.desktop {
    application {
        mainClass = "io.taskkling.ui.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "taskkling"
            // Tool version is 0.1.0 (see :core Taskkling.VERSION) — and the MSI/DEB
            // installers carry it verbatim. macOS is the lone exception: jpackage
            // forbids a 0.x major in a bundle version, so the .dmg is bumped to the
            // nearest legal value. Packaging metadata, not the tool's own version.
            packageVersion = "0.1.0"
            macOS { packageVersion = "1.0.0" }
        }
    }
}
