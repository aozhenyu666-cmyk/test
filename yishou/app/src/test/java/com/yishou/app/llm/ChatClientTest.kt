package com.yishou.app.llm

import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Task
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ChatClientTest {

    private lateinit var server: MockWebServer
    private var jsonMode = true

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    private fun config() = LlmConfig(server.url("/v1").toString(), "sk-test", "deepseek-chat", jsonMode)

    private fun client(http: OkHttpClient = ChatClient.defaultHttpClient()) = ChatClient({ config() }, http)

    /** 把模型回复的文本包成 Chat Completions 响应 */
    private fun completion(content: String) = MockResponse().setBody(
        JSONObject().put(
            "choices",
            org.json.JSONArray().put(JSONObject().put("message", JSONObject().put("role", "assistant").put("content", content))),
        ).toString(),
    )

    @Test
    fun normalizesUrl() {
        assertEquals("https://api.deepseek.com/chat/completions", ChatClient.chatCompletionsUrl("https://api.deepseek.com"))
        assertEquals("https://api.deepseek.com/v1/chat/completions", ChatClient.chatCompletionsUrl(" https://api.deepseek.com/v1/ "))
        assertEquals("https://x.com/v1/chat/completions", ChatClient.chatCompletionsUrl("https://x.com/v1/chat/completions"))
        assertNull(ChatClient.chatCompletionsUrl("api.deepseek.com"))
        assertNull(ChatClient.chatCompletionsUrl("https://"))
    }

    @Test
    fun sendsRequestAndReturnsContent() = runTest {
        server.enqueue(completion("""{"a":1}"""))
        val r = client().complete("系统", "用户")
        assertEquals(LlmResult.Ok("""{"a":1}"""), r)

        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-test", req.getHeader("Authorization"))
        val body = JSONObject(req.body.readUtf8())
        assertEquals("deepseek-chat", body.getString("model"))
        assertEquals("json_object", body.getJSONObject("response_format").getString("type"))
        assertEquals("系统", body.getJSONArray("messages").getJSONObject(0).getString("content"))
        assertEquals("用户", body.getJSONArray("messages").getJSONObject(1).getString("content"))
    }

    @Test
    fun jsonModeOffOmitsResponseFormat() = runTest {
        jsonMode = false
        server.enqueue(completion("{}"))
        client().complete("s", "u")
        assertFalse(JSONObject(server.takeRequest().body.readUtf8()).has("response_format"))
    }

    @Test
    fun notConfigured() = runTest {
        val r = ChatClient({ LlmConfig("", "", "", true) }).complete("s", "u")
        assertEquals(LlmResult.Err(LlmError.NotConfigured), r)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun httpErrorCarriesProviderMessage() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"Authentication Fails"}}"""))
        val r = client().complete("s", "u") as LlmResult.Err
        val e = r.error as LlmError.Http
        assertEquals(401, e.code)
        assertTrue(e.message, e.message.contains("密钥"))
        assertTrue(e.message, e.message.contains("Authentication Fails"))
    }

    @Test
    fun emptyContentIsBadFormat() = runTest {
        server.enqueue(completion(""))
        val r = client().complete("s", "u") as LlmResult.Err
        assertTrue(r.error is LlmError.BadFormat)
    }

    @Test
    fun timeout() = runTest {
        server.enqueue(completion("{}").setHeadersDelay(2, TimeUnit.SECONDS))
        val fast = OkHttpClient.Builder().callTimeout(300, TimeUnit.MILLISECONDS).build()
        val r = client(fast).complete("s", "u")
        assertEquals(LlmResult.Err(LlmError.Timeout), r)
    }

    @Test
    fun coachRetriesOnceOnBadJson() = runTest {
        server.enqueue(completion("这不是 JSON"))
        server.enqueue(completion("""{"coach_move": "问一个问题？", "stuck_type": "跳步"}"""))
        val r = LlmCoach(client()).opening(task, bp)
        assertEquals(LlmResult.Ok(OpeningMove("问一个问题？", "跳步")), r)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun coachGivesUpAfterSecondBadJson() = runTest {
        server.enqueue(completion("不是 JSON"))
        server.enqueue(completion("""{"stuck_type": "跳步"}"""))
        val r = LlmCoach(client()).opening(task, bp) as LlmResult.Err
        assertTrue(r.error is LlmError.BadFormat)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun coachDoesNotRetryHttpError() = runTest {
        server.enqueue(MockResponse().setResponseCode(500))
        val r = LlmCoach(client()).judge(task, bp, "一手", "回答") as LlmResult.Err
        assertTrue(r.error is LlmError.Http)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun judgeMessageContainsEverything() {
        val msg = CoachMessages.judge(task, bp, "陪练的一手内容", "用户原话内容")
        listOf("资料分析", "算对增长率", "已知内容", "卡点内容", "下一问内容", "陪练的一手内容", "用户原话内容", "next_coach_move")
            .forEach { assertTrue(it, msg.contains(it)) }
        assertTrue(CoachMessages.opening(task, bp).contains("材料摘录：（无）"))
    }

    @Test
    fun usageIsRecorded() = runTest {
        val records = mutableListOf<List<Any>>()
        val sink = UsageSink { kind, ok, p, c, _, chars -> records += listOf(kind, ok, p, c, chars) }
        server.enqueue(
            MockResponse().setBody(
                """{"choices":[{"message":{"content":"{}"}}],"usage":{"prompt_tokens":120,"completion_tokens":30}}""",
            ),
        )
        server.enqueue(MockResponse().setResponseCode(500))
        val c = ChatClient({ config() }, ChatClient.defaultHttpClient(), sink)
        c.complete("系统", "用户", "判定")
        c.complete("s", "u", "开局")
        assertEquals(listOf("判定", true, 120, 30, 4), records[0])
        assertEquals(listOf("开局", false, 0, 0, 2), records[1])
    }

    @Test
    fun imageRequestUsesContentArray() = runTest {
        server.enqueue(completion("转写内容"))
        val r = Vision(client()).transcribe("资料分析", "第一步是什么？", "data:image/jpeg;base64,AAAA")
        assertEquals(LlmResult.Ok("转写内容"), r)
        val body = JSONObject(server.takeRequest().body.readUtf8())
        assertFalse(body.has("response_format"))
        val content = body.getJSONArray("messages").getJSONObject(1).getJSONArray("content")
        assertEquals("text", content.getJSONObject(0).getString("type"))
        assertEquals("data:image/jpeg;base64,AAAA", content.getJSONObject(1).getJSONObject("image_url").getString("url"))
    }

    @Test
    fun contextAppearsInMessages() {
        val ctx = CoachContext(rules = listOf("规则一"), gapMinutes = 3 * 24 * 60L + 120, lastAnswer = "上次原话")
        val msg = CoachMessages.opening(task, bp, ctx)
        listOf("规则一", "3 天 2 小时", "上次原话").forEach { assertTrue(it, msg.contains(it)) }
        val recent = CoachMessages.judge(task, bp, "m", "a", CoachContext(gapMinutes = 30, lastAnswer = "不该出现"))
        assertFalse(recent.contains("不该出现"))
    }

    private val task = Task(id = 1, title = "资料分析", goal = "算对增长率", material = null, isCurrent = true, createdAt = 0)
    private val bp = Breakpoint(1, "已知内容", "卡点内容", "下一问内容", pendingCoachMove = null, updatedAt = 0)
}
