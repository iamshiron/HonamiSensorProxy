package io.shiron.honamisensorproxy

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import io.shiron.honamisensorproxy.bridge.source.FakeHistoricalSource
import io.shiron.honamisensorproxy.bridge.source.HealthConnectSource
import io.shiron.honamisensorproxy.bridge.source.HistoricalSource
import kotlinx.coroutines.launch

@Composable
actual fun rememberHistoricalProviders(): List<HistoricalSource> {
    val context = LocalContext.current
    return remember { listOf(HealthConnectSource(context), FakeHistoricalSource()) }
}

/** Holds the in-flight permission request across the Activity Result round-trip. */
private class PendingPermission {
    var onResult: ((Boolean) -> Unit)? = null
    var required: Set<String> = emptySet()
}

@Composable
actual fun rememberPermissionGate(): PermissionGate {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val pending = remember { PendingPermission() }

    val launcher = rememberLauncherForActivityResult(
        PermissionController.createRequestPermissionResultContract(),
    ) { granted: Set<String> ->
        val callback = pending.onResult
        pending.onResult = null
        callback?.invoke(granted.containsAll(pending.required))
    }

    return remember(context, launcher, scope) {
        PermissionGate { source, onResult ->
            // Only Health Connect needs a runtime grant; the fake provider is always usable.
            if (source !is HealthConnectSource) {
                onResult(true)
                return@PermissionGate
            }
            scope.launch {
                if (!source.isAvailable()) {
                    onResult(false)
                    return@launch
                }
                val needed = source.permissionsFor()
                val granted = HealthConnectClient.getOrCreate(context)
                    .permissionController.getGrantedPermissions()
                if (granted.containsAll(needed)) {
                    onResult(true)
                } else {
                    pending.onResult = onResult
                    pending.required = needed
                    launcher.launch(needed)
                }
            }
        }
    }
}
