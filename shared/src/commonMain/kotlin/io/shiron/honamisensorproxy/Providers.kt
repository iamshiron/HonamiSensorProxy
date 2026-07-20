package io.shiron.honamisensorproxy

import androidx.compose.runtime.Composable
import io.shiron.honamisensorproxy.bridge.source.HistoricalSource

/**
 * The set of historical providers available on this platform, in display order. The list is the
 * whole "select a provider" story — it stays provider-agnostic because every entry is just a
 * [HistoricalSource]. Android contributes Health Connect; every platform gets the synthetic one.
 */
@Composable
expect fun rememberHistoricalProviders(): List<HistoricalSource>

/** Ensures a provider's runtime permissions are granted before a push, driving any OS prompt. */
fun interface PermissionGate {
    /** Requests/verifies permissions for [source]; invokes [onResult] with whether it's usable. */
    fun ensure(source: HistoricalSource, onResult: (Boolean) -> Unit)
}

/** A [PermissionGate] wired to the platform's permission machinery (Health Connect on Android). */
@Composable
expect fun rememberPermissionGate(): PermissionGate
