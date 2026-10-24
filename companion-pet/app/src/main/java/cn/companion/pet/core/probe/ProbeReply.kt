package cn.companion.pet.core.probe

import cn.companion.pet.core.text.UnicodeText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject

enum class Mood { IDLE, CURIOUS, THINKING, TEASING, HAPPY, SLEEPY }

data class ProbeReply(
    val hookText: String,
    val contextSummary: String,
    val mood: Mood,
    val suggestGemini: Boolean,
    val skip: Boolean,
)

object ProbeReplyParser {
    /** 要求 30 字，留一点余量给两句话的接话；超过就截断。 */
    const val MAX_HOOK_CHARS = 60
    const val MAX_SUMMARY_CHARS = 100

    /**
     * 解析模型返回的 content。解析失败返回 null（调用方丢弃本次，不显示原文）。
     * 允许外面包一层 ```json 代码块，这是模型常见的不听话。
     */
    fun parse(content: String): ProbeReply? {
        val obj = extractObject(content)?.let { json ->
            try {
                Json.parseToJsonElement(json).jsonObject
            } catch (e: Exception) {
                null
            }
        } ?: return salvage(content)
        val skip = obj.bool("skip") ?: false
        val hook = obj.str("hook_text")?.trim().orEmpty()
        if (!skip && hook.isEmpty()) return null
        return ProbeReply(
            hookText = UnicodeText.truncate(hook, MAX_HOOK_CHARS),
            contextSummary = UnicodeText.truncate(obj.str("context_summary")?.trim().orEmpty(), MAX_SUMMARY_CHARS),
            mood = obj.str("mood")?.trim()?.uppercase()?.let { m -> Mood.entries.firstOrNull { it.name == m } } ?: Mood.IDLE,
            suggestGemini = obj.bool("suggest_gemini") ?: false,
            skip = skip,
        )
    }

    /**
     * 输出被 max_tokens 截断时 JSON 没有收尾，但前面的字段往往已经完整。
     * 只接受“引号已经闭合”的 hook_text；半句话宁可不说。
     */
    private fun salvage(content: String): ProbeReply? {
        val start = content.indexOf('{')
        if (start < 0) return null
        val body = content.substring(start)
        fun field(key: String): String? = Regex("\"" + key + "\"\\s*:\\s*(\"(?:[^\"\\\\]|\\\\.)*\")")
            .find(body)?.groupValues?.get(1)
            ?.let { runCatching { (Json.parseToJsonElement(it) as JsonPrimitive).content }.getOrNull() }
        fun flag(key: String): Boolean? = Regex("\"" + key + "\"\\s*:\\s*(true|false)").find(body)?.groupValues?.get(1)?.toBoolean()
        val skip = flag("skip") ?: false
        val hook = field("hook_text")?.trim().orEmpty()
        if (hook.isEmpty() && !skip) return null
        return ProbeReply(
            hookText = UnicodeText.truncate(hook, MAX_HOOK_CHARS),
            contextSummary = UnicodeText.truncate(field("context_summary")?.trim().orEmpty(), MAX_SUMMARY_CHARS),
            mood = field("mood")?.trim()?.uppercase()?.let { m -> Mood.entries.firstOrNull { it.name == m } } ?: Mood.IDLE,
            suggestGemini = flag("suggest_gemini") ?: false,
            skip = skip,
        )
    }

    private fun extractObject(content: String): String? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return content.substring(start, end + 1)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    private fun JsonObject.bool(key: String): Boolean? = (this[key] as? JsonPrimitive)?.booleanOrNull
}

/**
 * 输出后处理的硬规则：人设里写了，但模型偶尔还是会犯，命中就丢弃这次。
 */
object ContentGuard {
    private val banned = listOf(
        "加油", "你可以的", "任务", "待办", "计划", "自律", "效率",
        "又不理我", "好难过", "伤心了", "你应该",
    )

    fun violates(text: String): Boolean = banned.any { text.contains(it) }
}

/** 每日调用上限。按日期键计数，跨日自动归零。 */
class DailyBudget(var limit: Int, private var dateKey: String = "", private var used: Int = 0) {
    val usedToday: Int get() = used
    val currentDateKey: String get() = dateKey

    fun remaining(today: String): Int {
        rollover(today)
        return (limit - used).coerceAtLeast(0)
    }

    fun tryConsume(today: String): Boolean {
        rollover(today)
        if (used >= limit) return false
        used += 1
        return true
    }

    private fun rollover(today: String) {
        if (today != dateKey) {
            dateKey = today
            used = 0
        }
    }
}
