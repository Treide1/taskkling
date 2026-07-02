package io.taskkling.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.curl.Curl

/** libcurl-backed engine; needs libcurl dev headers present at link time (CI installs them on ubuntu). */
internal actual fun newHttpClient(): HttpClient = HttpClient(Curl)
