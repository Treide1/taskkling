package io.taskkling.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

/** NSURLSession-backed engine; shared by macosArm64 + macosX64 (compile/link-checked on CI only). */
internal actual fun newHttpClient(): HttpClient = HttpClient(Darwin)
