package one.aipass.domain

/**
 * OAuth2 Configuration
 * Contains all settings needed for OAuth2 Authorization Code + PKCE flow
 *
 * @param authorizationEndpoint Full URL to authorization endpoint (e.g., "https://example.com/oauth2/authorize")
 * @param tokenEndpoint Full URL to token endpoint (e.g., "https://example.com/oauth2/token")
 * @param revocationEndpoint Full URL to revocation endpoint (optional)
 * @param clientId OAuth2 client ID
 * @param clientSecret OAuth2 client secret (optional for public clients)
 * @param redirectUri Custom URL scheme for redirect (e.g., "com.yourapp://oauth/callback")
 * @param scopes Requested scopes (default: empty list)
 */
data class OAuth2Config(
    val authorizationEndpoint: String,
    val tokenEndpoint: String,
    val revocationEndpoint: String? = null,
    val clientId: String,
    val clientSecret: String? = null,
    val redirectUri: String,
    val scopes: List<String> = emptyList()
) {
    init {
        require(authorizationEndpoint.isNotBlank()) { "Authorization endpoint cannot be blank" }
        require(authorizationEndpoint.startsWith("http://") || authorizationEndpoint.startsWith("https://")) {
            "Authorization endpoint must start with http:// or https://"
        }
        require(tokenEndpoint.isNotBlank()) { "Token endpoint cannot be blank" }
        require(tokenEndpoint.startsWith("http://") || tokenEndpoint.startsWith("https://")) {
            "Token endpoint must start with http:// or https://"
        }
        require(clientId.isNotBlank()) { "Client ID cannot be blank" }
        require(redirectUri.isNotBlank()) { "Redirect URI cannot be blank" }
    }

    /**
     * Scopes as space-separated string (OAuth2 standard)
     */
    val scopeString: String
        get() = scopes.joinToString(" ")

    companion object {
        /**
         * Helper to create config for AI Key service
         */
        fun forAiKey(
            baseUrl: String,
            clientId: String,
            clientSecret: String? = null,
            redirectUri: String,
            scopes: List<String> = listOf("api:access", "profile:read")
        ): OAuth2Config {
            val normalizedBaseUrl = baseUrl.trimEnd('/')
            return OAuth2Config(
                authorizationEndpoint = "$normalizedBaseUrl/oauth2/authorize",
                tokenEndpoint = "$normalizedBaseUrl/oauth2/token",
                revocationEndpoint = "$normalizedBaseUrl/oauth2/revoke",
                clientId = clientId,
                clientSecret = clientSecret,
                redirectUri = redirectUri,
                scopes = scopes
            )
        }
    }
}
