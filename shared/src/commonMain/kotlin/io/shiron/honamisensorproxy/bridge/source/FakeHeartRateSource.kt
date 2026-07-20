package io.shiron.honamisensorproxy.bridge.source

import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Clock
import kotlin.math.sin
import kotlin.random.Random

/**
 * A synthetic heart-rate source. It requires no hardware or permissions, so it exercises the
 * whole pipeline (source → engine → sink) end-to-end. Emits one [Sample] every [intervalMillis]
 * on a gently oscillating curve around ~90 bpm with a little noise.
 *
 * This is the MVP stand-in for the real [SensorSource] adapters (Health Connect, BLE, Wear).
 */
class FakeHeartRateSource(
    private val intervalMillis: Long = 1_000L,
) : SensorSource {
    override val id: String = "fake-heart-rate"
    override val metrics: Set<Metric> = setOf(Metric.HeartRate)

    override suspend fun isAvailable(): Boolean = true
    override suspend fun ensurePermissions(): Boolean = true

    override fun stream(want: Set<Metric>): Flow<Sample> = flow {
        if (Metric.HeartRate !in want) return@flow
        var tick = 0
        while (true) {
            val baseline = 90f + 30f * sin(tick / 12.0).toFloat()
            val noise = Random.nextInt(-3, 4)
            emit(
                Sample(
                    metric = Metric.HeartRate,
                    value = (baseline + noise).coerceIn(40f, 200f),
                    epochMillis = Clock.System.now().toEpochMilliseconds(),
                    source = id,
                ),
            )
            tick++
            delay(intervalMillis)
        }
    }

    override fun close() {}
}
