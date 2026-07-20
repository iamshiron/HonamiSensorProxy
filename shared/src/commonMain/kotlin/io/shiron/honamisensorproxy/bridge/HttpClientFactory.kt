package io.shiron.honamisensorproxy.bridge

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json

/** Creates a Ktor client backed by the platform's HTTP engine (OkHttp on Android, Darwin on iOS). */
expect fun createBridgeHttpClient(): HttpClient

/** Shared client configuration: tolerant JSON + bounded timeouts so a dead sink fails fast and clearly. */
fun HttpClientConfig<*>.installBridgeDefaults() {
    install(ContentNegotiation) {
        json(
            Json {
                ignoreUnknownKeys = true
                isLenient = true
            },
        )
    }
    install(HttpTimeout) {
        connectTimeoutMillis = 8_000
        requestTimeoutMillis = 15_000
        socketTimeoutMillis = 15_000
    }
}
