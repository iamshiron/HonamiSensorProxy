package io.shiron.honamisensorproxy.profile

/**
 * A single configured sink ("profile") — the identity of the backend this app pushes to.
 * Provisioned by scanning a QR code (§7). The beta supports one profile at a time.
 *
 * The [token] is a bearer secret; persistence must store it encrypted (see [ProfileStore]).
 */
data class Profile(
    val name: String,
    val ingestUrl: String,
    val token: String,
) {
    val isUsable: Boolean get() = ingestUrl.isNotBlank()
}
