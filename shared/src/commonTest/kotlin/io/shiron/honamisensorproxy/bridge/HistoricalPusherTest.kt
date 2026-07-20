package io.shiron.honamisensorproxy.bridge

import io.shiron.honamisensorproxy.bridge.engine.HistoricalPusher
import io.shiron.honamisensorproxy.bridge.model.AuthConfig
import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import io.shiron.honamisensorproxy.bridge.model.SinkConfig
import io.shiron.honamisensorproxy.bridge.sink.PushResult
import io.shiron.honamisensorproxy.bridge.sink.SampleSink
import io.shiron.honamisensorproxy.bridge.source.FakeHistoricalSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HistoricalPusherTest {

    private class RecordingSink(override val config: SinkConfig) : SampleSink {
        val received = mutableListOf<Sample>()
        override suspend fun push(batch: List<Sample>): PushResult {
            val relevant = batch.filter { it.metric in config.metrics }
            received += relevant
            return PushResult.Accepted(relevant.size, relevant.size)
        }
    }

    private fun sink(metrics: Set<Metric> = Metric.entries.toSet()) = RecordingSink(
        SinkConfig("test", "https://x.test/ingest", metrics, 5, AuthConfig.StaticToken("t")),
    )

    private val oneHour = 3_600_000L

    @Test
    fun pushesEveryFakeSampleInRangeToSink() = runTest {
        val source = FakeHistoricalSource()
        val recording = sink()

        // Over exactly one hour [0, 1h): HR 60 (1/min) + SpO2 2 (1/30min) + Steps 1 + Calories 1 = 64.
        val summary = HistoricalPusher(listOf(recording)).push(
            source = source,
            want = source.metrics,
            startEpochMillis = 0L,
            endEpochMillis = oneHour,
        )

        assertEquals(64, summary.read)
        assertEquals(64, summary.accepted)
        assertEquals(64, recording.received.size)
        assertEquals(0, summary.failedBatches)
    }

    @Test
    fun onlyReadsRequestedMetrics() = runTest {
        val source = FakeHistoricalSource()
        val recording = sink()

        val summary = HistoricalPusher(listOf(recording)).push(
            source = source,
            want = setOf(Metric.HeartRate),
            startEpochMillis = 0L,
            endEpochMillis = oneHour,
        )

        assertEquals(60, summary.read, "only 60 per-minute heart-rate samples in one hour")
        assertTrue(recording.received.all { it.metric == Metric.HeartRate })
    }

    @Test
    fun emptyRangeReadsNothing() = runTest {
        val recording = sink()
        val summary = HistoricalPusher(listOf(recording)).push(
            FakeHistoricalSource(), setOf(Metric.HeartRate), startEpochMillis = 1_000L, endEpochMillis = 1_000L,
        )
        assertEquals(0, summary.read)
        assertEquals(0, recording.received.size)
    }
}
