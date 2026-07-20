package io.shiron.honamisensorproxy

import androidx.compose.runtime.Composable

/**
 * A full-screen QR scanner overlay. When [active] is true the platform shows a camera preview,
 * calls [onResult] with the first decoded QR string, then the caller should set active=false.
 * [onDismiss] fires if the user cancels or camera permission is denied.
 *
 * Android provides a real CameraX + ML Kit implementation; other targets render nothing
 * (iOS is out of scope — see BridgeApp.md §1 non-goals).
 */
@Composable
expect fun QrScannerHost(
    active: Boolean,
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
)
