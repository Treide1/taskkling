plugins {
    alias(libs.plugins.kotlinMultiplatform)
}

kotlin {
    jvm()

    mingwX64()
    macosArm64()
    macosX64()
    linuxX64()

    sourceSets {
        commonMain.dependencies {
            api(project(":contract"))
            implementation(libs.okio)
            implementation(libs.kotlinx.datetime)
        }
    }
}
