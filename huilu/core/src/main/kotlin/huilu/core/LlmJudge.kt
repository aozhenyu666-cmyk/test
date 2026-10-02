package huilu.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * 大模型在系统里的位置：判断层，而不是聊天界面。
 *
 * 它只在规则处理不了的时候出场——用户在检查里写了一句话，而不是点按钮。
 * 输入是当前局面和这次检查的客观观察，输出是结构化的判断。
 * 输出永远只能落在这次检查允许的选项里，干预强度仍由 [Policy] 决定，模型无法越权。
 */
object LlmJudge {
    data class Interpretation(
        val kind: AnswerKind?,
        val minutes: Int?,
        val newText: String?,
        /** 对用户说的一句话。 */
        val reply: String,
        /** 模型对局面的理解，进日志备查。 */
        val assessment: String,
    )

    fun messages(c: CheckIn, a: Action, text: String, recent: List<Episode>, label: (String) -> String): JSONArray {
        val sys = """
你是用户的外置认知系统里的判断层。用户之前声明了一个具体行动，系统在行动途中重新观察了现实，并向用户提出了一个问题。
用户没有点选按钮，而是写了一句话。你的任务是把这句话理解成系统可以执行的判断。

只输出一个 JSON 对象，不要任何其他文字：
{"assessment":"一句话说明你认为实际发生了什么","kind":"选项之一或 null","minutes":数字或 null,"new_text":"降低难度后的新行动，或 null","reply":"对用户说的一句话，简短、具体、不说教"}

规则：
- kind 只能是给定选项之一；意思不清楚就填 null，并在 reply 里追问一个具体问题。
- 用户说太难、卡住了，用 SHRINK，并给出一个两到五分钟就能验证的更小行动作为 new_text。
- 用户说累了、要歇一下，用 REST，minutes 为用户提到的分钟数，没提就是 null。
- 用户的自述和客观观察冲突时，不要指责，在 reply 里平静地指出差异并给出下一步。
""".trim()
        val choices = c.choices.joinToString("、") { "${it.name}（${it.label}）" }
        val history = recent.takeLast(5).joinToString("\n") { e ->
            "- ${e.action.text}：${e.outcome?.label ?: "进行中"}" + (e.whole?.let { "，${it.kind.label}" } ?: "")
        }
        val user = """
当前行动：${a.text}${if (a.why.isNotBlank()) "（为什么：${a.why}）" else ""}
计划 ${a.plannedMin} 分钟，现在是第 ${c.minuteOfAction} 分钟。验证方式：${a.expect.effectiveMode.label}${
            if (a.expect.targetApps.isNotEmpty()) "（" + a.expect.targetApps.joinToString("、") { label(it) } + "）" else ""}
检查原因：${c.trigger.label}
客观观察：${c.deviation.reality}（判定：${c.deviation.kind.label}）
系统的问题：${c.question}
可选项：$choices
最近几轮：
${history.ifBlank { "（无）" }}

用户写道：$text
""".trim()
        return JSONArray()
            .put(JSONObject().put("role", "system").put("content", sys))
            .put(JSONObject().put("role", "user").put("content", user))
    }

    /** 解析模型输出；不合法的 kind 一律当作 null，绝不信任模型越出选项。 */
    fun parse(raw: String, allowed: List<AnswerKind>): Interpretation? {
        val start = raw.indexOf('{')
        val end = raw.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val o = JSONObject(raw.substring(start, end + 1))
            val k = o.optString("kind").uppercase().trim()
            val kind = allowed.firstOrNull { it.name == k }
            val minutes = if (o.has("minutes") && !o.isNull("minutes")) o.optInt("minutes", 0).takeIf { it in 1..120 } else null
            val nt = if (o.has("new_text") && !o.isNull("new_text")) o.optString("new_text").trim().takeIf { it.isNotEmpty() } else null
            Interpretation(kind, minutes, nt, o.optString("reply").trim(), o.optString("assessment").trim())
        } catch (_: Exception) {
            null
        }
    }
}
