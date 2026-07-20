package io.shiron.honamisensorproxy.bridge.source

import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.math.sin

/**
 * A synthetic [HistoricalSource] that fabricates plausible readings across any requested range.
 * Requires no permissions or real data, so it makes the whole "Push from… + range" flow testable
 * end to end. Sample counts are deterministic (a fixed cadence per metric), which is what the
 * pusher tests assert on.
 */
class FakeHistoricalSource : HistoricalSource {
    override val id: String = "fake-historical"
    override val label: String = "Fake (synthetic)"
    override val metrics: Set<Metric> = setOf(Metric.HeartRate, Metric.Calories, Metric.Steps, Metric.SpO2)

    override suspend fun isAvailable(): Boolean = true
    override suspend fun ensurePermissions(): Boolean = true

    /** Not a live source; the historical read is the point. */
    override fun stream(want: Set<Metric>): Flow<Sample> = flow {}

    override fun readRange(want: Set<Metric>, startEpochMillis: Long, endEpochMillis: Long): Flow<Sample> = flow {
        if (endEpochMillis <= startEpochMillis) return@flow
        for (metric in metrics) {
            if (metric !in want) continue
            val stepMillis = cadenceMillis(metric)
            var t = startEpochMillis
            var i = 0
            while (t < endEpochMillis) {
                emit(Sample(metric, valueFor(metric, i), t, id))
                t += stepMillis
                i++
            }
        }
    }

    private fun cadenceMillis(metric: Metric): Long = when (metric) {
        Metric.HeartRate -> 60_000L        // 1/min
        Metric.SpO2 -> 30 * 60_000L        // 1/30 min
        Metric.Steps -> 60 * 60_000L       // 1/hour
        Metric.Calories -> 60 * 60_000L    // 1/hour
    }

    private fun valueFor(metric: Metric, i: Int): Float = when (metric) {
        Metric.HeartRate -> (85f + 30f * sin(i / 20.0).toFloat()).coerceIn(45f, 190f)
        Metric.SpO2 -> (97f + 1.5f * sin(i / 7.0).toFloat()).coerceIn(90f, 100f)
        Metric.Steps -> (300f + 250f * sin(i / 3.0).toFloat()).coerceAtLeast(0f)
        Metric.Calories -> (45f + 20f * sin(i / 4.0).toFloat()).coerceAtLeast(0f)
    }

    override fun close() {}
}
