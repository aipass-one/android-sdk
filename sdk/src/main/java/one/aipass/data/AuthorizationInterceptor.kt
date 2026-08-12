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
    private val tokenStorage: OAuth2TokenStorage,
    private val clientId: String
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val original = chain.request()

        val token = tokenStorage.getAccessToken()
        val requestBuilder = original.newBuilder()

        if (original.header("Authorization") == null && !token.isNullOrEmpty()) {
            requestBuilder
                .header("Authorization", "Bearer $token")
        }

        if (original.header(CLIENT_ID_HEADER) == null) {
            requestBuilder.header(CLIENT_ID_HEADER, clientId)
        }

        val request = requestBuilder.build()

        return chain.proceed(request)
    }

    private companion object {
        const val CLIENT_ID_HEADER = "X-AIPass-OAuth-Client-Id"
    }
}
