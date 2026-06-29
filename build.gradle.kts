// Root build file. Plugins are declared here with `apply false` so that the
// version catalog resolves them once; each module applies what it needs.
plugins {
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.composeMultiplatform) apply false
}

allprojects {
    group = "io.taskkling"
    // Single source of the version: the `version` property in gradle.properties.
    // Applied to every subproject so :core (generated BUILD_VERSION) and :ui
    // (Compose installer packageVersion) both derive from one declared value.
    version = providers.gradleProperty("version").get()
}
