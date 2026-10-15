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
    /** 捉虫：下一问里故意埋的那个漏洞（不给用户看，下一手判卷用） */
    val flaw: String = "",
)

/** 主线的教练：只走一手，只问一个问题，不换话题。 */
interface LineCoach {
    suspend fun reply(anchor: String, conclusions: List<String>, doubts: List<String>, recent: List<Pair<String, String>>, question: String, answer: String, face: Int): LlmResult<CoachReply>

    /**
     * 捉虫 / 变条件：beat 是这一问的类型（OPEN 开局、TRAP 捉虫、FLIP 变条件），flaw 是这一问埋的漏洞，
     * next 是下一问要出哪种，recall 是一条旧结论（变条件时可以拿来撞）。
     */
    suspend fun trap(anchor: String, conclusions: List<String>, question: String, answer: String, beat: String, flaw: String, next: String, recall: String): LlmResult<CoachReply> =
        reply(anchor, conclusions, emptyList(), emptyList(), question, answer, 1)
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

    val NAMES = mapOf("接住" to "caught", "原话" to "quote", "提炼" to "gist", "类型" to "kind", "差在" to "why", "下一问" to "next", "面" to "face", "漏洞" to "flaw")

    /** 岔路：教练不顺着问，而是抛带坑的判断让你捉虫，你锁定结论后马上改一个条件逼你接着推 */
    val TRAP_SYSTEM = """
        你是一个陪人“捉虫”的思维教练。用户不擅长从零写，但很擅长对现成的判断挑刺。你的打法只有两拍：
        捉虫：抛出一句关于【主线】的判断，里面故意藏一个错误条件（范围扩大、条件颠倒、偷换概念、漏了限定词、因果倒置之一），不要提示哪里错，读起来要像真的。
        变条件：用户锁定一句结论后，把结论里的一个条件抽掉、换掉、换成相反情况，或者换成【旧结论】里的场景，问他结论怎么改。
        规矩：只围着【主线】；不说教、不表扬、不解释；下一问不超过 50 个字；
        【原话】必须是用户回答里连续的一段，一个字都不改。
        严格按【标签】格式输出纯文本，不要 JSON，不要开场白。
    """.trimIndent()

    fun trapUser(anchor: String, conclusions: List<String>, question: String, answer: String, beat: String, flaw: String, next: String, recall: String) = buildString {
        appendLine("【主线】$anchor")
        if (conclusions.isNotEmpty()) appendLine("【已锁定的结论】" + conclusions.takeLast(4).joinToString("；"))
        if (recall.isNotBlank()) appendLine("【旧结论】$recall")
        appendLine("【这一问】$question")
        if (beat == "TRAP" && flaw.isNotBlank()) appendLine("【这一问埋的漏洞】$flaw")
        appendLine("【用户的回答】$answer")
        appendLine()
        appendLine(
            when (beat) {
                "TRAP" -> "判：用户有没有指出埋的那个漏洞，并改对一个条件？指出别的地方、只说“不对”不改条件，都算没接住。"
                "FLIP" -> "判：用户有没有按变了的条件给出改过的结论（或者说清为什么不用改）？只重复原结论不算。"
                else -> "判：用户有没有给出一句带条件的判断？"
            },
        )
        appendLine(if (next == "TRAP") "下一问出：捉虫（一句故意带一个错误条件的判断）。" else "下一问出：变条件（基于他刚锁定的结论，改一个条件追问）。")
        appendLine()
        appendLine("按这个格式回：")
        appendLine("【接住】是 或 否")
        appendLine("【原话】用户回答里最能代表他判断的一段，原样照抄")
        appendLine("【提炼】他锁定的结论，压成“只有当……，才……”，不超过 30 字")
        appendLine("【差在】没接住时一句话说差在哪（不要说出答案）；接住了写“无”")
        appendLine("【下一问】" + if (next == "TRAP") "那句带坑的判断本身（不要加“哪里不对”）" else "变了条件之后的追问")
        append(if (next == "TRAP") "【漏洞】你埋的那个错误条件，以及改对应该是什么" else "【漏洞】无")
    }
}

class LlmLineCoach(private val chat: ChatClient) : LineCoach {

    override suspend fun trap(anchor: String, conclusions: List<String>, question: String, answer: String, beat: String, flaw: String, next: String, recall: String): LlmResult<CoachReply> {
        var last: LlmError? = null
        repeat(2) {
            when (val r = chat.complete(LinePrompts.TRAP_SYSTEM, LinePrompts.trapUser(anchor, conclusions, question, answer, beat, flaw, next, recall), "捉虫", json = false)) {
                is LlmResult.Err -> return r
                is LlmResult.Ok -> {
                    val o = Tagged.toJson(r.value, LinePrompts.NAMES)
                    val q = o.optString("next").trim()
                    if (q.isNotEmpty()) {
                        return LlmResult.Ok(
                            CoachReply(
                                caught = !o.optString("caught").trim().startsWith("否"),
                                quote = o.optString("quote").trim(),
                                gist = o.optString("gist").trim().take(40),
                                kind = "结论",
                                next = q.lines().first().take(80),
                                face = if (next == "TRAP") 1 else 6,
                                why = o.optString("why").trim().takeIf { it != "无" }.orEmpty(),
                                flaw = o.optString("flaw").trim().takeIf { it != "无" }.orEmpty(),
                            ),
                        )
                    }
                    last = LlmError.BadFormat("没有下一问")
                }
            }
        }
        return LlmResult.Err(last ?: LlmError.BadFormat(""))
    }

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
