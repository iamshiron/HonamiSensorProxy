package io.shiron.honamisensorproxy.bridge.model

/**
 * A canonical health metric. Every source normalizes its readings to one of these,
 * and every sink receives them under the same [wireName] with the same [unit].
 *
 * See BridgeApp.md §3 (canonical data model) and §12.7 (metric registry).
 */
enum class Metric(val wireName: String, val unit: String) {
    HeartRate("heart_rate", "bpm"),
    Calories("calories", "kcal"),
    Steps("steps", "count"),
    SpO2("spo2", "percent");

    companion object {
        /** Resolves a wire name (e.g. `"heart_rate"`) back to a [Metric], or null if unknown. */
        fun fromWireName(name: String): Metric? = entries.firstOrNull { it.wireName == name }
    }
}
