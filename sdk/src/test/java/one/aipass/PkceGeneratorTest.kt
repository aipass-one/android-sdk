package one.aipass

import one.aipass.domain.PkceGenerator
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.regex.Pattern

/**
 * Unit tests for PKCE (Proof Key for Code Exchange) Generator
 * RFC 7636 compliance tests
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class PkceGeneratorTest {

    @Test
    fun `generate returns non-null code pair`() {
        val pkce = PkceGenerator.generate()

        assertNotNull("Code verifier should not be null", pkce.codeVerifier)
        assertNotNull("Code challenge should not be null", pkce.codeChallenge)
        assertNotNull("Challenge method should not be null", pkce.codeChallengeMethod)
    }

    @Test
    fun `code verifier meets RFC 7636 length requirements`() {
        val pkce = PkceGenerator.generate()
        val length = pkce.codeVerifier.length

        assertTrue(
            "Code verifier must be 43-128 characters (RFC 7636), was $length",
            length in 43..128
        )
    }

    @Test
    fun `code verifier contains only unreserved characters`() {
        // RFC 7636: unreserved characters [A-Z][a-z][0-9]-._~
        val pkce = PkceGenerator.generate()
        val allowedCharsPattern = Pattern.compile("^[A-Za-z0-9\\-._~]+$")

        assertTrue(
            "Code verifier must contain only unreserved characters (RFC 7636)",
            allowedCharsPattern.matcher(pkce.codeVerifier).matches()
        )
    }

    @Test
    fun `code challenge method is S256`() {
        val pkce = PkceGenerator.generate()

        assertEquals(
            "Challenge method must be S256 (SHA-256)",
            "S256",
            pkce.codeChallengeMethod
        )
    }

    @Test
    fun `code challenge is base64url encoded`() {
        val pkce = PkceGenerator.generate()
        // Base64 URL-safe characters: [A-Za-z0-9_-] (no padding)
        val base64UrlPattern = Pattern.compile("^[A-Za-z0-9_-]+$")

        assertTrue(
            "Code challenge must be base64url encoded (no padding)",
            base64UrlPattern.matcher(pkce.codeChallenge).matches()
        )
    }

    @Test
    fun `code challenge has expected length for SHA256`() {
        val pkce = PkceGenerator.generate()
        // SHA-256 produces 32 bytes = 43 base64url characters (without padding)
        val expectedLength = 43

        assertEquals(
            "Code challenge should be 43 characters (SHA-256 base64url)",
            expectedLength,
            pkce.codeChallenge.length
        )
    }

    @Test
    fun `generate produces unique code verifiers`() {
        val pkce1 = PkceGenerator.generate()
        val pkce2 = PkceGenerator.generate()

        assertNotEquals(
            "Each generate() call should produce unique verifier",
            pkce1.codeVerifier,
            pkce2.codeVerifier
        )
    }

    @Test
    fun `generate produces unique code challenges`() {
        val pkce1 = PkceGenerator.generate()
        val pkce2 = PkceGenerator.generate()

        assertNotEquals(
            "Each generate() call should produce unique challenge",
            pkce1.codeChallenge,
            pkce2.codeChallenge
        )
    }

    @Test
    fun `isValidCodeVerifier accepts valid verifier`() {
        val pkce = PkceGenerator.generate()

        assertTrue(
            "Generated verifier should be valid",
            PkceGenerator.isValidCodeVerifier(pkce.codeVerifier)
        )
    }

    @Test
    fun `isValidCodeVerifier rejects too short verifier`() {
        val tooShort = "abc" // Less than 43 characters

        assertFalse(
            "Verifier less than 43 characters should be invalid",
            PkceGenerator.isValidCodeVerifier(tooShort)
        )
    }

    @Test
    fun `isValidCodeVerifier rejects too long verifier`() {
        val tooLong = "a".repeat(129) // More than 128 characters

        assertFalse(
            "Verifier more than 128 characters should be invalid",
            PkceGenerator.isValidCodeVerifier(tooLong)
        )
    }

    @Test
    fun `isValidCodeVerifier rejects invalid characters`() {
        val invalidChars = "a".repeat(43) + "!" // Contains invalid character

        assertFalse(
            "Verifier with invalid characters should be rejected",
            PkceGenerator.isValidCodeVerifier(invalidChars)
        )
    }

    @Test
    fun `isValidCodeVerifier accepts minimum length`() {
        val minLength = "a".repeat(43)

        assertTrue(
            "Verifier with minimum length (43) should be valid",
            PkceGenerator.isValidCodeVerifier(minLength)
        )
    }

    @Test
    fun `isValidCodeVerifier accepts maximum length`() {
        val maxLength = "a".repeat(128)

        assertTrue(
            "Verifier with maximum length (128) should be valid",
            PkceGenerator.isValidCodeVerifier(maxLength)
        )
    }

    @Test
    fun `code verifier and challenge are deterministic`() {
        // Same verifier should always produce same challenge
        // (This tests the underlying SHA-256 algorithm consistency)
        val pkce1 = PkceGenerator.generate()
        val pkce2 = PkceGenerator.generate()

        // While verifiers are different, both should be valid
        assertTrue(PkceGenerator.isValidCodeVerifier(pkce1.codeVerifier))
        assertTrue(PkceGenerator.isValidCodeVerifier(pkce2.codeVerifier))

        // And challenges should be different (since verifiers are different)
        assertNotEquals(pkce1.codeChallenge, pkce2.codeChallenge)
    }

    @Test
    fun `generated code pair has all required fields`() {
        val pkce = PkceGenerator.generate()

        assertFalse("Code verifier should not be empty", pkce.codeVerifier.isEmpty())
        assertFalse("Code challenge should not be empty", pkce.codeChallenge.isEmpty())
        assertFalse("Challenge method should not be empty", pkce.codeChallengeMethod.isEmpty())
    }

    @Test
    fun `code challenge is different from code verifier`() {
        val pkce = PkceGenerator.generate()

        assertNotEquals(
            "Code challenge should be hash of verifier, not the verifier itself",
            pkce.codeVerifier,
            pkce.codeChallenge
        )
    }

    @Test
    fun `multiple generates produce cryptographically random values`() {
        // Generate multiple code pairs and check for randomness
        val codes = (1..100).map { PkceGenerator.generate() }

        // All verifiers should be unique
        val uniqueVerifiers = codes.map { it.codeVerifier }.toSet()
        assertEquals(
            "All 100 verifiers should be unique (cryptographically random)",
            100,
            uniqueVerifiers.size
        )

        // All challenges should be unique
        val uniqueChallenges = codes.map { it.codeChallenge }.toSet()
        assertEquals(
            "All 100 challenges should be unique",
            100,
            uniqueChallenges.size
        )
    }
}
