package io.shiron.honamisensorproxy.bridge.model

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The JSON envelope every backend receives. See BridgeApp.md §6 and §12.2.
 *
 * ```json
 * { "source": "galaxy-watch-fe", "samples": [ { "metric": "heart_rate", ... } ] }
 * ```
 */
@Serializable
data class Envelope(
    val source: String,
    val samples: List<WireSample>,
)

/** A single sample on the wire. `metric` is snake_case; `recordedAt` is UTC RFC 3339. */
@Serializable
data class WireSample(
    val metric: String,
    val value: Float,
    val unit: String,
    val recordedAt: String,
)

/** The success response the ingest endpoint returns (§12.2). */
@Serializable
data class IngestResponse(
    val accepted: Int = 0,
    val received: Int? = null,
    val duplicates: Int? = null,
    val rejected: Int? = null,
)

/** Recommended 4xx/5xx error body shape (§12.1). */
@Serializable
data class IngestError(
    val error: String? = null,
    val message: String? = null,
    val details: List<String>? = null,
)

/** Maps a normalized [Sample] onto its wire representation. */
fun Sample.toWire(): WireSample = WireSample(
    metric = metric.wireName,
    value = value,
    unit = metric.unit,
    recordedAt = Instant.fromEpochMilliseconds(epochMillis).toString(),
)
