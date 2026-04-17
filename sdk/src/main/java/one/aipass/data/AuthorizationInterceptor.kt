package one.aipass.data

import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that automatically injects the current access token as
 * `Authorization: Bearer <token>` on every outgoing request.
 *
 * Callers of the SDK's Retrofit services therefore don't need to pass the token
 * themselves — it's read from [OAuth2TokenStorage] at call time.
 *
 * If the request already carries an `Authorization` header (e.g. the token
 * endpoint uses client credentials in the body and doesn't need one), it is
 * left untouched. Paired with [TokenAuthenticator], which handles 401 refresh.
 */
internal class AuthorizationInterceptor(
    private val tokenStorage: OAuth2TokenStorage
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        // Skip if caller already set an Authorization header (e.g. legacy code
        // passing it manually, or the OAuth2 token endpoint itself).
        if (original.header("Authorization") != null) {
            return chain.proceed(original)
        }

        val token = tokenStorage.getAccessToken()
        val request = if (token.isNullOrEmpty()) {
            original
        } else {
            original.newBuilder()
                .header("Authorization", "Bearer $token")
                .build()
        }

        return chain.proceed(request)
    }
}
