package one.aipass.domain

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import one.aipass.data.OAuth2ApiService
import one.aipass.data.OAuth2ErrorResponse
import one.aipass.data.OAuth2TokenStorage
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.security.SecureRandom
import java.util.concurrent.TimeUnit

/**
 * OAuth2 Manager
 * Handles OAuth2 Authorization Code + PKCE flow
 *
 * This is a generic OAuth2 client that works with any RFC 6749 compliant server.
 *
 * Usage:
 * ```
 * val manager = OAuth2Manager(context, oauth2Config)
 * manager.startAuthorization(activity) { result ->
 *     when (result) {
 *         is OAuth2Result.Success -> // Use access token
 *         is OAuth2Result.Error -> // Handle error
 *         is OAuth2Result.Cancelled -> // User cancelled
 *     }
 * }
 * ```
 */
class OAuth2Manager(
    private val context: Context,
    private val config: OAuth2Config
) {
    private val tokenStorage = OAuth2TokenStorage(context)
    private val apiService: OAuth2ApiService
    private var pendingPkceCodePair: PkceGenerator.PkceCodePair? = null
    private var pendingState: String? = null

    // Serializes refresh attempts so concurrent callers share a single refresh
    // and don't stampede the token endpoint.
    private val refreshMutex = Mutex()

    // SharedPreferences for persisting OAuth2 flow state across process death
    private val flowStatePrefs by lazy {
        context.getSharedPreferences("oauth2_flow_state", Context.MODE_PRIVATE)
    }

    companion object {
        private const val TAG = "OAuth2Manager"
        const val REQUEST_CODE_AUTHORIZATION = 1001

        // Keys for persisting OAuth2 flow state
        private const val KEY_PENDING_STATE = "pending_state"
        private const val KEY_PENDING_CODE_VERIFIER = "pending_code_verifier"
        private const val KEY_PENDING_CODE_CHALLENGE = "pending_code_challenge"
        private const val KEY_PENDING_CODE_CHALLENGE_METHOD = "pending_code_challenge_method"
    }

    init {
        // Create Retrofit instance for OAuth2 endpoints
        // Use a base URL from config but override with @Url in service methods
        val baseUrl = extractBaseUrl(config.tokenEndpoint)

        val okHttpClient = OkHttpClient.Builder()
            // Timeouts optimized for mobile networks
            .connectTimeout(15, TimeUnit.SECONDS)  // Faster failure on bad connections
            .readTimeout(45, TimeUnit.SECONDS)     // OAuth responses are quick
            .writeTimeout(45, TimeUnit.SECONDS)
            .callTimeout(60, TimeUnit.SECONDS)     // Overall request timeout

            // Connection pool for reusing connections
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 3,
                    keepAliveDuration = 30,
                    TimeUnit.SECONDS
                )
            )

            // Retry on connection failures
            .retryOnConnectionFailure(true)

            .apply {
                // Add interceptor to set Content-Type header for JSON requests
                addInterceptor { chain ->
                    val originalRequest = chain.request()
                    val requestBuilder = originalRequest.newBuilder()

                    // Add Content-Type header if body exists and doesn't already have it
                    if (originalRequest.body != null && originalRequest.header("Content-Type") == null) {
                        requestBuilder.header("Content-Type", "application/json; charset=UTF-8")
                    }

                    chain.proceed(requestBuilder.build())
                }

                // Add retry interceptor for mobile networks
                addInterceptor { chain ->
                    var request = chain.request()
                    var response: okhttp3.Response? = null
                    var tryCount = 0
                    val maxRetries = 2

                    while (tryCount < maxRetries) {
                        try {
                            response = chain.proceed(request)

                            // If response is successful or client error, don't retry
                            if (response.isSuccessful || response.code in 400..499) {
                                return@addInterceptor response
                            }

                            // Close the failed response
                            response.close()

                            tryCount++
                            if (tryCount < maxRetries) {
                                // Exponential backoff: 1s, 2s
                                Thread.sleep((1000 * tryCount).toLong())
                            }
                        } catch (e: Exception) {
                            tryCount++
                            if (tryCount >= maxRetries) {
                                throw e
                            }
                            Thread.sleep((1000 * tryCount).toLong())
                        }
                    }

                    response ?: throw java.io.IOException("Failed after $maxRetries retries")
                }
            }
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        apiService = retrofit.create(OAuth2ApiService::class.java)
    }

    /**
     * Start OAuth2 authorization flow
     * Opens browser for user to authorize the application
     *
     * @param activity The activity to start the authorization from
     * @param callback Called when authorization completes (success or failure)
     */
    fun startAuthorization(activity: Activity, callback: (OAuth2Result) -> Unit) {
        try {
            // Generate PKCE code pair
            val pkce = PkceGenerator.generate()
            pendingPkceCodePair = pkce

            // Generate state for CSRF protection
            val state = generateState()
            pendingState = state

            // Persist state and PKCE to survive process death
            persistFlowState(state, pkce)

            // Build authorization URL
            val authUrl = buildAuthorizationUrl(
                endpoint = config.authorizationEndpoint,
                clientId = config.clientId,
                redirectUri = config.redirectUri,
                scope = config.scopeString,
                codeChallenge = pkce.codeChallenge,
                codeChallengeMethod = pkce.codeChallengeMethod,
                state = state
            )


            // Open browser with authorization URL
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(authUrl))
            activity.startActivity(intent)

        } catch (e: Exception) {
            callback(
                OAuth2Result.Error(
                    error = "client_error",
                    errorDescription = "Failed to start authorization: ${e.message}",
                    exception = e
                )
            )
        }
    }

    /**
     * Handle OAuth2 callback from browser redirect
     * Call this from your callback activity with the intent data
     *
     * @param intent The intent from the callback activity
     * @param callback Called with the authorization result
     */
    suspend fun handleCallback(intent: Intent, callback: (OAuth2Result) -> Unit) {
        withContext(Dispatchers.IO) {
            try {
                val uri = intent.data
                if (uri == null) {
                    clearPersistedFlowState()
                    callback(
                        OAuth2Result.Error(
                            error = "invalid_callback",
                            errorDescription = "No data in callback intent"
                        )
                    )
                    return@withContext
                }


                // Restore flow state if process was killed (in-memory variables will be null)
                if (pendingState == null || pendingPkceCodePair == null) {
                    restoreFlowState()
                }

                // Check for error in callback
                val error = uri.getQueryParameter("error")
                if (error != null) {
                    val errorDesc = uri.getQueryParameter("error_description")

                    clearPersistedFlowState()

                    if (error == "access_denied") {
                        callback(OAuth2Result.Cancelled)
                    } else {
                        callback(
                            OAuth2Result.Error(
                                error = error,
                                errorDescription = errorDesc
                            )
                        )
                    }
                    return@withContext
                }

                // Extract authorization code
                val code = uri.getQueryParameter("code")
                if (code == null) {
                    clearPersistedFlowState()
                    callback(
                        OAuth2Result.Error(
                            error = "invalid_callback",
                            errorDescription = "No authorization code in callback"
                        )
                    )
                    return@withContext
                }

                // Validate state (CSRF protection)
                val returnedState = uri.getQueryParameter("state")
                if (returnedState != pendingState) {
                    clearPersistedFlowState()
                    callback(
                        OAuth2Result.Error(
                            error = "invalid_state",
                            errorDescription = "State parameter mismatch (CSRF protection)"
                        )
                    )
                    return@withContext
                }

                // Get PKCE code verifier
                val codeVerifier = pendingPkceCodePair?.codeVerifier
                if (codeVerifier == null) {
                    clearPersistedFlowState()
                    callback(
                        OAuth2Result.Error(
                            error = "invalid_state",
                            errorDescription = "PKCE code verifier not found (process may have been killed)"
                        )
                    )
                    return@withContext
                }

                // Exchange authorization code for access token
                exchangeCodeForToken(code, codeVerifier, callback)

            } catch (e: Exception) {
                callback(
                    OAuth2Result.Error(
                        error = "client_error",
                        errorDescription = "Failed to handle callback: ${e.message}",
                        exception = e
                    )
                )
            } finally {
                // Clear pending state
                pendingPkceCodePair = null
                pendingState = null
            }
        }
    }

    /**
     * Exchange authorization code for access token
     */
    private suspend fun exchangeCodeForToken(
        code: String,
        codeVerifier: String,
        callback: (OAuth2Result) -> Unit
    ) {
        try {

            val request = one.aipass.data.OAuth2TokenRequest(
                grantType = "authorization_code",
                code = code,
                redirectUri = config.redirectUri,
                clientId = config.clientId,
                codeVerifier = codeVerifier,
                clientSecret = config.clientSecret
            )

            val response = apiService.token(
                url = config.tokenEndpoint,
                request = request
            )

            if (response.isSuccessful) {
                val tokenResponse = response.body()!!

                // Save tokens
                tokenStorage.saveTokenResponse(
                    accessToken = tokenResponse.access_token,
                    expiresIn = tokenResponse.expires_in,
                    scope = tokenResponse.scope,
                    refreshToken = tokenResponse.refresh_token
                )

                // Clear persisted flow state after successful exchange
                clearPersistedFlowState()


                callback(
                    OAuth2Result.Success(
                        accessToken = tokenResponse.access_token,
                        tokenType = tokenResponse.token_type,
                        expiresIn = tokenResponse.expires_in,
                        scope = tokenResponse.scope,
                        refreshToken = tokenResponse.refresh_token
                    )
                )
            } else {
                // Parse error response
                val errorBody = response.errorBody()?.string()

                // Clear persisted flow state on error
                clearPersistedFlowState()

                // Try to parse as OAuth2ErrorResponse
                val error = try {
                    val gson = com.google.gson.Gson()
                    if (errorBody?.isNotEmpty() == true) {
                        gson.fromJson(errorBody, OAuth2ErrorResponse::class.java)
                    } else {
                        null
                    }
                } catch (e: Exception) {
                    null
                }

                callback(
                    OAuth2Result.Error(
                        error = error?.error ?: "token_exchange_failed",
                        errorDescription = error?.error_description
                            ?: "HTTP ${response.code()}: ${response.message()}"
                    )
                )
            }

        } catch (e: Exception) {
            clearPersistedFlowState()
            callback(
                OAuth2Result.Error(
                    error = "network_error",
                    errorDescription = "Failed to exchange code for token: ${e.message}",
                    exception = e
                )
            )
        }
    }

    /**
     * Get current access token if valid
     * Returns null if expired or doesn't exist
     */
    fun getAccessToken(): String? {
        return if (tokenStorage.hasValidAccessToken()) {
            tokenStorage.getAccessToken()
        } else {
            null
        }
    }

    /**
     * Check if user is authenticated
     */
    fun isAuthenticated(): Boolean {
        return tokenStorage.isAuthenticated()
    }

    /**
     * Get token scope
     */
    fun getTokenScope(): String? {
        return tokenStorage.getTokenScope()
    }

    /**
     * Refresh access token using refresh token.
     *
     * Serialized by [refreshMutex] so concurrent callers share a single refresh
     * attempt. If another call already refreshed the token while this caller was
     * waiting on the lock, the already-valid token is returned without hitting
     * the network again.
     */
    suspend fun refreshAccessToken(): OAuth2Result {
        return refreshMutex.withLock {
            // Fast-path: another coroutine refreshed the token while we were
            // waiting. Return it without a second network call.
            if (tokenStorage.hasValidAccessToken()) {
                val token = tokenStorage.getAccessToken()
                if (token != null) {
                    return@withLock OAuth2Result.Success(
                        accessToken = token,
                        tokenType = "Bearer",
                        expiresIn = tokenStorage.getTimeUntilExpiry(),
                        scope = tokenStorage.getTokenScope() ?: "",
                        refreshToken = tokenStorage.getRefreshToken()
                    )
                }
            }

            performRefresh()
        }
    }

    /**
     * Refresh the token if it's missing or expired. Unlike [refreshAccessToken]
     * this returns the raw token string (or null on failure) and skips the
     * network call when the current token is still valid.
     *
     * Intended for internal use by the OkHttp Authenticator.
     */
    suspend fun refreshAccessTokenIfNeeded(): String? {
        // Cheap check before acquiring the lock.
        if (tokenStorage.hasValidAccessToken()) {
            return tokenStorage.getAccessToken()
        }

        return refreshMutex.withLock {
            // Re-check under the lock in case another coroutine just refreshed.
            if (tokenStorage.hasValidAccessToken()) {
                return@withLock tokenStorage.getAccessToken()
            }

            when (performRefresh()) {
                is OAuth2Result.Success -> tokenStorage.getAccessToken()
                else -> null
            }
        }
    }

    /**
     * Raw refresh operation. Callers must hold [refreshMutex] (or accept the
     * risk of a refresh stampede). Public entry points ([refreshAccessToken],
     * [refreshAccessTokenIfNeeded]) serialize access for you.
     */
    private suspend fun performRefresh(): OAuth2Result {
        return withContext(Dispatchers.IO) {
            try {
                val refreshToken = tokenStorage.getRefreshToken()
                if (refreshToken == null) {
                    return@withContext OAuth2Result.Error(
                        error = "no_refresh_token",
                        errorDescription = "Refresh token not available"
                    )
                }


                val request = one.aipass.data.OAuth2TokenRequest(
                    grantType = "refresh_token",
                    refreshToken = refreshToken,
                    clientId = config.clientId,
                    clientSecret = config.clientSecret
                )

                val response = apiService.token(
                    url = config.tokenEndpoint,
                    request = request
                )

                if (response.isSuccessful) {
                    val tokenResponse = response.body()!!

                    // Save new tokens
                    tokenStorage.saveTokenResponse(
                        accessToken = tokenResponse.access_token,
                        expiresIn = tokenResponse.expires_in,
                        scope = tokenResponse.scope,
                        refreshToken = tokenResponse.refresh_token ?: refreshToken
                    )


                    OAuth2Result.Success(
                        accessToken = tokenResponse.access_token,
                        tokenType = tokenResponse.token_type,
                        expiresIn = tokenResponse.expires_in,
                        scope = tokenResponse.scope,
                        refreshToken = tokenResponse.refresh_token
                    )
                } else {
                    OAuth2Result.Error(
                        error = "refresh_failed",
                        errorDescription = "Failed to refresh token: HTTP ${response.code()}"
                    )
                }

            } catch (e: Exception) {
                OAuth2Result.Error(
                    error = "network_error",
                    errorDescription = "Failed to refresh token: ${e.message}",
                    exception = e
                )
            }
        }
    }

    /**
     * Revoke access token
     */
    suspend fun revokeToken(): OAuth2RevocationResult {
        return withContext(Dispatchers.IO) {
            try {
                val revocationEndpoint = config.revocationEndpoint
                if (revocationEndpoint == null) {
                    // No revocation endpoint - just clear tokens locally
                    tokenStorage.clearTokens()
                    return@withContext OAuth2RevocationResult.Success
                }

                val accessToken = tokenStorage.getAccessToken()
                if (accessToken == null) {
                    // No token to revoke
                    tokenStorage.clearTokens()
                    return@withContext OAuth2RevocationResult.Success
                }


                val request = one.aipass.data.OAuth2RevokeRequest(token = accessToken)
                val response = apiService.revokeToken(revocationEndpoint, request)

                // Clear tokens regardless of response (RFC 7009: always return success)
                tokenStorage.clearTokens()

                if (response.isSuccessful || response.code() == 200) {
                    OAuth2RevocationResult.Success
                } else {
                    // Still clear tokens locally even if revocation failed
                    OAuth2RevocationResult.Success
                }

            } catch (e: Exception) {
                // Clear tokens locally even on error
                tokenStorage.clearTokens()
                OAuth2RevocationResult.Success // RFC 7009: always succeed
            }
        }
    }

    /**
     * Logout (clear tokens)
     */
    suspend fun logout(): OAuth2RevocationResult {
        return revokeToken()
    }

    /**
     * Build authorization URL
     */
    private fun buildAuthorizationUrl(
        endpoint: String,
        clientId: String,
        redirectUri: String,
        scope: String,
        codeChallenge: String,
        codeChallengeMethod: String,
        state: String
    ): String {
        return Uri.parse(endpoint).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge", codeChallenge)
            .appendQueryParameter("code_challenge_method", codeChallengeMethod)
            .appendQueryParameter("state", state)
            .apply {
                if (scope.isNotBlank()) {
                    appendQueryParameter("scope", scope)
                }
            }
            .build()
            .toString()
    }

    /**
     * Persist OAuth2 flow state to survive process death
     * Stores state and PKCE code pair in SharedPreferences
     * Uses commit() for atomic writes (synchronous) instead of apply() (async)
     */
    private fun persistFlowState(state: String, pkce: PkceGenerator.PkceCodePair) {
        flowStatePrefs.edit()
            .putString(KEY_PENDING_STATE, state)
            .putString(KEY_PENDING_CODE_VERIFIER, pkce.codeVerifier)
            .putString(KEY_PENDING_CODE_CHALLENGE, pkce.codeChallenge)
            .putString(KEY_PENDING_CODE_CHALLENGE_METHOD, pkce.codeChallengeMethod)
            .commit()  // Synchronous write for data integrity
    }

    /**
     * Restore OAuth2 flow state from SharedPreferences
     * Used when process is killed and restored during OAuth2 flow
     * Validates all required fields before restoring
     */
    private fun restoreFlowState() {
        val state = flowStatePrefs.getString(KEY_PENDING_STATE, null)
        val codeVerifier = flowStatePrefs.getString(KEY_PENDING_CODE_VERIFIER, null)
        val codeChallenge = flowStatePrefs.getString(KEY_PENDING_CODE_CHALLENGE, null)
        val codeChallengeMethod = flowStatePrefs.getString(KEY_PENDING_CODE_CHALLENGE_METHOD, null)

        if (state != null && codeVerifier != null && codeChallenge != null && codeChallengeMethod != null) {
            pendingState = state
            pendingPkceCodePair = PkceGenerator.PkceCodePair(
                codeVerifier = codeVerifier,
                codeChallenge = codeChallenge,
                codeChallengeMethod = codeChallengeMethod
            )
        } else {
            clearPersistedFlowState()
        }
    }

    /**
     * Clear persisted OAuth2 flow state
     * Called after successful token exchange or on errors
     */
    private fun clearPersistedFlowState() {
        flowStatePrefs.edit().clear().apply()
        pendingState = null
        pendingPkceCodePair = null
    }

    /**
     * Generate random state for CSRF protection
     */
    private fun generateState(): String {
        val random = SecureRandom()
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return android.util.Base64.encodeToString(
            bytes,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING
        )
    }

    /**
     * Extract base URL from endpoint for Retrofit
     */
    private fun extractBaseUrl(endpoint: String): String {
        val uri = Uri.parse(endpoint)
        return "${uri.scheme}://${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}/"
    }
}
