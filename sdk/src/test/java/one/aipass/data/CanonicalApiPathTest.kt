package one.aipass.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import retrofit2.http.GET
import retrofit2.http.POST

class CanonicalApiPathTest {

    @Test
    fun completionEndpointsUseCanonicalResourcePaths() {
        assertEquals("v1/chat/completions", postPath(AiPassCompletionApiService::class.java, "generateCompletion"))
        assertEquals("v1/models", getPath(AiPassCompletionApiService::class.java, "listModels"))
        assertEquals("v1/images/generations", postPath(AiPassCompletionApiService::class.java, "generateImage"))
    }

    @Test
    fun audioEndpointsUseCanonicalResourcePaths() {
        assertEquals("v1/audio/speech", postPath(AiPassAudioApiService::class.java, "generateSpeech"))
        assertEquals("v1/audio/speech", postPath(AiPassAudioApiService::class.java, "generateSpeechV1"))
        assertEquals("v1/audio/transcriptions", postPath(AiPassAudioApiService::class.java, "transcribeAudio"))
        assertEquals("v1/audio/transcriptions", postPath(AiPassAudioApiService::class.java, "transcribeAudioV1"))
    }

    @Test
    fun noAiEndpointUsesCredentialSpecificPrefix() {
        val paths = listOf(AiPassCompletionApiService::class.java, AiPassAudioApiService::class.java)
            .flatMap { service ->
                service.declaredMethods.mapNotNull { method ->
                    method.getAnnotation(POST::class.java)?.value
                        ?: method.getAnnotation(GET::class.java)?.value
                }
            }

        assertFalse(paths.any { it.startsWith("oauth2/") || it.startsWith("apikey/") })
    }

    private fun postPath(service: Class<*>, methodName: String): String =
        requireNotNull(
            service.declaredMethods.single { it.name == methodName }
                .getAnnotation(POST::class.java)
        ).value

    private fun getPath(service: Class<*>, methodName: String): String =
        requireNotNull(
            service.declaredMethods.single { it.name == methodName }
                .getAnnotation(GET::class.java)
        ).value
}
