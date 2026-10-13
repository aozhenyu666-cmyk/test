package com.yishou.app.llm

import com.yishou.app.log.RunLog
import org.json.JSONObject

/** 教练对一手的回应：接没接住、你的原话、提炼出的一条、下一问 */
data class CoachReply(
    val caught: Boolean,
    val quote: String,
    val gist: String,
    /** “结论”或“疑问” */
    val kind: String,
    val next: String,
    val face: Int,
    /** 没接住时，一句话说差在哪 */
    val why: String,
)

/** 主线的教练：只走一手，只问一个问题，不换话题。 */
interface LineCoach {
    suspend fun reply(anchor: String, conclusions: List<String>, doubts: List<String>, recent: List<Pair<String, String>>, question: String, answer: String, face: Int): LlmResult<CoachReply>
}

object LinePrompts {
    val SYSTEM = """
        你是一个下棋式的思维教练：用户围着一个核心问题一手一手地想，你每次只回应这一手，再问下一个问题。
        规矩：
        1. 只围着【主线】问，不换话题，不往外延伸到别的事；新问题必须能推进主线。
        2. 一次只问一个问题，不超过 40 个字，具体、能回答，不要“你怎么看”这种空问题。
        3. 判断用户有没有接住刚才的问题：答非所问、只表态不给内容，算没接住。
        4. 提炼只能来自用户自己说的话；【原话】必须是用户回答里连续的一段，一个字都不改。
        5. 下一问按六面轮换着推进：1 精简（说出核心关系）2 预判（结果出来前押一个判断）3 联想（像什么，哪里不同）4 提问（哪里还不确定）5 体系（和已知的什么有关系）6 回响（回到之前的结论，还成立吗）。
        严格按【标签】格式输出纯文本，不要 JSON，不要开场白。
    """.trimIndent()

    fun user(anchor: String, conclusions: List<String>, doubts: List<String>, recent: List<Pair<String, String>>, question: String, answer: String, face: Int) = buildString {
        appendLine("【主线】$anchor")
        if (conclusions.isNotEmpty()) appendLine("【已有结论】" + conclusions.takeLast(5).joinToString("；"))
        if (doubts.isNotEmpty()) appendLine("【还没弄清】" + doubts.takeLast(3).joinToString("；"))
        if (recent.isNotEmpty()) {
            appendLine("【前几手】")
            recent.takeLast(4).forEach { (q, a) -> appendLine("问：$q\n答：$a") }
        }
        appendLine("【这一问】$question（第 $face 面）")
        appendLine("【用户的回答】$answer")
        appendLine()
        appendLine("按这个格式回：")
        appendLine("【接住】是 或 否")
        appendLine("【原话】用户回答里最能代表他判断的一段，原样照抄")
        appendLine("【提炼】这一手给主线添了什么，不超过 30 字（结论或疑问）")
        appendLine("【类型】结论 或 疑问")
        appendLine("【差在】没接住时一句话说差在哪；接住了写“无”")
        appendLine("【下一问】不超过 40 字")
        append("【面】1–6 中的一个数字")
    }

    val NAMES = mapOf("接住" to "caught", "原话" to "quote", "提炼" to "gist", "类型" to "kind", "差在" to "why", "下一问" to "next", "面" to "face")
}

class LlmLineCoach(private val chat: ChatClient) : LineCoach {
    override suspend fun reply(anchor: String, conclusions: List<String>, doubts: List<String>, recent: List<Pair<String, String>>, question: String, answer: String, face: Int): LlmResult<CoachReply> {
        var last: LlmError? = null
        repeat(2) {
            when (val r = chat.complete(LinePrompts.SYSTEM, LinePrompts.user(anchor, conclusions, doubts, recent, question, answer, face), "主线", json = false)) {
                is LlmResult.Err -> return r
                is LlmResult.Ok -> {
                    val o: JSONObject = Tagged.toJson(r.value, LinePrompts.NAMES, numbers = setOf("face"))
                    val next = o.optString("next").trim()
                    if (next.isNotEmpty()) {
                        return LlmResult.Ok(
                            CoachReply(
                                caught = !o.optString("caught").trim().startsWith("否"),
                                quote = o.optString("quote").trim(),
                                gist = o.optString("gist").trim().take(40),
                                kind = if (o.optString("kind").contains("疑")) "疑问" else "结论",
                                next = next.lines().first().take(60),
                                face = o.optInt("face", (face % 6) + 1).coerceIn(1, 6),
                                why = o.optString("why").trim().takeIf { it != "无" }.orEmpty(),
                            ),
                        )
                    }
                    last = LlmError.BadFormat("没有下一问")
                }
            }
        }
        RunLog.e("主线", "两次都没得到下一问")
        return LlmResult.Err(last ?: LlmError.BadFormat(""))
    }
}
