package io.shiron.honamisensorproxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * Technical detail moved off the home screen: the exact sink URL, last result, and the raw event
 * log of every read/flush. This is the "background information" surface.
 */
@Composable
fun ActivityScreen(state: AppState) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "Activity",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Text("Sink endpoint", style = MaterialTheme.typography.titleSmall, color = HspColors.InkDim)
        Text(
            if (state.isConfigured) state.ingestUrl else "not configured",
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.fillMaxWidth(),
        )
        state.lastResult?.let {
            Text("Last sync: $it", style = MaterialTheme.typography.bodyMedium)
        }

        HorizontalDivider()

        Text("Event log", style = MaterialTheme.typography.titleMedium)
        if (state.log.isEmpty()) {
            Text("No events yet.", style = MaterialTheme.typography.bodySmall, color = HspColors.InkDim)
        }
        for (line in state.log) {
            Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
        }
    }
}
