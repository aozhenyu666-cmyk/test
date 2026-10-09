package com.yishou.app.llm

import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.RoundSource
import com.yishou.app.data.Task
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** 开局请求的结果。 */
data class OpeningMove(
    val coachMove: String,
    val stuckType: String,
)

/** 判定请求返回的新断点。 */
data class BreakpointUpdate(
    val known: String,
    val stuck: String,
    val nextQuestion: String,
)

/** 判定并出下一手的结果。 */
data class Judgement(
    val effective: Boolean,
    val moveType: String,
    val reason: String,
    val feedback: String,
    /** 模型没给断点时为 null，保持原断点不变 */
    val breakpoint: BreakpointUpdate?,
    val nextCoachMove: String,
)

/** 每晚总结的结果。 */
data class SummaryResult(
    /** 条件 → 做法 → 预期结果；证据不足时为空 */
    val rule: String,
    val tomorrowQuestion: String,
    val note: String,
)

/**
 * 出一手时附带的背景：以前总结出的规则（让陪练在合适时提醒调用），
 * 以及距离上一轮过了多久（隔得久时先帮用户找回局面）。
 */
data class CoachContext(
    val rules: List<String> = emptyList(),
    /** 距离上一轮的分钟数；没有上一轮时为 null */
    val gapMinutes: Long? = null,
    /** 上一轮的用户原话，用于“恢复” */
    val lastAnswer: String? = null,
)

/** 陪练。抽成接口，方便测试时换成假的。 */
interface Coach {
    suspend fun opening(task: Task, breakpoint: Breakpoint, context: CoachContext = CoachContext()): LlmResult<OpeningMove>
    suspend fun judge(
        task: Task,
        breakpoint: Breakpoint,
        coachMove: String,
        answer: String,
        context: CoachContext = CoachContext(),
    ): LlmResult<Judgement>
    suspend fun summary(task: Task?, breakpoint: Breakpoint?, rounds: List<Round>): LlmResult<SummaryResult>
}

/**
 * 用大模型扮演陪练。
 * 所有请求都要求只返回 JSON；返回内容解析失败时重试一次，仍失败就把错误交给调用方按离线处理。
 * 网络错误、超时、HTTP 错误不重试，直接返回，避免界面等太久。
 */
class LlmCoach(private val chat: ChatClient) : Coach {

    override suspend fun opening(task: Task, breakpoint: Breakpoint, context: CoachContext): LlmResult<OpeningMove> =
        requestJson(CoachMessages.opening(task, breakpoint, context), CoachParser::parseOpening)

    override suspend fun judge(
        task: Task,
        breakpoint: Breakpoint,
        coachMove: String,
        answer: String,
        context: CoachContext,
    ): LlmResult<Judgement> =
        requestJson(CoachMessages.judge(task, breakpoint, coachMove, answer, context), CoachParser::parseJudgement)

    override suspend fun summary(task: Task?, breakpoint: Breakpoint?, rounds: List<Round>): LlmResult<SummaryResult> =
        requestJson(CoachMessages.summary(task, breakpoint, rounds), CoachParser::parseSummary)

    private suspend fun <T> requestJson(user: String, parse: (String) -> T): LlmResult<T> {
        var lastError: LlmError? = null
        repeat(2) {
            when (val r = chat.complete(Prompts.SYSTEM, user)) {
                is LlmResult.Err -> return r
                is LlmResult.Ok -> try {
                    return LlmResult.Ok(parse(r.value))
                } catch (e: BadJsonException) {
                    lastError = LlmError.BadFormat(e.message ?: "")
                }
            }
        }
        return LlmResult.Err(lastError ?: LlmError.BadFormat(""))
    }
}

/** 拼用户消息。任务、断点、原话都原样放进去。 */
object CoachMessages {

    fun opening(task: Task, breakpoint: Breakpoint, context: CoachContext = CoachContext()): String = buildString {
        appendLine("请求类型：开局（这是本任务的第一手，或断点刚被手动修改过）。")
        appendLine("请根据下面的任务和断点出一手。")
        appendLine()
        appendTask(task)
        appendBreakpoint(breakpoint)
        appendContext(context)
        appendLine()
        appendLine("只返回 JSON，格式如下：")
        append(Prompts.OPENING_FORMAT)
    }

    fun judge(
        task: Task,
        breakpoint: Breakpoint,
        coachMove: String,
        answer: String,
        context: CoachContext = CoachContext(),
    ): String = buildString {
        appendLine("请求类型：判定并出下一手。")
        appendLine("请判定用户这一手是否有效，更新断点，并出下一手。")
        appendLine()
        appendTask(task)
        appendBreakpoint(breakpoint)
        appendContext(context)
        appendLine("【本轮陪练的一手】")
        appendLine(coachMove)
        appendLine("【用户回答原话】")
        appendLine(answer)
        appendLine()
        appendLine("只返回 JSON，格式如下（effective 为 true 或 false；无效时 move_type 填“无”）：")
        append(Prompts.JUDGE_FORMAT)
    }

