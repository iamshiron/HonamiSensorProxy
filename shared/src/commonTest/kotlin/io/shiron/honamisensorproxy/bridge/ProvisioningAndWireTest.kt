package io.shiron.honamisensorproxy.bridge

import io.shiron.honamisensorproxy.bridge.model.AuthConfig
import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import io.shiron.honamisensorproxy.bridge.model.toWire
import io.shiron.honamisensorproxy.bridge.provisioning.SinkProvisioning
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvisioningAndWireTest {

    @Test
    fun parsesJsonQrPayload() {
        // The primary form the in-app scanner expects — note the local-dev http:// ingest.
        val payload = """
            {"v":1,"name":"BeatDash","ingest":"http://192.168.1.10:8080/api/hsp/ingest",
             "auth":"token","token":"abc123","metrics":["heart_rate","calories","steps","spo2"]}
        """.trimIndent()

        val cfg = SinkProvisioning.parse(payload).getOrThrow()

        assertEquals("BeatDash", cfg.name)
        assertEquals("http://192.168.1.10:8080/api/hsp/ingest", cfg.ingestUrl)
        assertEquals(setOf(Metric.HeartRate, Metric.Calories, Metric.Steps, Metric.SpO2), cfg.metrics)
        assertEquals(AuthConfig.StaticToken("abc123"), cfg.auth)
    }

    @Test
    fun parsesTier1DeepLink() {
        val link = "beatsensor://sink?name=BeatDash" +
            "&ingest=https%3A%2F%2Fbeatdash.app%2Fapi%2Fhealth%2Fingest" +
            "&metrics=heart_rate,calories&auth=token&token=abc123"

        val cfg = SinkProvisioning.parse(link).getOrThrow()

        assertEquals("BeatDash", cfg.name)
        assertEquals("https://beatdash.app/api/health/ingest", cfg.ingestUrl)
        assertEquals(setOf(Metric.HeartRate, Metric.Calories), cfg.metrics)
        assertEquals(AuthConfig.StaticToken("abc123"), cfg.auth)
    }

    @Test
    fun rejectsMissingToken() {
        val payload = """{"name":"X","ingest":"https://x.test/ingest","auth":"token","metrics":["heart_rate"]}"""
        assertTrue(SinkProvisioning.parse(payload).isFailure)
    }

    @Test
    fun sampleMapsToCanonicalWireForm() {
        // 2026-07-20T18:03:12Z == 1784570592000 ms
        val wire = Sample(Metric.HeartRate, 148f, 1_784_570_592_000L, "fake").toWire()

        assertEquals("heart_rate", wire.metric)
        assertEquals("bpm", wire.unit)
        assertEquals(148f, wire.value)
        assertEquals("2026-07-20T18:03:12Z", wire.recordedAt)
    }
}
