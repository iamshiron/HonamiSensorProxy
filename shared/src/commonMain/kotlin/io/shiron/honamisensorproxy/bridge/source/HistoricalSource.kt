package io.shiron.honamisensorproxy.bridge.source

import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import kotlinx.coroutines.flow.Flow

/**
 * A [SensorSource] that can also be read historically over a time range — the "Push from…"
 * flow. Pull-based providers (Health Connect and friends) expose already-recorded data; the
 * range and metrics are the user's choice, so this is a one-shot read that completes.
 *
 * Live push/stream sources (BLE, Wear) implement only [SensorSource]; this is the seam for
 * the batched, post-hoc side described in BridgeApp.md §4 / §4.2.
 */
interface HistoricalSource : SensorSource {
    /** Human-friendly name for the provider picker, e.g. "Health Connect". */
    val label: String

    /**
     * Emits every recorded sample for the requested [want] metrics in
     * `[startEpochMillis, endEpochMillis)` (UTC), then completes. Fail-soft: a metric with no
     * permission or no data simply yields nothing.
     */
    fun readRange(want: Set<Metric>, startEpochMillis: Long, endEpochMillis: Long): Flow<Sample>
}
