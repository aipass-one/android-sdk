package one.aipass.data

import com.google.gson.annotations.SerializedName
import okhttp3.MultipartBody
import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part

/**
 * Retrofit interface for AI Pass audio endpoints (TTS and STT).
 *
 * Authorization is injected automatically by [AuthorizationInterceptor] and
 * refreshed on 401 by [TokenAuthenticator] — callers don't pass tokens.
 *
 * Supports:
 * - Text-to-Speech (TTS): Convert text to audio
 * - Speech-to-Text (STT): Transcribe audio to text
 */
interface AiPassAudioApiService {

    /**
     * Generate speech audio from text (Text-to-Speech)
     * POST /oauth2/v1/audio/speech
     *
     * @param request Text-to-speech request
     * @return Audio file as ResponseBody (binary data)
     */
    @POST("oauth2/v1/audio/speech")
    suspend fun generateSpeech(
        @Body request: AudioSpeechRequest
    ): Response<ResponseBody>

    /**
     * Generate speech audio from text (Text-to-Speech) - Alternative endpoint
     * POST /oauth2/v1/audio/speech
     */
    @POST("oauth2/v1/audio/speech")
    suspend fun generateSpeechV1(
        @Body request: AudioSpeechRequest
    ): Response<ResponseBody>

    /**
     * Transcribe audio to text (Speech-to-Text)
     * POST /oauth2/v1/audio/transcriptions
     *
     * @param file Audio file as multipart
     * @param model Model ID (e.g., "whisper-1")
     * @param language Optional language code (ISO 639-1, e.g., "en", "es")
     * @param prompt Optional context/prompt to guide transcription
     * @param temperature Optional sampling temperature (0-1)
     * @param responseFormat Optional response format ("json", "text", "srt", "vtt")
     * @return Transcription response
     */
    @Multipart
    @POST("oauth2/v1/audio/transcriptions")
    suspend fun transcribeAudio(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody? = null,
        @Part("prompt") prompt: RequestBody? = null,
        @Part("temperature") temperature: RequestBody? = null,
        @Part("response_format") responseFormat: RequestBody? = null
    ): Response<AudioTranscriptionResponse>

    /**
     * Transcribe audio to text (Speech-to-Text) - Alternative endpoint
     * POST /oauth2/v1/audio/transcriptions
     */
    @Multipart
    @POST("oauth2/v1/audio/transcriptions")
    suspend fun transcribeAudioV1(
        @Part file: MultipartBody.Part,
        @Part("model") model: RequestBody,
        @Part("language") language: RequestBody? = null,
        @Part("prompt") prompt: RequestBody? = null,
        @Part("temperature") temperature: RequestBody? = null,
        @Part("response_format") responseFormat: RequestBody? = null
    ): Response<AudioTranscriptionResponse>
}

/**
 * Request for text-to-speech generation
 *
 * @param model The TTS model to use (e.g., "tts-1", "tts-1-hd")
 * @param input The text to convert to speech
 * @param voice The voice to use (e.g., "alloy", "echo", "fable", "onyx", "nova", "shimmer")
 * @param responseFormat Optional audio format (mp3, opus, aac, flac, wav, pcm), defaults to "mp3"
 * @param speed Optional playback speed (0.25 - 4.0), defaults to 1.0
 */
data class AudioSpeechRequest(
    val model: String,
    val input: String,
    val voice: String,
    @SerializedName("response_format")
    val responseFormat: String? = "mp3",
    val speed: Double? = 1.0
)

/**
 * Response from audio transcription
 *
 * @param text The transcribed text
 * @param language Optional detected language (if not specified in request)
 * @param duration Optional audio duration in seconds
 * @param words Optional word-level timestamps (if requested)
 * @param segments Optional segment-level timestamps (if requested)
 */
data class AudioTranscriptionResponse(
    val text: String,
    val language: String? = null,
    val duration: Double? = null,
    val words: List<Word>? = null,
    val segments: List<Segment>? = null
)

/**
 * Word-level timestamp in transcription
 */
data class Word(
    val word: String,
    val start: Double,
    val end: Double
)

/**
 * Segment-level timestamp in transcription
 */
data class Segment(
    val id: Int,
    val seek: Int,
    val start: Double,
    val end: Double,
    val text: String,
    val tokens: List<Int>,
    val temperature: Double,
    @SerializedName("avg_logprob")
    val avgLogprob: Double,
    @SerializedName("compression_ratio")
    val compressionRatio: Double,
    @SerializedName("no_speech_prob")
    val noSpeechProb: Double
)
