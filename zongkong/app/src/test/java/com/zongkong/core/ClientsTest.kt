package com.zongkong.core

import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClientsTest {
    private val server = MockWebServer().apply { start() }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun llmPostsChatCompletion() = runTest {
        server.enqueue(MockResponse().setBody("""{"choices":[{"message":{"content":"{\"pass\":true}"}}]}"""))
        val c = LlmClient({ AiConfig(server.url("/v1").toString(), "sk-1", "deepseek-chat") })
        val r = c.complete("sys", "user")
        assertEquals(ApiResult.Ok("{\"pass\":true}"), r)
        val req = server.takeRequest()
        assertEquals("/v1/chat/completions", req.path)
        assertEquals("Bearer sk-1", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"response_format\":{\"type\":\"json_object\"}"))
        assertTrue(body.contains("deepseek-chat"))
    }

    @Test
    fun llmErrorsAreReadable() = runTest {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"error":{"message":"invalid key"}}"""))
        val c = LlmClient({ AiConfig(server.url("/").toString(), "bad", "m") })
        val r = c.complete("s", "u") as ApiResult.Err
        assertTrue(r.message, r.message.contains("密钥") && r.message.contains("invalid key"))
        assertTrue((LlmClient({ AiConfig() }).complete("s", "u") as ApiResult.Err).message.contains("还没有配置"))
    }

    @Test
    fun notionQueryAndParse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"object":"list","results":[
                  {"object":"page","id":"p1","url":"https://notion.so/p1","created_time":"2026-10-09T01:00:00.000Z",
                   "properties":{"类型":{"type":"select","select":{"name":"问题"}},
                                 "标题":{"type":"title","title":[{"plain_text":"为什么"},{"plain_text":"拖延"}]}}}
                ],"has_more":false}""",
            ),
        )
        val n = NotionClient({ "secret_x" }, base = server.url("/v1").toString().trimEnd('/'))
        val r = n.createdSince("1a2b3c4d5e6f708192a3b4c5d6e7f809", "2026-10-09T04:00:00+08:00") as ApiResult.Ok
        assertEquals("为什么拖延", r.value.single().title)
        val req = server.takeRequest()
        assertEquals("/v1/databases/1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809/query", req.path)
        assertEquals("2022-06-28", req.getHeader("Notion-Version"))
        assertEquals("Bearer secret_x", req.getHeader("Authorization"))
        val body = req.body.readUtf8()
        assertTrue(body, body.contains("\"timestamp\":\"created_time\""))
        assertTrue(body.contains("\"on_or_after\":\"2026-10-09T04:00:00+08:00\""))
    }

    @Test
    fun notion404ExplainsSharing() = runTest {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"object":"error","status":404,"code":"object_not_found","message":"Could not find database"}"""))
        val n = NotionClient({ "t" }, base = server.url("/v1").toString().trimEnd('/'))
        val r = n.schema("1a2b3c4d5e6f708192a3b4c5d6e7f809") as ApiResult.Err
        assertTrue(r.message.contains("连接"))
        assertTrue(r.message.contains("Could not find database"))
    }

    @Test
    fun notionBlocksText() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"results":[
                  {"type":"heading_2","heading_2":{"rich_text":[{"plain_text":"结论"}]}},
                  {"type":"paragraph","paragraph":{"rich_text":[{"plain_text":"先做行测"}]}},
                  {"type":"to_do","to_do":{"rich_text":[{"plain_text":"买书"}],"checked":true}},
                  {"type":"image","image":{}}
                ]}""",
            ),
        )
        val n = NotionClient({ "t" }, base = server.url("/v1").toString().trimEnd('/'))
        val r = n.pageText("1a2b3c4d5e6f708192a3b4c5d6e7f809", 1000) as ApiResult.Ok
        assertEquals("【结论】\n先做行测\n☑ 买书", r.value)
    }

    @Test
    fun notionSetupCreatesThreeDatabases() = runTest {
        repeat(3) { i -> server.enqueue(MockResponse().setBody("""{"object":"database","id":"db$i"}""")) }
        val n = NotionClient({ "t" }, base = server.url("/v1").toString().trimEnd('/'))
        val r = Workspace.setup(n, "https://www.notion.so/Zongkong-1a2b3c4d5e6f708192a3b4c5d6e7f809") as ApiResult.Ok
        assertEquals(Workspace.Created("db0", "db1", "db2"), r.value)
        val first = server.takeRequest().body.readUtf8()
        assertTrue(first.contains("\"page_id\":\"1a2b3c4d-5e6f-7081-92a3-b4c5d6e7f809\""))
        assertTrue(first.contains("信息收集库"))
        assertTrue(first.contains("\"title\":{}"))
    }
}
