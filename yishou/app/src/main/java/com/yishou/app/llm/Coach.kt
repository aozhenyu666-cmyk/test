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
    /** 帮用户开口的半句话，可能为空 */
    val starters: List<String> = emptyList(),
    /** 这一手练骰子第几面，0 表示没标 */
    val face: Int = 0,
)

/** 看屏出一手的结果。 */
data class ObservedMove(
    val observation: String,
    val coachMove: String,
    val starters: List<String> = emptyList(),
    val face: Int = 0,
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
    val starters: List<String> = emptyList(),
    /** 下一手练骰子第几面，0 表示没标 */
    val nextFace: Int = 0,
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
    /** 用户掷出的骰子面：这一手必须练这一面 */
    val forcedFace: Int? = null,
    /** 用户请求示范 */
    val demo: Boolean = false,
    /** 示范时：当前这一手的原文 */
    val currentMove: String? = null,
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

    /** 看了一眼屏幕（screen 是识图模型的描述）之后，针对屏幕上这一步出一手。 */
    suspend fun observe(
        task: Task,
        breakpoint: Breakpoint,
        screen: String,
        context: CoachContext = CoachContext(),
    ): LlmResult<ObservedMove>
}

/**
 * 用大模型扮演陪练。
 * 所有请求都要求只返回 JSON；返回内容解析失败时重试一次，仍失败就把错误交给调用方按离线处理。
 * 网络错误、超时、HTTP 错误不重试，直接返回，避免界面等太久。
 */
class LlmCoach(private val chat: ChatClient) : Coach {

    override suspend fun opening(task: Task, breakpoint: Breakpoint, context: CoachContext): LlmResult<OpeningMove> =
        requestJson(CoachMessages.opening(task, breakpoint, context), "开局", CoachParser::parseOpening)

    override suspend fun judge(
        task: Task,
        breakpoint: Breakpoint,
        coachMove: String,
        answer: String,
        context: CoachContext,
    ): LlmResult<Judgement> =
        requestJson(CoachMessages.judge(task, breakpoint, coachMove, answer, context), "判定", CoachParser::parseJudgement)

    override suspend fun summary(task: Task?, breakpoint: Breakpoint?, rounds: List<Round>): LlmResult<SummaryResult> =
        requestJson(CoachMessages.summary(task, breakpoint, rounds), "总结", CoachParser::parseSummary)

    override suspend fun observe(
        task: Task,
        breakpoint: Breakpoint,
        screen: String,
        context: CoachContext,
    ): LlmResult<ObservedMove> =
        requestJson(CoachMessages.observe(task, breakpoint, screen, context), "看屏出题", CoachParser::parseObserved)

    private suspend fun <T> requestJson(user: String, kind: String, parse: (String) -> T): LlmResult<T> {
        var lastError: LlmError? = null
        repeat(2) {
            when (val r = chat.complete(Prompts.SYSTEM, user, kind)) {
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
        when {
            context.demo -> {
                appendLine("请求类型：示范。")
                appendLine("用户卡在下面这一手，请求示范。先用另一个短例子完整示范这一面的标准动作（两三句），")
                appendLine("再请他在自己的题上做同样的动作。示范和请求一起写进 coach_move。")
                context.currentMove?.let { appendLine("【卡住的这一手】$it") }
            }
            context.forcedFace != null -> {
                appendLine("请求类型：掷骰换一手。")
                appendLine("用户掷出了骰子，请按下面的任务和断点，出一手专门练他掷出的那一面。")
            }
            else -> {
                appendLine("请求类型：开局（这是本任务的第一手，或断点刚被手动修改过）。")
                appendLine("请根据下面的任务和断点出一手。")
            }
        }
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

    fun observe(task: Task, breakpoint: Breakpoint, screen: String, context: CoachContext = CoachContext()): String = buildString {
        appendLine("请求类型：看屏出一手。")
        appendLine("用户刚让你看了一眼他的手机屏幕。请针对屏幕上他正在做的这一步出一手：")
        appendLine("问题必须落在屏幕上的具体内容（这道题、这个选项、这段材料），并尽量和断点接上。")
        appendLine("如果屏幕上的内容和学习任务无关，就用一句话把他拉回任务，再出问题。")
        appendLine()
        appendTask(task)
        appendBreakpoint(breakpoint)
        appendContext(context)
        appendLine("【屏幕描述】")
        appendLine(screen)
        appendLine()
        appendLine("只返回 JSON，格式如下：")
        append(Prompts.OBSERVE_FORMAT)
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
        c.forcedFace?.let { Faces.of(it) }?.let { f ->
            appendLine("【掷骰】掷出了 ${f.number}（${f.name}）。这一手必须练这一面：最小动作是“${f.action}”，face 填 ${f.number}。")
        }
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
        return OpeningMove(move, stuck, obj.starters(), obj.face("face"))
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
            starters = obj.starters(),
            nextFace = obj.face("next_face"),
        )
    }

    fun parseObserved(content: String): ObservedMove {
        val obj = extractJsonObject(content)
        return ObservedMove(obj.optText("observation"), obj.requireText("coach_move"), obj.starters(), obj.face("face"))
    }

    /** 骰子面：1–6，缺了或不对时为 0。 */
    private fun JSONObject.face(key: String): Int {
        val v = optInt(key, 0)
        return if (v in 1..6) v else 0
    }

    /** 起手式：最多两个，去空、去重、截短。缺了不算错。 */
    private fun JSONObject.starters(): List<String> {
        val arr = optJSONArray("starters") ?: return emptyList()
        return (0 until arr.length())
            .map { arr.optString(it).trim() }
            .filter { it.isNotEmpty() && it != "null" }
            .distinct()
            .take(2)
            .map { it.take(24) }
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
