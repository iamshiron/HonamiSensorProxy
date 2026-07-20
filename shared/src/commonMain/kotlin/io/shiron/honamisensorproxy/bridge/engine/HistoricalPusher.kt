package io.shiron.honamisensorproxy.bridge.engine

import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import io.shiron.honamisensorproxy.bridge.sink.PushResult
import io.shiron.honamisensorproxy.bridge.sink.SampleSink
import io.shiron.honamisensorproxy.bridge.source.HistoricalSource

/** Outcome of a historical push. */
data class PushSummary(
    val read: Int,
    val accepted: Int,
    val batches: Int,
    val failedBatches: Int,
)

/**
 * Reads a [HistoricalSource] over a range and forwards it to sinks in fixed-size batches,
 * reporting progress. This is the batched counterpart to [BridgeEngine]'s live merge — same
 * sink contract, but driven by a bounded one-shot read instead of a continuous stream.
 */
class HistoricalPusher(
    private val sinks: List<SampleSink>,
    private val batchSize: Int = 200,
) {
    /** Progress messages, human-readable, for the UI log. */
    fun interface Progress {
        fun emit(line: String)
    }

    suspend fun push(
        source: HistoricalSource,
        want: Set<Metric>,
        startEpochMillis: Long,
        endEpochMillis: Long,
        progress: Progress = Progress {},
    ): PushSummary {
        progress.emit("reading ${source.id} for ${want.joinToString(",") { it.wireName }}…")
        var read = 0
        var accepted = 0
        var batches = 0
        var failedBatches = 0
        val buffer = ArrayList<Sample>(batchSize)

        suspend fun flush() {
            if (buffer.isEmpty()) return
            batches++
            val batch = buffer.toList()
            buffer.clear()
            for (sink in sinks) {
                when (val result = sink.push(batch)) {
                    is PushResult.Accepted -> {
                        accepted += result.accepted
                        progress.emit("batch $batches → ${sink.config.name}: accepted ${result.accepted}/${result.sent}")
                    }
                    is PushResult.Failed -> {
                        failedBatches++
                        progress.emit("batch $batches → ${sink.config.name}: FAILED (${result.reason})")
                    }
                    PushResult.Skipped ->
                        progress.emit("batch $batches → ${sink.config.name}: skipped (no wanted metrics)")
                }
            }
        }

        source.readRange(want, startEpochMillis, endEpochMillis).collect { sample ->
            read++
            buffer.add(sample)
            if (buffer.size >= batchSize) flush()
        }
        flush()

        progress.emit("done: read $read, accepted $accepted across $batches batch(es), $failedBatches failed")
        return PushSummary(read = read, accepted = accepted, batches = batches, failedBatches = failedBatches)
    }
}
