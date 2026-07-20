package io.shiron.honamisensorproxy.bridge.auth

/**
 * The authorization seam. The push is always `Authorization: Bearer <token>` (RFC 6750);
 * only *how* the token is obtained varies. See BridgeApp.md §5.
 */
interface Authenticator {
    /** A currently-valid bearer token for the push. Refreshes silently / re-pairs as needed. */
    suspend fun accessToken(): String
}

/** Tier 1 — the backend mints a long-lived token; the app just stores and presents it (§5, §12.3). */
class StaticTokenAuthenticator(private val token: String) : Authenticator {
    override suspend fun accessToken(): String = token
}
