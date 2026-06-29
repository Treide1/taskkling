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
            // One flat installer version across MSI/DEB/DMG, derived from the single
            // source (`version` in gradle.properties → project.version) so it always
            // matches the tool's own version (:core Taskkling.VERSION). Compose ≥1.11
            // lifted the macOS 0.x-major restriction, so no per-OS override is needed.
            packageVersion = project.version.toString()
        }
    }
}
