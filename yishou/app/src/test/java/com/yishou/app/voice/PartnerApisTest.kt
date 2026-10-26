package com.yishou.app.voice

import com.yishou.app.llm.ChatClient
import com.yishou.app.llm.LlmConfig
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.speech.SpeechApi
import com.yishou.app.speech.SpeechConfig
import com.yishou.app.speech.SpeechEndpoint
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class PartnerApisTest {
    private val tag = RequestTag(12, 34)
    private fun reply(text: String) = MockResponse().setBody("""{"choices":[{"message":{"content":"$text"}}]}""")

    @Test
    fun independentSpeechEndpointsUseTheirOwnCredentialsAndOptions() = runTest {
        val input = MockWebServer()
        val output = MockWebServer()
        input.start(); output.start()
        try {
            input.enqueue(MockResponse().setBody("""{"text":"最终听写"}"""))
            output.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
            val config = SpeechConfig(
                stt = SpeechEndpoint(input.url("/v1").toString(), "input-key", "in", language = "en"),
                tts = SpeechEndpoint(output.url("/v1").toString(), "output-key", "out", voice = "v", format = "wav"),
            )
            val api = SpeechApi({ config })
            val heard = HttpTranscriber(api).transcribe(tag, byteArrayOf(4, 5))
            assertEquals(tag, heard.tag)
            assertEquals(LlmResult.Ok("最终听写"), heard.result)
            val request = input.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer input-key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            assertTrue(body.contains("en"))
            assertFalse(body.contains("output-key"))
            val said = HttpSpeechSynthesizer(api) { config.output }.synthesize(tag, "只读这句")
            assertEquals(tag, said.tag)
            val audio = (said.result as LlmResult.Ok).value
            assertEquals("wav", audio.format)
            assertEquals(3, audio.bytes.size)
            val ttsRequest = output.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer output-key", ttsRequest.getHeader("Authorization"))
            val json = JSONObject(ttsRequest.body.readUtf8())
            assertEquals("wav", json.getString("response_format"))
            assertEquals("只读这句", json.getString("input"))
            assertEquals("v", json.getString("voice"))
        } finally { input.shutdown(); output.shutdown() }
    }

    @Test
    fun textConversationPreservesRolesAndVisionStaysOnAnotherService() = runTest {
        val text = MockWebServer()
        val image = MockWebServer()
        text.start(); image.start()
        try {
            text.enqueue(reply("下一句"))
            image.enqueue(reply("当前页有三个条件"))
            val textClient = ChatClient({ LlmConfig(text.url("/v1").toString(), "text-key", "text", false) })
            val imageClient = ChatClient({ LlmConfig(image.url("/v1").toString(), "image-key", "image", false) })
            val answer = HttpPartnerTextModel(textClient).reply(tag, "内部计划", listOf("user" to "上句", "assistant" to "已解释"), "继续")
            assertEquals(ApiReply(tag, LlmResult.Ok("下一句")), answer)
            val request = text.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer text-key", request.getHeader("Authorization"))
            val messages = JSONObject(request.body.readUtf8()).getJSONArray("messages")
            assertEquals(4, messages.length())
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals("assistant", messages.getJSONObject(2).getString("role"))
            assertEquals("继续", messages.getJSONObject(3).getString("content"))
            val seen = HttpScreenReader(imageClient).read(tag, "提取条件", "data:image/jpeg;base64,ZmFrZQ==")
            assertEquals(ApiReply(tag, LlmResult.Ok("当前页有三个条件")), seen)
            val imageRequest = image.takeRequest(2, TimeUnit.SECONDS)!!
            assertEquals("Bearer image-key", imageRequest.getHeader("Authorization"))
            val imageBody = imageRequest.body.readUtf8()
            assertTrue(imageBody.contains("image_url"))
            assertTrue(imageBody.contains("提取条件"))
            assertFalse(imageBody.contains("text-key"))
        } finally { text.shutdown(); image.shutdown() }
    }

    @Test
    fun failedImageRequestKeepsItsRequestTagAndDoesNotRouteElsewhere() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            server.enqueue(MockResponse().setResponseCode(401).setBody("denied"))
            val client = ChatClient({ LlmConfig(server.url("/v1").toString(), "test-key", "image", false) })
            val result = HttpScreenReader(client).read(tag, "看看", "data:image/jpeg;base64,eA==")
            assertEquals(tag, result.tag)
            assertTrue((result.result as LlmResult.Err).error is LlmError.Http)
            assertEquals(1, server.requestCount)
        } finally { server.shutdown() }
    }

    @Test
    fun unsupportedRawAudioFormatFailsBeforeSending() = runTest {
        val server = MockWebServer()
        server.start()
        try {
            val config = SpeechConfig(tts = SpeechEndpoint(server.url("/v1").toString(), "test-key", "out", format = "pcm"))
            assertTrue(SpeechApi({ config }).synthesize("你好") is LlmResult.Err)
            assertEquals(0, server.requestCount)
        } finally { server.shutdown() }
    }
}
