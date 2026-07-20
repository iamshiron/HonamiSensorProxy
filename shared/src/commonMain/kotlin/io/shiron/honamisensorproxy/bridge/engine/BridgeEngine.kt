package io.shiron.honamisensorproxy.bridge.engine

import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import io.shiron.honamisensorproxy.bridge.sink.SampleSink
import io.shiron.honamisensorproxy.bridge.source.SensorSource
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * The heart of the bridge. It merges every available [SensorSource] into one `Flow<Sample>`,
 * batches by size *or* time (whichever comes first), and forwards each batch to every [SampleSink].
 *
 * That single merge point is the whole "same flow for any watch" story (BridgeApp.md §2, §8).
 */
class BridgeEngine(
    private val sources: List<SensorSource>,
    private val sinks: List<SampleSink>,
    /** Flush once this many samples have buffered. */
    private val batchSize: Int = 32,
    /** Flush at least this often, even if [batchSize] hasn't been reached. */
    private val flushIntervalMillis: Long = 5_000L,
    private val onEvent: (BridgeEvent) -> Unit = {},
) {
    /** Union of the metrics every sink wants — sources are asked for exactly these. */
    private val wantedMetrics: Set<Metric> = sinks.flatMap { it.config.metrics }.toSet()

    /**
     * Runs until the calling coroutine is cancelled. Launch this in a scope and cancel the job to stop.
     */
    suspend fun run() = coroutineScope {
        onEvent(BridgeEvent.Started)
        val channel = Channel<Sample>(capacity = Channel.BUFFERED)

        // One producer per available source, all feeding the single channel.
        val liveSources = sources.filter { source ->
            val available = source.isAvailable() && source.ensurePermissions()
            if (available) onEvent(BridgeEvent.SourceReady(source.id))
            else onEvent(BridgeEvent.SourceUnavailable(source.id, "unavailable or permission denied"))
            available
        }

        launch {
            val flows = liveSources.map { it.stream(wantedMetrics) }
            flows.merge().collect { sample ->
                onEvent(BridgeEvent.SampleEmitted(sample))
                channel.send(sample)
            }
            // All (finite) sources completed — signal the consumer to drain and stop.
            channel.close()
        }

        // Consumer: batch by size or time, then dispatch to every sink.
        try {
            val batch = ArrayList<Sample>(batchSize)
            outer@ while (isActive) {
                val first = channel.receiveCatching().getOrNull() ?: break
                batch.add(first)
                val flushAt = TimeSource.Monotonic.markNow() + flushIntervalMillis.milliseconds
                while (batch.size < batchSize) {
                    val remaining = flushAt - TimeSource.Monotonic.markNow()
                    if (remaining <= 0.milliseconds) break // time-based flush
                    val next = withTimeoutOrNull(remaining) { channel.receiveCatching() }
                    when {
                        next == null -> break // timed out → flush what we have
                        next.isClosed -> {
                            dispatch(batch.toList())
                            batch.clear()
                            break@outer
                        }
                        else -> next.getOrNull()?.let { batch.add(it) }
                    }
                }
                dispatch(batch.toList())
                batch.clear()
            }
        } finally {
            liveSources.forEach { it.close() }
            onEvent(BridgeEvent.Stopped)
        }
    }

    private suspend fun dispatch(batch: List<Sample>) {
        for (sink in sinks) {
            val result = sink.push(batch)
            onEvent(BridgeEvent.BatchFlushed(sink.config.name, batch.size, result))
        }
    }
}
