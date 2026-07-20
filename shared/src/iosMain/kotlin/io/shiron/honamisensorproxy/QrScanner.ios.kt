package io.shiron.honamisensorproxy

import androidx.compose.runtime.Composable

/** No-op — camera QR scanning is Android-only for now (BridgeApp.md §1 non-goals). */
@Composable
actual fun QrScannerHost(
    active: Boolean,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
) {
}
