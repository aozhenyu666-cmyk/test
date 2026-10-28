package com.yishou.app.voice

import com.yishou.app.llm.ChatClient
import com.yishou.app.llm.LlmConfig
import com.yishou.app.llm.LlmResult
import com.yishou.app.llm.SseParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class SseParserTest {

    private fun data(text: String) = "data: {\"choices\":[{\"delta\":{\"content\":\"$text\"}}]}\n\n"

    @Test
    fun splitsAcrossChunks() {
        val p = SseParser()
        val whole = data("你好") + ": keep-alive\n\n" + data("，我在") + "data: [DONE]\n\n"
        val out = mutableListOf<String>()
        whole.chunked(7).forEach { out += p.feed(it) }
        assertEquals(listOf("你好", "，我在"), out)
        assertTrue(p.done)
        assertTrue(p.feed(data("晚到")).isEmpty())
    }

    @Test
    fun errorsAndOddLines() {
        val p = SseParser()
        assertTrue(p.feed("event: message\n").isEmpty())
        assertTrue(p.feed("data: {not json}\n").isEmpty())
        assertTrue(p.feed("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n").isEmpty())
        p.feed("data: {\"error\":{\"message\":\"额度用完\"}}\n")
        assertEquals("额度用完", p.error)
        val q = SseParser()
        q.feed("data: {\"choices\":[{\"delta\":{\"content\":\"没换行\"}}]}")
        assertEquals(listOf("没换行"), q.finish())
    }
}

class SentencesTest {

    @Test
    fun cutsAtSentenceEnds() {
        val s = SentenceSplitter()
        val out = mutableListOf<String>()
        listOf("先说条", "件。再", "说结论！最后", "一句").forEach { out += s.feed(it) }
        assertEquals(listOf("先说条件。", "再说结论！"), out)
        assertEquals("最后一句", s.flush())
        assertNull(s.flush())
    }

    @Test
    fun longClauseCutsAtComma() {
        val s = SentenceSplitter(maxLen = 30)
        val out = s.feed("这一段讲的是县城咖啡店为什么开在新区能活下来，而开在老城区的半年就关了一半")
        assertEquals(1, out.size)
        assertTrue(out[0].endsWith("活下来"))
        assertTrue(s.flush()!!.startsWith("而开在老城区"))
    }

    @Test
    fun cleansTagsAndMarks() {
        val s = SentenceSplitter()
        val out = s.feed("【内部导演卡】**先别看答案**。")
        assertEquals(listOf("先别看答案。"), out)
        assertEquals("", Speakable.clean("【钩子】"))
        assertEquals("你好", Speakable.clean("  ## 你好  "))
    }

    @Test
    fun closingQuoteStaysWithSentence() {
        val s = SentenceSplitter()
        val out = s.feed("他说：“先读题。”然后呢")
        assertEquals(listOf("他说：“先读题。”"), out)
    }
}

class TurnEndTest {

    @Test
    fun waits() {
        val r = TurnEnd.Rules()
        assertEquals(r.completeMs, TurnEnd.waitMs("我觉得主要是客群不同", r))
        assertEquals(r.completeMs, TurnEnd.waitMs("是租金吗？", r))
        assertEquals(r.incompleteMs, TurnEnd.waitMs("主要是因为", r))
        assertEquals(r.incompleteMs, TurnEnd.waitMs("然后就是", r))
        assertEquals(r.incompleteMs, TurnEnd.waitMs("嗯", r))
        assertEquals(r.thinkingMs, TurnEnd.waitMs("这个嘛，我想想", r))
        assertEquals(r.thinkingMs, TurnEnd.waitMs("等一下", r))
        assertEquals(0, TurnEnd.waitMs("客群不同，好了", r))
        assertEquals(0, TurnEnd.waitMs("我说完了。", r))
        assertEquals(r.unknownMs, TurnEnd.waitMs(null, r))
        assertEquals(r.unknownMs, TurnEnd.waitMs("  ", r))
        assertTrue(TurnEnd.ended("客群不同", 900))
        assertFalse(TurnEnd.ended("主要是因为", 900))
    }

    @Test
    fun stripDoneSignal() {
        assertEquals("客群不同", TurnEnd.strip("客群不同，好了。"))
        assertEquals("好了", TurnEnd.strip("好了"))
        assertEquals("先读题", TurnEnd.strip("先读题，我说完了"))
    }

    @Test
    fun guardDropsStale() {
        val g = TurnGuard(7)
        val a = g.next()
        assertTrue(g.isCurrent(a))
        g.invalidate()
        assertFalse(g.isCurrent(a))
        val b = g.next()
        assertTrue(g.isCurrent(b))
        assertFalse(g.isCurrent(RequestTag(8, b.turnId)))
    }
}

class StreamingChatTest {

    private fun client(server: MockWebServer) = ChatClient(config = { LlmConfig(server.url("/v1").toString(), "k", "m", false) })

    private fun sse(vararg parts: String) = parts.joinToString("") { "data: {\"choices\":[{\"delta\":{\"content\":\"$it\"}}]}\n\n" } + "data: [DONE]\n\n"

    @Test
    fun streamsDeltas() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody(sse("先说", "条件。", "再说结论")))
        server.start()
        val got = mutableListOf<String>()
        val r = client(server).streamConversation("系统", listOf("user" to "早", "assistant" to "早"), "你好") { got += it }
        assertEquals(LlmResult.Ok("先说条件。再说结论"), r)
        assertEquals(listOf("先说", "条件。", "再说结论"), got)
        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"stream\":true"))
        assertTrue(body.contains("早"))
        server.shutdown()
    }

    @Test
    fun fallsBackToWholeReply() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setHeader("Content-Type", "application/json").setBody("{\"choices\":[{\"message\":{\"content\":\"整段回复\"}}]}"))
        server.start()
        val got = mutableListOf<String>()
        val r = client(server).streamConversation("系统", emptyList(), "你好") { got += it }
        assertEquals(LlmResult.Ok("整段回复"), r)
        assertEquals(listOf("整段回复"), got)
        server.shutdown()
    }

    @Test
    fun cancelStopsReading() = runBlocking {
        val server = MockWebServer()
        // 第一段马上到，后面的字迟迟不来：取消要立刻生效，不等它读完
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody(sse("第一句。", "第二句。"))
                .throttleBody(70, 2, TimeUnit.SECONDS),
        )
        server.start()
        val got = mutableListOf<String>()
        val started = System.currentTimeMillis()
        withTimeout(5_000) {
            val job = launch(Dispatchers.Default) {
                client(server).streamConversation("系统", emptyList(), "你好") { got += it }
            }
            delay(800)
            job.cancel()
            job.join()
        }
        assertTrue(System.currentTimeMillis() - started < 3_000)
        assertFalse(got.contains("第二句。"))
        server.shutdown()
    }

    @Test
    fun httpError() = runBlocking {
        val server = MockWebServer()
        server.enqueue(MockResponse().setResponseCode(401).setBody("{\"error\":{\"message\":\"bad key\"}}"))
        server.start()
        val r = withContext(Dispatchers.IO) { client(server).streamConversation("系统", emptyList(), "你好") {} }
        assertTrue(r is LlmResult.Err)
        server.shutdown()
    }
}
