package one.aipass.data

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.POST
import retrofit2.http.Url

/**
 * Retrofit interface for OAuth2 endpoints
 * Note: This implementation uses JSON instead of form-urlencoded
 * The backend does not accept application/x-www-form-urlencoded (not RFC 6749 compliant)
 */
interface OAuth2ApiService {

    /**
     * Token endpoint - handles both authorization_code and refresh_token grant types
     * POST {tokenEndpoint}
     *
     * For authorization_code grant:
     *   - Set grantType = "authorization_code"
     *   - Provide code, redirectUri, clientId, codeVerifier
     *
     * For refresh_token grant:
     *   - Set grantType = "refresh_token"
     *   - Provide refreshToken, clientId
     */
    @POST
    suspend fun token(
        @Url url: String,
        @Body request: OAuth2TokenRequest
    ): Response<OAuth2TokenResponse>

    /**
     * Revoke access token
     * POST {revocationEndpoint}
     */
    @POST
    suspend fun revokeToken(
        @Url url: String,
        @Body request: OAuth2RevokeRequest
    ): Response<Void>
}

/**
 * OAuth2 token request
 * Used for both authorization_code and refresh_token grant types
 */
data class OAuth2TokenRequest(
    val grantType: String,

    // For authorization_code grant:
    val code: String? = null,
    val redirectUri: String? = null,
    val codeVerifier: String? = null,

    // For refresh_token grant:
    val refreshToken: String? = null,

    // Common:
    val clientId: String,
    val clientSecret: String? = null
)

/**
 * OAuth2 token revocation request
 */
data class OAuth2RevokeRequest(
    val token: String
)

/**
 * OAuth2 token response (RFC 6749 compliant)
 */
data class OAuth2TokenResponse(
    val access_token: String,
    val token_type: String = "Bearer",
    val expires_in: Long,
    val scope: String,
    val refresh_token: String? = null
)

/**
 * OAuth2 error response (RFC 6749 compliant)
 */
data class OAuth2ErrorResponse(
    val error: String,
    val error_description: String? = null,
    val error_uri: String? = null
)
