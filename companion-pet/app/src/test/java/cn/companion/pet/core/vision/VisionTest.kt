package cn.companion.pet.core.vision

import cn.companion.pet.core.agent.AgentEventLedger
import cn.companion.pet.net.ChatCompletionClient
import cn.companion.pet.net.ChatRequest
import cn.companion.pet.net.ChatResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class VisionTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun capturePolicyRejectsExcludedSecureAndCooldown() {
        val now = 10_000L
        assertEquals(CapturedStatus.SECURE_WINDOW,
            ScreenCapturePolicy.canCapture("com.bank", "cn.companion.pet", setOf("com.bank"), now, 0, false))
        assertEquals(CapturedStatus.SECURE_WINDOW,
            ScreenCapturePolicy.canCapture("com.other", "cn.companion.pet", emptySet(), now, 0, true))
        assertEquals(CapturedStatus.COOLDOWN,
            ScreenCapturePolicy.canCapture("com.other", "cn.companion.pet", emptySet(), now, now - 100, false))
        assertEquals(CapturedStatus.NO_ACTIVE_WINDOW,
            ScreenCapturePolicy.canCapture(null, "cn.companion.pet", emptySet(), now, 0, false))
        assertEquals(CapturedStatus.OK,
            ScreenCapturePolicy.canCapture("com.other", "cn.companion.pet", emptySet(), now, 0, false))
    }

    @Test
    fun observationExpiresAndStatusesAreSeparate() {
        val store = ScreenObservationStore()
        val obs = ScreenObservation("o1", 1_000, "pkg", ObservationSource.SCREENSHOT,
            screenshotAvailable = true, nodesAvailable = false, visionAvailable = false,
            status = CapturedStatus.OK, summary = "页面", expiresAt = 5_000)
        store.publish(obs)
        assertTrue(store.latestValid(4_999) != null)
        assertNull(store.latestValid(5_001))
        assertTrue(obs.screenshotAvailable)
        assertTrue(!obs.nodesAvailable)
        assertTrue(!obs.visionAvailable)
    }

    @Test
    fun ledgerSeparatesFromTraceAndKeepsManyEvents() {
        val ledger = AgentEventLedger(tmp.root, maxEntries = 600)
        repeat(501) { ledger.mark("event-" + it, 1_000L + it) }
        assertEquals(501, ledger.count(2_000L))
        assertEquals(501, ledger.processed(2_000L).size)
        assertTrue("event-0" in ledger.processed(2_000L))
        assertTrue("event-500" in ledger.processed(2_000L))
    }

    @Test
    fun ledgerPrunesOldAndSkipsCorruptLines() {
        val ledger = AgentEventLedger(tmp.root, maxAgeMillis = 1_000)
        ledger.mark("old", 1_000)
        val file = java.io.File(tmp.root, "agent/event-ledger.jsonl")
        file.appendText("not-json\n")
        val fresh = AgentEventLedger(tmp.root, maxAgeMillis = 1_000)
        assertTrue(fresh.processed(5_000).isEmpty())
        fresh.mark("new", 5_000)
        assertEquals(setOf("new"), fresh.processed(5_500))
    }

    @Test
    fun visionAnalyzerSendsImageBlockAndParsesSummary() = runBlocking {
        var captured: ChatRequest? = null
        val client = object : ChatCompletionClient {
            override suspend fun complete(request: ChatRequest): ChatResult {
                captured = request
                return ChatResult.Success("""{"subject":"题目页","elements":["题干","选项"],"uncertain":["小字"],"changes":"无","next_step":"读题干","confidence":"high"}""")
            }
        }
        val analyzer = VisionAnalyzer(client) { VisionModelConfig("https://api.example.com", "deepseek-flash", "key") }
        val summary = analyzer.analyze(byteArrayOf(1, 2, 3))
        assertEquals("题目页", summary?.subject)
        assertEquals(listOf("题干", "选项"), summary?.elements)
        assertTrue(captured!!.messages[1].imageDataUrls.single().startsWith("data:image/jpeg;base64,"))
    }

    @Test
    fun visionAnalyzerWithoutConfigDoesNotCallClient() = runBlocking {
        var called = false
        val client = object : ChatCompletionClient {
            override suspend fun complete(request: ChatRequest): ChatResult { called = true; return ChatResult.Success("{}") }
        }
        val analyzer = VisionAnalyzer(client) { null }
        assertNull(analyzer.analyze(byteArrayOf(1)))
        assertTrue(!called)
    }
}
/** 真机：“看了，但视觉模型没给出可用的结果”——不按 JSON 回答的模型也要给出主题，而不是整个丢掉。 */
class VisionLenientParseTest {
    private val analyzer = VisionAnalyzer(object : cn.companion.pet.net.ChatCompletionClient {
        override suspend fun complete(request: cn.companion.pet.net.ChatRequest) = cn.companion.pet.net.ChatResult.Success("")
    }) { null }

    @org.junit.Test
    fun plainTextAnswerBecomesLowConfidenceSubject() {
        val s = analyzer.parse("这是一个示例网页，标题是 Example Domain。\n下面有一段说明文字。")!!
        org.junit.Assert.assertEquals("这是一个示例网页，标题是 Example Domain。", s.subject)
        org.junit.Assert.assertEquals("low", s.confidence)
    }

    @org.junit.Test
    fun jsonStillParsedNormally() {
        val s = analyzer.parse("""{"subject":"设置页","elements":["开关"],"uncertain":[],"changes":"","next_step":"","confidence":"high"}""")!!
        org.junit.Assert.assertEquals("设置页", s.subject)
        org.junit.Assert.assertEquals(listOf("开关"), s.elements)
    }

    @org.junit.Test
    fun brokenJsonOrEmptyIsStillNull() {
        org.junit.Assert.assertNull(analyzer.parse("{\"subject\": \"被截断"))
        org.junit.Assert.assertNull(analyzer.parse("   "))
    }
}
