package io.shiron.honamisensorproxy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

/**
 * Profile configuration (the persisted single sink) plus an Advanced section. Scanning a QR code
 * fills these fields, but they stay directly editable — the app is an open ecosystem.
 */
@Composable
fun SettingsScreen(state: AppState) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            "Settings",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.primary,
        )

        Text("Profile", style = MaterialTheme.typography.titleMedium)
        OutlinedButton(
            onClick = { state.scanning = true },
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Scan QR code to configure sink") }

        OutlinedTextField(
            value = state.sinkName,
            onValueChange = { state.sinkName = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = state.ingestUrl,
            onValueChange = { state.ingestUrl = it },
            label = { Text("Ingest URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        )
        OutlinedTextField(
            value = state.token,
            onValueChange = { state.token = it },
            label = { Text("Bearer token") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = { state.saveProfile() }) { Text("Save") }
            OutlinedButton(onClick = { state.clearProfile() }) { Text("Clear profile") }
        }

        HorizontalDivider()

        Text("Advanced", style = MaterialTheme.typography.titleMedium)
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Show test provider", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Adds the synthetic \"Fake\" source on Home for testing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = HspColors.InkDim,
                )
            }
            Switch(checked = state.showAdvanced, onCheckedChange = { state.showAdvanced = it })
        }
    }
}
