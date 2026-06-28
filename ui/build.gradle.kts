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
}

compose.desktop {
    application {
        mainClass = "io.taskkling.ui.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Dmg, TargetFormat.Deb)
            packageName = "taskkling"
            packageVersion = "1.0.0"
        }
    }
}
