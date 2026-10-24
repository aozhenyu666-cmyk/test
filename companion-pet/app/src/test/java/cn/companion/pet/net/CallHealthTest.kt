package cn.companion.pet.net

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CallHealthTest {
    private class Fixed(val result: ChatResult) : ChatCompletionClient {
        override suspend fun complete(request: ChatRequest): ChatResult = result
    }

    private class Sink : CallStatusSink {
        val seen = mutableListOf<CallStatus>()
        override fun record(status: CallStatus) { seen += status }
    }

    private val request = ChatRequest("https://api.deepseek.com", "deepseek-chat", "k", listOf(ChatMessage(ChatRole.USER, "hi")))

    @Test
    fun wrongKeyIsReportedInPlainWords() = runBlocking {
        val sink = Sink()
        val result = RecordingChatClient(Fixed(ChatResult.Failure(ChatFailureKind.HTTP, 401)), sink) { 42L }.complete(request)
        assertTrue(result is ChatResult.Failure)
        val status = sink.seen.single()
        assertFalse(status.ok)
        assertTrue(status.message.contains("API Key"))
        assertEquals(42L, status.at)
    }

    @Test
    fun successIsRecordedWithoutContent() = runBlocking {
        val sink = Sink()
        RecordingChatClient(Fixed(ChatResult.Success("秘密回复内容")), sink).complete(request)
        val status = sink.seen.single()
        assertTrue(status.ok)
        assertFalse(status.message.contains("秘密"))
    }

    @Test
    fun brokenSinkNeverBreaksTheCall() = runBlocking {
        val sink = object : CallStatusSink { override fun record(status: CallStatus) = error("disk full") }
        val result = RecordingChatClient(Fixed(ChatResult.Success("ok")), sink).complete(request)
        assertEquals(ChatResult.Success("ok"), result)
    }

    @Test
    fun everyFailureKindHasAMessage() {
        ChatFailureKind.entries.forEach { kind ->
            assertTrue(CallDiagnosis.describe(ChatResult.Failure(kind, if (kind == ChatFailureKind.HTTP) 500 else null)).isNotBlank())
        }
    }
}
