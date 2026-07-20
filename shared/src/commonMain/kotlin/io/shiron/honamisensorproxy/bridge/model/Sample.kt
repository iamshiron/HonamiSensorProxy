package io.shiron.honamisensorproxy.bridge.model

/**
 * One normalized reading. [value] is a float in the metric's canonical unit
 * (see [Metric.unit]); a bare number is ambiguous, so the [metric] always travels with it.
 *
 * See BridgeApp.md §3.
 */
data class Sample(
    val metric: Metric,
    val value: Float,
    /** UTC epoch milliseconds, provided by the sensor/SDK — never "now" unless genuinely unknown. */
    val epochMillis: Long,
    /** Adapter id that produced this reading, e.g. `"fake-heart-rate"`. */
    val source: String,
)
