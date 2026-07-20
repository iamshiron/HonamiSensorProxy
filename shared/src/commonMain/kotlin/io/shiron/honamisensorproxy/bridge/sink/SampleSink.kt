package io.shiron.honamisensorproxy.bridge.sink

import io.shiron.honamisensorproxy.bridge.model.Sample
import io.shiron.honamisensorproxy.bridge.model.SinkConfig

/**
 * The backend seam. A sink receives a batch of samples and forwards the ones it wants.
 * The concrete [HttpSink] speaks the wire contract; tests can supply an in-memory sink.
 * See BridgeApp.md §6.
 */
interface SampleSink {
    val config: SinkConfig

    /** Forwards the relevant samples in [batch] to the backend. Never throws — returns a [PushResult]. */
    suspend fun push(batch: List<Sample>): PushResult
}

/** Outcome of a single push, surfaced to the UI/outbox. */
sealed interface PushResult {
    /** Backend accepted [accepted] of [sent] samples. */
    data class Accepted(val accepted: Int, val sent: Int) : PushResult

    /** Nothing in the batch matched this sink's metrics; no request was made. */
    data object Skipped : PushResult

    /** The push failed; [retryable] indicates a transient error (network/5xx) worth retrying. */
    data class Failed(val reason: String, val retryable: Boolean) : PushResult
}
