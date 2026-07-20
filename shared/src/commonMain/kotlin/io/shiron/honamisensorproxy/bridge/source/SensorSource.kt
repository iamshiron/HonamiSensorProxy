package io.shiron.honamisensorproxy.bridge.source

import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import kotlinx.coroutines.flow.Flow

/**
 * The device seam. Every source — Health Connect, BLE, Wear Health Services, or a fake —
 * normalizes to a [Flow] of [Sample]. A stream unifies pull SDKs (poll + emit) and push
 * SDKs (emit from callback); downstream never sees the difference. See BridgeApp.md §4.
 */
interface SensorSource {
    /** Stable adapter id, e.g. `"health-connect"`, `"ble-hrs"`, `"fake-heart-rate"`. */
    val id: String

    /** Which metrics this adapter can produce. */
    val metrics: Set<Metric>

    /** True when the SDK is present and the device is supported. */
    suspend fun isAvailable(): Boolean

    /** Requests/verifies the runtime permissions this source needs; false disables just this source. */
    suspend fun ensurePermissions(): Boolean

    /** Emits normalized samples for the requested metrics until the flow is cancelled. */
    fun stream(want: Set<Metric>): Flow<Sample>

    /** Releases any resources held by the adapter. */
    fun close()
}
