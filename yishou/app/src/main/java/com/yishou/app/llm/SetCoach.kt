package com.yishou.app.llm

import com.yishou.app.data.MoveKind
import com.yishou.app.data.MoveVerdict
import com.yishou.app.data.SetMove
import com.yishou.app.data.Task
import com.yishou.app.data.TrainSet
import com.yishou.app.log.RunLog
import org.json.JSONObject

/** 开组：今天练什么、材料、完成标准、第一问。 */
data class SetOpening(
    val goal: String,
    val material: String,
    val standard: String,
    val question: String,
    val say: String,
)

/** 判定一手。 */
data class MoveJudgement(
    /** 取值见 [MoveVerdict]：pass / redo / unclear */
    val verdict: String,
    val gap: String,
    val conclusion: String,
    val reason: String,
    val correctStep: String,
    val nextQuestion: String,
    val nextSay: String,
)

data class HelpReply(val help: String, val say: String)

/** 开组时给教练的背景。 */
data class SetContext(
    val task: Task,
    val face: Int,
    val level: Int,
    val setMoves: Int,
    /** 用户自己给的材料（粘贴、照片转写、屏幕描述）；为空就请教练出 */
    val userMaterial: String? = null,
    /** 最近几组用户自己说的“练会了什么”，接着练，不重复 */
    val recentLessons: List<String> = emptyList(),
)

/** 教练：开组、判定一手、给帮助。抽成接口，测试时换成假的。 */
interface SetCoach {
    suspend fun open(ctx: SetContext): LlmResult<SetOpening>

    suspend fun judge(
        set: TrainSet,
        chain: List<String>,
        move: SetMove,
        answer: String,
        nextKind: String,
        giveAnswer: Boolean,
    ): LlmResult<MoveJudgement>

    /** explain：没看懂材料；否则按 level 给线索（1）、半步（2）、示范（3） */
    suspend fun help(set: TrainSet, chain: List<String>, move: SetMove, level: Int, explain: Boolean): LlmResult<HelpReply>
}

/** 教练的提示词。改教练的标准，只改这里。 */
object CoachPrompts {

    /** 主练动作落到一份材料上是什么意思 */
    val FACE_ACTIONS = mapOf(
        1 to "精简：留下会改变答案的条件，说清核心关系",
        2 to "预判：结果出来之前，先说条件变化会影响哪里、依据是什么",
        3 to "联想：找一个结构相似的例子，说明相似在哪、哪里不能照搬",
        4 to "提问：指出还缺哪个信息，没有它判断就做不了",
        5 to "体系：把条件、中间结论和结果连成一条线",
    )

    fun levelText(level: Int): String = when (level) {
        0 -> "等级 0：每问前先用一个相似的小例子示范这一面的动作（两三句），再请他做一步。"
        1 -> "等级 1：问题里给一个线索（指向该看的条件），他完成整步。"
        2 -> "等级 2：不给线索，他自己完成。"
        else -> "等级 3：不给线索；接力手一次要接两步；最后一问请他自己提出下一个该问的问题。"
    }

    fun kindText(kind: String): String = when (kind) {
        MoveKind.TRY -> "首次尝试"
        MoveKind.RELAY -> "接力（要用上一结论）"
        MoveKind.VERIFY -> "独立验证（相似新题，不给提示）"
        else -> kind
    }

    val SYSTEM = """
        你是一个思维教练，不是陪练。陪练只要球打回来就行；教练看动作标不标准：
        结论要有依据，依据要落到材料里的具体条件，刚得到的结论要接到下一步。

        一组训练：一份材料、一个主练动作，几手连着练，最后独立做一道相似题。你负责安排，用户只面对眼前这一问。

        出题：
        - 一问只要一个动作，能用一两句话、或一个数、一个选项加一句依据回答。
        - 不提前透露答案，不在问题里暗示结论。
        - 接力手：必须用到结论链里最近的结论，再加一个新条件或变化，让他更新判断。
        - 独立验证手：在问题里给一份结构相似的新材料（三四句以内），不给任何提示，让他独立完成同一个动作。
        - 帮助等级决定给多少支架，见请求里的说明。

        判定（像教练看动作，不只看球有没有回去）：
        - pass：结论对，并且依据能指到材料里的具体条件或上一结论。开放问题里推理成立也算。
        - redo：结论错、漏了条件、依据只是“感觉”“差不多”“接近大意”，或者说了很多但没推进到要回答的那一步。
        - unclear：题目本身有歧义、材料不全、语音或图片识别明显出错。这不算他的失败。
        - gap：只指出最关键的一处，引用材料里的原词，告诉他这次只改哪一步。不批评，不说教，不给答案。
          例：“车程算进去了，但漏了‘提前15分钟到场’。只改出门时间。”
          例：“你说了‘可能迟到’，还没说是哪个条件导致迟到。选出那个条件。”
        - pass 时 gap 写一句具体的肯定：指出他做对的那个动作，不说“很好”“不错”这类空话。
        - conclusion：pass 时把这一步的结论压成不超过 12 个字的标签，例如“最晚14:15出门”。
        - give_answer 为真时（他已经改了两次）：在 correct_step 里用两句以内给出这一步正确的做法和结果，然后照常出下一问。

        帮助（他卡住时，原问题不变）：
        - 线索：指向材料里该看的那个条件，不说怎么用。
        - 半步：给出推理的前半步，后半步留给他。
        - 示范：用一个结构相似、数字或对象不同的小例子完整做一遍，再请他回到原题自己做。
        - 没看懂材料：指出相关原句，用更具体的话解释，再请他确认看懂了。

        say：朗读版，只说要他回答的那一句，口语，不超过 30 个字，不带编号和符号。
        只返回一个 JSON 对象，不要任何其他文字，不要用代码块包裹。
    """.trimIndent()

