package one.aipass

import android.app.Activity
import android.content.Context
import android.content.Intent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import one.aipass.data.AiPassAudioApiService
import one.aipass.data.AiPassCompletionApiService
import one.aipass.data.AudioSpeechRequest
import one.aipass.data.AuthorizationInterceptor
import one.aipass.data.CompletionRequest
import one.aipass.data.ImageGenerationRequest
import one.aipass.data.Message
import one.aipass.data.OAuth2TokenStorage
import one.aipass.data.TokenAuthenticator
import one.aipass.data.UsageBalanceData
import one.aipass.domain.OAuth2Config
import one.aipass.domain.OAuth2Manager
import one.aipass.domain.OAuth2Result
import one.aipass.domain.OAuth2RevocationResult
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * AI Pass SDK - Simple OAuth2 + AI API Client
 *
 * Dead-simple API for OAuth2 authentication and AI completions.
 * Authentication, token refresh, and retries are handled automatically by
 * OkHttp interceptors inside the SDK — callers never touch Bearer tokens.
 *
 * Usage:
 * ```
 * // 1. Initialize (safe to call repeatedly; subsequent calls with the same
 * //    config are no-ops)
 * AiPassSDK.initialize(
 *     context = this,
 *     clientId = "your_client_id"
 * )
 *
 * // 2. Observe auth state
 * AiPassSDK.authState.collect { state ->
 *     when (state) {
 *         AuthState.Authenticated -> // User logged in
 *         AuthState.Unauthenticated -> // Need to login
 *         is AuthState.Error -> // Auth error
 *     }
 * }
 *
 * // 3. Start login
 * AiPassSDK.login(activity)
 *
 * // 4. Make API calls
 * val result = AiPassSDK.generateCompletion(
 *     model = "gpt-4o-mini",
 *     prompt = "Hello, how are you?"
 * )
 *
 * // 5. Logout
 * AiPassSDK.logout()
 * ```
 */
object AiPassSDK {

    private const val TAG = "AiPassSDK"
    private const val DEFAULT_BASE_URL = "https://aipass.one"
    private const val SDK_CONFIG_PREFS_NAME = "aipass_sdk_config"
    private const val KEY_AUTHORIZATION_ENDPOINT = "authorization_endpoint"
    private const val KEY_TOKEN_ENDPOINT = "token_endpoint"
    private const val KEY_REVOCATION_ENDPOINT = "revocation_endpoint"
    private const val KEY_CLIENT_ID = "client_id"
    private const val KEY_CLIENT_SECRET = "client_secret"
    private const val KEY_REDIRECT_URI = "redirect_uri"
    private const val KEY_SCOPES = "scopes"
    private val DEFAULT_SCOPES = listOf("api:access", "profile:read")

    // Internal OAuth manager
    private var manager: OAuth2Manager? = null
    private var config: OAuth2Config? = null
    private var context: Context? = null
    private var tokenStorage: OAuth2TokenStorage? = null
    private var apiService: AiPassCompletionApiService? = null
    private var audioApiService: AiPassAudioApiService? = null
    private var retrofit: Retrofit? = null

    // Auth state flow (public API)
    private val _authState = MutableStateFlow<AuthState>(AuthState.Unauthenticated)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    /**
     * Authentication state
     */
    sealed class AuthState {
        /** User is authenticated and ready to make API calls */
        object Authenticated : AuthState()

        /** User is not authenticated, need to call login() */
        object Unauthenticated : AuthState()

        /** Authentication error occurred */
        data class Error(val message: String, val exception: Throwable? = null) : AuthState()
    }

    /**
     * API call result
     */
    sealed class ApiResult<out T> {
        data class Success<T>(val data: T) : ApiResult<T>()
        data class Error(val message: String, val exception: Throwable? = null) :
            ApiResult<Nothing>()

        object Unauthenticated : ApiResult<Nothing>()
    }

