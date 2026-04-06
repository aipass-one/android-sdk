package one.aipass.domain

import android.util.Base64
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * PKCE (Proof Key for Code Exchange) Generator
 * RFC 7636 compliant implementation
 *
 * PKCE protects against authorization code interception attacks
 * by requiring the client to prove they initiated the authorization request.
 */
object PkceGenerator {

    private const val CODE_VERIFIER_LENGTH = 128 // Maximum length per RFC 7636
    private const val CODE_CHALLENGE_METHOD_S256 = "S256"
    private const val CODE_CHALLENGE_METHOD_PLAIN = "plain"

    /**
     * PKCE code pair (verifier and challenge)
     * The verifier is kept secret, the challenge is sent in the authorization request
     */
    data class PkceCodePair(
        val codeVerifier: String,
        val codeChallenge: String,
        val codeChallengeMethod: String = CODE_CHALLENGE_METHOD_S256
    )

    /**
     * Generate a PKCE code pair
     * Returns both code_verifier and code_challenge
     *
     * Usage:
     * ```
     * val pkce = PkceGenerator.generate()
     * // Send pkce.codeChallenge and pkce.codeChallengeMethod to authorization endpoint
     * // Later send pkce.codeVerifier to token endpoint
     * ```
     */
    fun generate(): PkceCodePair {
        val codeVerifier = generateCodeVerifier()
        val codeChallenge = generateCodeChallenge(codeVerifier)

        return PkceCodePair(
            codeVerifier = codeVerifier,
            codeChallenge = codeChallenge,
            codeChallengeMethod = CODE_CHALLENGE_METHOD_S256
        )
    }

    /**
     * Generate a cryptographically random code verifier
     * Length: 128 characters (maximum allowed by RFC 7636)
     * Characters: Unreserved characters [A-Z, a-z, 0-9, -, ., _, ~]
     */
    private fun generateCodeVerifier(): String {
        val secureRandom = SecureRandom()
        val bytes = ByteArray(CODE_VERIFIER_LENGTH)
        secureRandom.nextBytes(bytes)

        // Use URL-safe base64 encoding without padding
        return Base64.encodeToString(
            bytes,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        ).take(CODE_VERIFIER_LENGTH) // Ensure exactly 128 chars
    }

    /**
     * Generate code challenge from code verifier
     * Method: S256 = BASE64URL(SHA256(code_verifier))
     *
     * @param codeVerifier The code verifier to hash
     * @return The code challenge
     */
    private fun generateCodeChallenge(codeVerifier: String): String {
        // Compute SHA-256 hash of code verifier
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(codeVerifier.toByteArray(Charsets.US_ASCII))

        // Base64 URL-safe encode without padding
        return Base64.encodeToString(
            hash,
            Base64.URL_SAFE or Base64.NO_WRAP or Base64.NO_PADDING
        )
    }

    /**
     * Validate a code verifier (for testing/debugging)
     * Checks if the verifier meets RFC 7636 requirements
     */
    fun isValidCodeVerifier(codeVerifier: String): Boolean {
        // RFC 7636: verifier must be 43-128 characters
        if (codeVerifier.length !in 43..128) {
            return false
        }

        // Must contain only unreserved characters: [A-Z][a-z][0-9]-._~
        val allowedCharsRegex = Regex("^[A-Za-z0-9\\-._~]+$")
        return codeVerifier.matches(allowedCharsRegex)
    }
}
