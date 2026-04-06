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
import one.aipass.data.CompletionRequest
import one.aipass.data.ImageGenerationRequest
import one.aipass.data.Message
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
 * Everything is handled automatically - tokens, refresh, errors, etc.
 *
 * Usage:
 * ```
 * // 1. Initialize (in Application.onCreate)
 * AiPassSDK.initialize(
 *     context = this,
 *     clientId = "your_client_id"
 * )
 *
 * // 2. Observe auth state (in ViewModel/Activity)
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
 * // 4. Make API calls (that's it!)
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
    private val DEFAULT_SCOPES = listOf("api:access", "profile:read")

    // Internal OAuth manager
    private var manager: OAuth2Manager? = null
    private var config: OAuth2Config? = null
    private var context: Context? = null
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
     * Initialize SDK with minimal configuration
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

        this.context = context.applicationContext

        // Auto-generate redirect URI from package name
        val packageName = context.packageName
        val redirectUri = "aipass://$packageName/callback"

        // Create config with sensible defaults
        this.config = OAuth2Config.forAiKey(
            baseUrl = baseUrl,
            clientId = clientId,
            redirectUri = redirectUri,
            scopes = scopes
        )

        // Initialize OAuth manager
        this.manager = OAuth2Manager(context.applicationContext, config!!)

        // Check current auth status
        updateAuthState()

        CoroutineScope(Dispatchers.IO).launch {
            fetchBalanceInBackground()
        }
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
     * Get user's usage balance and budget
     * Automatically handles authentication and token refresh
     *
     * @return API result with balance data or error
     */
    suspend fun getUserBalance(): ApiResult<UsageBalanceData> = withContext(Dispatchers.IO) {
        try {
            ensureApiService() ?: return@withContext ApiResult.Error("SDK not initialized")

            val token = getValidToken()
                ?: return@withContext ApiResult.Unauthenticated

            val response = apiService!!.getUserBalance(
                authorization = "Bearer $token"
            )

            if (response.isSuccessful) {
                val balanceResponse = response.body()
                if (balanceResponse?.success == true && balanceResponse.data != null) {
                    ApiResult.Success(balanceResponse.data)
                } else {
                    ApiResult.Error("Empty or invalid balance response")
                }
            } else {
                if (response.code() == 401) {
                    val refreshed = attemptTokenRefresh()
                    if (refreshed) {
                        return@withContext retryBalanceFetch()
                    } else {
                        _authState.value = AuthState.Unauthenticated
                        ApiResult.Unauthenticated
                    }
                } else {
                    ApiResult.Error("API error: ${response.code()} ${response.message()}")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Balance API call failed: ${e.message}", e)
        }
    }

    /**
     * Generate AI completion
     * Automatically handles authentication, token refresh, and errors
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
            ensureApiService() ?: return@withContext ApiResult.Error("SDK not initialized")

            val token = getValidToken()
                ?: return@withContext ApiResult.Unauthenticated

            val request = CompletionRequest(
                model = model,
                messages = listOf(
                    Message(role = "user", content = prompt)
                ),
                temperature = temperature,
                maxTokens = maxTokens
            )

            val response = apiService!!.generateCompletion(
                authorization = "Bearer $token",
                request = request
            )

            if (response.isSuccessful) {
                val completion = response.body()?.choices?.firstOrNull()?.message?.content
                if (completion != null) {
                    ApiResult.Success(completion as String)
                } else {
                    ApiResult.Error("Empty response from API")
                }
            } else {
                if (response.code() == 401) {
                    val refreshed = attemptTokenRefresh()
                    if (refreshed) {
                        return@withContext retryCompletion(model, prompt, temperature, maxTokens)
                    } else {
                        _authState.value = AuthState.Unauthenticated
                        ApiResult.Unauthenticated
                    }
                } else {
                    ApiResult.Error("API error: ${response.code()} ${response.message()}")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("API call failed: ${e.message}", e)
        }
    }

    /**
     * Generate speech audio from text (Text-to-Speech)
     * Automatically handles authentication, token refresh, and errors
     * Works with any LiteLLM-supported TTS provider (OpenAI, Azure, Gemini, etc.)
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

            // Ensure audio service is created
            val audioService = createAudioApiService()
                ?: return@withContext ApiResult.Error("SDK not initialized")

            // Get or refresh token
            val token = getValidToken()
                ?: return@withContext ApiResult.Unauthenticated

            // Create request
            val request = AudioSpeechRequest(
                model = model,
                input = text.trim(),
                voice = voice,
                responseFormat = responseFormat,
                speed = speed
            )

            // Make API call
            val response = audioService.generateSpeech(
                authorization = "Bearer $token",
                request = request
            )

            // Handle response
            if (response.isSuccessful) {
                val audioBytes = response.body()?.bytes()
                if (audioBytes != null && audioBytes.isNotEmpty()) {
                    // Save to temp file in cache directory
                    val extension = when (responseFormat.lowercase()) {
                        "mp3" -> "mp3"
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
            } else {
                if (response.code() == 401) {
                    val refreshed = attemptTokenRefresh()
                    if (refreshed) {
                        return@withContext retrySpeechGeneration(
                            text,
                            model,
                            voice,
                            responseFormat,
                            speed
                        )
                    } else {
                        _authState.value = AuthState.Unauthenticated
                        ApiResult.Unauthenticated
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: response.message()
                    ApiResult.Error("TTS API error: ${response.code()} - $errorBody")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("TTS API call failed: ${e.message}", e)
        }
    }

    /**
     * Transcribe audio to text (Speech-to-Text)
     * Automatically handles authentication, token refresh, and errors
     * Works with any LiteLLM-supported STT provider (OpenAI Whisper, Groq, Azure, Deepgram, etc.)
     *
     * @param audioFile Audio file to transcribe
     * @param model Model name in LiteLLM format (e.g., "whisper-1", "groq/whisper-large-v3")
     * @param language Optional language code (ISO 639-1, e.g., "en", "es", "fr")
     * @param prompt Optional context/prompt to guide transcription
     * @param temperature Optional sampling temperature (0-1), defaults to 0 for deterministic output
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

            // Ensure audio service is created
            val audioService = createAudioApiService()
                ?: return@withContext ApiResult.Error("SDK not initialized")

            // Get or refresh token
            val token = getValidToken()
                ?: return@withContext ApiResult.Unauthenticated

            // Prepare multipart file
            val requestFile = audioFile.asRequestBody("audio/*".toMediaTypeOrNull())
            val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)

            // Prepare form fields
            val modelPart = model.toRequestBody("text/plain".toMediaTypeOrNull())
            val languagePart = language?.toRequestBody("text/plain".toMediaTypeOrNull())
            val promptPart = prompt?.toRequestBody("text/plain".toMediaTypeOrNull())
            val temperaturePart =
                temperature.toString().toRequestBody("text/plain".toMediaTypeOrNull())
            val responseFormatPart = "json".toRequestBody("text/plain".toMediaTypeOrNull())

            // Make API call
            val response = audioService.transcribeAudio(
                authorization = "Bearer $token",
                file = filePart,
                model = modelPart,
                language = languagePart,
                prompt = promptPart,
                temperature = temperaturePart,
                responseFormat = responseFormatPart
            )

            // Handle response
            if (response.isSuccessful) {
                val transcription = response.body()
                if (transcription != null && transcription.text.isNotBlank()) {
                    ApiResult.Success(transcription.text)
                } else {
                    ApiResult.Error("Empty transcription response from API")
                }
            } else {
                if (response.code() == 401) {
                    val refreshed = attemptTokenRefresh()
                    if (refreshed) {
                        return@withContext retryAudioTranscription(
                            audioFile,
                            model,
                            language,
                            prompt,
                            temperature
                        )
                    } else {
                        _authState.value = AuthState.Unauthenticated
                        ApiResult.Unauthenticated
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: response.message()
                    ApiResult.Error("STT API error: ${response.code()} - $errorBody")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("STT API call failed: ${e.message}", e)
        }
    }

    /**
     * Generate an image from a text prompt
     * Automatically handles authentication, token refresh, and errors
     * Uses OpenAI-compatible /v1/images/generations endpoint via LiteLLM proxy
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
            ensureApiService() ?: return@withContext ApiResult.Error("SDK not initialized")

            val token = getValidToken()
                ?: return@withContext ApiResult.Unauthenticated

            val request = ImageGenerationRequest(
                model = model,
                prompt = prompt,
                n = 1,
                size = size,
                quality = quality,
                responseFormat = responseFormat
            )

            val response = apiService!!.generateImage(
                authorization = "Bearer $token",
                request = request
            )

            if (response.isSuccessful) {
                val imageResponse = response.body()
                val imageData = imageResponse?.data?.firstOrNull()
                when {
                    responseFormat == "b64_json" && imageData?.b64Json != null -> {
                        ApiResult.Success(imageData.b64Json)
                    }
                    imageData?.url != null -> {
                        ApiResult.Success(imageData.url)
                    }
                    else -> {
                        ApiResult.Error("Empty image response from API")
                    }
                }
            } else {
                if (response.code() == 401) {
                    val refreshed = attemptTokenRefresh()
                    if (refreshed) {
                        return@withContext retryImageGeneration(prompt, model, size, quality, responseFormat)
                    } else {
                        _authState.value = AuthState.Unauthenticated
                        ApiResult.Unauthenticated
                    }
                } else {
                    val errorBody = response.errorBody()?.string() ?: response.message()
                    ApiResult.Error("Image API error: ${response.code()} - $errorBody")
                }
            }
        } catch (e: Exception) {
            ApiResult.Error("Image API call failed: ${e.message}", e)
        }
    }

    private suspend fun retryImageGeneration(
        prompt: String,
        model: String,
        size: String,
        quality: String,
        responseFormat: String
    ): ApiResult<String> {
        val token = manager?.getAccessToken() ?: return ApiResult.Unauthenticated

        val request = ImageGenerationRequest(
            model = model,
            prompt = prompt,
            n = 1,
            size = size,
            quality = quality,
            responseFormat = responseFormat
        )

        val response = apiService!!.generateImage(
            authorization = "Bearer $token",
            request = request
        )

        return if (response.isSuccessful) {
            val imageData = response.body()?.data?.firstOrNull()
            when {
                responseFormat == "b64_json" && imageData?.b64Json != null -> {
                    ApiResult.Success(imageData.b64Json)
                }
                imageData?.url != null -> {
                    ApiResult.Success(imageData.url)
                }
                else -> {
                    ApiResult.Error("Empty image response from API")
                }
            }
        } else {
            ApiResult.Error("Image API error: ${response.code()}")
        }
    }

    /**
     * Logout - revokes token and clears local storage
     */
    suspend fun logout(): Boolean = withContext(Dispatchers.IO) {
        val mgr = manager ?: return@withContext false

        val result = mgr.logout()
        val success = result is OAuth2RevocationResult.Success

        // Update auth state
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

        // Update auth state
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
        // Extract base URL from token endpoint
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
            is OAuth2Result.Success -> {
                AuthState.Authenticated
            }

            is OAuth2Result.Error -> {
                val message = result.errorDescription ?: result.error
                AuthState.Error(message, result.exception)
            }

            OAuth2Result.Cancelled -> {
                AuthState.Unauthenticated
            }
        }
    }

    private suspend fun getValidToken(): String? {
        val mgr = manager ?: return null

        // Check if we have a valid token
        var token = mgr.getAccessToken()
        if (token != null) {
            return token
        }

        val refreshed = attemptTokenRefresh()
        return if (refreshed) {
            mgr.getAccessToken()
        } else {
            null
        }
    }

    private suspend fun attemptTokenRefresh(): Boolean {
        val mgr = manager ?: return false

        return when (val result = mgr.refreshAccessToken()) {
            is OAuth2Result.Success -> {
                _authState.value = AuthState.Authenticated
                true
            }

            else -> {
                _authState.value = AuthState.Unauthenticated
                false
            }
        }
    }

    private suspend fun retryBalanceFetch(): ApiResult<UsageBalanceData> {
        val token = manager?.getAccessToken()
            ?: return ApiResult.Unauthenticated

        val response = apiService!!.getUserBalance(
            authorization = "Bearer $token"
        )

        return if (response.isSuccessful) {
            val balanceResponse = response.body()
            if (balanceResponse?.success == true && balanceResponse.data != null) {
                ApiResult.Success(balanceResponse.data)
            } else {
                ApiResult.Error("Empty or invalid balance response")
            }
        } else {
            ApiResult.Error("API error: ${response.code()}")
        }
    }

    private suspend fun retryCompletion(
        model: String,
        prompt: String,
        temperature: Double,
        maxTokens: Int
    ): ApiResult<String> {
        val token = manager?.getAccessToken()
            ?: return ApiResult.Unauthenticated

        val request = CompletionRequest(
            model = model,
            messages = listOf(
                Message(role = "user", content = prompt)
            ),
            temperature = temperature,
            maxTokens = maxTokens
        )

        val response = apiService!!.generateCompletion(
            authorization = "Bearer $token",
            request = request
        )

        return if (response.isSuccessful) {
            val completion = response.body()?.choices?.firstOrNull()?.message?.content
            if (completion != null) {
                ApiResult.Success(completion as String)
            } else {
                ApiResult.Error("Empty response from API")
            }
        } else {
            ApiResult.Error("API error: ${response.code()}")
        }
    }

    private suspend fun retrySpeechGeneration(
        text: String,
        model: String,
        voice: String,
        responseFormat: String,
        speed: Double
    ): ApiResult<String> {
        val ctx = context ?: return ApiResult.Error("SDK not initialized")
        val token = manager?.getAccessToken() ?: return ApiResult.Unauthenticated
        val audioService =
            audioApiService ?: return ApiResult.Error("Audio service not initialized")

        val request = AudioSpeechRequest(
            model = model,
            input = text.trim(),
            voice = voice,
            responseFormat = responseFormat,
            speed = speed
        )

        val response = audioService.generateSpeech(
            authorization = "Bearer $token",
            request = request
        )

        return if (response.isSuccessful) {
            val audioBytes = response.body()?.bytes()
            if (audioBytes != null && audioBytes.isNotEmpty()) {
                val extension = when (responseFormat.lowercase()) {
                    "mp3" -> "mp3"
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
        } else {
            ApiResult.Error("TTS API error: ${response.code()}")
        }
    }

    private suspend fun retryAudioTranscription(
        audioFile: File,
        model: String,
        language: String?,
        prompt: String?,
        temperature: Double
    ): ApiResult<String> {
        val token = manager?.getAccessToken() ?: return ApiResult.Unauthenticated
        val audioService =
            audioApiService ?: return ApiResult.Error("Audio service not initialized")

        // Prepare multipart file
        val requestFile = audioFile.asRequestBody("audio/*".toMediaTypeOrNull())
        val filePart = MultipartBody.Part.createFormData("file", audioFile.name, requestFile)

        // Prepare form fields
        val modelPart = model.toRequestBody("text/plain".toMediaTypeOrNull())
        val languagePart = language?.toRequestBody("text/plain".toMediaTypeOrNull())
        val promptPart = prompt?.toRequestBody("text/plain".toMediaTypeOrNull())
        val temperaturePart = temperature.toString().toRequestBody("text/plain".toMediaTypeOrNull())
        val responseFormatPart = "json".toRequestBody("text/plain".toMediaTypeOrNull())

        val response = audioService.transcribeAudio(
            authorization = "Bearer $token",
            file = filePart,
            model = modelPart,
            language = languagePart,
            prompt = promptPart,
            temperature = temperaturePart,
            responseFormat = responseFormatPart
        )

        return if (response.isSuccessful) {
            val transcription = response.body()
            if (transcription != null && transcription.text.isNotBlank()) {
                ApiResult.Success(transcription.text)
            } else {
                ApiResult.Error("Empty transcription response from API")
            }
        } else {
            ApiResult.Error("STT API error: ${response.code()}")
        }
    }

    private fun ensureRetrofit(): Retrofit? {
        if (retrofit != null) {
            return retrofit
        }

        val cfg = config ?: return null

        // Extract base URL from token endpoint
        val baseUrl = cfg.tokenEndpoint.substringBefore("/oauth2")
        val normalizedBaseUrl = if (baseUrl.endsWith("/")) baseUrl else "$baseUrl/"

        // Create OkHttp client with mobile network optimizations
        val okHttpClient = OkHttpClient.Builder()
            // Timeouts optimized for mobile networks
            .connectTimeout(15, TimeUnit.SECONDS) // Faster failure on bad connections
            .readTimeout(90, TimeUnit.SECONDS) // Longer for AI/audio processing on slow networks
            .writeTimeout(90, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS) // Overall request timeout

            // Connection pool for reusing connections (critical for mobile)
            .connectionPool(
                okhttp3.ConnectionPool(
                    maxIdleConnections = 5,
                    keepAliveDuration = 30, // Keep connections alive for 30 seconds
                    TimeUnit.SECONDS
                )
            )

            // Retry on connection failures (mobile networks are flaky)
            .retryOnConnectionFailure(true)

            // Add interceptor for better mobile network handling
            .addInterceptor { chain ->
                var request = chain.request()
                var response: okhttp3.Response? = null
                var tryCount = 0
                val maxRetries = 2

                while (tryCount < maxRetries) {
                    try {
                        response = chain.proceed(request)

                        // If response is successful or client error, don't retry
                        if (response.isSuccessful || response.code in 400..499) {
                            return@addInterceptor response
                        }

                        // Close the failed response
                        response.close()

                        tryCount++
                        if (tryCount < maxRetries) {
                            // Exponential backoff: 1s, 2s
                            Thread.sleep((1000 * tryCount).toLong())
                        }
                    } catch (e: Exception) {
                        tryCount++
                        if (tryCount >= maxRetries) {
                            throw e
                        }
                        // Exponential backoff for exceptions too
                        Thread.sleep((1000 * tryCount).toLong())
                    }
                }

                // Return last response or throw
                response ?: throw java.io.IOException("Failed after $maxRetries retries")
            }
            .build()

        // Create Retrofit (shared for all API services)
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
     * Initialize with custom configuration (advanced users only)
     * Most users should use the simple initialize(context, clientId) method
     */
    fun initialize(context: Context, config: OAuth2Config) {
        this.context = context.applicationContext
        this.config = config
        this.manager = OAuth2Manager(context.applicationContext, config)
        updateAuthState()
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
        // Convert Flow to callback for backward compatibility
        CoroutineScope(Dispatchers.Main).launch {
            authState.collect { state ->
                when (state) {
                    AuthState.Authenticated -> {
                        // Get token and create success result
                        val token = getAccessToken() ?: return@collect
                        callback(
                            OAuth2Result.Success(
                                accessToken = token,
                                expiresIn = 3600, // Default value
                                scope = DEFAULT_SCOPES.joinToString(" ")
                            )
                        )
                    }

                    AuthState.Unauthenticated -> {
                        // Don't call callback for unauthenticated state
                        // as it might be the initial state
                    }

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
     * Get access token directly (advanced users only)
     * Most users should just call generateCompletion() which handles tokens automatically
     */
    fun getAccessToken(): String? = manager?.getAccessToken()

    /**
     * Create completion API service (advanced users only)
     * Most users should just call generateCompletion() which handles everything
     */
    fun createCompletionApiService(): AiPassCompletionApiService? {
        return ensureApiService()
    }

    /**
     * Create audio API service for Text-to-Speech and Speech-to-Text (advanced users only)
     * Provides direct access to audio endpoints with OAuth2 token handling
     *
     * @return AiPassAudioApiService instance or null if SDK not initialized
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
     * Manually refresh access token (advanced users only)
     * Token refresh is handled automatically in generateCompletion()
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
            val storage = one.aipass.data.OAuth2TokenStorage(it)
            storage.clearTokens()
            _authState.value = AuthState.Unauthenticated
        }
    }
}
