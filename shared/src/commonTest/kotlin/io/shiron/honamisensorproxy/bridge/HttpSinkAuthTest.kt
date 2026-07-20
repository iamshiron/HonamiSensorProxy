package io.shiron.honamisensorproxy.bridge

import io.shiron.honamisensorproxy.bridge.auth.StaticTokenAuthenticator
import io.shiron.honamisensorproxy.bridge.model.AuthConfig
import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import io.shiron.honamisensorproxy.bridge.provisioning.SinkProvisioning
import io.shiron.honamisensorproxy.bridge.sink.HttpSink
import io.shiron.honamisensorproxy.bridge.sink.PushResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Proves the tier-1 auth flow end to end without a real backend or camera:
 * a scanned provisioning payload -> [SinkConfig] -> [HttpSink] emits the correct
 * `Authorization: Bearer <token>` header + JSON envelope, and parses the `{accepted}` response.
 */
class HttpSinkAuthTest {

    @Test
    fun scannedPayload_producesBearerPushAndParsesResponse() = runTest {
        // 1. The exact JSON a scanned QR carries (per the app's provisioning contract).
        val qr = """
            {"v":1,"name":"BeatDash","ingest":"http://10.0.0.5:8080/api/hsp/ingest",
             "auth":"token","token":"s3cr3t-opaque","metrics":["heart_rate","calories"]}
        """.trimIndent()
        val config = SinkProvisioning.parse(qr).getOrThrow()
        val staticToken = (config.auth as AuthConfig.StaticToken).token

        // 2. Capture what the sink actually sends over the wire.
        var capturedMethod: HttpMethod? = null
        var capturedUrl: String? = null
        var capturedAuth: String? = null
        var capturedBody: String? = null

        val engine = MockEngine { request ->
            capturedMethod = request.method
            capturedUrl = request.url.toString()
            capturedAuth = request.headers[HttpHeaders.Authorization]
            capturedBody = (request.body as TextContent).text
            respond(
                content = """{"accepted":2}""",
                status = HttpStatusCode.OK,
                headers = headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val client = HttpClient(engine) { installBridgeDefaults() }

        val sink = HttpSink(
            config = config,
            auth = StaticTokenAuthenticator(staticToken),
            http = client,
            deviceId = "dev-1",
        )

        val samples = listOf(
            Sample(Metric.HeartRate, 148f, 1_784_570_592_000L, "fake"),
            Sample(Metric.Calories, 3.2f, 1_784_570_592_000L, "fake"),
            Sample(Metric.Steps, 11f, 1_784_570_592_000L, "fake"), // not wanted by this sink → filtered out
        )
        val result = sink.push(samples)

        // 3. The push is authorized exactly as the RFC 6750 contract requires.
        assertEquals(HttpMethod.Post, capturedMethod)
        assertEquals("http://10.0.0.5:8080/api/hsp/ingest", capturedUrl)
        assertEquals("Bearer s3cr3t-opaque", capturedAuth)

        // 4. Envelope carries the wanted metrics (HR + calories), not steps.
        val body = capturedBody ?: ""
        assertTrue(body.contains("\"source\":\"dev-1\""), body)
        assertTrue(body.contains("heart_rate"), body)
        assertTrue(body.contains("calories"), body)
        assertTrue(!body.contains("steps"), "steps is not in this sink's metrics: $body")

        // 5. Response parsed back into a typed result.
        assertEquals(PushResult.Accepted(accepted = 2, sent = 2), result)
    }

    @Test
    fun unauthorizedResponse_isNonRetryableFailure() = runTest {
        val engine = MockEngine {
            respond("""{"error":"invalid_token"}""", HttpStatusCode.Unauthorized)
        }
        val client = HttpClient(engine) { installBridgeDefaults() }
        val config = SinkProvisioning.parse(
            """{"name":"X","ingest":"https://x.test/ingest","auth":"token","token":"bad","metrics":["heart_rate"]}""",
        ).getOrThrow()
        val sink = HttpSink(config, StaticTokenAuthenticator("bad"), client, "dev-1")

        val result = sink.push(listOf(Sample(Metric.HeartRate, 100f, 1L, "fake")))

        assertTrue(result is PushResult.Failed)
        assertTrue(!result.retryable, "401 should not be retried")
    }
}
