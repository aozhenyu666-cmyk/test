package com.yishou.app.integration

import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.yishou.app.assistant.ChatHistory
import com.yishou.app.assistant.ChatLine
import com.yishou.app.data.AssistantChat
import com.yishou.app.ui.AssistantViewModel
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.SocketPolicy
import org.json.JSONObject
import org.junit.*
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit
import com.yishou.app.integration.RuntimeSupport.app
import com.yishou.app.integration.RuntimeSupport.main
import com.yishou.app.integration.RuntimeSupport.reset
import com.yishou.app.integration.RuntimeSupport.eventually
import com.yishou.app.integration.RuntimeSupport.activity
import com.yishou.app.integration.RuntimeSupport.config
import com.yishou.app.integration.RuntimeSupport.streamed
import com.yishou.app.integration.RuntimeSupport.completion

@RunWith(AndroidJUnit4::class)
class AssistantIntegrationTest {
    @get:Rule val evidence = RuntimeEvidence()
    private val owners = mutableListOf<ViewModelStore>()
    @Before fun before() = runBlocking { reset() }
    @After fun after() { main { owners.forEach { it.clear() } }; app.settings.saveLlm(config("")) }
    private fun vm(): AssistantViewModel {
        lateinit var model: AssistantViewModel
        main { val owner = ViewModelStore(); owners += owner; model = AssistantViewModel(app); owner.put("assistant", model) }
        return model
    }
    @Test fun savedFailureCanBeRetriedAfterOpeningChatAgain() = runBlocking {
        val id = app.database.everyday().insertChat(AssistantChat(title = "待重试", json = ChatHistory.encode(listOf(
            ChatLine("user", "先检查哪个条件？"), ChatLine("error", "接口暂时不可用"))), updatedAt = 1))
        val model = vm()
        eventually { model.id == id }
        Assert.assertEquals("先检查哪个条件？", model.lines.first().text)
        Assert.assertEquals("接口暂时不可用", model.error)
    }
    @Test fun realHttpConversationPersistsAcrossReopeningAndRetryDoesNotDuplicateUser() = runBlocking {
        val server = MockWebServer().apply { start() }
        val activity = activity()
        try {
            app.settings.saveLlm(config(server.url("/v1").toString()))
            val model = vm()
            server.enqueue(streamed("先找进入的必要条件。"))
            main { model.send("我应当先检查哪一个条件？") }
            eventually { !model.busy && model.lines.size == 2 }
            Assert.assertNull(model.error)
            val first = server.takeRequest(2, TimeUnit.SECONDS)!!
            Assert.assertEquals("/v1/chat/completions", first.path)
            Assert.assertEquals("Bearer local-test-key", first.getHeader("Authorization"))
            server.enqueue(MockResponse().setResponseCode(503).setBody("busy"))
            main { model.send("那开放时间算不算进入资格？") }
            eventually { !model.busy && model.error != null && model.lines.lastOrNull()?.role == "error" }
            val id = model.id
            val restored = vm()
            eventually { restored.id == id && restored.error != null }
            Assert.assertNotNull(restored.error)
            server.takeRequest(2, TimeUnit.SECONDS)
            server.enqueue(completion("时间和资格要分开核对。"))
            main { restored.retry() }
            eventually { !restored.busy && restored.lines.lastOrNull()?.role == "assistant" }
            val body = JSONObject(server.takeRequest(2, TimeUnit.SECONDS)!!.body.readUtf8())
            val messages = body.getJSONArray("messages")
            Assert.assertEquals(4, messages.length())
            Assert.assertEquals("先找进入的必要条件。", messages.getJSONObject(2).getString("content"))
            Assert.assertEquals(2, restored.lines.count { it.role == "user" })
            val third = vm()
            eventually { third.id == id && third.lines.lastOrNull()?.text == "时间和资格要分开核对。" }
            Assert.assertEquals("时间和资格要分开核对。", third.lines.last().text)
            main { third.fresh() }
            Assert.assertEquals(0, third.lines.size)
            main { third.choose(id) }
            eventually { third.id == id }
            main { third.delete() }
            eventually { third.id == 0L }
            Assert.assertNull(app.database.everyday().chat(id))
        } finally { activity.close(); server.shutdown() }
    }
    @Test fun stoppingAStreamingReplyReleasesTheRequestAndKeepsOnlyReceivedText() = runBlocking {
        val server = MockWebServer().apply { start() }
        val activity = activity()
        try {
            app.settings.saveLlm(config(server.url("/v1").toString()))
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: {\"choices\":[{\"delta\":{\"content\":\"先核对条件\"}}]}\n\n" + " ".repeat(100_000))
                .throttleBody(128, 1, TimeUnit.SECONDS))
            val model = vm()
            main { model.send("一步一步解释这个问题。") }
            eventually { model.draftReply.contains("先核对条件") }
            main { model.stop() }
            eventually(3_000) { !model.busy }
            Assert.assertTrue(model.lines.last().text.contains("已停止生成"))
            Assert.assertEquals(1, model.lines.count { it.role == "assistant" })
            Assert.assertEquals("", model.draftReply)
        } finally { activity.close(); server.shutdown() }
    }
}
