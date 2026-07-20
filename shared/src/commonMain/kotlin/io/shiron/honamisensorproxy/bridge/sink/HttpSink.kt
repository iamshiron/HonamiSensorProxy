package io.shiron.honamisensorproxy.bridge.sink

import io.shiron.honamisensorproxy.bridge.auth.Authenticator
import io.shiron.honamisensorproxy.bridge.model.Envelope
import io.shiron.honamisensorproxy.bridge.model.IngestResponse
import io.shiron.honamisensorproxy.bridge.model.Sample
import io.shiron.honamisensorproxy.bridge.model.SinkConfig
import io.shiron.honamisensorproxy.bridge.model.toWire
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlin.coroutines.cancellation.CancellationException

/**
 * Pushes batches to a backend over HTTPS as `Authorization: Bearer <token>` + the JSON envelope.
 * See BridgeApp.md §6 and §12.2.
 */
class HttpSink(
    override val config: SinkConfig,
    private val auth: Authenticator,
    private val http: HttpClient,
    /** Free-form device id sent as the envelope `source` (§12.2). */
    private val deviceId: String,
) : SampleSink {
    override suspend fun push(batch: List<Sample>): PushResult {
        val relevant = batch.filter { it.metric in config.metrics }
        if (relevant.isEmpty()) return PushResult.Skipped

        val response: HttpResponse = try {
            http.post(config.ingestUrl) {
                contentType(ContentType.Application.Json)
                header(HttpHeaders.Authorization, "Bearer ${auth.accessToken()}")
                setBody(Envelope(source = deviceId, samples = relevant.map(Sample::toWire)))
            }
        } catch (e: CancellationException) {
            // Genuine coroutine cancellation must propagate — never swallow it as a "failure".
            throw e
        } catch (e: Exception) {
            // Network failure / DNS / TLS / timeout — transient, worth a retry.
            val detail = e.message ?: e::class.simpleName ?: "network error"
            return PushResult.Failed(detail, retryable = true)
        }

        return when {
            response.status.isSuccess() ->
                PushResult.Accepted(response.body<IngestResponse>().accepted, relevant.size)
            response.status.value in 500..599 ->
                PushResult.Failed("HTTP ${response.status.value}", retryable = true)
            response.status.value == 429 ->
                PushResult.Failed("rate limited (429)", retryable = true)
            else ->
                PushResult.Failed("HTTP ${response.status.value}: ${response.bodyAsText()}", retryable = false)
        }
    }
}
