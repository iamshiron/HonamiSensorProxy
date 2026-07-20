package io.shiron.honamisensorproxy.bridge.provisioning

import io.shiron.honamisensorproxy.bridge.model.AuthConfig
import io.shiron.honamisensorproxy.bridge.model.Metric
import io.shiron.honamisensorproxy.bridge.model.SinkConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Turns a provisioning QR payload (or deep link) into a [SinkConfig]. See BridgeApp.md §7 / §12.5.
 *
 * Two accepted forms — [parse] auto-detects by first non-space character:
 *
 * 1. **JSON (primary, what the app's in-app scanner expects):**
 *    ```json
 *    {"v":1,"name":"BeatDash","ingest":"http://host:port/api/hsp/ingest",
 *     "auth":"token","token":"<opaque>","metrics":["heart_rate","calories","steps","spo2"]}
 *    ```
 *    JSON needs no registered URL scheme and is trivial to parse from a scanned code.
 *
 * 2. **Deep link (fallback, matches the doc sketch):**
 *    ```
 *    beatsensor://sink?name=BeatDash&ingest=<url-enc>&metrics=heart_rate,calories&auth=token&token=<opaque>
 *    ```
 *
 * Only the tier-1 (`auth=token`) form is fully supported today; `auth=oidc` parses into
 * [AuthConfig.Oidc] but its authenticator isn't wired up yet.
 */
object SinkProvisioning {
    private const val DEEP_LINK_SCHEME = "beatsensor://sink"
    private const val DEFAULT_CADENCE_SECONDS = 5

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parse(payload: String): Result<SinkConfig> = runCatching {
        val trimmed = payload.trim()
        when {
            trimmed.startsWith("{") -> parseJson(trimmed)
            trimmed.startsWith(DEEP_LINK_SCHEME) -> parseDeepLink(trimmed)
            else -> error("unrecognized provisioning payload (expected JSON or $DEEP_LINK_SCHEME)")
        }
    }

    // --- JSON form -----------------------------------------------------------

    @Serializable
    private data class QrPayload(
        val v: Int = 1,
        val name: String = "sink",
        val ingest: String? = null,
        val auth: String = "token",
        val token: String? = null,
        val issuer: String? = null,
        val clientId: String? = null,
        val scope: String? = null,
        val metrics: List<String> = emptyList(),
        val cadence: Int? = null,
    )

    private fun parseJson(payload: String): SinkConfig {
        val p = json.decodeFromString(QrPayload.serializer(), payload)
        val ingest = requireIngest(p.ingest)
        val metrics = knownMetrics(p.metrics)
        val auth = resolveAuth(p.auth, p.token, p.issuer, p.clientId, p.scope)
        return SinkConfig(
            name = p.name,
            ingestUrl = ingest,
            metrics = metrics,
            cadenceSeconds = p.cadence ?: DEFAULT_CADENCE_SECONDS,
            auth = auth,
        )
    }

    // --- Deep-link form ------------------------------------------------------

    private fun parseDeepLink(payload: String): SinkConfig {
        val params = payload.substringAfter('?', "")
            .split('&')
            .filter { it.isNotEmpty() }
            .associate { pair ->
                pair.substringBefore('=') to urlDecode(pair.substringAfter('=', ""))
            }
        val ingest = requireIngest(params["ingest"])
        val metrics = knownMetrics((params["metrics"] ?: "").split(','))
        val auth = resolveAuth(
            kind = params["auth"],
            token = params["token"],
            issuer = params["issuer"],
            clientId = params["clientId"],
            scope = params["scope"],
        )
        return SinkConfig(
            name = params["name"] ?: "sink",
            ingestUrl = ingest,
            metrics = metrics,
            cadenceSeconds = params["cadence"]?.toIntOrNull() ?: DEFAULT_CADENCE_SECONDS,
            auth = auth,
        )
    }

    // --- Shared validation ---------------------------------------------------

    private fun requireIngest(ingest: String?): String {
        val url = requireNotNull(ingest) { "missing 'ingest'" }
        // Spec (§12.1/§14) prefers HTTPS in production; http:// is allowed here for local-dev backends.
        require(url.startsWith("http://") || url.startsWith("https://")) {
            "ingest must be an http(s) URL"
        }
        return url
    }

    private fun knownMetrics(names: Iterable<String>): Set<Metric> {
        val metrics = names.mapNotNull { Metric.fromWireName(it.trim()) }.toSet()
        require(metrics.isNotEmpty()) { "no known metrics in 'metrics'" }
        return metrics
    }

    private fun resolveAuth(
        kind: String?,
        token: String?,
        issuer: String?,
        clientId: String?,
        scope: String?,
    ): AuthConfig = when (kind) {
        "token" -> AuthConfig.StaticToken(requireNotNull(token) { "missing 'token'" })
        "oidc" -> AuthConfig.Oidc(
            issuer = requireNotNull(issuer) { "missing 'issuer'" },
            clientId = clientId ?: "sensor-bridge",
            scope = scope ?: "ingest:write",
        )
        else -> error("unsupported auth '$kind'")
    }

    /** Minimal percent-decoding (`%XX` and `+` → space) — enough for URL-encoded query values. */
    private fun urlDecode(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            when (val c = s[i]) {
                '+' -> out.append(' ')
                '%' -> {
                    out.append(s.substring(i + 1, i + 3).toInt(16).toChar())
                    i += 2
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }
}
