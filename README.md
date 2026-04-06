# AI Pass SDK - Generic OAuth2 for Android

A lightweight, standalone OAuth2 Authorization Code + PKCE library for Android.

## Overview

AI Pass SDK is a **generic OAuth2 client library** that works with any RFC 6749 compliant OAuth2
authorization server. It's not tied to any specific service - use it with AI Key, Google, GitHub, or
your own OAuth2 server.

## Features

- ✅ OAuth2 Authorization Code + PKCE flow (RFC 7636)
- ✅ Secure token storage (Android Keystore encryption)
- ✅ Token refresh support (if provider supports it; AI Key currently does not)
- ✅ Custom URL scheme redirect handling
- ✅ CSRF protection (state parameter)
- ✅ Type-safe result handling (sealed classes)
- ✅ Coroutine-based async operations
- ✅ RFC 6749 & 7636 compliant
- ✅ **No external dependencies on specific services**
- ✅ Lightweight (minimal dependencies)

## Installation

### Add to your project

Copy the `ai-pass-sdk` directory to your Android project:

```
your-project/
├── app/
└── ai-pass-sdk/
```

### Update settings.gradle

```gradle
include ':app'
include ':ai-pass-sdk'
```

### Add dependency

```gradle
// app/build.gradle
dependencies {
    implementation project(':ai-pass-sdk')
}
```

## Quick Start

### 1. Configure AndroidManifest.xml

```xml
<activity
    android:name="one.aipass.ui.OAuth2CallbackActivity"
    android:exported="true"
    android:launchMode="singleTask">
    <intent-filter>
        <action android:name="android.intent.action.VIEW" />
        <category android:name="android.intent.category.DEFAULT" />
        <category android:name="android.intent.category.BROWSABLE" />

        <!-- Your custom URL scheme -->
        <data
            android:scheme="com.yourapp"
            android:host="oauth"
            android:path="/callback" />
    </intent-filter>
</activity>
```

### 2. Initialize SDK

```kotlin
class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()

        // For AI Key:
        AiPassSDK.initialize(
            context = this,
            config = OAuth2Config.forAiKey(
                baseUrl = "https://aikey.ir",
                clientId = "your_client_id",
                clientSecret = null, // Android apps should NOT embed secrets - PKCE provides security
                redirectUri = "com.yourapp://oauth/callback",
                scopes = listOf("api:access", "profile:read")
            )
        )

        // Or for any OAuth2 provider:
        AiPassSDK.initialize(
            context = this,
            config = OAuth2Config(
                authorizationEndpoint = "https://example.com/oauth/authorize",
                tokenEndpoint = "https://example.com/oauth/token",
                revocationEndpoint = "https://example.com/oauth/revoke",
                clientId = "your_client_id",
                clientSecret = null, // For public mobile clients, set to null (PKCE is used instead)
                redirectUri = "com.yourapp://oauth/callback",
                scopes = listOf("read", "write")
            )
        )
    }
}
```

### 3. Implement Login

```kotlin
class LoginActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)

        // Check if already logged in
        if (AiPassSDK.isAuthenticated()) {
            navigateToMain()
            return
        }

        // Set authorization callback
        AiPassSDK.setAuthorizationCallback { result ->
            handleAuthResult(result)
        }

        // Login button
        findViewById<Button>(R.id.loginButton).setOnClickListener {
            AiPassSDK.authorize(this)
        }
    }

    private fun handleAuthResult(result: OAuth2Result) {
        when (result) {
            is OAuth2Result.Success -> {
                // Save or use access token
                val accessToken = result.accessToken
                Toast.makeText(this, "Login successful!", Toast.LENGTH_SHORT).show()
                navigateToMain()
            }

            is OAuth2Result.Error -> {
                Toast.makeText(
                    this,
                    "Login failed: ${result.errorDescription}",
                    Toast.LENGTH_LONG
                ).show()
            }

            is OAuth2Result.Cancelled -> {
                Toast.makeText(this, "Login cancelled", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
```

### 4. Use Access Token

```kotlin
// Get current access token
val token = AiPassSDK.getAccessToken()

// Use in API calls
val client = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        chain.proceed(request)
    }
    .build()

// Or with Retrofit
val retrofit = Retrofit.Builder()
    .client(client)
    .baseUrl("https://api.example.com/")
    .build()
```

### 5. Handle Token Expiration

**⚠️ Important for AI Key**: AI Key does not currently support refresh tokens. When tokens expire,
you must re-authorize the user by calling `AiPassSDK.authorize()` again.

```kotlin
lifecycleScope.launch {
    // Check if token is still valid
    if (!AiPassSDK.isAuthenticated()) {
        // For providers that support refresh tokens:
        val result = AiPassSDK.refreshAccessToken()
        when (result) {
            is OAuth2Result.Success -> {
                // Token refreshed, continue
                val newToken = result.accessToken
            }
            is OAuth2Result.Error -> {
                // Refresh failed or not supported (e.g., AI Key)
                // Re-authorize user
                navigateToLogin()
            }
            else -> {}
        }
    }
}
```

### 6. Logout

```kotlin
lifecycleScope.launch {
    val result = AiPassSDK.logout()
    when (result) {
        is OAuth2RevocationResult.Success -> {
            navigateToLogin()
        }
        is OAuth2RevocationResult.Error -> {
            // Still logged out locally
            navigateToLogin()
        }
    }
}
```

