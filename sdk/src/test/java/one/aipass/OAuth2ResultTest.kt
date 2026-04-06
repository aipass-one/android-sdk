package one.aipass

import one.aipass.domain.OAuth2Result
import one.aipass.domain.OAuth2RevocationResult
import one.aipass.domain.OAuth2ValidationResult
import org.junit.Assert.*
import org.junit.Test

/**
 * Unit tests for OAuth2 Result sealed classes
 * Tests type safety and result handling
 */
class OAuth2ResultTest {

    @Test
    fun `OAuth2Result Success contains required fields`() {
        val result = OAuth2Result.Success(
            accessToken = "token123",
            tokenType = "Bearer",
            expiresIn = 3600,
            scope = "read write",
            refreshToken = "refresh456"
        )

        assertEquals("token123", result.accessToken)
        assertEquals("Bearer", result.tokenType)
        assertEquals(3600L, result.expiresIn)
        assertEquals("read write", result.scope)
        assertEquals("refresh456", result.refreshToken)
    }

    @Test
    fun `OAuth2Result Success without refresh token is valid`() {
        val result = OAuth2Result.Success(
            accessToken = "token123",
            tokenType = "Bearer",
            expiresIn = 3600,
            scope = "read write",
            refreshToken = null
        )

        assertNotNull("Result should not be null", result)
        assertNull("Refresh token should be null", result.refreshToken)
    }

    @Test
    fun `OAuth2Result Error contains error code and description`() {
        val result = OAuth2Result.Error(
            error = "invalid_grant",
            errorDescription = "Authorization code expired",
            exception = null
        )

        assertEquals("invalid_grant", result.error)
        assertEquals("Authorization code expired", result.errorDescription)
        assertNull(result.exception)
    }

    @Test
    fun `OAuth2Result Error can contain exception`() {
        val exception = RuntimeException("Network error")
        val result = OAuth2Result.Error(
            error = "network_error",
            errorDescription = "Failed to connect",
            exception = exception
        )

        assertEquals("network_error", result.error)
        assertEquals("Failed to connect", result.errorDescription)
        assertEquals(exception, result.exception)
    }

    @Test
    fun `OAuth2Result Cancelled is singleton`() {
        val cancelled1 = OAuth2Result.Cancelled
        val cancelled2 = OAuth2Result.Cancelled

        assertSame("Cancelled should be the same instance", cancelled1, cancelled2)
    }

    @Test
    fun `OAuth2Result sealed class hierarchy works with when expression`() {
        val results = listOf<OAuth2Result>(
            OAuth2Result.Success("token", "Bearer", 3600, "read"),
            OAuth2Result.Error("error", "description"),
            OAuth2Result.Cancelled
        )

        results.forEach { result ->
            val handled = when (result) {
                is OAuth2Result.Success -> "success"
                is OAuth2Result.Error -> "error"
                OAuth2Result.Cancelled -> "cancelled"
            }

            assertNotNull("Result should be handled", handled)
        }
    }

    @Test
    fun `OAuth2ValidationResult Valid is singleton`() {
        val valid1 = OAuth2ValidationResult.Valid
        val valid2 = OAuth2ValidationResult.Valid

        assertSame("Valid should be the same instance", valid1, valid2)
    }

    @Test
    fun `OAuth2ValidationResult Expired is singleton`() {
        val expired1 = OAuth2ValidationResult.Expired
        val expired2 = OAuth2ValidationResult.Expired

        assertSame("Expired should be the same instance", expired1, expired2)
    }

    @Test
    fun `OAuth2ValidationResult Invalid is singleton`() {
        val invalid1 = OAuth2ValidationResult.Invalid
        val invalid2 = OAuth2ValidationResult.Invalid

        assertSame("Invalid should be the same instance", invalid1, invalid2)
    }

    @Test
    fun `OAuth2ValidationResult sealed class works with when expression`() {
        val results = listOf<OAuth2ValidationResult>(
            OAuth2ValidationResult.Valid,
            OAuth2ValidationResult.Expired,
            OAuth2ValidationResult.Invalid
        )

        results.forEach { result ->
            val handled = when (result) {
                OAuth2ValidationResult.Valid -> "valid"
                OAuth2ValidationResult.Expired -> "expired"
                OAuth2ValidationResult.Invalid -> "invalid"
            }

            assertNotNull("Result should be handled", handled)
        }
    }

    @Test
    fun `OAuth2RevocationResult Success is singleton`() {
        val success1 = OAuth2RevocationResult.Success
        val success2 = OAuth2RevocationResult.Success

        assertSame("Success should be the same instance", success1, success2)
    }

    @Test
    fun `OAuth2RevocationResult Error contains message`() {
        val result = OAuth2RevocationResult.Error(
            message = "Failed to revoke token",
            exception = null
        )

        assertEquals("Failed to revoke token", result.message)
        assertNull(result.exception)
    }

    @Test
    fun `OAuth2RevocationResult Error can contain exception`() {
        val exception = RuntimeException("Network error")
        val result = OAuth2RevocationResult.Error(
            message = "Failed to revoke token",
            exception = exception
        )

        assertEquals("Failed to revoke token", result.message)
        assertEquals(exception, result.exception)
    }

    @Test
    fun `OAuth2RevocationResult sealed class works with when expression`() {
        val results = listOf<OAuth2RevocationResult>(
            OAuth2RevocationResult.Success,
            OAuth2RevocationResult.Error("Error message")
        )

        results.forEach { result ->
            val handled = when (result) {
                OAuth2RevocationResult.Success -> "success"
                is OAuth2RevocationResult.Error -> "error"
            }

            assertNotNull("Result should be handled", handled)
        }
    }

    @Test
    fun `OAuth2Result Success default token type is Bearer`() {
        val result = OAuth2Result.Success(
            accessToken = "token123",
            expiresIn = 3600,
            scope = "read"
        )

        assertEquals("Bearer", result.tokenType)
    }

    @Test
    fun `OAuth2Result Error without description is valid`() {
        val result = OAuth2Result.Error(
            error = "invalid_request",
            errorDescription = null
        )

        assertEquals("invalid_request", result.error)
        assertNull(result.errorDescription)
    }

    @Test
    fun `OAuth2Result types are distinct`() {
        val success = OAuth2Result.Success("token", "Bearer", 3600, "read")
        val error = OAuth2Result.Error("error", "description")
        val cancelled = OAuth2Result.Cancelled

        assertFalse("Success should not equal Error", success == error)
        assertFalse("Success should not equal Cancelled", success == cancelled)
        assertFalse("Error should not equal Cancelled", error == cancelled)
    }

    @Test
    fun `OAuth2Result Success with same values are equal`() {
        val result1 = OAuth2Result.Success(
            accessToken = "token123",
            tokenType = "Bearer",
            expiresIn = 3600,
            scope = "read",
            refreshToken = "refresh456"
        )
        val result2 = OAuth2Result.Success(
            accessToken = "token123",
            tokenType = "Bearer",
            expiresIn = 3600,
            scope = "read",
            refreshToken = "refresh456"
        )

        assertEquals("Same success values should be equal", result1, result2)
    }

    @Test
    fun `OAuth2Result Error with same values are equal`() {
        val result1 = OAuth2Result.Error("error_code", "description")
        val result2 = OAuth2Result.Error("error_code", "description")

        assertEquals("Same error values should be equal", result1, result2)
    }
}