    /**
     * Initialize SDK with minimal configuration.
     *
     * Safe to call repeatedly — subsequent calls with the same config are
     * effectively no-ops. Call this once in `Application.onCreate` (or
     * wherever your DI graph is built) and let the SDK handle the rest.
     *
     * @param context Application context
     * @param clientId Your OAuth2 client ID
     * @param baseUrl API base URL (defaults to https://aipass.one)
     * @param scopes OAuth2 scopes (defaults to api:access, profile:read)
     */
    @JvmOverloads
    fun initialize(
        context: Context,
        clientId: String,
        baseUrl: String = DEFAULT_BASE_URL,
        scopes: List<String> = DEFAULT_SCOPES
    ) {
        if (clientId.isBlank()) {
            throw IllegalArgumentException("Client ID cannot be blank")
        }

        // Auto-generate redirect URI from package name
        val redirectUri = "aipass://${context.packageName}/callback"

        val newConfig = OAuth2Config.forAiKey(
            baseUrl = baseUrl,
            clientId = clientId,
            redirectUri = redirectUri,
            scopes = scopes
        )

        initialize(context.applicationContext, newConfig)
    }

    /**
     * Fetch user balance in background
     * Result is available via getUserBalance() API call
     */
    private suspend fun fetchBalanceInBackground() {
        try {
            getUserBalance()
        } catch (e: Exception) {
            // Ignore - this is non-critical background operation
        }
    }

    /**
     * Start login flow
     * Opens browser for user to authenticate
     *
     * @param activity Current activity
     */
    fun login(activity: Activity) {
        val mgr =
            manager ?: throw IllegalStateException("SDK not initialized. Call initialize() first.")

        try {
            mgr.startAuthorization(activity) { result ->
                handleOAuthResult(result)
            }
        } catch (e: Exception) {
            _authState.value = AuthState.Error("Failed to start login", e)
        }
    }

