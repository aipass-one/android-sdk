package one.aipass

import com.google.gson.Gson
import one.aipass.data.CompletionRequest
import one.aipass.data.ContentPart
import one.aipass.data.Message
import one.aipass.data.ResponseFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

class MultimodalRequestTest {

    private val gson = Gson()

    @Test
    fun `audio content serializes in OpenAI compatible shape`() {
        val request = CompletionRequest(
            model = "gemini-3.5-flash-lite",
            messages = listOf(
                Message(
                    role = "user",
                    content = listOf(
                        ContentPart.text("Return JSON"),
                        ContentPart.audio("ZmFrZQ==", "mp4")
                    )
                )
            ),
            temperature = 0.0,
            maxTokens = 150,
            responseFormat = ResponseFormat("json_object")
        )

        val json = gson.toJsonTree(request).asJsonObject
        val content = json.getAsJsonArray("messages")[0]
            .asJsonObject
            .getAsJsonArray("content")

        assertEquals("input_audio", content[1].asJsonObject.get("type").asString)
        assertEquals(
            "ZmFrZQ==",
            content[1].asJsonObject.getAsJsonObject("input_audio").get("data").asString
        )
        assertEquals(
            "mp4",
            content[1].asJsonObject.getAsJsonObject("input_audio").get("format").asString
        )
        assertEquals(150, json.get("max_tokens").asInt)
        assertFalse(json.has("maxTokens"))
        assertEquals(
            "json_object",
            json.getAsJsonObject("response_format").get("type").asString
        )
    }

    @Test
    fun `content part factories omit unrelated payloads`() {
        val audio = gson.toJsonTree(ContentPart.audio("audio", "wav")).asJsonObject
        val inputAudio = audio.getAsJsonObject("input_audio")

        assertNotNull(inputAudio)
        assertFalse(audio.has("text"))
        assertFalse(audio.has("image_url"))
    }
}
