package io.shiron.honamisensorproxy.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin

actual fun createBridgeHttpClient(): HttpClient = HttpClient(Darwin) { installBridgeDefaults() }
