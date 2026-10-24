package cn.companion.pet.core.vision

import cn.companion.pet.net.ChatCompletionClient
import cn.companion.pet.net.ChatFailureKind
import cn.companion.pet.net.ChatRequest
import cn.companion.pet.net.ChatResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** C2 设备问题：视觉状态说清楚；换了服务商不偷用搭话 Key；证据能区分“真发了图”和“没发”。 */
class VisionSetupTest {
    private val deepseek = "https://api.deepseek.com/v1"
    private val dashscope = "https://dashscope.aliyuncs.com/compatible-mode/v1"

    @Test
    fun noModelIsNotConfigured() {
        val s = VisionSetupResolver.resolve("", dashscope, deepseek, "vk", "ck")
        assertEquals(VisionSetupStatus.NO_MODEL, s.status)
        assertFalse(s.ready)
        assertNull(s.config)
    }

    @Test
    fun otherProviderWithoutOwnKeyDoesNotBorrowChatKey() {
        // 设备上看到过：清掉视觉 Key 后仍在用搭话 Key 发图。现在换了服务商就必须填视觉 Key。
        val s = VisionSetupResolver.resolve("qwen-vl-plus", dashscope, deepseek, null, "deepseek-key")
        assertEquals(VisionSetupStatus.NEEDS_OWN_KEY, s.status)
        assertNull(s.config)
        assertTrue(s.label().contains("dashscope.aliyuncs.com"))
        assertFalse(s.label().contains("deepseek-key"))
    }

    @Test
    fun ownKeyWins() {
        val s = VisionSetupResolver.resolve("qwen-vl-plus", dashscope, deepseek, "qwen-key", "deepseek-key")
        assertEquals(VisionSetupStatus.READY, s.status)
        assertEquals("qwen-key", s.config!!.apiKey)
        assertEquals(dashscope, s.config!!.endpoint)
        assertFalse("label never shows the key", s.label().contains("qwen-key"))
    }

    @Test
    fun sameProviderMayReuseChatKey() {
        val blank = VisionSetupResolver.resolve("gpt-4.1-mini", "", "https://api.openai.com/v1", null, "ck")
        assertEquals(VisionSetupStatus.READY, blank.status)
        assertEquals("搭话 Key", blank.keySource)
        val same = VisionSetupResolver.resolve("gpt-4.1-mini", "https://API.openai.com/v1/", "https://api.openai.com/v1", "  ", "ck")
        assertEquals(VisionSetupStatus.READY, same.status)
    }

    @Test
    fun noKeyAtAll() {
        assertEquals(VisionSetupStatus.NO_KEY, VisionSetupResolver.resolve("qwen-vl-plus", dashscope, deepseek, null, null).status)
    }

    private class FakeClient(val result: ChatResult) : ChatCompletionClient {
        var last: ChatRequest? = null
        override suspend fun complete(request: ChatRequest): ChatResult { last = request; return result }
    }

    private val jpeg = ByteArray(2048) { (it % 251).toByte() }
    private val cfg = VisionModelConfig(dashscope, "qwen-vl-plus", "secret-key")

    @Test
    fun evidenceRecordsImageBlockWithoutContent() = runBlocking {
        val client = FakeClient(ChatResult.Success("""{"subject":"红底上一个白圆和一个蓝方块","elements":["白圆","蓝方块"]}"""))
        var t = 1_000L
        val run = VisionAnalyzer(client, clock = { t.also { t += 250 } }) { cfg }.analyzeDetailed(jpeg, 1080, 2400)
        val e = run.evidence
        assertEquals(VisionOutcome.OK, e.outcome)
        assertEquals(1, e.imageBlocks)
        assertEquals("image/jpeg", e.mime)
        assertEquals(2048, e.imageBytes)
        assertEquals(12, e.imageSha256.length)
        assertEquals("dashscope.aliyuncs.com", e.host)
        assertEquals(250L, e.durationMs)
        // 真正交给网络层的请求里确实带着这张图。
        assertEquals(1, client.last!!.messages.sumOf { it.imageDataUrls.size })
        val label = e.label()
        assertFalse(label.contains("secret-key"))
        assertFalse(label.contains("base64"))
        assertFalse("summary text is not stored", label.contains("白圆"))
    }

    @Test
    fun requestFailureAndParseFailureAreDistinguished() = runBlocking {
        val failed = VisionAnalyzer(FakeClient(ChatResult.Failure(ChatFailureKind.HTTP, 401))) { cfg }.analyzeDetailed(jpeg)
        assertEquals(VisionOutcome.REQUEST_FAILED, failed.evidence.outcome)
        assertTrue(failed.evidence.failure.contains("401"))
        assertEquals(1, failed.evidence.imageBlocks)
        val unparsable = VisionAnalyzer(FakeClient(ChatResult.Success("{broken"))) { cfg }.analyzeDetailed(jpeg)
        assertEquals(VisionOutcome.PARSE_FAILED, unparsable.evidence.outcome)
    }

    @Test
    fun notConfiguredSendsNothing() = runBlocking {
        val client = FakeClient(ChatResult.Success("{}"))
        val run = VisionAnalyzer(client) { null }.analyzeDetailed(jpeg)
        assertEquals(VisionOutcome.NOT_CONFIGURED, run.evidence.outcome)
        assertEquals(0, run.evidence.imageBlocks)
        assertNull(client.last)
    }
}
