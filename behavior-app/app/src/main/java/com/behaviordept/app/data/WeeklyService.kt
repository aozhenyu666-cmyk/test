package com.behaviordept.app.data

import com.behaviordept.app.record.Metrics
import com.behaviordept.app.training.Diagnosis
import com.behaviordept.app.training.ESSAY
import com.behaviordept.app.util.Time
import java.time.LocalDate
import kotlin.math.roundToInt

/** 一周的数据摘要。每个数字都来自日志（PRD M6 验收）。 */
data class WeekSummary(
    val weekStart: LocalDate,
    val text: String,
    val trainedDays: Int,
    val trainingMinutes: Int,
    val fiddleMinutes: Int,
)

/** 周复盘：先用数据写摘要，再交给旗舰模型分析“断在哪、下周唯一重点”。 */
class WeeklyService(private val db: AppDatabase, private val events: EventLog) {
    fun observeAll() = db.weeklyDao().observeAll()

    suspend fun saved(weekStart: LocalDate): WeeklyReview? = db.weeklyDao().get(weekStart.toString())

    suspend fun save(weekStart: LocalDate, summary: String, analysis: String, focus: String) {
        db.weeklyDao().put(WeeklyReview(weekStart.toString(), summary, analysis, focus.trim()))
        events.log(EventType.WEEKLY_REVIEW, value = null, note = "下周重点：${focus.trim()}")
    }

