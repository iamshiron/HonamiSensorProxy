package io.shiron.honamisensorproxy.bridge.model

/**
 * A sink is pure configuration plus an authenticator. The app knows nothing about the
 * backend beyond what a [SinkConfig] carries. See BridgeApp.md §6.
 */
data class SinkConfig(
    val name: String,
    val ingestUrl: String,
    val metrics: Set<Metric>,
    val cadenceSeconds: Int,
    val auth: AuthConfig,
)

/** How a push to a sink is authorized. Only tier 1 (static token) is implemented for now. */
sealed interface AuthConfig {
    data class StaticToken(val token: String) : AuthConfig

    /** Tier 2 — OAuth 2.0 Device Authorization Grant. Not wired up yet (see §5, §12.4). */
    data class Oidc(val issuer: String, val clientId: String, val scope: String) : AuthConfig
}
