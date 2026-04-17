package one.aipass.data

import kotlinx.coroutines.runBlocking
import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route
import one.aipass.domain.OAuth2Manager

/**
 * OkHttp [Authenticator] that reacts to 401 responses by refreshing the OAuth2
 * access token and retrying the original request exactly once.
 *
 * Refresh is serialized via [OAuth2Manager.refreshAccessTokenIfNeeded] so
 * concurrent 401s share a single refresh attempt.
 *
 * If the refresh fails (no refresh token, refresh endpoint returns error), the
 * authenticator returns `null`, which causes OkHttp to surface the 401 to the
 * caller — the app can then prompt the user to re-authenticate.
 */
internal class TokenAuthenticator(
    private val managerProvider: () -> OAuth2Manager?,
    private val tokenStorage: OAuth2TokenStorage
) : Authenticator {

    override fun authenticate(route: Route?, response: Response): Request? {
        // Prevent infinite retry loop: if we already added a fresh token to this
        // request and still got 401, give up.
        if (response.request.header(RETRY_HEADER) != null) {
            return null
        }

        val manager = managerProvider() ?: return null

        // Capture the token that was on the failing request so we can detect
        // whether another in-flight refresh already produced a newer one.
        val previousAuthHeader = response.request.header("Authorization")

        val newToken = runBlocking {
            manager.refreshAccessTokenIfNeeded()
        } ?: return null

        val newAuthHeader = "Bearer $newToken"

        // If somehow we got back the exact same header we already sent (another
        // thread failed to refresh), don't loop forever.
        if (newAuthHeader == previousAuthHeader) {
            return null
        }

        return response.request.newBuilder()
            .header("Authorization", newAuthHeader)
            .header(RETRY_HEADER, "1")
            .build()
    }

    companion object {
        // Internal marker header stripped before the request leaves the client
        // -- OkHttp will send it, but we only read it back inside this class.
        private const val RETRY_HEADER = "X-AiPass-Auth-Retry"
    }
}
