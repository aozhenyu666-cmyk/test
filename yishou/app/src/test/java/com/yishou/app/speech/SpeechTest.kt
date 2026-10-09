package com.yishou.app.speech

import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTest {

    @Test
    fun endpointNormalizes() {
        assertEquals("https://a.com/v1/audio/speech", SpeechApi.endpoint("https://a.com/v1/", "audio/speech"))
        assertEquals("https://a.com/v1/audio/transcriptions", SpeechApi.endpoint("https://a.com/v1/audio/transcriptions", "audio/transcriptions"))
        assertEquals("https://a.com/v1/audio/speech", SpeechApi.endpoint("https://a.com/v1/chat/completions", "audio/speech"))
        assertNull(SpeechApi.endpoint("a.com", "audio/speech"))
    }

    @Test
    fun parseTranscriptVariants() {
        assertEquals(LlmResult.Ok("你好"), SpeechApi.parseTranscript("""{"text":" 你好 "}"""))
        assertEquals(LlmResult.Ok("纯文本"), SpeechApi.parseTranscript("纯文本"))
        assertTrue(SpeechApi.parseTranscript("""{"text":""}""") is LlmResult.Err)
    }

    @Test
    fun transcribeAndSynthesizeAgainstServer() = runTest {
        val server = MockWebServer()
        server.enqueue(MockResponse().setBody("""{"text":"我预计增长率会下降"}"""))
        server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
        server.enqueue(MockResponse().setResponseCode(401).setBody("bad key"))
        server.start()
        val cfg = SpeechConfig(server.url("/v1").toString(), "k", sttModel = "whisper-1", ttsModel = "tts-1", ttsVoice = "alloy")
        val api = SpeechApi({ cfg })

        assertEquals(LlmResult.Ok("我预计增长率会下降"), api.transcribe(Vad.wav(ByteArray(320), 16_000)))
        val req1 = server.takeRequest()
        assertEquals("/v1/audio/transcriptions", req1.path)
        assertEquals("Bearer k", req1.getHeader("Authorization"))
        val body1 = req1.body.readUtf8()
        assertTrue(body1.contains("whisper-1") && body1.contains("answer.wav"))

        val audio = api.synthesize("问题")
        assertTrue(audio is LlmResult.Ok && audio.value.size == 3)
        val req2 = server.takeRequest()
        assertEquals("/v1/audio/speech", req2.path)
        assertTrue(req2.body.readUtf8().contains("\"voice\":\"alloy\""))

        val err = api.synthesize("问题")
        assertTrue(err is LlmResult.Err && err.error is LlmError.Http)
        server.shutdown()
    }

    @Test
    fun notConfigured() = runTest {
        val api = SpeechApi({ SpeechConfig("https://a.com", "k") })
        assertEquals(LlmResult.Err(LlmError.NotConfigured), api.transcribe(ByteArray(0)))
    }

    @Test
    fun vadDetectsEndOfSpeech() {
        val vad = Vad(silenceMs = 600, waitMs = 3_000)
        repeat(15) { vad.feed(100.0, 20) } // 校准 + 安静
        assertEquals(Vad.State.WAITING, vad.state)
        repeat(25) { vad.feed(3_000.0, 20) } // 说 500ms
        assertEquals(Vad.State.SPEAKING, vad.state)
        repeat(29) { vad.feed(120.0, 20) }
        assertEquals(Vad.State.SPEAKING, vad.state)
        vad.feed(120.0, 20)
        assertEquals(Vad.State.DONE, vad.state)
    }

    @Test
    fun vadNoSpeechAndShortClick() {
        val quiet = Vad(waitMs = 1_000)
        repeat(60) { quiet.feed(80.0, 20) }
        assertEquals(Vad.State.NO_SPEECH, quiet.state)

        val click = Vad(silenceMs = 400)
        repeat(15) { click.feed(80.0, 20) }
        repeat(3) { click.feed(5_000.0, 20) }
        repeat(25) { click.feed(80.0, 20) }
        assertEquals(Vad.State.NO_SPEECH, click.state)
    }

    @Test
    fun wavHeader() {
        val w = Vad.wav(ByteArray(100), 16_000)
        assertEquals(144, w.size)
        assertEquals("RIFF", String(w.copyOfRange(0, 4)))
        assertEquals("WAVE", String(w.copyOfRange(8, 12)))
        assertEquals(100, (w[40].toInt() and 0xff) or ((w[41].toInt() and 0xff) shl 8))
    }

    @Test
    fun commands() {
        assertEquals(VoiceCommand.DONT_KNOW, VoiceCommands.parse("我不知道。"))
        assertEquals(VoiceCommand.REPEAT, VoiceCommands.parse("再说一遍"))
        assertEquals(VoiceCommand.PAUSE, VoiceCommands.parse("先停吧"))
        assertEquals(VoiceCommand.DEMO, VoiceCommands.parse("示范一下"))
        assertEquals(VoiceCommand.ROLL, VoiceCommands.parse("换一个"))
        assertNull(VoiceCommands.parse("我预计基期会比现期小，因为增长率是正的"))
        assertNull(VoiceCommands.parse(""))
    }

    @Test
    fun spokenForm() {
        assertEquals("短问题？", Spoken.of("短问题？", null))
        assertEquals("口语版", Spoken.of("很长".repeat(40), "口语版"))
        val long = "你刚才找到了基期量。" + "这里先分析一下材料里的关系，".repeat(5) + "那现期量比它大还是小？依据是什么？"
        assertEquals("依据是什么？", Spoken.of(long, ""))
        assertEquals("这手有效。抓住了关键。", Spoken.verdict(true, "抓住了关键。还可以更细。"))
        assertEquals("这手还不算。", Spoken.verdict(false, ""))
    }
}
