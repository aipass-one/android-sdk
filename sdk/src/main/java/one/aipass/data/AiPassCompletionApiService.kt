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
     * Generate an AI completion through the canonical resource API.
     * POST /v1/chat/completions
     */
    @POST("v1/chat/completions")
    suspend fun generateCompletion(
        @Body request: CompletionRequest
    ): Response<CompletionResponse>

    /**
     * Discover the models currently available to this OAuth client and user.
     * Model availability is dynamic, so applications should not rely only on
     * model IDs compiled into the APK.
     */
    @GET("v1/models")
    suspend fun listModels(): Response<ModelListResponse>

    /**
     * Get user's usage summary (balance, cost, remaining budget)
     * GET /api/v1/usage/me/summary
     */
    @GET("api/v1/usage/me/summary")
    suspend fun getUserBalance(): Response<UsageBalanceResponse>

    /**
     * Generate an image through the canonical resource API.
     * POST /v1/images/generations
     */
    @POST("v1/images/generations")
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
    @SerializedName("max_tokens")
    val maxTokens: Int? = null,
    val stream: Boolean = false,
    val tools: List<ToolDefinition>? = null,
    @SerializedName("response_format")
    val responseFormat: ResponseFormat? = null
)

/** OpenAI-compatible response format request. */
data class ResponseFormat(
    val type: String
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
 * Content can be either String (text-only) or List<ContentPart> (multimodal).
 */
data class Message(
    val role: String, // "system", "user", "assistant"
    val content: Any // String for text-only, List<ContentPart> for multimodal
)

/**
 * Content part for multimodal messages (text, images, or inline audio).
 */
data class ContentPart(
    val type: String, // "text", "image_url", or "input_audio"
    val text: String? = null, // for type="text"
    @SerializedName("image_url")
    val imageUrl: ImageUrl? = null, // for type="image_url"
    @SerializedName("input_audio")
    val inputAudio: InputAudio? = null // for type="input_audio"
) {
    companion object {
        @JvmStatic
        fun text(text: String): ContentPart = ContentPart(type = "text", text = text)

        @JvmStatic
        fun image(url: String): ContentPart = ContentPart(
            type = "image_url",
            imageUrl = ImageUrl(url)
        )

        @JvmStatic
        fun audio(data: String, format: String): ContentPart = ContentPart(
            type = "input_audio",
            inputAudio = InputAudio(data = data, format = format)
        )
    }
}

/**
 * Image URL wrapper for vision API
 */
data class ImageUrl(
    val url: String // "data:image/jpeg;base64,..." or http URL
)

/** Base64-encoded inline audio for an OpenAI-compatible multimodal request. */
data class InputAudio(
    val data: String,
    val format: String
)

/** OpenAI-compatible model catalog response. */
data class ModelListResponse(
    @SerializedName("object")
    val objectType: String? = null,
    val data: List<AiPassModel> = emptyList()
)

/** A model made available by AI Pass to the current OAuth client and user. */
data class AiPassModel(
    val id: String,
    @SerializedName("object")
    val objectType: String? = null,
    @SerializedName("owned_by")
    val ownedBy: String? = null
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
