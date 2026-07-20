package io.shiron.honamisensorproxy

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.ktor.client.HttpClient
import io.shiron.honamisensorproxy.bridge.auth.StaticTokenAuthenticator
import io.shiron.honamisensorproxy.bridge.engine.HistoricalPusher
import io.shiron.honamisensorproxy.bridge.model.AuthConfig
import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.SinkConfig
import io.shiron.honamisensorproxy.bridge.provisioning.SinkProvisioning
import io.shiron.honamisensorproxy.bridge.sink.HttpSink
import io.shiron.honamisensorproxy.bridge.source.HistoricalSource
import io.shiron.honamisensorproxy.profile.Profile
import io.shiron.honamisensorproxy.profile.ProfileStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/** Bottom-navigation destinations. */
enum class Screen(val title: String) {
    Home("Home"),
    Activity("Activity"),
    Settings("Settings"),
}

/** Selectable push ranges. */
enum class RangePreset(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    Last7Days("Last 7 days"),
    Last30Days("Last 30 days"),
    Custom("Custom"),
}

private const val FAKE_PROVIDER_ID = "fake-historical"
private const val DEVICE_ID = "honami-sensor-proxy"

/**
 * Central UI state + actions, shared across the Home/Activity/Settings screens. Holds the single
 * configured profile, provider/range selection, the event log, and a user-facing last-result line.
 * Created once per composition via `remember`.
 */
class AppState(
    private val scope: CoroutineScope,
    private val http: HttpClient,
    val providers: List<HistoricalSource>,
    private val permissionGate: PermissionGate,
    private val profileStore: ProfileStore,
) {
    // Profile / sink
    var sinkName by mutableStateOf("")
    var ingestUrl by mutableStateOf("")
    var token by mutableStateOf("")
    val isConfigured: Boolean get() = ingestUrl.isNotBlank()

    // Provider + range
    var showAdvanced by mutableStateOf(false)
    var selectedProviderId by mutableStateOf(defaultProviderId())
    var rangePreset by mutableStateOf(RangePreset.Today)
    var customStart by mutableStateOf<LocalDate?>(null)
    var customEnd by mutableStateOf<LocalDate?>(null)

    // Runtime
    var scanning by mutableStateOf(false)
    var pushing by mutableStateOf(false)
    var lastResult by mutableStateOf<String?>(null)
    val log = mutableStateListOf<String>()

    init {
        scope.launch { profileStore.load()?.let(::applyProfile) }
    }

    /** Providers shown on Home — the synthetic one is hidden unless Advanced is enabled. */
    val visibleProviders: List<HistoricalSource>
        get() = (if (showAdvanced) providers else providers.filterNot { it.id == FAKE_PROVIDER_ID })
            .ifEmpty { providers }

    val selectedProvider: HistoricalSource?
        get() = providers.firstOrNull { it.id == selectedProviderId }

    private fun defaultProviderId(): String =
        providers.firstOrNull { it.id != FAKE_PROVIDER_ID }?.id ?: providers.firstOrNull()?.id ?: ""

    private fun applyProfile(p: Profile) {
        sinkName = p.name
        ingestUrl = p.ingestUrl
        token = p.token
    }

    fun logLine(line: String) {
        log.add(0, line)
        if (log.size > 300) log.removeAt(log.lastIndex)
    }

    /** Persists the current field values as the single profile. */
    fun saveProfile() {
        scope.launch { profileStore.save(Profile(sinkName, ingestUrl, token)) }
    }

    fun clearProfile() {
        sinkName = ""
        ingestUrl = ""
        token = ""
        scope.launch { profileStore.clear() }
        logLine("profile cleared")
    }

    fun onScanned(payload: String) {
        SinkProvisioning.parse(payload)
            .onSuccess { cfg ->
                sinkName = cfg.name
                ingestUrl = cfg.ingestUrl
                (cfg.auth as? AuthConfig.StaticToken)?.let { token = it.token }
                saveProfile()
                logLine("provisioned '${cfg.name}' for ${cfg.metrics}")
            }
            .onFailure { logLine("bad QR payload: ${it.message}") }
    }

    fun setCustomRange(start: LocalDate, end: LocalDate) {
        customStart = start
        customEnd = end
        rangePreset = RangePreset.Custom
    }

    fun rangeLabel(): String = when (rangePreset) {
        RangePreset.Custom -> {
            val s = customStart
            val e = customEnd
            if (s == null) "custom (pick dates)" else if (e == null || e == s) "$s" else "$s → $e"
        }
        else -> rangePreset.label
    }

    /** Resolves the selected range to `[startMillis, endMillis)` at local day boundaries. */
    fun resolveRange(): Pair<Long, Long> {
        val tz = TimeZone.currentSystemDefault()
        val today = Clock.System.now().toLocalDateTime(tz).date
        val (start, endExclusive) = when (rangePreset) {
            RangePreset.Today -> today to today.plus(1, DateTimeUnit.DAY)
            RangePreset.Yesterday -> today.minus(1, DateTimeUnit.DAY) to today
            RangePreset.Last7Days -> today.minus(6, DateTimeUnit.DAY) to today.plus(1, DateTimeUnit.DAY)
            RangePreset.Last30Days -> today.minus(29, DateTimeUnit.DAY) to today.plus(1, DateTimeUnit.DAY)
            RangePreset.Custom -> {
                val s = customStart ?: today
                val e = customEnd ?: s
                s to e.plus(1, DateTimeUnit.DAY)
            }
        }
        return start.atStartOfDayIn(tz).toEpochMilliseconds() to endExclusive.atStartOfDayIn(tz).toEpochMilliseconds()
    }

    /** Reads the selected range from the selected provider and pushes it to the configured sink. */
    fun push() {
        val provider = selectedProvider ?: return
        if (!isConfigured) {
            logLine("no sink configured")
            return
        }
        val (startMs, endMs) = resolveRange()
        logLine("sync requested: ${provider.label} · ${rangeLabel()}")
        permissionGate.ensure(provider) { granted ->
            if (!granted) {
                lastResult = "Permission denied for ${provider.label}"
                logLine("permission denied or provider unavailable: ${provider.label}")
                return@ensure
            }
            pushing = true
            lastResult = "Syncing…"
            val sink = HttpSink(
                config = SinkConfig(
                    name = sinkName.ifBlank { "sink" },
                    ingestUrl = ingestUrl.trim(),
                    metrics = Metric.entries.toSet(),
                    cadenceSeconds = 5,
                    auth = AuthConfig.StaticToken(token),
                ),
                auth = StaticTokenAuthenticator(token),
                http = http,
                deviceId = DEVICE_ID,
            )
            scope.launch {
                try {
                    val summary = HistoricalPusher(listOf(sink)).push(
                        source = provider,
                        want = provider.metrics,
                        startEpochMillis = startMs,
                        endEpochMillis = endMs,
                    ) { line -> logLine(line) }
                    lastResult = when {
                        summary.read == 0 -> "No data found in ${rangeLabel()}"
                        summary.failedBatches == 0 -> "Pushed ${summary.accepted} of ${summary.read} readings"
                        else -> "Partial: ${summary.accepted} accepted, ${summary.failedBatches} batch(es) failed"
                    }
                } catch (e: Exception) {
                    lastResult = "Sync error: ${e.message}"
                    logLine("push error: ${e.message}")
                } finally {
                    pushing = false
                }
            }
        }
    }
}
