package cn.companion.pet.core.agent

import cn.companion.pet.net.ChatCompletionClient
import cn.companion.pet.net.ChatMessage
import cn.companion.pet.net.ChatRequest
import cn.companion.pet.net.ChatResult
import cn.companion.pet.net.ChatRole

/** 真实模型决策来源：OpenAI 兼容 Chat Completions，输出 JSON 文本；预算由 AgentLoop 先预留。 */
class ModelAgentDecisionSource(
    private val client: ChatCompletionClient,
    private val config: () -> AgentModelConfig?,
) : AgentDecisionSource {
    override val canReport: Boolean = true

    override suspend fun decide(context: AgentContext): String? {
        val cfg = config() ?: return null
        if (cfg.endpoint.isBlank() || cfg.model.isBlank() || cfg.apiKey.isBlank()) return null
        val request = ChatRequest(
            endpoint = cfg.endpoint,
            model = cfg.model,
            apiKey = cfg.apiKey,
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, AgentPrompt.system()),
                ChatMessage(ChatRole.USER, AgentPrompt.user(context)),
            ),
            maxTokens = 260,
            timeoutMillis = 15_000,
        )
        return (client.complete(request) as? ChatResult.Success)?.content
    }

    override suspend fun report(
        context: AgentContext,
        decision: AgentDecision.ToolCall,
        result: cn.companion.pet.core.action.ActionResult,
    ): String? {
        val cfg = config() ?: return null
        if (cfg.endpoint.isBlank() || cfg.model.isBlank() || cfg.apiKey.isBlank()) return null
        val request = ChatRequest(
            endpoint = cfg.endpoint,
            model = cfg.model,
            apiKey = cfg.apiKey,
            messages = listOf(
                ChatMessage(ChatRole.SYSTEM, AgentPrompt.receiptSystem()),
                ChatMessage(ChatRole.USER, AgentPrompt.receiptUser(context, decision, result)),
            ),
            maxTokens = 180,
            timeoutMillis = 10_000,
        )
        return (client.complete(request) as? ChatResult.Success)?.content
    }
}
