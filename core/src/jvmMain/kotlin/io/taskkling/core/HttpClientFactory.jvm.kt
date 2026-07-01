package io.taskkling.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

/** Test-only (the shipped binaries are native); CIO needs no extra native deps. */
internal actual fun newHttpClient(): HttpClient = HttpClient(CIO)