    val OPEN_FORMAT = """
        {
          "goal": "今天练：……（一句，说清这组的主练动作）",
          "material": "材料：把必要条件写清楚，五句以内",
          "standard": "本组完成标准：……（一句，包括最后独立做一道相似题）",
          "question": "第一问",
          "say": "第一问的朗读版"
        }
    """.trimIndent()

    val JUDGE_FORMAT = """
        {
          "verdict": "pass | redo | unclear",
          "gap": "一句定位反馈",
          "conclusion": "pass 时不超过 12 字的结论标签，否则空字符串",
          "reason": "一句判定理由",
          "correct_step": "give_answer 为真且没过时，给出正确的这一步；否则空字符串",
          "next_question": "下一问（规则见请求）",
          "next_say": "下一问的朗读版"
        }
    """.trimIndent()

    val HELP_FORMAT = """
        {
          "help": "帮助内容（三句以内；示范可以五句）",
          "say": "朗读版，一句"
        }
    """.trimIndent()
}

object SetMessages {

    fun open(c: SetContext): String = buildString {
        appendLine("请求类型：开一组训练。")
        appendLine("【领域】${c.task.title}")
        if (c.task.goal.isNotBlank()) appendLine("【目标】${c.task.goal}")
        val face = Faces.of(c.face)
        appendLine("【主练动作】第 ${c.face} 面 ${face?.name.orEmpty()}。${CoachPrompts.FACE_ACTIONS[c.face].orEmpty()}")
        appendLine("【帮助】${CoachPrompts.levelText(c.level)}")
        appendLine("【这组几手】${c.setMoves} 手：第 1 手首次尝试，中间接力，最后 1 手独立验证。")
        val m = c.userMaterial?.trim().orEmpty()
        if (m.isNotEmpty()) {
            appendLine("【用户给的材料】（整理成必要条件，不要另出材料）")
            appendLine(m)
        } else {
            appendLine("【材料】用户没给材料。请按领域出一份短材料：贴近真实，条件清楚，能连着推三四步。")
        }
        if (c.recentLessons.isNotEmpty()) {
            appendLine("【最近几组他自己说练会了什么】（接着往前走，不要重复同一个点）")
            c.recentLessons.forEach { appendLine("- $it") }
        }
        appendLine()
        appendLine("只返回 JSON，格式如下：")
        append(CoachPrompts.OPEN_FORMAT)
    }

    fun judge(set: TrainSet, chain: List<String>, move: SetMove, answer: String, nextKind: String, giveAnswer: Boolean): String = buildString {
        appendLine("请求类型：判定一手。")
        appendSet(set, chain)
        appendLine("【这一手】第 ${move.idx} 手，${CoachPrompts.kindText(move.kind)}，第 ${move.attempt} 次尝试，用过的帮助等级 ${move.help}")
        appendLine("【问题】${move.question}")
        if (move.helpText.isNotBlank()) appendLine("【给过的帮助】${move.helpText}")
        appendLine("【他的回答原话】")
        appendLine(answer)
        appendLine()
        appendLine("give_answer：${if (giveAnswer) "真" else "假"}")
        appendLine(
            "next_question 的规则：redo 时，写清这次只改哪一步（一句）；unclear 时，把问题问清楚；" +
                when (nextKind) {
                    MoveKind.RELAY -> "pass 或给出了正确这一步时，出接力手：用上结论链最新的结论，再加一个新条件。"
                    MoveKind.VERIFY -> "pass 或给出了正确这一步时，出独立验证手：给一份结构相似的新材料，不给提示。"
                    else -> "pass 或给出了正确这一步时，这组结束，next_question 和 next_say 填空字符串。"
                },
        )
        appendLine()
        appendLine("只返回 JSON，格式如下：")
        append(CoachPrompts.JUDGE_FORMAT)
    }