    suspend fun summarize(weekStart: LocalDate, now: Long = Time.now()): WeekSummary {
        val from = Time.startOf(weekStart)
        val to = minOf(Time.startOf(weekStart.plusDays(7)), now)
        val days = (0L..6L).map { weekStart.plusDays(it) }.filter { Time.startOf(it) < to }
        val ev = db.eventDao().since(from).filter { it.time < to }

        val trained = Metrics.trainedDates(ev, Time::dateOf)
        val trainMin = Metrics.minutesOf(ev, EventType.SESSION)
        val fiddleMin = Metrics.minutesOf(ev, EventType.SETTINGS_TIME)

        val reviews = db.studyDao().allReviews()
        val todayStart = Time.startOf(Time.today())
        val overdue = db.studyDao().allUnits().count { it.nextReviewAt != null && it.nextReviewAt < todayStart && it.nextReviewAt >= from }
        val (onTime, due) = Metrics.reviewOnTime(reviews, from, to, overdue, Time::dateOf)
        val newUnits = ev.count { it.type == EventType.UNIT_CREATED }

        val text = buildString {
            appendLine("# 周复盘 ${Time.md(weekStart)}–${Time.md(weekStart.plusDays(6))}")
            appendLine()
            appendLine("## 训练")
            appendLine("- 练了 ${trained.size}/${days.size} 天（目标每周 ≥ 5 天），专注共 $trainMin 分钟")
            val ratio = if (fiddleMin == 0) "0" else if (trainMin == 0) "∞" else "1 : ${"%.1f".format(trainMin.toDouble() / fiddleMin)}"
            appendLine("- 折腾系统 $fiddleMin 分钟，折腾 : 训练 = $ratio（目标 ≤ 1 : 4）")
            appendLine()
            appendLine("## 学习")
            appendLine("- 间隔自测按时 $onTime/$due" + (Metrics.percent(onTime, due)?.let { "（$it%，目标 ≥ 70%）" } ?: ""))
            appendLine("- 新建学习单元 $newUnits 个")

            db.skillDao().skills().filter { it.template != Template.LEARNING }.forEach { skill ->
                val subs = db.skillDao().subSkills(skill.id)
                val focus = subs.firstOrNull { it.id == skill.focusSubSkillId }
                appendLine()
                append("## ${skill.name}")
                if (focus != null && skill.focusSince != null) {
                    val d = (Time.today().toEpochDay() - Time.dateOf(skill.focusSince).toEpochDay()) + 1
                    append("（当前重点：${focus.name}，第 $d 天）")
                } else {
                    append("（还没有当前重点）")
                }
                appendLine()
                val drillIds = subs.flatMap { db.skillDao().activeDrills(it.id) }.associateBy { it.id }
                val drillEv = ev.filter { it.type == EventType.DRILL_DONE && it.refId in drillIds }
                if (drillEv.isNotEmpty()) {
                    appendLine("- 专项练 ${drillEv.size} 次：" + drillEv.groupBy { it.refId }.entries.joinToString("；") { (id, list) ->
                        val d = drillIds[id]!!
                        val vals = list.sortedBy { it.time }.mapNotNull { it.value?.let { v -> fmt(v) } }
                        d.title + if (vals.isNotEmpty()) "（${d.metric}：${vals.joinToString(" → ")}）" else ""
                    })
                } else {
                    appendLine("- 本周没有专项练")
                }
                when (skill.template) {
                    Template.COMPETITIVE -> {
                        val matches = db.skillDao().matchesOf(skill.id).filter { it.time in from until to }
                        val deaths = matches.filter { it.result == MatchResult.DIED }
                        appendLine("- 登记 ${matches.size} 局，撤离 ${matches.size - deaths.size}，阵亡 ${deaths.size}")
                        if (deaths.isNotEmpty()) {
                            val shares = Diagnosis.causeShares(deaths, subs.map { it.name }, window = deaths.size)
                            appendLine("- 死因：" + shares.filter { it.count > 0 }.sortedByDescending { it.count }
                                .joinToString("、") { "${it.name} ${it.count}" })
                        }
                    }
                    Template.EXAM -> {
                        val sittings = db.skillDao().sittingsOf(skill.id).filter { it.time in from until to }
                        val sections = db.skillDao().sectionsOf(skill.id)
                        if (sittings.isEmpty()) appendLine("- 本周没有登记考试")
                        sittings.sortedBy { it.time }.forEach { st ->
                            val acc = Diagnosis.sittingAccuracy(sections, st.id)
                            val parts = sections.filter { it.sittingId == st.id }.joinToString("、") { "${it.module} ${it.correct}/${it.total}" }
                            appendLine("- ${ExamKind.label(st.kind)} ${Time.md(st.time)}：总正确率 ${acc?.let { "${(it * 100).roundToInt()}%" } ?: "—"}" +
                                (st.score?.let { "，行测 ${fmt(it)}" } ?: "") + (st.essayScore?.let { "，$ESSAY ${fmt(it)}" } ?: "") +
                                if (parts.isNotEmpty()) "；$parts" else "")
                        }
                    }
                    else -> {
                        val retests = ev.filter { it.type == EventType.RETEST_DONE && subs.any { s -> s.id == it.refId } }
                        retests.forEach { appendLine("- 实战复测：${it.note}") }
                    }
                }
            }

            appendLine()
            appendLine("## 防线")
            val rules = db.guardDao().rules()
            val usage = db.guardDao().usageSince(weekStart.toString())
            val past = days.filter { it.isBefore(Time.today()) }
            val (kept, counted) = Metrics.usageKept(usage, rules, past)
            if (counted == 0) {
                appendLine("- 没有用时数据（需要开启使用情况访问权限）")
            } else {
                appendLine("- 守住上限 $kept/$counted 天（目标 ≥ 80%）")
                rules.forEach { r ->
                    val daily = Metrics.ruleDaily(usage, r, past).map { it.second }
                    appendLine("- ${r.name.ifBlank { "规则" }}：上限 ${r.dailyLimitMin} 分钟，日均 ${daily.average().roundToInt()} 分钟，最多 ${daily.maxOrNull() ?: 0} 分钟")
                }
            }
            val urges = db.guardDao().urgesSince(from).filter { it.time < to }
            if (urges.isNotEmpty()) {
                appendLine("- 点了“我想刷” ${urges.size} 次，其中 ${urges.count { it.startedTraining }} 次转去训练；原因：" +
                    urges.groupBy { it.reason }.entries.joinToString("、") { "${it.key} ${it.value.size}" })
            }
        }
        return WeekSummary(weekStart, text.trim(), trained.size, trainMin, fiddleMin)
    }

    private fun fmt(v: Double): String = if (v == v.roundToInt().toDouble()) v.roundToInt().toString() else "%.1f".format(v)
}
