package com.yishou.app.partner

import com.yishou.app.llm.ChatClient
import com.yishou.app.llm.DirectorPrompts
import com.yishou.app.llm.LlmConfig
import com.yishou.app.llm.LlmDirector
import com.yishou.app.llm.LlmResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import okhttp3.EventListener
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DirectorTest {
    private fun input(
        elapsed: Int = 2,
        transcript: List<Turn> = emptyList(),
        memory: List<String> = emptyList(),
        gear: Gear = Gear.ACCOMPANY,
    ) = DirectorInput(PartnerPlan(Material.ARTICLE, "弄懂条件关系", 25, gear), elapsed, transcript, 2, "先找限定词", memory)

    private fun cardText(face: String = "提问", gear: String = "陪", hook: String = "先看条件还是结论？", close: String = "不收") = """
        【下一步】找出影响结论的条件
        【问法】$face
        【强度】$gear
        【钩子】$hook
        【收不收】$close
    """.trimIndent()

    @Test
    fun parsesFiveFieldsAndAllSixFaces() {
        listOf("精简", "预判", "联想", "提问", "体系", "回响").forEachIndexed { index, name ->
            val card = Directors.parse(cardText(face = name), input())!!
            assertEquals(index + 1, card.face)
            assertEquals("找出影响结论的条件", card.next)
            assertEquals(Gear.ACCOMPANY, card.gear)
            assertEquals("先看条件还是结论？", card.hook)
            assertFalse(card.close)
        }
    }

    @Test
    fun parsesAllGearsAndCloseRule() {
        Gear.entries.forEach { assertEquals(it, Directors.parse(cardText(gear = it.label), input())!!.gear) }
        assertTrue(Directors.parse(cardText(close = "现在收"), input())!!.close)
        assertFalse(Directors.parse(cardText(close = "暂时不收"), input())!!.close)
    }

    @Test
    fun rejectsMissingEmptyUnknownDuplicateAndMultilineFields() {
        val original = cardText()
        listOf(
            original.substringBefore("\n【收不收】"),
            original.replace("找出影响结论的条件", ""),
            original.replace("先看条件还是结论？", ""),
            original.replace("【问法】提问", "【问法】其他"),
            original.replace("【强度】陪", "【强度】随便"),
            original.replace("【收不收】不收", "【收不收】继续"),
            original.replace("【下一步】", "【其他】"),
            original + "\n【问法】精简",
            original + "\n多说一句",
        ).forEach { assertNull(it, Directors.parse(it, input())) }
    }

    @Test
    fun toleratesTaggedBracketAndColonForms() {
        val text = cardText().replace("【下一步】", "下一步：").replace("【问法】", "[问法]")
        assertNotNull(Directors.parse(text, input()))
    }

    @Test
    fun truncatesHookTo25CharactersWithoutSplittingSupplementaryCharacters() {
        val hook = "想".repeat(24) + "😀" + "继续"
        val parsed = Directors.parse(cardText(hook = hook), input())!!
        assertEquals("想".repeat(24) + "😀", parsed.hook)
        assertEquals(25, parsed.hook.codePointCount(0, parsed.hook.length))
    }

    @Test
    fun deadlineOverridesModelAndLocalCards() {
        assertFalse(Directors.parse(cardText(), input(elapsed = 24))!!.close)
        listOf(25, 26).forEach {
            assertTrue(Directors.parse(cardText(), input(elapsed = it))!!.close)
            assertTrue(Directors.local(input(elapsed = it)).close)
        }
    }

    @Test
    fun requestOnlyIncludesLast12TurnsAndFirst5Memories() {
        val turns = (0..15).map { Turn(if (it % 2 == 0) "我" else "伙伴", "发言-$it-内容") }
        val memories = (0..6).map { "记忆-$it-内容" }
        val user = DirectorPrompts.user(input(transcript = turns, memory = memories))
        (0..3).forEach { assertFalse(user.contains("发言-$it-内容")) }
        (4..15).forEach { assertTrue(user.contains("发言-$it-内容")) }
        (0..4).forEach { assertTrue(user.contains("记忆-$it-内容")) }
        (5..6).forEach { assertFalse(user.contains("记忆-$it-内容")) }
        listOf("【计划】", "弄懂条件关系", "文章", "25", "陪", "【已经过了】2", "【没接次数】2", "【上次收线】先找限定词").forEach {
            assertTrue(it, user.contains(it))
        }
        assertTrue(user.indexOf("发言-4-内容") < user.indexOf("发言-15-内容"))
    }

    @Test
    fun localCardsAreDeterministicRotateAndKeepSelectedGear() {
        val cards = (0..6).map { Directors.local(input(elapsed = it, gear = Gear.LURE)) }
        assertEquals(listOf(1, 2, 3, 4, 5, 6, 1), cards.map { it.face })
        cards.forEach {
            assertEquals(Gear.LURE, it.gear)
            assertTrue(it.hook.contains("还是"))
            assertTrue(it.hook.length <= 25)
            assertFalse(it.close)
        }
        assertEquals(Directors.local(input()), Directors.local(input()))
        assertEquals(1, Directors.local(input(elapsed = -1)).face)
    }

    @Test
    fun internalContextIsTaggedAndNeverContainsTranscriptOrMemory() {
        val card = Directors.parse(cardText(), input())!!
        val context = Directors.context(card)
        listOf("【内部导演卡】", "【下一步】找出影响结论的条件", "【问法】提问", "【强度】陪", "【钩子】先看条件还是结论？", "【收不收】不收").forEach {
            assertTrue(it, context.contains(it))
        }
        assertFalse(context.contains("先找限定词"))
    }

    @Test
    fun adapterSendsExactPromptAndParsesResponse() = runBlocking {
        withServer { server ->
            server.enqueue(completion(cardText()))
            val result = director(server).card(input()) as LlmResult.Ok
            assertEquals(4, result.value.face)
            val request = server.takeRequest(3, TimeUnit.SECONDS)!!
            val body = JSONObject(request.body.readUtf8())
            assertFalse(body.has("response_format"))
            val messages = body.getJSONArray("messages")
            assertEquals(DirectorPrompts.SYSTEM, messages.getJSONObject(0).getString("content"))
            assertEquals(DirectorPrompts.user(input()), messages.getJSONObject(1).getString("content"))
            assertEquals("system", messages.getJSONObject(0).getString("role"))
            assertEquals("user", messages.getJSONObject(1).getString("role"))
        }
    }

    @Test
    fun adapterFallsBackOnHttpAndMalformedResponse() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setResponseCode(503))
            server.enqueue(completion("不知道怎么写"))
            val director = director(server)
            repeat(2) { assertEquals(LlmResult.Ok(Directors.local(input())), director.card(input())) }
        }
    }

    @Test
    fun unconfiguredAdapterFallsBackWithoutNetwork() = runBlocking {
        val director = LlmDirector(ChatClient({ LlmConfig("", "", "", false) }))
        assertEquals(LlmResult.Ok(Directors.local(input())), director.card(input()))
    }

    @Test
    fun adapterForcesCloseOnDeadline() = runBlocking {
        withServer { server ->
            server.enqueue(completion(cardText()))
            val result = director(server).card(input(elapsed = 25)) as LlmResult.Ok
            assertTrue(result.value.close)
        }
    }

    @Test
    fun cancellingAdapterCancelsNetworkAndDoesNotReturnFallback() = runBlocking {
        withServer { server ->
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val failed = CountDownLatch(1)
            val http = OkHttpClient.Builder().eventListener(object : EventListener() {
                override fun callFailed(call: okhttp3.Call, ioe: IOException) { failed.countDown() }
            }).build()
            val pending = async(Dispatchers.IO) { director(server, http).card(input()) }
            assertNotNull(server.takeRequest(3, TimeUnit.SECONDS))
            pending.cancelAndJoin()
            try {
                pending.await()
                fail("取消后不能返回本机卡")
            } catch (_: CancellationException) {
                assertTrue(failed.await(3, TimeUnit.SECONDS))
            }
        }
    }

    private fun director(server: MockWebServer, http: OkHttpClient = ChatClient.defaultHttpClient()) = LlmDirector(
        ChatClient({ LlmConfig(server.url("/v1").toString(), "test-key", "test-text", true) }, http),
    )

    private fun completion(text: String) = MockResponse().setBody(
        JSONObject().put("choices", JSONArray().put(JSONObject().put("message", JSONObject().put("content", text)))).toString(),
    )

    private suspend fun withServer(block: suspend (MockWebServer) -> Unit) {
        val server = MockWebServer()
        server.start()
        try { block(server) } finally { server.shutdown() }
    }
}
