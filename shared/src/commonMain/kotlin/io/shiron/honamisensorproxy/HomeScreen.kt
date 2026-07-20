package io.shiron.honamisensorproxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(state: AppState, onOpenSettings: () -> Unit) {
    var showRangePicker by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text(
            "Honami Sensor Proxy",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        // Sink status card ---------------------------------------------------------------------
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (state.isConfigured) {
                    Text("● Connected", style = MaterialTheme.typography.labelMedium, color = HspColors.Success)
                    Text(
                        state.sinkName.ifBlank { "Sink" },
                        style = MaterialTheme.typography.titleMedium,
                    )
                } else {
                    Text("No sink configured", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Scan your QR code to connect a backend.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = HspColors.InkDim,
                    )
                    Button(onClick = onOpenSettings) { Text("Set up in Settings") }
                }
            }
        }

        // Source (provider) --------------------------------------------------------------------
        Text("Source", style = MaterialTheme.typography.titleSmall, color = HspColors.InkDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (provider in state.visibleProviders) {
                FilterChip(
                    selected = provider.id == state.selectedProviderId,
                    onClick = { state.selectedProviderId = provider.id },
                    label = { Text(provider.label) },
                    enabled = !state.pushing,
                )
            }
        }

        // Range --------------------------------------------------------------------------------
        Text("Range", style = MaterialTheme.typography.titleSmall, color = HspColors.InkDim)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (preset in RangePreset.entries) {
                FilterChip(
                    selected = preset == state.rangePreset,
                    onClick = {
                        state.rangePreset = preset
                        if (preset == RangePreset.Custom) showRangePicker = true
                    },
                    label = { Text(preset.label) },
                    enabled = !state.pushing,
                )
            }
        }
        Text(
            "Selected: ${state.rangeLabel()}",
            style = MaterialTheme.typography.bodySmall,
            color = HspColors.InkDim,
        )

        // Sync --------------------------------------------------------------------------------
        Button(
            onClick = { state.push() },
            enabled = state.isConfigured && !state.pushing,
            modifier = Modifier.fillMaxWidth(),
        ) { Text(if (state.pushing) "Syncing…" else "Sync now") }

        state.lastResult?.let { result ->
            Text(result, style = MaterialTheme.typography.bodyMedium)
        }
    }

    if (showRangePicker) {
        val pickerState = rememberDateRangePickerState()
        DatePickerDialog(
            onDismissRequest = { showRangePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    val startUtc = pickerState.selectedStartDateMillis
                    if (startUtc != null) {
                        val start = utcMillisToLocalDate(startUtc)
                        val end = pickerState.selectedEndDateMillis?.let(::utcMillisToLocalDate) ?: start
                        state.setCustomRange(start, end)
                    }
                    showRangePicker = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { showRangePicker = false }) { Text("Cancel") } },
        ) {
            DateRangePicker(state = pickerState, modifier = Modifier.weight(1f))
        }
    }
}

/** The Material date picker reports UTC-midnight millis; read the calendar date off it. */
private fun utcMillisToLocalDate(utcMillis: Long): LocalDate =
    Instant.fromEpochMilliseconds(utcMillis).toLocalDateTime(TimeZone.UTC).date
