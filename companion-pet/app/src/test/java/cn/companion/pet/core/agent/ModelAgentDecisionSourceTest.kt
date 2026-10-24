package cn.companion.pet.core.agent

import cn.companion.pet.core.action.ActionKind
import cn.companion.pet.core.action.ActionResult
import cn.companion.pet.core.action.ActionStatus
import cn.companion.pet.net.ChatCompletionClient
import cn.companion.pet.net.ChatRequest
import cn.companion.pet.net.ChatResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ModelAgentDecisionSourceTest {

    @Test
    fun sendsSystemToolContractAndReturnsContent() = runBlocking {
        var captured: ChatRequest? = null
        val client = object : ChatCompletionClient {
            override suspend fun complete(request: ChatRequest): ChatResult {
                captured = request
                return ChatResult.Success("""{"decision":"no_action","reason":"够了"}""")
            }
        }
        val source = ModelAgentDecisionSource(client) {
            AgentModelConfig("https://api.example.com", "fake-model", "fake-key")
        }
        val content = source.decide(
            AgentContext(AgentTrigger.MATERIAL_SAVED, 1L, "2026-10-24", "NORMAL", "浏览器", emptyList(), emptyList(), emptyList(), 5),
        )
        assertNotNull(content)
        val req = captured!!
        assertEquals(2, req.messages.size)
        assertTrue(req.messages[0].content.contains("READ_MEMORY"))
        assertTrue(req.messages[0].content.contains("no_action"))
        assertEquals(260, req.maxTokens)
    }

    @Test
    fun missingConfigDoesNotCallClient() = runBlocking {
        var called = false
        val client = object : ChatCompletionClient {
            override suspend fun complete(request: ChatRequest): ChatResult {
                called = true
                return ChatResult.Success("{}")
            }
        }
        val source = ModelAgentDecisionSource(client) { null }
        assertNull(source.decide(
            AgentContext(AgentTrigger.NOTE_SAVED, 1L, "2026-10-24", "NORMAL", "桌面", emptyList(), emptyList(), emptyList(), 5),
        ))
        assertEquals(false, called)
    }

    @Test
    fun reportSendsBoundedReceiptAndStatus() = runBlocking {
        var captured: ChatRequest? = null
        val client = object : ChatCompletionClient {
            override suspend fun complete(request: ChatRequest): ChatResult {
                captured = request
                return ChatResult.Success("读到了这条素材")
            }
        }
        val source = ModelAgentDecisionSource(client) { AgentModelConfig("https://api.example.com", "fake", "key") }
        val ctx = AgentContext(AgentTrigger.MATERIAL_SAVED, 1L, "2026-10-24", "NORMAL", "浏览器", emptyList(), emptyList(), listOf("e1"), 5, "e1")
        val decision = AgentDecision.ToolCall(ActionKind.READ_MATERIALS, mapOf("limit" to "5"), listOf("e1"), "x")
        val result = ActionResult("a1", ActionKind.READ_MATERIALS, ActionStatus.SUCCESS, "2 条", mapOf("text" to "标题：合成素材"))
        assertEquals("读到了这条素材", source.report(ctx, decision, result))
        assertTrue(source.canReport)
        val req = captured!!
        assertEquals(180, req.maxTokens)
        assertTrue(req.messages[1].content.contains("SUCCESS"))
        assertTrue(req.messages[1].content.contains("标题：合成素材"))
    }
}