package io.shiron.honamisensorproxy

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import io.shiron.honamisensorproxy.bridge.source.FakeHistoricalSource
import io.shiron.honamisensorproxy.bridge.source.HistoricalSource

/** iOS has no Health Connect; only the synthetic provider (real HealthKit is out of scope). */
@Composable
actual fun rememberHistoricalProviders(): List<HistoricalSource> =
    remember { listOf(FakeHistoricalSource()) }

@Composable
actual fun rememberPermissionGate(): PermissionGate =
    remember { PermissionGate { _, onResult -> onResult(true) } }
