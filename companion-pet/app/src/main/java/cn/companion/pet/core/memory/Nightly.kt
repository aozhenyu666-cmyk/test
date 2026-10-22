package cn.companion.pet.core.memory

import cn.companion.pet.core.text.UnicodeText
import cn.companion.pet.net.ChatMessage
import cn.companion.pet.net.ChatRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject

data class NightlyOutput(val profileLines: List<String>, val diary: String, val letter: String)

/**
 * 每晚整理：一次请求产出新档案、它给自己的日记、给用户的信。
 */
object Nightly {
    const val MAX_TOKENS = 2000
    const val TIMEOUT_MILLIS = 60_000L
    const val MAX_LETTER_CHARS = 100

    fun prompt(persona: String, profile: String, todayLines: List<String>, yesterdayDiary: String, deleted: Set<String>): List<ChatMessage> {
        val system = """
            ${persona.trim()}

            # 现在是每晚整理时间
            你要根据今天和他的交流记录，一次写出三样东西，只返回一个 JSON 对象，不要别的文字：
            {"profile": "更新后的长期档案", "diary": "今天给自己写的日记", "letter": "给他的一封短信"}

            ## profile 规则
            - 每行一条，必须以 [他说过] 或 [我推测] 开头。他亲口说过的才能标 [他说过]，你推断的一律标 [我推测]。
            - 在旧档案基础上增删改，总长不超过 3000 字。
            - 只记对他的长期了解，不记屏幕内容，不记一次性琐事。
            - “已删除条目”里的内容是他亲手删掉的，绝不能再写回来，换个说法也不行。

            ## diary 规则
            - 写给自己看的，不加滤镜：今天观察到了什么、他哪句话让你印象深、你自己的心情。他一直没理你也如实写。
            - 200 字以内。

            ## letter 规则
            - 明天早上给他看，100 字以内。
            - 写一个对今天的具体观察，或一个想继续聊的话题。不总结、不评价、不提任务和计划，不用愧疚感施压。
        """.trimIndent()
        val user = buildString {
            append("# 旧档案\n").append(profile.trim().ifEmpty { "（空）" }).append("\n\n")
            append("# 已删除条目\n").append(if (deleted.isEmpty()) "（无）" else deleted.joinToString("\n")).append("\n\n")
            append("# 我昨天的日记\n").append(yesterdayDiary.trim().ifEmpty { "（没有）" }).append("\n\n")
            append("# 今天的交流记录\n").append(if (todayLines.isEmpty()) "（今天没怎么聊）" else todayLines.joinToString("\n"))
        }
        return listOf(ChatMessage(ChatRole.SYSTEM, system), ChatMessage(ChatRole.USER, user))
    }

    /**
     * 三样都通过校验才返回结果；任何一样不合格返回 null，调用方保留旧文件。
     */
    fun parse(content: String, oldProfileLines: List<String>): NightlyOutput? {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        val obj = runCatching { Json.parseToJsonElement(content.substring(start, end + 1)).jsonObject }.getOrNull() ?: return null
        fun str(k: String) = (obj[k] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim()
        val profile = str("profile") ?: return null
        val diary = str("diary")?.takeIf { it.isNotEmpty() } ?: return null
        val letter = str("letter")?.takeIf { it.isNotEmpty() } ?: return null

        val lines = profile.lines().map { it.trim().removePrefix("-").trim() }.filter { ProfileRules.hasTag(it) }
        // 模型把档案整个弄丢了：不接受，免得一夜清空
        if (lines.isEmpty() && oldProfileLines.isNotEmpty()) return null
        if (UnicodeText.count(letter) > MAX_LETTER_CHARS * 3 / 2) return null
        return NightlyOutput(
            profileLines = ProfileRules.clamp(lines),
            diary = diary,
            letter = UnicodeText.truncate(letter, MAX_LETTER_CHARS),
        )
    }
}