## Integration with AI Key SDK

If you're using the AI Key SDK, here's how to integrate:

```kotlin
// 1. Initialize both SDKs
AiKeySDK.initialize(context, "https://aikey.ir")
AiPassSDK.initialize(context, OAuth2Config.forAiKey(...))

// 2. Login with OAuth2
AiPassSDK.setAuthorizationCallback { result ->
    when (result) {
        is OAuth2Result.Success -> {
            // Token obtained!
            // AI Key SDK will use OAuth2 token automatically via SecurityContext
            // Just make API calls normally:
            lifecycleScope.launch {
                val completion = AiKeySDK.generateCompletion(...)
            }
        }
    }
}

AiPassSDK.authorize(activity)
```

The AI Key backend automatically accepts OAuth2 tokens on all `/v1/**` endpoints!

## API Reference

### AiPassSDK

- `initialize(context, config)` - Initialize SDK with OAuth2 configuration
- `isInitialized()` - Check if SDK is initialized
- `setAuthorizationCallback(callback)` - Set callback for authorization results
- `authorize(activity)` - Start OAuth2 authorization flow
- `handleAuthorizationCallback(intent)` - Handle redirect (called automatically)
- `getAccessToken()` - Get current access token (null if expired)
- `isAuthenticated()` - Check if user is authenticated
- `getTokenScope()` - Get granted scopes
- `refreshAccessToken()` - Refresh expired token (suspend function)
- `logout()` - Revoke token and clear storage (suspend function)
- `clearTokens()` - Clear tokens without server revocation
- `getConfig()` - Get current configuration

### OAuth2Config

**Generic Constructor:**

```kotlin
OAuth2Config(
    authorizationEndpoint: String,
    tokenEndpoint: String,
    revocationEndpoint: String?,
    clientId: String,
    clientSecret: String?,
    redirectUri: String,
    scopes: List<String>
)
```

**AI Key Helper:**

```kotlin
OAuth2Config.forAiKey(
    baseUrl: String,
    clientId: String,
    clientSecret: String?,
    redirectUri: String,
    scopes: List<String>
)
```

### OAuth2Result (Sealed Class)

- `Success(accessToken, tokenType, expiresIn, scope, refreshToken?)`
- `Error(error, errorDescription?, exception?)`
- `Cancelled`

## Security

### PKCE

The SDK automatically generates PKCE code challenge:

```
1. Generate random code_verifier (128 chars)
2. Compute code_challenge = BASE64URL(SHA256(code_verifier))
3. Send code_challenge with authorization request
4. Send code_verifier with token exchange
5. Server validates: SHA256(code_verifier) == code_challenge
```

### Secure Storage

All tokens are encrypted using Android Keystore:

- Master key: AES256_GCM
- Key encryption: AES256_SIV
- Value encryption: AES256_GCM

### CSRF Protection

State parameter is automatically generated and validated:

```
1. Generate random state (256-bit)
2. Send with authorization request
3. Validate returned state matches
```

## Advanced Usage

### Custom HTTP Client

If you need custom OkHttp configuration:

```kotlin
// The SDK uses OkHttp and Retrofit internally
// For custom configuration, you can:

// Option 1: Use SDK-provided tokens with your own client
val token = AiPassSDK.getAccessToken()
val client = OkHttpClient.Builder()
    .addInterceptor { chain ->
        val request = chain.request().newBuilder()
            .header("Authorization", "Bearer $token")
            .build()
        chain.proceed(request)
    }
    .connectTimeout(30, TimeUnit.SECONDS)
    .build()

// Option 2: Extend OAuth2Manager if you need more control
```

### Multiple OAuth2 Providers

You can use multiple instances for different providers:

```kotlin
// For AI Key
val aiKeyConfig = OAuth2Config.forAiKey(...)
val aiKeyManager = OAuth2Manager(context, aiKeyConfig)

// For another service
val otherConfig = OAuth2Config(...)
val otherManager = OAuth2Manager(context, otherConfig)
```

Note: The singleton `AiPassSDK` can only manage one provider at a time. For multiple providers, use
`OAuth2Manager` directly.

## Troubleshooting

### "SDK not initialized" Error

Call `AiPassSDK.initialize()` in your Application class before using any other methods.

### Browser Doesn't Open

- Check AndroidManifest has correct intent filter
- Verify custom URL scheme matches redirect URI exactly
- Ensure OAuth2CallbackActivity is exported=true

### "Invalid grant" Error

- Authorization code expired (check server TTL)
- Code already used (one-time use)
- PKCE verification failed
- Redirect URI mismatch

### Callback Not Caught

- Verify intent filter scheme matches exactly
- Check launchMode is singleTask
- Restart app after manifest changes

## Requirements

- Min SDK: 21 (Android 5.0)
- Target SDK: 35 (Android 15)
- Kotlin: 1.8+

## Dependencies

- AndroidX Core
- AndroidX AppCompat
- AndroidX Browser
- Kotlin Coroutines
- AndroidX Security Crypto
- Gson
- OkHttp
- Retrofit

## License

Licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE) for details.

## Support

For issues, questions, or feature requests, please open an issue on the repository.

## Contributing

Contributions are welcome! Please see [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

---

**Version:** 1.0.0
**Last Updated:** November 2, 2024
