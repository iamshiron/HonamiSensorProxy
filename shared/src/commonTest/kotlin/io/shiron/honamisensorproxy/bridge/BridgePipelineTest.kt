package io.shiron.honamisensorproxy.bridge

import io.shiron.honamisensorproxy.bridge.engine.BridgeEngine
import io.shiron.honamisensorproxy.bridge.model.AuthConfig
import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import io.shiron.honamisensorproxy.bridge.model.SinkConfig
import io.shiron.honamisensorproxy.bridge.sink.PushResult
import io.shiron.honamisensorproxy.bridge.sink.SampleSink
import io.shiron.honamisensorproxy.bridge.source.SensorSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** End-to-end proof that samples flow: source -> engine (merge/batch) -> sink. */
class BridgePipelineTest {

    private class ListSource(
        override val id: String,
        override val metrics: Set<Metric>,
        private val samples: List<Sample>,
    ) : SensorSource {
        override suspend fun isAvailable(): Boolean = true
        override suspend fun ensurePermissions(): Boolean = true
        override fun stream(want: Set<Metric>): Flow<Sample> =
            samples.filter { it.metric in want }.asFlow()
        override fun close() {}
    }

    private class RecordingSink(override val config: SinkConfig) : SampleSink {
        val received = mutableListOf<Sample>()
        var pushes = 0
        override suspend fun push(batch: List<Sample>): PushResult {
            val relevant = batch.filter { it.metric in config.metrics }
            received += relevant
            pushes++
            return PushResult.Accepted(relevant.size, relevant.size)
        }
    }

    private fun sinkConfig(metrics: Set<Metric>) = SinkConfig(
        name = "test-sink",
        ingestUrl = "https://example.test/ingest",
        metrics = metrics,
        cadenceSeconds = 5,
        auth = AuthConfig.StaticToken("t"),
    )

    private fun hr(value: Float, t: Long) = Sample(Metric.HeartRate, value, t, "test-src")

    @Test
    fun engine_batchesEverySampleThroughToSink() = runTest {
        val samples = (1..7).map { hr(60f + it, it.toLong()) }
        val source = ListSource("test-src", setOf(Metric.HeartRate), samples)
        val sink = RecordingSink(sinkConfig(setOf(Metric.HeartRate)))

        val engine = BridgeEngine(
            sources = listOf(source),
            sinks = listOf(sink),
            batchSize = 5,
            flushIntervalMillis = 60_000L,
        )
        engine.run()

        assertEquals(7, sink.received.size, "every emitted sample should reach the sink")
        assertEquals(samples.map { it.value }, sink.received.map { it.value })
        assertTrue(sink.pushes >= 2, "7 samples at batchSize 5 should flush at least twice")
    }

    @Test
    fun engine_dropsMetricsTheSinkDoesNotWant() = runTest {
        val samples = listOf(
            hr(72f, 1),
            Sample(Metric.Steps, 10f, 2, "test-src"),
            hr(75f, 3),
        )
        val source = ListSource("test-src", setOf(Metric.HeartRate, Metric.Steps), samples)
        val sink = RecordingSink(sinkConfig(setOf(Metric.HeartRate))) // only wants HR

        BridgeEngine(listOf(source), listOf(sink), batchSize = 10, flushIntervalMillis = 60_000L).run()

        assertEquals(2, sink.received.size)
        assertTrue(sink.received.all { it.metric == Metric.HeartRate })
    }
}
