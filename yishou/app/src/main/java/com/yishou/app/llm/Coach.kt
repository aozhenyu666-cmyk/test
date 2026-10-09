package com.yishou.app.llm

import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Task
import org.json.JSONException
import org.json.JSONObject

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

/** 陪练。抽成接口，方便测试时换成假的。 */
interface Coach {
    suspend fun opening(task: Task, breakpoint: Breakpoint): LlmResult<OpeningMove>
    suspend fun judge(task: Task, breakpoint: Breakpoint, coachMove: String, answer: String): LlmResult<Judgement>
}

/**
 * 用大模型扮演陪练。
 * 所有请求都要求只返回 JSON；返回内容解析失败时重试一次，仍失败就把错误交给调用方按离线处理。
 * 网络错误、超时、HTTP 错误不重试，直接返回，避免界面等太久。
 */
class LlmCoach(private val chat: ChatClient) : Coach {

    override suspend fun opening(task: Task, breakpoint: Breakpoint): LlmResult<OpeningMove> =
        requestJson(CoachMessages.opening(task, breakpoint), CoachParser::parseOpening)

    override suspend fun judge(
        task: Task,
        breakpoint: Breakpoint,
        coachMove: String,
        answer: String,
    ): LlmResult<Judgement> =
        requestJson(CoachMessages.judge(task, breakpoint, coachMove, answer), CoachParser::parseJudgement)

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

    fun opening(task: Task, breakpoint: Breakpoint): String = buildString {
        appendLine("请求类型：开局（这是本任务的第一手，或断点刚被手动修改过）。")
        appendLine("请根据下面的任务和断点出一手。")
        appendLine()
        appendTask(task)
        appendBreakpoint(breakpoint)
        appendLine()
        appendLine("只返回 JSON，格式如下：")
        append(Prompts.OPENING_FORMAT)
    }

    fun judge(task: Task, breakpoint: Breakpoint, coachMove: String, answer: String): String = buildString {
        appendLine("请求类型：判定并出下一手。")
        appendLine("请判定用户这一手是否有效，更新断点，并出下一手。")
        appendLine()
        appendTask(task)
        appendBreakpoint(breakpoint)
        appendLine("【本轮陪练的一手】")
        appendLine(coachMove)
        appendLine("【用户回答原话】")
        appendLine(answer)
        appendLine()
        appendLine("只返回 JSON，格式如下（effective 为 true 或 false；无效时 move_type 填“无”）：")
        append(Prompts.JUDGE_FORMAT)
    }

    private fun StringBuilder.appendTask(task: Task) {
        appendLine("【任务】")
        appendLine("对象：${task.title}")
        appendLine("目标：${task.goal}")
        appendLine("材料摘录：${task.material?.takeIf { it.isNotBlank() } ?: "（无）"}")
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
