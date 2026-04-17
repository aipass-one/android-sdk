package one.aipass.data

import com.google.gson.annotations.SerializedName
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST

/**
 * Retrofit interface for AI Pass completion endpoints.
 *
 * The `Authorization: Bearer <token>` header is injected automatically by
 * [AuthorizationInterceptor] — callers do not need to pass it.
 */
interface AiPassCompletionApiService {

    /**
     * Generate AI completion via OAuth2 LiteLLM proxy
     * POST /oauth2/v1/chat/completions
     */
    @POST("oauth2/v1/chat/completions")
    suspend fun generateCompletion(
        @Body request: CompletionRequest
    ): Response<CompletionResponse>

    /**
     * Get user's usage summary (balance, cost, remaining budget)
     * GET /api/v1/usage/me/summary
     */
    @GET("api/v1/usage/me/summary")
    suspend fun getUserBalance(): Response<UsageBalanceResponse>

    /**
     * Generate image via OAuth2 LiteLLM proxy
     * POST /oauth2/v1/images/generations
     */
    @POST("oauth2/v1/images/generations")
    suspend fun generateImage(
        @Body request: ImageGenerationRequest
    ): Response<ImageGenerationResponse>
}

/**
 * Request for AI completion
 */
data class CompletionRequest(
    val model: String,
    val messages: List<Message>,
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    @SerializedName("max_tokens")
    val max_tokens: Int? = maxTokens, // For JSON serialization
    val stream: Boolean = false,
    val tools: List<ToolDefinition>? = null
)

/**
 * Tool definition for OpenAI-compatible API (used for Gemini Google Search grounding via LiteLLM)
 */
data class ToolDefinition(
    val type: String = "function",
    val function: ToolFunction
)

data class ToolFunction(
    val name: String,
    val description: String? = null,
    val parameters: Map<String, Any>? = null
)

/**
 * Message in completion request
 * Content can be either String (text-only) or List<ContentPart> (multimodal with images)
 */
data class Message(
    val role: String, // "system", "user", "assistant"
    val content: Any // String for text-only, List<ContentPart> for multimodal
)

/**
 * Content part for multimodal messages (text + images)
 */
data class ContentPart(
    val type: String, // "text" or "image_url"
    val text: String? = null, // for type="text"
    @SerializedName("image_url")
    val imageUrl: ImageUrl? = null // for type="image_url"
)

/**
 * Image URL wrapper for vision API
 */
data class ImageUrl(
    val url: String // "data:image/jpeg;base64,..." or http URL
)

/**
 * Response from AI completion
 */
data class CompletionResponse(
    val id: String,
    @SerializedName("object")
    val objectType: String,
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: Usage?
)

/**
 * Choice in completion response
 */
data class Choice(
    val index: Int,
    val message: Message,
    @SerializedName("finish_reason")
    val finishReason: String?
)

/**
 * Token usage information
 */
data class Usage(
    @SerializedName("prompt_tokens")
    val promptTokens: Int,
    @SerializedName("completion_tokens")
    val completionTokens: Int,
    @SerializedName("total_tokens")
    val totalTokens: Int
)

/**
 * User balance response from /api/v1/usage/me/summary
 */
data class UsageBalanceResponse(
    val success: Boolean,
    val message: String?,
    val data: UsageBalanceData?,
    val timestamp: String?
)

/**
 * Balance data
 */
data class UsageBalanceData(
    @SerializedName("totalCost")
    val totalCost: Double,
    @SerializedName("maxBudget")
    val maxBudget: Double,
    @SerializedName("remainingBudget")
    val remainingBudget: Double
)

/**
 * Request for image generation (OpenAI-compatible /v1/images/generations)
 */
data class ImageGenerationRequest(
    val model: String,
    val prompt: String,
    val n: Int = 1,
    val size: String = "1024x1024",
    val quality: String = "auto",
    @SerializedName("response_format")
    val responseFormat: String = "b64_json"
)

/**
 * Response from image generation
 */
data class ImageGenerationResponse(
    val created: Long?,
    val data: List<ImageData>?
)

/**
 * Individual image data in generation response
 */
data class ImageData(
    val url: String?,
    @SerializedName("b64_json")
    val b64Json: String?,
    @SerializedName("revised_prompt")
    val revisedPrompt: String?
)
