package io.shiron.honamisensorproxy.bridge.source

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.ActiveCaloriesBurnedRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.Record
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.Sample
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import java.time.Instant
import kotlin.reflect.KClass

/**
 * Reads already-recorded health data from Health Connect (BridgeApp.md §4.2). On a Samsung phone
 * this is the data Samsung Health synced in; the same adapter transparently covers Fitbit, Google
 * Fit, Zepp, Mi Fitness, etc. — Health Connect is the provider-agnostic datastore.
 *
 * Batched/post-hoc by nature: it reads what the companion app recorded over the requested range.
 */
class HealthConnectSource(private val context: Context) : HistoricalSource {
    override val id: String = "health-connect"
    override val label: String = "Health Connect"
    override val metrics: Set<Metric> =
        setOf(Metric.HeartRate, Metric.Calories, Metric.Steps, Metric.SpO2)

    private val client: HealthConnectClient by lazy { HealthConnectClient.getOrCreate(context) }

    /** The Health Connect read permission backing each metric. */
    fun readPermission(metric: Metric): String = when (metric) {
        Metric.HeartRate -> HealthPermission.getReadPermission(HeartRateRecord::class)
        Metric.Steps -> HealthPermission.getReadPermission(StepsRecord::class)
        Metric.Calories -> HealthPermission.getReadPermission(ActiveCaloriesBurnedRecord::class)
        Metric.SpO2 -> HealthPermission.getReadPermission(OxygenSaturationRecord::class)
    }

    /** Read permissions needed for [want] (defaults to everything this adapter can read). */
    fun permissionsFor(want: Set<Metric> = metrics): Set<String> =
        want.intersect(metrics).map(::readPermission).toSet()

    override suspend fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    override suspend fun ensurePermissions(): Boolean {
        if (!isAvailable()) return false
        val granted = client.permissionController.getGrantedPermissions()
        return granted.containsAll(permissionsFor())
    }

    /** Health Connect is pull-only; there is no live stream. */
    override fun stream(want: Set<Metric>): Flow<Sample> = flow {}

    override fun readRange(
        want: Set<Metric>,
        startEpochMillis: Long,
        endEpochMillis: Long,
    ): Flow<Sample> = flow {
        if (!isAvailable() || endEpochMillis <= startEpochMillis) return@flow
        val granted = client.permissionController.getGrantedPermissions()
        val start = Instant.ofEpochMilli(startEpochMillis)
        val end = Instant.ofEpochMilli(endEpochMillis)

        // Fail-soft per metric: skip anything not requested or not permitted (§1 principle 6).
        if (Metric.HeartRate in want && readPermission(Metric.HeartRate) in granted) {
            readAll(HeartRateRecord::class, start, end) { record ->
                for (s in record.samples) {
                    emit(Sample(Metric.HeartRate, s.beatsPerMinute.toFloat(), s.time.toEpochMilli(), id))
                }
            }
        }
        if (Metric.Steps in want && readPermission(Metric.Steps) in granted) {
            readAll(StepsRecord::class, start, end) { record ->
                emit(Sample(Metric.Steps, record.count.toFloat(), record.endTime.toEpochMilli(), id))
            }
        }
        if (Metric.Calories in want && readPermission(Metric.Calories) in granted) {
            readAll(ActiveCaloriesBurnedRecord::class, start, end) { record ->
                emit(Sample(Metric.Calories, record.energy.inKilocalories.toFloat(), record.endTime.toEpochMilli(), id))
            }
        }
        if (Metric.SpO2 in want && readPermission(Metric.SpO2) in granted) {
            readAll(OxygenSaturationRecord::class, start, end) { record ->
                emit(Sample(Metric.SpO2, record.percentage.value.toFloat(), record.time.toEpochMilli(), id))
            }
        }
    }

    /** Pages through every record of [type] in the range, invoking [onRecord] for each. */
    private suspend fun <T : Record> FlowCollector<Sample>.readAll(
        type: KClass<T>,
        start: Instant,
        end: Instant,
        onRecord: suspend FlowCollector<Sample>.(T) -> Unit,
    ) {
        var pageToken: String? = null
        do {
            val response = client.readRecords(
                ReadRecordsRequest(
                    recordType = type,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = pageToken,
                ),
            )
            for (record in response.records) onRecord(record)
            pageToken = response.pageToken
        } while (pageToken != null)
    }

    override fun close() {}
}
