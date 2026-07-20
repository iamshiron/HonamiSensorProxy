package io.shiron.honamisensorproxy

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import com.adamglin.PhosphorIcons
import com.adamglin.phosphoricons.Regular
import com.adamglin.phosphoricons.regular.Gear
import com.adamglin.phosphoricons.regular.House
import com.adamglin.phosphoricons.regular.Pulse
import io.shiron.honamisensorproxy.bridge.createBridgeHttpClient
import io.shiron.honamisensorproxy.profile.rememberProfileStore

/**
 * Honami Sensor Proxy (HSP) — beta shell. A bottom-nav app over three screens: a clean [HomeScreen]
 * for everyday syncing, an [ActivityScreen] for the event log/technical detail, and a
 * [SettingsScreen] for the persisted profile. All screens share one [AppState].
 */
@Composable
@Preview
fun App() {
    HspTheme {
        val scope = rememberCoroutineScope()
        val http = remember { createBridgeHttpClient() }
        val providers = rememberHistoricalProviders()
        val permissionGate = rememberPermissionGate()
        val profileStore = rememberProfileStore()
        val state = remember { AppState(scope, http, providers, permissionGate, profileStore) }
        var screen by remember { mutableStateOf(Screen.Home) }

        Scaffold(
            bottomBar = {
                NavigationBar {
                    for (destination in Screen.entries) {
                        NavigationBarItem(
                            selected = screen == destination,
                            onClick = { screen = destination },
                            icon = { Icon(destination.navIcon, contentDescription = destination.title) },
                            label = { Text(destination.title) },
                            colors = NavigationBarItemDefaults.colors(
                                // Solid lavender pill + dark icon reads clearly on the near-black nav bar.
                                indicatorColor = MaterialTheme.colorScheme.primary,
                                selectedIconColor = MaterialTheme.colorScheme.onPrimary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            ),
                        )
                    }
                }
            },
        ) { innerPadding ->
            Box(Modifier.padding(innerPadding).fillMaxSize()) {
                when (screen) {
                    Screen.Home -> HomeScreen(state, onOpenSettings = { screen = Screen.Settings })
                    Screen.Activity -> ActivityScreen(state)
                    Screen.Settings -> SettingsScreen(state)
                }
            }
        }

        QrScannerHost(
            active = state.scanning,
            onResult = { payload ->
                state.onScanned(payload)
                state.scanning = false
            },
            onDismiss = { state.scanning = false },
        )
    }
}

/** Phosphor icon for each bottom-nav destination. */
private val Screen.navIcon: ImageVector
    get() = when (this) {
        Screen.Home -> PhosphorIcons.Regular.House
        Screen.Activity -> PhosphorIcons.Regular.Pulse
        Screen.Settings -> PhosphorIcons.Regular.Gear
    }

