package cn.companion.pet.core.partner

import cn.companion.pet.core.companion.*
import cn.companion.pet.core.voice.*
import cn.companion.pet.net.*
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Real HTTPS request + production responder + memory projection + output accounting. No live model. */
class PartnerRequestRegressionTest {
    @get:Rule val tmp = TemporaryFolder()
    private lateinit var server: MockWebServer
    private lateinit var client: DeepSeekClient
    @Before fun setup() {
        val cert = HeldCertificate.Builder().addSubjectAlternativeName("localhost").build()
        val serverCert = HandshakeCertificates.Builder().heldCertificate(cert).build()
        val clientCert = HandshakeCertificates.Builder().addTrustedCertificate(cert.certificate).build()
        server = MockWebServer().apply { useHttps(serverCert.sslSocketFactory(), false); start() }
        client = DeepSeekClient(OkHttpClient.Builder().sslSocketFactory(clientCert.sslSocketFactory(), clientCert.trustManager).build())
    }
    @After fun stop() { server.shutdown() }
    private fun enqueue(raw: String) { server.enqueue(MockResponse().setBody("{\"choices\":[{\"message\":{\"content\":${JsonPrimitive(raw)}}}]}")) }
    private suspend fun call(messages: List<ChatMessage>): String? =
        (client.complete(ChatRequest("https://localhost:${server.port}/v1", "test", "test-key", messages, timeoutMillis = 3000)) as? ChatResult.Success)?.content

    @Test fun aNormalGoalReplyIsNotSilentlyDiscardedByTheLegacyKeywordBan() = runBlocking {
        val input = ReplyInput("复习计划每天做五道题", TopicSource.GOAL_CHECK, proactive = true)
        enqueue("""{"say":"这个复习计划里的五道题，你想先挑哪一类？","anchor":"每天做五道题","memoryRefs":[]}""")
        val reply = PartnerResponder().respond(input, "同桌", emptyList(), ::call)
        assertNotNull(reply)
        assertEquals(1, server.requestCount)
        val governor = ProactiveGovernor()
        val delivery = PartnerDelivery(governor, VoiceIntentQueue(), SayingLedger())
        val candidate = TopicCandidate("goal", TopicSource.GOAL_CHECK, input.anchor, observedAt = 1000)
        assertFalse(delivery.confirm("request-1", candidate, reply!!.say, 1000, delivered = false))
        assertEquals(0, governor.shownLastHour(1000))
        assertTrue(delivery.confirm("request-1", candidate, reply.say, 1000, delivered = true))
        assertEquals(1, governor.shownLastHour(1000))
    }

    @Test fun oldRelevantMemoryReachesTheActualRequestAfterManyUnrelatedFacts() = runBlocking {
        val events = EventLog(tmp.root)
        val facts = FactStore(tmp.root)
        val old = facts.recordSaid("国考薄弱项是资料分析")!!
        repeat(25) { facts.recordSaid("午餐口味编号$it") }
        val memories = PartnerContext(MemoryRecall(events, facts)).memories("国考资料分析", System.currentTimeMillis(), emptySet())
        assertTrue(old.id in memories)
        enqueue("""{"say":"资料分析里，计算速度还是读题更拖时间？","anchor":"资料分析","memoryRefs":["${old.id}"]}""")
        val reply = PartnerResponder().respond(ReplyInput("国考资料分析", TopicSource.THREAD_FOLLOW_UP, memories, proactive = false), "同桌", emptyList(), ::call)
        assertNotNull(reply)
        val request = server.takeRequest().body.readUtf8()
        assertTrue(request.contains("国考薄弱项是资料分析"))
        assertFalse(request.contains("午餐口味"))
        facts.forget(old.id)
        assertFalse(old.id in PartnerContext(MemoryRecall(events, facts)).memories("国考资料分析", System.currentTimeMillis(), emptySet()))
    }

    @Test fun explicitSummaryCanBeLongerAndStructuredButRidingRemainsShort() = runBlocking {
        val text = "这篇文章主要讲了阅读理解。\n1. 找出观点。\n2. 检查依据。" + "说明依据。".repeat(30)
        val input = ReplyInput("阅读理解", TopicSource.THREAD_FOLLOW_UP, proactive = false, intent = ReplyIntent.from("请总结一下"))
        enqueue("""{"say":${JsonPrimitive(text)},"anchor":"阅读理解","memoryRefs":[]}""")
        assertNotNull(PartnerResponder().respond(input, "短句人设", emptyList(), ::call))
        val parsed = PartnerReply(text, "阅读理解", emptyList())
        assertTrue(ReplyViolation.TOO_LONG in ReplyCheck.violations(parsed, input.copy(voiceOnly = true)))
        assertTrue(ReplyViolation.SUMMARY_OPENER in ReplyCheck.violations(parsed, input.copy(intent = ReplyIntent.CHAT)))
    }

    @Test fun relationshipPressureStillRequiresARewrite() = runBlocking {
        val input = ReplyInput("资料分析", TopicSource.THREAD_FOLLOW_UP, proactive = true)
        enqueue("""{"say":"你又不理我，资料分析还做不做？","anchor":"资料分析","memoryRefs":[]}""")
        enqueue("""{"say":"资料分析这部分，我想接着聊读题的方法。","anchor":"资料分析","memoryRefs":[]}""")
        val reply = PartnerResponder().respond(input, "同桌", emptyList(), ::call)
        assertNotNull(reply)
        assertFalse(reply!!.say.contains("你又不理我"))
        assertEquals(2, server.requestCount)
    }

    @Test fun contextBoundKeepsCurrentMessageStateAndThreadWithoutSendingAllHistory() {
        val projected = PartnerContext.project(listOf("旧内容".repeat(4000), "他：最后一句具体问题"), "有兴趣", "还没聊完资料分析", 400)
        assertTrue(projected.sumOf { it.length } <= 400)
        assertTrue(projected.last().contains("最后一句具体问题"))
        assertTrue(projected.first().contains("有兴趣"))
        assertTrue(projected[1].contains("资料分析"))
    }
}
