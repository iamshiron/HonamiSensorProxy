package io.shiron.honamisensorproxy.bridge.engine

import io.shiron.honamisensorproxy.bridge.model.Sample
import io.shiron.honamisensorproxy.bridge.sink.PushResult

/** Observable events emitted by the [BridgeEngine], for logging/UI. */
sealed interface BridgeEvent {
    data class SourceReady(val id: String) : BridgeEvent
    data class SourceUnavailable(val id: String, val reason: String) : BridgeEvent
    data class SampleEmitted(val sample: Sample) : BridgeEvent
    data class BatchFlushed(val sinkName: String, val batchSize: Int, val result: PushResult) : BridgeEvent
    data object Started : BridgeEvent
    data object Stopped : BridgeEvent
}
