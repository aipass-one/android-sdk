package one.aipass

import one.aipass.domain.OAuth2Config
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for OAuth2Config
 * Tests validation and configuration logic
 */
class OAuth2ConfigTest {

    @Test
    fun `valid config is created successfully`() {
        val config = OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )

        assertNotNull("Config should be created", config)
        assertEquals("https://example.com/oauth/authorize", config.authorizationEndpoint)
        assertEquals("https://example.com/oauth/token", config.tokenEndpoint)
        assertEquals("test_client", config.clientId)
        assertEquals("com.test://callback", config.redirectUri)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank authorization endpoint throws exception`() {
        OAuth2Config(
            authorizationEndpoint = "",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank token endpoint throws exception`() {
        OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank client ID throws exception`() {
        OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "",
            redirectUri = "com.test://callback"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `blank redirect URI throws exception`() {
        OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            redirectUri = ""
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `authorization endpoint without https or http throws exception`() {
        OAuth2Config(
            authorizationEndpoint = "example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun `token endpoint without https or http throws exception`() {
        OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "example.com/oauth/token",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )
    }

    @Test
    fun `http endpoints are accepted for development`() {
        val config = OAuth2Config(
            authorizationEndpoint = "http://localhost/oauth/authorize",
            tokenEndpoint = "http://localhost/oauth/token",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )

        assertNotNull("HTTP endpoints should be accepted", config)
    }

    @Test
    fun `optional client secret is accepted`() {
        val config = OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            clientSecret = "secret123",
            redirectUri = "com.test://callback"
        )

        assertEquals("secret123", config.clientSecret)
    }

    @Test
    fun `null client secret is accepted`() {
        val config = OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            clientSecret = null,
            redirectUri = "com.test://callback"
        )

        assertNull("Client secret should be null", config.clientSecret)
    }

    @Test
    fun `optional revocation endpoint is accepted`() {
        val config = OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            revocationEndpoint = "https://example.com/oauth/revoke",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )

        assertEquals("https://example.com/oauth/revoke", config.revocationEndpoint)
    }

    @Test
    fun `empty scopes list is accepted`() {
        val config = OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            redirectUri = "com.test://callback",
            scopes = emptyList()
        )

        assertTrue("Empty scopes should be accepted", config.scopes.isEmpty())
        assertEquals("", config.scopeString)
    }

    @Test
    fun `multiple scopes are joined with spaces`() {
        val config = OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            redirectUri = "com.test://callback",
            scopes = listOf("read", "write", "profile")
        )

        assertEquals("read write profile", config.scopeString)
    }

    @Test
    fun `single scope produces correct scope string`() {
        val config = OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            redirectUri = "com.test://callback",
            scopes = listOf("api:access")
        )

        assertEquals("api:access", config.scopeString)
    }

    @Test
    fun `forAiKey creates correct configuration`() {
        val config = OAuth2Config.forAiKey(
            baseUrl = "https://aipass.one",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )

        assertEquals("https://aipass.one/oauth2/authorize", config.authorizationEndpoint)
        assertEquals("https://aipass.one/oauth2/token", config.tokenEndpoint)
        assertEquals("https://aipass.one/oauth2/revoke", config.revocationEndpoint)
        assertEquals("test_client", config.clientId)
        assertEquals("com.test://callback", config.redirectUri)
        assertTrue("Should have default scopes", config.scopes.isNotEmpty())
    }

    @Test
    fun `forAiKey trims trailing slash from base URL`() {
        val config = OAuth2Config.forAiKey(
            baseUrl = "https://aipass.one/",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )

        assertEquals("https://aipass.one/oauth2/authorize", config.authorizationEndpoint)
        assertEquals("https://aipass.one/oauth2/token", config.tokenEndpoint)
    }

    @Test
    fun `forAiKey accepts custom scopes`() {
        val customScopes = listOf("custom:scope1", "custom:scope2")
        val config = OAuth2Config.forAiKey(
            baseUrl = "https://aipass.one",
            clientId = "test_client",
            redirectUri = "com.test://callback",
            scopes = customScopes
        )

        assertEquals(customScopes, config.scopes)
    }

    @Test
    fun `forAiKey accepts client secret`() {
        val config = OAuth2Config.forAiKey(
            baseUrl = "https://aipass.one",
            clientId = "test_client",
            clientSecret = "secret123",
            redirectUri = "com.test://callback"
        )

        assertEquals("secret123", config.clientSecret)
    }

    @Test
    fun `forAiKey uses default scopes when not specified`() {
        val config = OAuth2Config.forAiKey(
            baseUrl = "https://aipass.one",
            clientId = "test_client",
            redirectUri = "com.test://callback"
        )

        assertTrue("Should have api:access scope", config.scopes.contains("api:access"))
        assertTrue("Should have profile:read scope", config.scopes.contains("profile:read"))
    }

    @Test
    fun `custom URL schemes are supported for redirect URI`() {
        val config = OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            redirectUri = "myapp://oauth/callback"
        )

        assertEquals("myapp://oauth/callback", config.redirectUri)
    }

    @Test
    fun `localhost redirect URI is accepted`() {
        val config = OAuth2Config(
            authorizationEndpoint = "https://example.com/oauth/authorize",
            tokenEndpoint = "https://example.com/oauth/token",
            clientId = "test_client",
            redirectUri = "http://localhost:8080/callback"
        )

        assertEquals("http://localhost:8080/callback", config.redirectUri)
    }
}
