package io.taskkling.core

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.runBlocking

/**
 * Construct the platform HTTP engine (ADR-002 `update` / `update_check` transport).
 * Implemented per target, mirroring the [currentExecutablePath] expect/actual
 * OS-split: Curl (linuxX64, needs libcurl dev headers at link time), Darwin /
 * NSURLSession (macosArm64 + macosX64, shared via `macosMain`), WinHTTP
 * (mingwX64), CIO (JVM, test-only — the shipped binaries are native).
 */
internal expect fun newHttpClient(): HttpClient

/**
 * Minimal GET helper: fetch [url], returning the HTTP status code and the body
 * text. This is the seam later `update` / `update_check` work builds on — kept
 * tiny on purpose, no retry/backoff/mocking here. Public (unlike [newHttpClient])
 * because `:cli` calls it directly from the hidden `__http-selftest` path.
 */
public suspend fun httpGetText(url: String, userAgent: String? = null): Pair<Int, String> {
    val client = newHttpClient()
    try {
        val response = client.get(url) {
            if (userAgent != null) header(HttpHeaders.UserAgent, userAgent)
        }
        return response.status.value to response.bodyAsText()
    } finally {
        client.close()
    }
}

/**
 * Synchronous bridge to [httpGetText] for callers outside a coroutine — namely
 * `:cli`'s `main`, which is not itself suspending. Kept in `:core` so `:cli`
 * doesn't need its own `kotlinx-coroutines-core` dependency for this seam.
 */
public fun httpGetTextBlocking(url: String, userAgent: String? = null): Pair<Int, String> =
    runBlocking { httpGetText(url, userAgent) }

/**
 * Binary counterpart to [httpGetText]: fetch [url], returning the HTTP status
 * code and the raw response body bytes. `update` (ADR-002) needs this to
 * download a release asset — a `.exe`/ELF binary can't round-trip through
 * [httpGetText]'s text decoding.
 */
public suspend fun httpGetBytes(url: String, userAgent: String? = null): Pair<Int, ByteArray> {
    val client = newHttpClient()
    try {
        val response = client.get(url) {
            if (userAgent != null) header(HttpHeaders.UserAgent, userAgent)
        }
        return response.status.value to response.bodyAsBytes()
    } finally {
        client.close()
    }
}

/** Synchronous bridge to [httpGetBytes], mirroring [httpGetTextBlocking]. */
public fun httpGetBytesBlocking(url: String, userAgent: String? = null): Pair<Int, ByteArray> =
    runBlocking { httpGetBytes(url, userAgent) }
