package io.shiron.honamisensorproxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.tooling.preview.Preview
import io.shiron.honamisensorproxy.bridge.auth.StaticTokenAuthenticator
import io.shiron.honamisensorproxy.bridge.createBridgeHttpClient
import io.shiron.honamisensorproxy.bridge.engine.HistoricalPusher
import io.shiron.honamisensorproxy.bridge.model.AuthConfig
import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.SinkConfig
import io.shiron.honamisensorproxy.bridge.provisioning.SinkProvisioning
import io.shiron.honamisensorproxy.bridge.sink.HttpSink
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime

private enum class RangePreset(val label: String) {
    Today("Today"),
    Yesterday("Yesterday"),
    Last7Days("Last 7 days"),
    Last30Days("Last 30 days"),
    Custom("Custom"),
}

/**
 * Honami Sensor Proxy (HSP) control panel. Configure a sink, pick a **provider** (any
 * [io.shiron.honamisensorproxy.bridge.source.HistoricalSource]) and a **range**, then push that
 * window of health data to the sink. Provider-agnostic: Health Connect and the synthetic source
 * are the same abstraction to this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@Preview
fun App() {
    HspTheme {
        val scope = rememberCoroutineScope()
        val http = remember { createBridgeHttpClient() }
        val log = remember { mutableStateListOf<String>() }
        val providers = rememberHistoricalProviders()
        val permissionGate = rememberPermissionGate()

        var ingestUrl by remember { mutableStateOf("") }
        var token by remember { mutableStateOf("") }
        var scanning by remember { mutableStateOf(false) }

        var selectedProviderId by remember { mutableStateOf(providers.firstOrNull()?.id ?: "") }
        var rangePreset by remember { mutableStateOf(RangePreset.Today) }
        var customStart by remember { mutableStateOf<LocalDate?>(null) }
        var customEnd by remember { mutableStateOf<LocalDate?>(null) }
        var showRangePicker by remember { mutableStateOf(false) }
        var pushing by remember { mutableStateOf(false) }

        fun logLine(line: String) {
            log.add(0, line)
            if (log.size > 200) log.removeAt(log.lastIndex)
        }

        fun applyProvisioning(payload: String) {
            SinkProvisioning.parse(payload)
                .onSuccess { cfg ->
                    ingestUrl = cfg.ingestUrl
                    (cfg.auth as? AuthConfig.StaticToken)?.let { token = it.token }
                    logLine("provisioned sink '${cfg.name}' for ${cfg.metrics}")
                }
                .onFailure { logLine("bad provisioning payload: ${it.message}") }
        }

        // Resolve the selected preset/custom range into [startMillis, endMillis) at local day bounds.
        fun resolveRange(): Pair<Long, Long> {
            val tz = TimeZone.currentSystemDefault()
            val today = Clock.System.now().toLocalDateTime(tz).date
            val (startDate, endExclusive) = when (rangePreset) {
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
            return startDate.atStartOfDayIn(tz).toEpochMilliseconds() to
                endExclusive.atStartOfDayIn(tz).toEpochMilliseconds()
        }

        fun rangeLabel(): String = when (rangePreset) {
            RangePreset.Custom -> {
                val s = customStart
                val e = customEnd
                if (s == null) "custom (pick dates)" else if (e == null || e == s) "$s" else "$s → $e"
            }
            else -> rangePreset.label
        }

        fun push() {
            val provider = providers.firstOrNull { it.id == selectedProviderId } ?: return
            val (startMs, endMs) = resolveRange()
            logLine("push requested: ${provider.label} · ${rangeLabel()}")
            permissionGate.ensure(provider) { granted ->
                if (!granted) {
                    logLine("permission denied or provider unavailable: ${provider.label}")
                    return@ensure
                }
                pushing = true
                val sink = HttpSink(
                    config = SinkConfig(
                        name = "sink",
                        ingestUrl = ingestUrl.trim(),
                        metrics = Metric.entries.toSet(),
                        cadenceSeconds = 5,
                        auth = AuthConfig.StaticToken(token),
                    ),
                    auth = StaticTokenAuthenticator(token),
                    http = http,
                    deviceId = "honami-sensor-proxy",
                )
                scope.launch {
                    try {
                        HistoricalPusher(listOf(sink)).push(
                            source = provider,
                            want = provider.metrics,
                            startEpochMillis = startMs,
                            endEpochMillis = endMs,
                        ) { line -> logLine(line) }
                    } catch (e: Exception) {
                        logLine("push error: ${e.message}")
                    } finally {
                        pushing = false
                    }
                }
            }
        }

        Column(
            modifier = Modifier
                .safeContentPadding()
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Honami Sensor Proxy",
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                "HSP · sensor → HTTP bridge",
                style = MaterialTheme.typography.labelMedium,
                color = HspColors.InkDim,
            )

            // --- Sink configuration: scan a QR code, or edit the fields directly -------------
            OutlinedButton(
                onClick = { scanning = true },
                enabled = !pushing,
                modifier = Modifier.fillMaxWidth(),
            ) { Text("Scan QR code to configure sink") }
            OutlinedTextField(
                value = ingestUrl,
                onValueChange = { ingestUrl = it },
                label = { Text("Ingest URL") },
                singleLine = true,
                enabled = !pushing,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Bearer token") },
                singleLine = true,
                enabled = !pushing,
                modifier = Modifier.fillMaxWidth(),
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )

            HorizontalDivider()

            // --- Push from provider + range -------------------------------------------------
            Text("Push from…", style = MaterialTheme.typography.titleMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (provider in providers) {
                    FilterChip(
                        selected = provider.id == selectedProviderId,
                        onClick = { selectedProviderId = provider.id },
                        label = { Text(provider.label) },
                        enabled = !pushing,
                    )
                }
            }

            Text("Range", style = MaterialTheme.typography.titleSmall, color = HspColors.InkDim)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (preset in RangePreset.entries) {
                    FilterChip(
                        selected = preset == rangePreset,
                        onClick = {
                            rangePreset = preset
                            if (preset == RangePreset.Custom) showRangePicker = true
                        },
                        label = { Text(preset.label) },
                        enabled = !pushing,
                    )
                }
            }
            Text("Selected: ${rangeLabel()}", style = MaterialTheme.typography.bodySmall, color = HspColors.InkDim)

            Button(
                onClick = { push() },
                enabled = !pushing && ingestUrl.isNotBlank() && selectedProviderId.isNotEmpty(),
                modifier = Modifier.fillMaxWidth(),
            ) { Text(if (pushing) "Pushing…" else "Push") }

            HorizontalDivider()

            Text("Event log", style = MaterialTheme.typography.titleMedium)
            Column(modifier = Modifier.fillMaxWidth()) {
                for (line in log) {
                    Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
        }

        if (showRangePicker) {
            val pickerState = rememberDateRangePickerState()
            DatePickerDialog(
                onDismissRequest = { showRangePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        val startUtc = pickerState.selectedStartDateMillis
                        val endUtc = pickerState.selectedEndDateMillis
                        if (startUtc != null) {
                            customStart = utcMillisToLocalDate(startUtc)
                            customEnd = endUtc?.let { utcMillisToLocalDate(it) } ?: customStart
                            rangePreset = RangePreset.Custom
                        }
                        showRangePicker = false
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { showRangePicker = false }) { Text("Cancel") } },
            ) {
                DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
            }
        }

        QrScannerHost(
            active = scanning,
            onResult = { payload ->
                applyProvisioning(payload)
                scanning = false
            },
            onDismiss = { scanning = false },
        )
    }
}

/** The Material date picker reports UTC-midnight millis; read the calendar date off it. */
private fun utcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(utcMillis).toLocalDateTime(TimeZone.UTC).date
