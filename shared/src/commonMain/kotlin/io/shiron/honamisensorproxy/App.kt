package io.shiron.honamisensorproxy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.dp
import io.shiron.honamisensorproxy.bridge.auth.StaticTokenAuthenticator
import io.shiron.honamisensorproxy.bridge.createBridgeHttpClient
import io.shiron.honamisensorproxy.bridge.engine.BridgeEngine
import io.shiron.honamisensorproxy.bridge.engine.BridgeEvent
import io.shiron.honamisensorproxy.bridge.model.AuthConfig
import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.SinkConfig
import io.shiron.honamisensorproxy.bridge.provisioning.SinkProvisioning
import io.shiron.honamisensorproxy.bridge.sink.HttpSink
import io.shiron.honamisensorproxy.bridge.sink.PushResult
import io.shiron.honamisensorproxy.bridge.source.FakeHeartRateSource
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import androidx.compose.ui.tooling.preview.Preview

/**
 * Honami Sensor Proxy (HSP) control panel. It runs the [FakeHeartRateSource] through the
 * [BridgeEngine] into a single [HttpSink], and shows the live event log — enough to prove the
 * whole source → engine → sink pipeline end to end.
 */
@Composable
@Preview
fun App() {
    HspTheme {
        val scope = rememberCoroutineScope()
        val http = remember { createBridgeHttpClient() }
        val log = remember { mutableStateListOf<String>() }

        var ingestUrl by remember { mutableStateOf("https://beatdash.app/api/health/ingest") }
        var token by remember { mutableStateOf("") }
        var provisionLink by remember { mutableStateOf("") }
        var scanning by remember { mutableStateOf(false) }
        var engineJob by remember { mutableStateOf<Job?>(null) }
        val running = engineJob != null

        fun logLine(line: String) {
            log.add(0, line)
            if (log.size > 100) log.removeAt(log.lastIndex)
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

        fun start() {
            if (running) return
            log.clear()
            val sink = HttpSink(
                config = SinkConfig(
                    name = "sink",
                    ingestUrl = ingestUrl.trim(),
                    metrics = setOf(Metric.HeartRate),
                    cadenceSeconds = 5,
                    auth = AuthConfig.StaticToken(token),
                ),
                auth = StaticTokenAuthenticator(token),
                http = http,
                deviceId = "honami-sensor-proxy",
            )
            val engine = BridgeEngine(
                sources = listOf(FakeHeartRateSource()),
                sinks = listOf(sink),
                batchSize = 5,
                flushIntervalMillis = 5_000L,
                onEvent = { event -> logLine(event.render()) },
            )
            engineJob = scope.launch { engine.run() }
        }

        fun stop() {
            engineJob?.cancel()
            engineJob = null
        }

        Column(
            modifier = Modifier
                .background(MaterialTheme.colorScheme.background)
                .safeContentPadding()
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
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
            Text(
                if (running) "● streaming — fake heart rate → sink" else "○ idle",
                style = MaterialTheme.typography.bodyMedium,
                color = if (running) HspColors.Success else HspColors.InkDim,
            )

            OutlinedTextField(
                value = provisionLink,
                onValueChange = { provisionLink = it },
                label = { Text("Provisioning payload (scanned JSON or beatsensor:// link)") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                keyboardActions = KeyboardActions(onDone = { applyProvisioning(provisionLink) }),
            )
            Button(
                onClick = { scanning = true },
                enabled = !running,
            ) { Text("Scan QR code") }

            OutlinedTextField(
                value = ingestUrl,
                onValueChange = { ingestUrl = it },
                label = { Text("Ingest URL (HTTPS)") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Bearer token") },
                singleLine = true,
                enabled = !running,
                modifier = Modifier.fillMaxWidth(),
            )

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Button(
                    onClick = { start() },
                    enabled = !running && ingestUrl.isNotBlank(),
                ) { Text("Start") }
                Button(
                    onClick = { stop() },
                    enabled = running,
                ) { Text("Stop") }
            }

            Text("Event log", style = MaterialTheme.typography.titleMedium)
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
            ) {
                for (line in log) {
                    Text(
                        line,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                    )
                }
            }
        }

        QrScannerHost(
            active = scanning,
            onResult = { payload ->
                provisionLink = payload
                applyProvisioning(payload)
                scanning = false
            },
            onDismiss = { scanning = false },
        )
    }
}

private fun BridgeEvent.render(): String = when (this) {
    BridgeEvent.Started -> "engine started"
    BridgeEvent.Stopped -> "engine stopped"
    is BridgeEvent.SourceReady -> "source ready: $id"
    is BridgeEvent.SourceUnavailable -> "source unavailable: $id ($reason)"
    is BridgeEvent.SampleEmitted ->
        "sample ${sample.metric.wireName}=${sample.value} @${sample.epochMillis}"
    is BridgeEvent.BatchFlushed -> {
        val outcome = when (val r = result) {
            is PushResult.Accepted -> "accepted ${r.accepted}/${r.sent}"
            PushResult.Skipped -> "skipped (no relevant samples)"
            is PushResult.Failed -> "FAILED (${r.reason})${if (r.retryable) " [retryable]" else ""}"
        }
        "flush → $sinkName [$batchSize] : $outcome"
    }
}
