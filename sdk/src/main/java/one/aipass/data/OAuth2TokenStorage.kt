package one.aipass.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.security.KeyStore

/**
 * Secure storage for OAuth2 tokens
 * Uses Android Keystore for encryption
 *
 * This is a generic OAuth2 token storage that can be used with any OAuth2 provider
 */
class OAuth2TokenStorage(context: Context) {

    private val sharedPreferences: SharedPreferences

    init {
        sharedPreferences = createSharedPreferences(context.applicationContext)
    }

    private fun createSharedPreferences(context: Context): SharedPreferences {
        return try {
            createEncryptedSharedPreferences(context)
        } catch (initializationError: Exception) {
            Log.w(TAG, "Failed to initialize encrypted OAuth storage, clearing local state and retrying", initializationError)
            clearCorruptedStorage(context)

            try {
                createEncryptedSharedPreferences(context)
            } catch (retryError: Exception) {
                Log.e(TAG, "Falling back to plain SharedPreferences for OAuth storage", retryError)
                context.getSharedPreferences(FALLBACK_PREFS_NAME, Context.MODE_PRIVATE)
            }
        }
    }

    private fun createEncryptedSharedPreferences(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearCorruptedStorage(context: Context) {
        context.deleteSharedPreferences(PREFS_NAME)
        context.deleteSharedPreferences(FALLBACK_PREFS_NAME)

        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (keyStore.containsAlias(MasterKey.DEFAULT_MASTER_KEY_ALIAS)) {
                keyStore.deleteEntry(MasterKey.DEFAULT_MASTER_KEY_ALIAS)
            }
        } catch (keyStoreError: Exception) {
            Log.w(TAG, "Failed to clear Android Keystore entry during OAuth storage recovery", keyStoreError)
        }
    }

    /**
     * Save OAuth2 access token
     */
    fun saveAccessToken(token: String) {
        sharedPreferences.edit()
            .putString(KEY_ACCESS_TOKEN, token)
            .apply()
    }

    /**
     * Get OAuth2 access token
     */
    fun getAccessToken(): String? {
        return sharedPreferences.getString(KEY_ACCESS_TOKEN, null)
    }

    /**
     * Save OAuth2 refresh token
     */
    fun saveRefreshToken(token: String) {
        sharedPreferences.edit()
            .putString(KEY_REFRESH_TOKEN, token)
            .apply()
    }

    /**
     * Get OAuth2 refresh token
     */
    fun getRefreshToken(): String? {
        return sharedPreferences.getString(KEY_REFRESH_TOKEN, null)
    }

    /**
     * Save token expiration time (milliseconds since epoch)
     */
    fun saveTokenExpiry(expiryTimeMillis: Long) {
        sharedPreferences.edit()
            .putLong(KEY_TOKEN_EXPIRY, expiryTimeMillis)
            .apply()
    }

    /**
     * Get token expiration time
     */
    fun getTokenExpiry(): Long {
        return sharedPreferences.getLong(KEY_TOKEN_EXPIRY, 0L)
    }

    /**
     * Save token scope
     */
    fun saveTokenScope(scope: String) {
        sharedPreferences.edit()
            .putString(KEY_TOKEN_SCOPE, scope)
            .apply()
    }

    /**
     * Get token scope
     */
    fun getTokenScope(): String? {
        return sharedPreferences.getString(KEY_TOKEN_SCOPE, null)
    }

    /**
     * Check if access token exists and is not expired
     */
    fun hasValidAccessToken(): Boolean {
        val token = getAccessToken()
        val expiry = getTokenExpiry()

        return token != null && System.currentTimeMillis() < expiry
    }

    /**
     * Check if access token is expired
     */
    fun isAccessTokenExpired(): Boolean {
        val expiry = getTokenExpiry()
        return System.currentTimeMillis() >= expiry
    }

    /**
     * Get time until token expires (in seconds)
     * Returns 0 if already expired
     */
    fun getTimeUntilExpiry(): Long {
        val expiry = getTokenExpiry()
        val now = System.currentTimeMillis()

        return if (expiry > now) {
            (expiry - now) / 1000
        } else {
            0L
        }
    }

    /**
     * Save complete token response
     */
    fun saveTokenResponse(
        accessToken: String,
        expiresIn: Long,
        scope: String,
        refreshToken: String? = null
    ) {
        // Subtract buffer from expiry to account for clock skew and network latency
        val adjustedExpiresIn = expiresIn - EXPIRY_BUFFER_SECONDS
        val expiryTime = System.currentTimeMillis() + (adjustedExpiresIn * 1000)

        sharedPreferences.edit()
            .putString(KEY_ACCESS_TOKEN, accessToken)
            .putLong(KEY_TOKEN_EXPIRY, expiryTime)
            .putString(KEY_TOKEN_SCOPE, scope)
            .apply {
                if (refreshToken != null) {
                    putString(KEY_REFRESH_TOKEN, refreshToken)
                }
            }
            .apply()
    }

    /**
     * Clear all OAuth2 tokens
     */
    fun clearTokens() {
        sharedPreferences.edit()
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_TOKEN_SCOPE)
            .apply()
    }

    /**
     * Check if user is authenticated (has valid or refreshable token)
     */
    fun isAuthenticated(): Boolean {
        return hasValidAccessToken() || getRefreshToken() != null
    }

    companion object {
        private const val TAG = "OAuth2TokenStorage"
        private const val PREFS_NAME = "aipass_oauth2_prefs"
        private const val FALLBACK_PREFS_NAME = "aipass_oauth2_prefs_fallback"
        private const val KEY_ACCESS_TOKEN = "oauth2_access_token"
        private const val KEY_REFRESH_TOKEN = "oauth2_refresh_token"
        private const val KEY_TOKEN_EXPIRY = "oauth2_token_expiry"
        private const val KEY_TOKEN_SCOPE = "oauth2_token_scope"

        /**
         * Expiry buffer in seconds
         * Tokens are considered expired this many seconds before actual expiry
         * to account for clock skew and network latency
         */
        private const val EXPIRY_BUFFER_SECONDS = 60L
    }
}