    fun summary(
        task: Task?,
        breakpoint: Breakpoint?,
        rounds: List<Round>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("请求类型：每晚总结。")
        appendLine("请根据用户今天全部的轮次和当前断点，总结一条规则、明天第一问和今天的实际进展。")
        appendLine()
        if (task != null) appendTask(task)
        if (breakpoint != null) appendBreakpoint(breakpoint)
        appendLine("【今天的轮次】共 ${rounds.size} 轮")
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        rounds.forEachIndexed { i, r ->
            appendLine("第 ${i + 1} 轮（${time.format(Instant.ofEpochMilli(r.createdAt))}，${sourceName(r.source)}）")
            appendLine("陪练：${r.coachMove}")
            appendLine("用户原话：${r.userAnswer}")
            val verdict = when {
                !r.judged -> "未判定（离线保存）"
                r.effective -> "有效（${r.moveType}）"
                else -> "无效"
            }
            appendLine("判定：$verdict${if (r.reason.isNotBlank()) "；理由：${r.reason}" else ""}")
        }
        appendLine()
        appendLine("只返回 JSON，格式如下（tomorrow_question 必须引用当天原话）：")
        append(Prompts.SUMMARY_FORMAT)
    }

    fun sourceName(source: String) = when (source) {
        RoundSource.GATE -> "入口思考页"
        RoundSource.WINDOW -> "陪练窗口"
        else -> "主页"
    }

    private fun StringBuilder.appendTask(task: Task) {
        appendLine("【任务】")
        appendLine("对象：${task.title}")
        appendLine("目标：${task.goal}")
        appendLine("材料摘录：${task.material?.takeIf { it.isNotBlank() } ?: "（无）"}")
    }

    private fun StringBuilder.appendContext(c: CoachContext) {
        if (c.rules.isNotEmpty()) {
            appendLine("【用户以前总结出的规则】（条件符合时，在支架里提醒他调用其中一条）")
            c.rules.forEach { appendLine("- $it") }
        }
        val gap = c.gapMinutes
        if (gap != null && gap >= RESUME_GAP_MINUTES) {
            appendLine("【距离上一轮】${formatGap(gap)}。用户隔了很久才回来：下一手先用一句话复述他上次停在哪里，再出问题。")
            c.lastAnswer?.takeIf { it.isNotBlank() }?.let { appendLine("上一轮用户原话：$it") }
        }
    }

    /** 隔多久算“很久没回来”：6 小时 */
    const val RESUME_GAP_MINUTES = 6 * 60L

    fun formatGap(minutes: Long): String = when {
        minutes < 60 -> "$minutes 分钟"
        minutes < 24 * 60 -> "${minutes / 60} 小时"
        else -> "${minutes / (24 * 60)} 天 ${minutes % (24 * 60) / 60} 小时"
    }

    private fun StringBuilder.appendBreakpoint(bp: Breakpoint) {
        appendLine("【断点】")
        appendLine("已知：${bp.known.ifBlank { "（空）" }}")
        appendLine("卡点：${bp.stuck.ifBlank { "（空）" }}")
        appendLine("下一问：${bp.nextQuestion.ifBlank { "（空）" }}")
    }
}

class BadJsonException(message: String) : Exception(message)

/** 把模型回复解析成数据。缺少必需字段时抛 [BadJsonException]。 */
object CoachParser {

    fun parseOpening(content: String): OpeningMove {
        val obj = extractJsonObject(content)
        val move = obj.requireText("coach_move")
        val stuck = obj.optText("stuck_type")
        return OpeningMove(move, stuck)
    }

    fun parseJudgement(content: String): Judgement {
        val obj = extractJsonObject(content)
        val effective = obj.requireBoolean("effective")
        val rawType = obj.optText("move_type")
        // 无效时 moveType 一律是“无”；有效但类型不在约定取值里时，保留“无”而不是乱填
        val moveType = if (effective && rawType in Prompts.MOVE_TYPES) rawType else Prompts.MOVE_NONE
        val bp = obj.optJSONObject("breakpoint")?.let {
            BreakpointUpdate(
                known = it.optText("known"),
                stuck = it.optText("stuck"),
                nextQuestion = it.optText("next_question"),
            )
        }
        return Judgement(
            effective = effective,
            moveType = moveType,
            reason = obj.optText("reason"),
            feedback = obj.optText("feedback"),
            breakpoint = bp,
            nextCoachMove = obj.requireText("next_coach_move"),
        )
    }

    fun parseSummary(content: String): SummaryResult {
        val obj = extractJsonObject(content)
        return SummaryResult(
            rule = obj.optText("rule"),
            tomorrowQuestion = obj.requireText("tomorrow_question"),
            note = obj.optText("note"),
        )
    }

    /** 模型偶尔会包一层 ```json 代码块或前后带几句话，这里只取第一个 { 到最后一个 } 之间的部分。 */
    fun extractJsonObject(content: String): JSONObject {
        val start = content.indexOf('{')
        val end = content.lastIndexOf('}')
        if (start < 0 || end <= start) throw BadJsonException("没有找到 JSON 对象：${content.take(80)}")
        return try {
            JSONObject(content.substring(start, end + 1))
        } catch (e: JSONException) {
            throw BadJsonException("JSON 解析失败：${content.take(80)}")
        }
    }

    private fun JSONObject.optText(key: String): String =
        if (isNull(key)) "" else optString(key, "").trim()

    private fun JSONObject.requireText(key: String): String =
        optText(key).ifEmpty { throw BadJsonException("缺少 $key") }

    private fun JSONObject.requireBoolean(key: String): Boolean = when (val v = opt(key)) {
        is Boolean -> v
        is String -> when (v.trim().lowercase()) {
            "true" -> true
            "false" -> false
            else -> throw BadJsonException("$key 不是 true/false：$v")
        }
        else -> throw BadJsonException("缺少 $key")
    }
}
