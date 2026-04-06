package one.aipass.domain

/**
 * Result types for OAuth2 operations
 * Uses sealed classes for type-safe result handling
 */
sealed class OAuth2Result {
    /**
     * OAuth2 authorization successful
     * Contains the access token and related information
     */
    data class Success(
        val accessToken: String,
        val tokenType: String = "Bearer",
        val expiresIn: Long,
        val scope: String,
        val refreshToken: String? = null
    ) : OAuth2Result()

    /**
     * OAuth2 authorization failed
     * Contains error code and description (RFC 6749 format)
     */
    data class Error(
        val error: String,
        val errorDescription: String? = null,
        val exception: Throwable? = null
    ) : OAuth2Result()

    /**
     * User cancelled the authorization
     */
    object Cancelled : OAuth2Result()
}

/**
 * OAuth2 token validation result
 */
sealed class OAuth2ValidationResult {
    object Valid : OAuth2ValidationResult()
    object Expired : OAuth2ValidationResult()
    object Invalid : OAuth2ValidationResult()
}

/**
 * OAuth2 token revocation result
 */
sealed class OAuth2RevocationResult {
    object Success : OAuth2RevocationResult()
    data class Error(val message: String, val exception: Throwable? = null) :
        OAuth2RevocationResult()
}
