package io.shiron.honamisensorproxy.profile

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * In-memory stub — iOS is out of scope for the beta (§1 non-goals). A real implementation would
 * use the iOS Keychain.
 */
@Composable
actual fun rememberProfileStore(): ProfileStore = remember { InMemoryProfileStore() }

private class InMemoryProfileStore : ProfileStore {
    private var stored: Profile? = null
    override suspend fun load(): Profile? = stored
    override suspend fun save(profile: Profile) { stored = profile }
    override suspend fun clear() { stored = null }
}
