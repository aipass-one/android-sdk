package one.aipass.ui

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import one.aipass.AiPassSDK

/**
 * OAuth2 Callback Activity
 * Handles redirects from browser after user authorizes the application
 *
 * This activity must be registered in your app's AndroidManifest.xml with an intent filter
 * for your custom URL scheme.
 *
 * Example configuration:
 * ```xml
 * <activity
 *     android:name="one.aipass.ui.OAuth2CallbackActivity"
 *     android:exported="true"
 *     android:launchMode="singleTask">
 *     <intent-filter>
 *         <action android:name="android.intent.action.VIEW" />
 *         <category android:name="android.intent.category.DEFAULT" />
 *         <category android:name="android.intent.category.BROWSABLE" />
 *         <data
 *             android:scheme="com.yourapp"
 *             android:host="oauth"
 *             android:path="/callback" />
 *     </intent-filter>
 * </activity>
 * ```
 *
 * The activity will automatically handle the callback and notify AiPassSDK.
 * The result will be delivered to the callback set via AiPassSDK.setAuthorizationCallback()
 */
class OAuth2CallbackActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "OAuth2CallbackActivity"

        /**
         * Extra to specify the target activity class name to return to after handling callback
         * If not specified, the activity will just finish
         */
        const val EXTRA_TARGET_ACTIVITY = "oauth2_target_activity"

        /**
         * Extra to pass result back if using startActivityForResult pattern
         */
        const val EXTRA_AUTH_RESULT = "oauth2_auth_result"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Disable enter animation
        disableTransitionAnimation()

        // No UI - just handle the callback and finish
        handleCallback()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleCallback()
    }

    private fun handleCallback() {
        val intent = intent
        if (intent == null) {
            if (one.aipass.BuildConfig.DEBUG) Log.e(TAG, "No intent in callback activity")
            finishWithError("no_intent", "No intent data")
            return
        }

        val data = intent.data
        if (data == null) {
            if (one.aipass.BuildConfig.DEBUG) Log.e(TAG, "No data URI in intent")
            finishWithError("no_data", "No data in callback")
            return
        }

        // Use lifecycleScope to ensure callback handler completes before finishing
        // This prevents data loss if activity is destroyed during callback processing
        lifecycleScope.launch {
            try {
                // Deliver callback to AiPassSDK (SDK will handle initialization check)
                AiPassSDK.handleAuthorizationCallback(intent)

                // Give callback time to deliver result
                delay(500)

                // Navigate back to target activity if specified
                val targetActivity = intent.getStringExtra(EXTRA_TARGET_ACTIVITY)
                if (targetActivity != null) {
                    try {
                        val targetClass = Class.forName(targetActivity)
                        val targetIntent = Intent(this@OAuth2CallbackActivity, targetClass)
                        targetIntent.flags =
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                        startActivity(targetIntent)
                    } catch (e: Exception) {
                        if (one.aipass.BuildConfig.DEBUG) Log.e(TAG, "Failed to launch target activity: $targetActivity", e)
                    }
                }
            } finally {
                // Finish this activity after callback is processed
                finish()
                // Disable exit animation
                disableTransitionAnimation()
            }
        }
    }

    private fun finishWithError(error: String, description: String) {
        if (one.aipass.BuildConfig.DEBUG) Log.e(TAG, "Callback error: $error - $description")
        finish()
        // Disable exit animation
        disableTransitionAnimation()
    }

    /**
     * Disables transition animations for this activity
     * Uses the newer API for Android 14+ and fallback for older versions
     */
    private fun disableTransitionAnimation() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            overrideActivityTransition(
                OVERRIDE_TRANSITION_OPEN,
                0,
                0
            )
            overrideActivityTransition(
                OVERRIDE_TRANSITION_CLOSE,
                0,
                0
            )
        } else {
            @Suppress("DEPRECATION")
            overridePendingTransition(0, 0)
        }
    }
}
