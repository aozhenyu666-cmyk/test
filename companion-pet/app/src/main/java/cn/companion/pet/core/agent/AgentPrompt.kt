package cn.companion.pet.core.agent

import cn.companion.pet.core.action.ActionKind
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object AgentPrompt {
    fun system(): String = """
        你是桌宠的决策层，不是聊天窗口。只输出一个 JSON 对象，不要 Markdown、不要解释。
        可用工具（本轮只选一个或 no_action）：
        - READ_MEMORY：读取可见记忆。参数 {"query":"可选关键词","limit":"1-20"}
        - READ_MATERIALS：读取用户主动保存的素材。参数 {"limit":"1-10"}
        - SAVE_NOTE：保存用户明确要求记下的念头。参数 {"text":"..."}，需要真实 source_id
        - OPEN_ASSISTANT：打开已配置的 AI App。参数 {"target":"GEMINI|CHATGPT|QWEN"}，需要真实 source_id
        - SPEAK：朗读文字。参数 {"text":"..."}，需要真实 source_id
        规则：
        1. 只依据下面给出的场景、记忆摘要、素材标题和事件 ID；不要编造屏幕内容、聊天记录或工具结果。
        2. 只读工具可以在自主模式下直接执行；写入、打开外部 App、朗读都只会被记录为“需要确认”，不会自动做。
        3. 没有足够理由就输出 no_action。不要为了显得主动而硬选工具。
        4. 输出格式：
        {"decision":"tool_call","tool":"READ_MATERIALS","args":{"limit":"5"},"source_ids":[],"reason":"..."}
        或
        {"decision":"no_action","reason":"..."}
    """.trimIndent()

    fun user(context: AgentContext): String = buildString {
        append("触发：").append(context.trigger.name).append('\n')
        append("场景：").append(context.scene).append('\n')
        append("前台：").append(context.foreground).append('\n')
        append("可见记忆摘要：\n")
        if (context.memoryLines.isEmpty()) append("- （无）\n") else context.memoryLines.take(8).forEach { append("- ").append(it.take(160)).append('\n') }
        append("素材标题：\n")
        if (context.materialTitles.isEmpty()) append("- （无）\n") else context.materialTitles.take(5).forEach { append("- ").append(it.take(120)).append('\n') }
        append("可用来源事件 ID（写动作必须从这里选）：").append(if (context.eventIds.isEmpty()) "无" else context.eventIds.joinToString(",")).append('\n')
        append("今日剩余模型预算：").append(context.budgetRemaining).append('\n')
    }
}

object AgentDecisionParser {
    fun parse(content: String): AgentDecision {
        val json = extractObject(content) ?: return AgentDecision.Invalid("模型没有返回 JSON")
        val root = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull()
            ?: return AgentDecision.Invalid("JSON 无法解析")
        val kind = root["decision"]?.jsonPrimitive?.content?.trim()?.lowercase()
        return when (kind) {
            "no_action" -> AgentDecision.NoAction(root["reason"]?.jsonPrimitive?.content?.take(200).orEmpty())
            "tool_call", "tool" -> {
                val tool = root["tool"]?.jsonPrimitive?.content?.trim() ?: return AgentDecision.Invalid("缺少 tool")
                val actionKind = ActionKind.entries.firstOrNull { it.name == tool.uppercase() }
                    ?: return AgentDecision.Invalid("未知工具")
                val args = runCatching {
                    (root["args"] as? JsonObject)?.entries
                        ?.associate { (k, v) -> k to (v.jsonPrimitive.contentOrNull ?: "") }
                        .orEmpty()
                }.getOrElse { return AgentDecision.Invalid("args 无法解析") }
                val sourceIds = when (val s = root["source_ids"]) {
                    is JsonArray -> s.mapNotNull { it.jsonPrimitive.contentOrNull }
                    else -> root["source_id"]?.jsonPrimitive?.contentOrNull?.let { listOf(it) }.orEmpty()
                }
                AgentDecision.ToolCall(
                    actionKind,
                    args,
                    sourceIds.filter { it.isNotBlank() }.take(5),
                    root["reason"]?.jsonPrimitive?.content?.take(200).orEmpty(),
                )
            }
            else -> AgentDecision.Invalid("decision 只能是 tool_call 或 no_action")
        }
    }

    private fun extractObject(content: String): String? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return content.substring(start, end + 1)
    }
}
