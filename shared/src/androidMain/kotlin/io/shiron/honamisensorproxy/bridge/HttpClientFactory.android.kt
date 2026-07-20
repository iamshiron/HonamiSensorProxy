package io.shiron.honamisensorproxy.bridge

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp

actual fun createBridgeHttpClient(): HttpClient = HttpClient(OkHttp) { installBridgeDefaults() }