    fun help(set: TrainSet, chain: List<String>, move: SetMove, level: Int, explain: Boolean): String = buildString {
        appendLine("请求类型：给帮助。")
        appendSet(set, chain)
        appendLine("【当前问题】${move.question}")
        if (move.answer.isNotBlank()) appendLine("【他刚才的回答】${move.answer}")
        if (move.gap.isNotBlank()) appendLine("【刚才的反馈】${move.gap}")
        appendLine(
            "【要的帮助】" + when {
                explain -> "没看懂材料：指出相关原句，用更具体的话解释，再请他确认。"
                level <= 1 -> "线索：指向该看的那个条件，不说怎么用。"
                level == 2 -> "半步：给出推理的前半步，后半步留给他。"
                else -> "示范：用结构相似、数字或对象不同的小例子完整做一遍，再请他回原题自己做。"
            },
        )
        appendLine()
        appendLine("只返回 JSON，格式如下：")
        append(CoachPrompts.HELP_FORMAT)
    }

    private fun StringBuilder.appendSet(set: TrainSet, chain: List<String>) {
        appendLine("【这组】${set.goal}")
        appendLine("【主练动作】${CoachPrompts.FACE_ACTIONS[set.face].orEmpty()}")
        appendLine("【帮助】${CoachPrompts.levelText(set.level)}")
        appendLine("【材料】${set.material}")
        appendLine("【完成标准】${set.standard}")
        if (chain.isEmpty()) {
            appendLine("【结论链】（还没有）")
        } else {
            appendLine("【结论链】")
            chain.forEachIndexed { i, c -> appendLine("${i + 1}. $c") }
        }
    }
}

object SetParser {
    fun parseOpening(content: String): SetOpening {
        val o = CoachParser.extractJsonObject(content)
        return SetOpening(
            goal = o.need("goal"),
            material = o.need("material"),
            standard = o.text("standard"),
            question = o.need("question"),
            say = o.text("say").take(80),
        )
    }

    fun parseJudgement(content: String, nextKind: String): MoveJudgement {
        val o = CoachParser.extractJsonObject(content)
        val raw = o.need("verdict").lowercase()
        val verdict = when {
            "unclear" in raw -> MoveVerdict.UNCLEAR
            "redo" in raw -> MoveVerdict.REDO
            "pass" in raw -> MoveVerdict.PASS
            else -> throw BadJsonException("verdict 不是 pass、redo 或 unclear：$raw")
        }
        val next = o.text("next_question")
        // 除了“过了且这组结束”，都必须有下一问
        val ending = verdict == MoveVerdict.PASS && nextKind == "end"
        if (!ending && next.isEmpty()) throw BadJsonException("缺少 next_question")
        return MoveJudgement(
            verdict = verdict,
            gap = o.text("gap"),
            conclusion = o.text("conclusion").take(24),
            reason = o.text("reason"),
            correctStep = o.text("correct_step"),
            nextQuestion = next,
            nextSay = o.text("next_say").take(80),
        )
    }

    fun parseHelp(content: String): HelpReply {
        val o = CoachParser.extractJsonObject(content)
        return HelpReply(o.need("help"), o.text("say").take(80))
    }

    private fun JSONObject.text(key: String): String = optString(key, "").trim().takeUnless { it == "null" }.orEmpty()

    private fun JSONObject.need(key: String): String = text(key).ifEmpty { throw BadJsonException("缺少 $key") }
}

/** 用大模型当教练。解析失败重试一次；网络错误直接返回。 */
class LlmSetCoach(private val chat: ChatClient) : SetCoach {

    override suspend fun open(ctx: SetContext) = request(SetMessages.open(ctx), "开组") { SetParser.parseOpening(it) }

    override suspend fun judge(set: TrainSet, chain: List<String>, move: SetMove, answer: String, nextKind: String, giveAnswer: Boolean) =
        request(SetMessages.judge(set, chain, move, answer, nextKind, giveAnswer), "教练判定") { SetParser.parseJudgement(it, nextKind) }

    override suspend fun help(set: TrainSet, chain: List<String>, move: SetMove, level: Int, explain: Boolean) =
        request(SetMessages.help(set, chain, move, level, explain), "帮助") { SetParser.parseHelp(it) }

    private suspend fun <T> request(user: String, kind: String, parse: (String) -> T): LlmResult<T> {
        var last: LlmError? = null
        repeat(2) {
            when (val r = chat.complete(CoachPrompts.SYSTEM, user, kind)) {
                is LlmResult.Err -> return r
                is LlmResult.Ok -> try {
                    return LlmResult.Ok(parse(r.value))
                } catch (e: BadJsonException) {
                    last = LlmError.BadFormat(e.message ?: "")
                }
            }
        }
        val error = last ?: LlmError.BadFormat("")
        RunLog.e(kind, "两次都没解析出约定的 JSON：${error.message}")
        return LlmResult.Err(error)
    }
}
