package com.yishou.app.summary

import com.yishou.app.data.AppDao
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.DailySummary
import com.yishou.app.data.Round
import com.yishou.app.data.Task
import com.yishou.app.llm.Coach
import com.yishou.app.llm.CoachMessages
import com.yishou.app.llm.LlmError
import com.yishou.app.llm.LlmResult
import com.yishou.app.settings.AppPrefs
import com.yishou.app.window.WindowClock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * 每晚总结：读当天全部 Round 和当前断点 → 请求总结 → 存 DailySummary，
 * 并把“明天第一问”写进断点，作为第二天第一轮陪练的那一手。
 */
class SummaryEngine(
    private val dao: AppDao,
    private val coach: Coach,
    private val prefs: () -> AppPrefs,
    private val clock: () -> Long = System::currentTimeMillis,
    private val zone: () -> ZoneId = ZoneId::systemDefault,
) {

    sealed interface Outcome {
        data class Saved(val summary: DailySummary) : Outcome
        /** 当天没有任何轮次，不发请求，只记一条“当日无记录” */
        data class NoRecord(val summary: DailySummary) : Outcome
        data class Failed(val error: LlmError) : Outcome
    }

    suspend fun run(date: LocalDate): Outcome {
        val z = zone()
        val now = clock()
        val p = prefs()
        val rounds = dao.roundsBetween(WindowClock.startOfDay(date, z), WindowClock.startOfDay(date.plusDays(1), z))
        val windowMinutes = WindowClock.windowMinutesOn(date, p, z, now)
        val dateText = date.toString()

        if (rounds.isEmpty()) {
            val s = DailySummary(dateText, "", "", NO_RECORD, 0, 0, windowMinutes)
            dao.upsertSummary(s)
            return Outcome.NoRecord(s)
        }

        val task = dao.getCurrentTask()
        val bp = task?.let { dao.getBreakpoint(it.id) }
        val result = when (val r = coach.summary(task, bp, rounds)) {
            is LlmResult.Err -> return Outcome.Failed(r.error)
            is LlmResult.Ok -> r.value
        }
        val summary = DailySummary(
            date = dateText,
            rule = result.rule,
            tomorrowQuestion = result.tomorrowQuestion,
            note = result.note,
            roundCount = rounds.size,
            effectiveCount = rounds.count { it.effective },
            windowMinutes = windowMinutes,
        )
        // 明天第一问既是断点的“下一问”，也是第二天第一轮陪练直接出的那一手
        val newBp = task?.let {
            (bp ?: Breakpoint(it.id, "", "", "", null, now)).copy(
                nextQuestion = result.tomorrowQuestion,
                pendingCoachMove = result.tomorrowQuestion,
                updatedAt = now,
            )
        }
        dao.saveSummary(summary, newBp)
        return Outcome.Saved(summary)
    }

    companion object {
        const val NO_RECORD = "当日无记录"
    }
}

/** 导出给 GPT、Gemini、WorkBuddy 的 Markdown。 */
object SummaryMarkdown {

    fun build(
        summary: DailySummary,
        task: Task?,
        breakpoint: Breakpoint?,
        rounds: List<Round>,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = buildString {
        appendLine("# 一手 · ${summary.date} 学习记录")
        appendLine()
        if (task != null) {
            appendLine("## 任务")
            appendLine("- 对象：${task.title}")
            appendLine("- 目标：${task.goal}")
            task.material?.takeIf { it.isNotBlank() }?.let { appendLine("- 材料摘录：$it") }
            appendLine()
        }
        if (breakpoint != null) {
            appendLine("## 断点")
            appendLine("- 已知：${breakpoint.known.ifBlank { "（空）" }}")
            appendLine("- 卡点：${breakpoint.stuck.ifBlank { "（空）" }}")
            appendLine("- 下一问：${breakpoint.nextQuestion.ifBlank { "（空）" }}")
            appendLine()
        }
        appendLine("## 今天的规则")
        appendLine(summary.rule.ifBlank { "（证据不足，今天没有总结出规则）" })
        appendLine()
        if (summary.tomorrowQuestion.isNotBlank()) {
            appendLine("## 明天第一问")
            appendLine(summary.tomorrowQuestion)
            appendLine()
        }
        if (summary.note.isNotBlank()) {
            appendLine("## 今天推进到哪里")
            appendLine(summary.note)
            appendLine()
        }
        appendLine("## 当天原话（${summary.roundCount} 轮，有效 ${summary.effectiveCount} 轮，陪练窗口 ${summary.windowMinutes} 分钟）")
        val time = DateTimeFormatter.ofPattern("HH:mm").withZone(zone)
        rounds.forEachIndexed { i, r ->
            val verdict = when {
                !r.judged -> "未判定"
                r.effective -> "有效 · ${r.moveType}"
                else -> "无效"
            }
            appendLine()
            appendLine("### ${i + 1}. ${time.format(Instant.ofEpochMilli(r.createdAt))} · ${CoachMessages.sourceName(r.source)} · $verdict")
            appendLine("- 陪练：${r.coachMove}")
            appendLine("- 我：${r.userAnswer}")
            if (r.feedback.isNotBlank()) appendLine("- 反馈：${r.feedback}")
            if (r.reason.isNotBlank()) appendLine("- 理由：${r.reason}")
        }
    }.trimEnd() + "\n"
}