    /**
     * Get user's usage balance and budget.
     *
     * Authentication and token refresh are handled transparently by the SDK's
     * OkHttp authenticator — you just call this and get the result.
     *
     * @return API result with balance data or error
     */
    suspend fun getUserBalance(): ApiResult<UsageBalanceData> = withContext(Dispatchers.IO) {
        try {
            val service = ensureApiService()
                ?: return@withContext ApiResult.Error("SDK not initialized")

            if (!isAuthenticated()) {
                return@withContext ApiResult.Unauthenticated
            }

            val response = service.getUserBalance()

            when {
                response.isSuccessful -> {
                    val balanceResponse = response.body()
                    if (balanceResponse?.success == true && balanceResponse.data != null) {
                        ApiResult.Success(balanceResponse.data)
                    } else {
                        ApiResult.Error("Empty or invalid balance response")
                    }
                }
                response.code() == 401 -> {
                    // Authenticator already tried to refresh; if we still see 401
                    // the refresh failed (or user was logged out).
                    _authState.value = AuthState.Unauthenticated
                    ApiResult.Unauthenticated
                }
                else -> ApiResult.Error("API error: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            ApiResult.Error("Balance API call failed: ${e.message}", e)
        }
    }

    /**
     * Generate AI completion.
     *
     * Authentication and token refresh are handled transparently by the SDK's
     * OkHttp authenticator.
     *
     * @param model Model name (e.g., "gpt-4o-mini", "gpt-4o")
     * @param prompt User prompt
     * @param temperature Sampling temperature (0.0 to 2.0)
     * @param maxTokens Maximum tokens in response
     * @return API result with completion text or error
     */
    suspend fun generateCompletion(
        model: String,
        prompt: String,
        temperature: Double = 0.7,
        maxTokens: Int = 1000
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val service = ensureApiService()
                ?: return@withContext ApiResult.Error("SDK not initialized")

            if (!isAuthenticated()) {
                return@withContext ApiResult.Unauthenticated
            }

            val request = CompletionRequest(
                model = model,
                messages = listOf(Message(role = "user", content = prompt)),
                temperature = temperature,
                maxTokens = maxTokens
            )

            val response = service.generateCompletion(request)

            when {
                response.isSuccessful -> {
                    val completion = response.body()?.choices?.firstOrNull()?.message?.content
                    if (completion != null) {
                        ApiResult.Success(completion as String)
                    } else {
                        ApiResult.Error("Empty response from API")
                    }
                }
                response.code() == 401 -> {
                    _authState.value = AuthState.Unauthenticated
                    ApiResult.Unauthenticated
                }
                else -> ApiResult.Error("API error: ${response.code()} ${response.message()}")
            }
        } catch (e: Exception) {
            ApiResult.Error("API call failed: ${e.message}", e)
        }
    }

    /**
     * Generate speech audio from text (Text-to-Speech).
     *
     * @param text The text to convert to speech
     * @param model Model name in LiteLLM format (e.g., "tts-1", "azure/tts-hd")
     * @param voice Voice selection (e.g., "alloy", "echo", "fable", "onyx", "nova", "shimmer")
     * @param responseFormat Audio format (mp3, opus, aac, flac, wav, pcm), defaults to "mp3"
     * @param speed Playback speed (0.25 - 4.0), defaults to 1.0
     * @return ApiResult with audio file path or error
     */
    suspend fun generateSpeech(
        text: String,
        model: String = "tts-1",
        voice: String = "alloy",
        responseFormat: String = "mp3",
        speed: Double = 1.0
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val ctx = context ?: return@withContext ApiResult.Error("SDK not initialized")
            val audioService = createAudioApiService()
                ?: return@withContext ApiResult.Error("SDK not initialized")

            if (!isAuthenticated()) {
                return@withContext ApiResult.Unauthenticated
            }

            val request = AudioSpeechRequest(
                model = model,
                input = text.trim(),
                voice = voice,
                responseFormat = responseFormat,
                speed = speed
            )

            val response = audioService.generateSpeech(request)

            when {
                response.isSuccessful -> {
                    val audioBytes = response.body()?.bytes()
                    if (audioBytes != null && audioBytes.isNotEmpty()) {
                        val extension = when (responseFormat.lowercase()) {
                            "opus" -> "opus"
                            "aac" -> "aac"
                            "flac" -> "flac"
                            "wav" -> "wav"
                            "pcm" -> "pcm"
                            else -> "mp3"
                        }
                        val tempFile = File.createTempFile("tts_", ".$extension", ctx.cacheDir)
                        tempFile.outputStream().use { it.write(audioBytes) }
                        ApiResult.Success(tempFile.absolutePath)
                    } else {
                        ApiResult.Error("Empty audio response from API")
                    }
                }
                response.code() == 401 -> {
                    _authState.value = AuthState.Unauthenticated
                    ApiResult.Unauthenticated
                }
                else -> {
                    val errorBody = response.errorBody()?.string() ?: response.message()
                    ApiResult.Error("TTS API error: ${response.code()} - $errorBody")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("TTS API call failed: ${e.message}", e)
        }
    }

    /**
     * Transcribe audio to text (Speech-to-Text).
     *
     * @param audioFile Audio file to transcribe
     * @param model Model name in LiteLLM format (e.g., "whisper-1", "groq/whisper-large-v3")
     * @param language Optional language code (ISO 639-1, e.g., "en", "es", "fr")
     * @param prompt Optional context/prompt to guide transcription
     * @param temperature Optional sampling temperature (0-1)
     * @return ApiResult with transcribed text or error
     */
    suspend fun transcribeAudio(
        audioFile: File,
        model: String = "whisper-1",
        language: String? = null,
        prompt: String? = null,
        temperature: Double = 0.0
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            if (!audioFile.exists()) {
                return@withContext ApiResult.Error("Audio file does not exist: ${audioFile.path}")
            }

            val audioService = createAudioApiService()
                ?: return@withContext ApiResult.Error("SDK not initialized")

            if (!isAuthenticated()) {
                return@withContext ApiResult.Unauthenticated
            }

            val requestFile = audioFile.asRequestBody("audio/*".toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)

            val modelPart = model.toRequestBody("text/plain".toMediaTypeOrNull())
            val languagePart = language?.toRequestBody("text/plain".toMediaTypeOrNull())
            val promptPart = prompt?.toRequestBody("text/plain".toMediaTypeOrNull())
            val temperaturePart =
                temperature.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val responseFormatPart = "json".toRequestBody("text/plain".toMediaTypeOrNull())

            val response = audioService.transcribeAudio(
                file = filePart,
                model = modelPart,
                language = languagePart,
                prompt = promptPart,
                temperature = temperaturePart,
                responseFormat = responseFormatPart
            )

            when {
                response.isSuccessful -> {
                    val transcription = response.body()
                    if (transcription != null && transcription.text.isNotBlank()) {
                        ApiResult.Success(transcription.text)
                    } else {
                        ApiResult.Error("Empty transcription response from API")
                    }
                }
                response.code() == 401 -> {
                    _authState.value = AuthState.Unauthenticated
                    ApiResult.Unauthenticated
                }
                else -> {
                    val errorBody = response.errorBody()?.string() ?: response.message()
                    ApiResult.Error("STT API error: ${response.code()} - $errorBody")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("STT API call failed: ${e.message}", e)
        }
    }

    /**
     * Generate an image from a text prompt.
     *
     * @param prompt Text description of the image to generate
     * @param model Model name (e.g., "gpt-image-1", "dall-e-3")
     * @param size Image size (e.g., "1024x1024", "1024x1536")
     * @param quality Image quality ("auto", "low", "medium", "high")
     * @param responseFormat Response format ("b64_json" or "url")
     * @return ApiResult with base64-encoded image data (b64_json) or URL string
     */
    suspend fun generateImage(
        prompt: String,
        model: String = "gpt-image-1",
        size: String = "1024x1024",
        quality: String = "auto",
        responseFormat: String = "b64_json"
    ): ApiResult<String> = withContext(Dispatchers.IO) {
        try {
            val service = ensureApiService()
                ?: return@withContext ApiResult.Error("SDK not initialized")

            if (!isAuthenticated()) {
                return@withContext ApiResult.Unauthenticated
            }

            val request = ImageGenerationRequest(
                model = model,
                prompt = prompt,
                n = 1,
                size = size,
                quality = quality,
                responseFormat = responseFormat
            )

            val response = service.generateImage(request)

            when {
                response.isSuccessful -> {
                    val imageData = response.body()?.data?.firstOrNull()
                    when {
                        responseFormat == "b64_json" && imageData?.b64Json != null ->
                            ApiResult.Success(imageData.b64Json)
                        imageData?.url != null ->
                            ApiResult.Success(imageData.url)
                        else -> ApiResult.Error("Empty image response from API")
                    }
                }
                response.code() == 401 -> {
                    _authState.value = AuthState.Unauthenticated
                    ApiResult.Unauthenticated
                }
                else -> {
                    val errorBody = response.errorBody()?.string() ?: response.message()
                    ApiResult.Error("Image API error: ${response.code()} - $errorBody")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Image API call failed: ${e.message}", e)
        }
    }

    /**
     * Logout - revokes token and clears local storage
     */
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        val mgr = manager ?: return@withContext false

        val result = mgr.logout()
        val success = result is OAuth2RevocationResult.Success

        _authState.value = AuthState.Unauthenticated
        success
    }

    /**
     * Logout (alternative signature that returns OAuth2RevocationResult)
     * For backward compatibility
     */
    suspend fun logoutWithResult(): OAuth2RevocationResult = withContext(Dispatchers.IO) {
        val mgr = manager ?: return@withContext OAuth2RevocationResult.Error("SDK not initialized")

        val result = mgr.logout()
        _authState.value = AuthState.Unauthenticated
        result
    }

    /**
     * Check if user is currently authenticated
     */
    fun isAuthenticated(): Boolean {
        return manager?.isAuthenticated() ?: false
    }

    /**
     * Open AI Pass dashboard in browser
     * Useful for users to check balance, add funds, or manage their account
     *
     * @param activity Current activity to launch browser from
     */
    fun openDashboard(activity: Activity) {
        val baseUrl = config?.tokenEndpoint?.substringBefore("/oauth2") ?: DEFAULT_BASE_URL
        val dashboardUrl = "$baseUrl/panel/dashboard.html"
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse(dashboardUrl))
        activity.startActivity(intent)
    }

    // ========== INTERNAL METHODS (NOT PUBLIC API) ==========

    /**
     * Handle OAuth callback from browser redirect
     * Internal use only - called by OAuth2CallbackActivity
     */
    internal fun handleAuthorizationCallback(intent: Intent) {
        val mgr = manager ?: run {
            _authState.value = AuthState.Error("SDK not initialized")
            return
        }

        CoroutineScope(Dispatchers.Main).launch {
            mgr.handleCallback(intent) { result ->
                handleOAuthResult(result)
            }
        }
    }

    private fun updateAuthState() {
        _authState.value = if (isAuthenticated()) {
            AuthState.Authenticated
        } else {
            AuthState.Unauthenticated
        }
    }

    private fun handleOAuthResult(result: OAuth2Result) {
        _authState.value = when (result) {
            is OAuth2Result.Success -> AuthState.Authenticated
            is OAuth2Result.Error -> {
                val message = result.errorDescription ?: result.error
                AuthState.Error(message, result.exception)
            }
            OAuth2Result.Cancelled -> AuthState.Unauthenticated
        }
    }

    private fun ensureRetrofit(): Retrofit? {
        if (retrofit != null) {
            return retrofit
        }

        val cfg = config ?: return null
        val storage = tokenStorage ?: return null
        val mgr = manager ?: return null

        // Extract base URL from token endpoint
        val baseUrl = cfg.tokenEndpoint.substringBefore("/oauth2")
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 5,
                    keepAliveDuration = 30,
                    TimeUnit.SECONDS
                )
            )
            .retryOnConnectionFailure(true)
            // Auto-inject `Authorization: Bearer <token>` on every request.
            .addInterceptor(AuthorizationInterceptor(storage))
            // Auto-refresh on 401 and retry once.
            .authenticator(TokenAuthenticator(managerProvider = { manager }, tokenStorage = storage))
            // Retry on transient 5xx / network errors.
            .addInterceptor { chain ->
                var request = chain.request()
                var response: okhttp3.Response? = null
                var tryCount = 0
                val maxRetries = 2

                while (tryCount < maxRetries) {
                    try {
                        response = chain.proceed(request)

                        if (response.isSuccessful || response.code in 400..499) {
                            return@addInterceptor response
                        }

                        response.close()
                        tryCount++
                        if (tryCount < maxRetries) {
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
            .build()

        retrofit = Retrofit.Builder()
            .baseUrl(normalizedBaseUrl)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create())
            .build()

        return retrofit
    }

    private fun ensureApiService(): AiPassCompletionApiService? {
        if (apiService != null) {
            return apiService
        }

        val retrofit = ensureRetrofit() ?: return null
        apiService = retrofit.create(AiPassCompletionApiService::class.java)
        return apiService
    }

    // ========== LEGACY/ADVANCED API (for backward compatibility) ==========

    /**
     * Initialize with custom configuration (advanced users only).
     *
     * Idempotent: if the SDK is already initialized with an equivalent config,
     * this call is a no-op. If the config differs, the internal manager and
     * Retrofit client are rebuilt.
     */
    fun initialize(context: Context, config: OAuth2Config) {
        // Idempotency: skip work if already initialized with same config.
        if (isInitialized() && this.config == config) {
            return
        }

        val appContext = context.applicationContext
        this.context = appContext
        this.config = config
        persistConfig(appContext, config)

        // Rebuild everything so the new config's interceptors pick up the
        // fresh token storage and manager.
        this.tokenStorage = OAuth2TokenStorage(appContext)
        this.manager = OAuth2Manager(appContext, config)
        this.retrofit = null
        this.apiService = null
        this.audioApiService = null

        updateAuthState()

        // Warm up the balance cache in the background. Only do this on a fresh
        // init — we don't want to burn a request every time the app rebinds.
        CoroutineScope(Dispatchers.IO).launch {
            fetchBalanceInBackground()
        }
    }

    internal fun recoverInitialization(context: Context): Boolean {
        if (isInitialized()) {
            return true
        }

        val prefs = context.applicationContext.getSharedPreferences(
            SDK_CONFIG_PREFS_NAME,
            Context.MODE_PRIVATE
        )

        val authorizationEndpoint = prefs.getString(KEY_AUTHORIZATION_ENDPOINT, null)
        val tokenEndpoint = prefs.getString(KEY_TOKEN_ENDPOINT, null)
        val clientId = prefs.getString(KEY_CLIENT_ID, null)
        val redirectUri = prefs.getString(KEY_REDIRECT_URI, null)
        val scopes = prefs.getStringSet(KEY_SCOPES, null)?.toList() ?: DEFAULT_SCOPES

        if (
            authorizationEndpoint.isNullOrBlank() ||
            tokenEndpoint.isNullOrBlank() ||
            clientId.isNullOrBlank() ||
            redirectUri.isNullOrBlank()
        ) {
            _authState.value = AuthState.Error("SDK not initialized")
            return false
        }

        val recoveredConfig = OAuth2Config(
            authorizationEndpoint = authorizationEndpoint,
            tokenEndpoint = tokenEndpoint,
            revocationEndpoint = prefs.getString(KEY_REVOCATION_ENDPOINT, null),
            clientId = clientId,
            clientSecret = prefs.getString(KEY_CLIENT_SECRET, null),
            redirectUri = redirectUri,
            scopes = scopes
        )

        initialize(context.applicationContext, recoveredConfig)
        return true
    }

    private fun persistConfig(context: Context, config: OAuth2Config) {
        context.getSharedPreferences(SDK_CONFIG_PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_AUTHORIZATION_ENDPOINT, config.authorizationEndpoint)
            .putString(KEY_TOKEN_ENDPOINT, config.tokenEndpoint)
            .putString(KEY_REVOCATION_ENDPOINT, config.revocationEndpoint)
            .putString(KEY_CLIENT_ID, config.clientId)
            .putString(KEY_CLIENT_SECRET, config.clientSecret)
            .putString(KEY_REDIRECT_URI, config.redirectUri)
            .putStringSet(KEY_SCOPES, config.scopes.toSet())
            .commit()
    }

    /**
     * Start authorization (legacy method name)
     * Use login(activity) instead
     */
    @Deprecated("Use login(activity) instead", ReplaceWith("login(activity)"))
    fun authorize(activity: Activity) {
        login(activity)
    }

    /**
     * Set authorization callback (legacy callback-based API)
     * Use authState Flow instead
     *
     * @param callback Function to receive OAuth2Result
     */
    @Deprecated("Use authState Flow instead", ReplaceWith("authState.collect { }"))
    fun setAuthorizationCallback(callback: (OAuth2Result) -> Unit) {
        CoroutineScope(Dispatchers.Main).launch {
            authState.collect { state ->
                when (state) {
                    AuthState.Authenticated -> {
                        val token = getAccessToken() ?: return@collect
                        callback(
                            OAuth2Result.Success(
                                accessToken = token,
                                expiresIn = 3600,
                                scope = DEFAULT_SCOPES.joinToString(" ")
                            )
                        )
                    }
                    AuthState.Unauthenticated -> Unit
                    is AuthState.Error -> {
                        callback(
                            OAuth2Result.Error(
                                error = "auth_error",
                                errorDescription = state.message,
                                exception = state.exception
                            )
                        )
                    }
                }
            }
        }
    }

    /**
     * Check if SDK is initialized
     */
    fun isInitialized(): Boolean {
        return manager != null && config != null
    }

    /**
     * Get current OAuth2 configuration
     */
    fun getConfig(): OAuth2Config? = config

    /**
     * Get access token directly (advanced users only).
     *
     * Most users don't need this — the SDK's Retrofit services inject the
     * token automatically and refresh it on 401.
     */
    fun getAccessToken(): String? = manager?.getAccessToken()

    /**
     * Create completion API service (advanced users only).
     *
     * Returns the cached instance — safe to call repeatedly.
     */
    fun createCompletionApiService(): AiPassCompletionApiService? {
        return ensureApiService()
    }

    /**
     * Create audio API service for TTS/STT (advanced users only).
     *
     * Returns the cached instance — safe to call repeatedly.
     */
    fun createAudioApiService(): AiPassAudioApiService? {
        if (audioApiService != null) {
            return audioApiService
        }

        val retrofit = ensureRetrofit() ?: return null
        audioApiService = retrofit.create(AiPassAudioApiService::class.java)
        return audioApiService
    }

    /**
     * Manually refresh access token (advanced users only).
     *
     * Not normally needed — the SDK's authenticator refreshes automatically
     * on 401. Provided for diagnostics and edge cases.
     */
    suspend fun refreshAccessToken(): OAuth2Result {
        val mgr = manager ?: return OAuth2Result.Error(
            error = "not_initialized",
            errorDescription = "SDK not initialized"
        )
        return mgr.refreshAccessToken()
    }

    /**
     * Clear tokens locally without server call (advanced users only)
     */
    fun clearTokens() {
        context?.let {
            val storage = OAuth2TokenStorage(it)
            storage.clearTokens()
            _authState.value = AuthState.Unauthenticated
        }
    }
}
