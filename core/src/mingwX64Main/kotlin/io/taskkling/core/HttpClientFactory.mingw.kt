package io.taskkling.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.winhttp.WinHttp

/** WinHTTP-backed engine; the native Windows client, no extra link-time deps. */
internal actual fun newHttpClient(): HttpClient = HttpClient(WinHttp)
