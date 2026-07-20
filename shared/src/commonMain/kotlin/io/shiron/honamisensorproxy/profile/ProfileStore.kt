package io.shiron.honamisensorproxy.profile

import androidx.compose.runtime.Composable

/**
 * Persists the single [Profile] across launches. Implementations must store the bearer token
 * in platform-secure storage (Android Keystore–backed), never in plaintext (§14).
 */
interface ProfileStore {
    /** Loads the saved profile, or null if none has been configured. */
    suspend fun load(): Profile?

    /** Saves (replaces) the profile. */
    suspend fun save(profile: Profile)

    /** Removes the saved profile and its secrets. */
    suspend fun clear()
}

/** The platform's secure [ProfileStore]. */
@Composable
expect fun rememberProfileStore(): ProfileStore
