package com.behaviordept.app.training

import com.behaviordept.app.data.Drill
import com.behaviordept.app.data.Event
import com.behaviordept.app.data.ExamSection
import com.behaviordept.app.data.ExamSitting
import com.behaviordept.app.data.MatchLog
import com.behaviordept.app.data.MatchResult
import com.behaviordept.app.data.Skill
import com.behaviordept.app.data.SubSkill
import com.behaviordept.app.data.Template
import java.time.LocalDate

/*
 * 训练引擎的纯逻辑：诊断短板、锁定重点、判断复测。不碰数据库和界面，单元测试直接覆盖。
 * 引擎对所有模板一样：标准 → 诊断 → 专项练 → 反馈 → 实战复测。各模板只是“诊断数据从哪来”不同：
 *   对抗竞技：对局速记里的死因；考试：模考里各模块丢的题；动作技能：实战复测时对照标准的达标比例。
 */

/** 某个类别的占比。 */
data class Share(val name: String, val count: Int, val share: Double)

object Diagnosis {
    /** 累计这么多次阵亡后才给诊断（PRD M3）。 */
    const val MIN_DEATHS = 20

    /** 诊断看最近这么多次阵亡。 */
    const val DEATH_WINDOW = 50

    /** 考试诊断看最近几次考试。 */
    const val SITTING_WINDOW = 3

    /** 最近 window 次阵亡里各死因的占比，按 categories 的顺序返回（没出现的类别占比为 0）。 */
    fun causeShares(matches: List<MatchLog>, categories: List<String>, window: Int = DEATH_WINDOW): List<Share> {
        val deaths = matches.filter { it.result == MatchResult.DIED && it.causeCategory != null }
            .sortedByDescending { it.time }
            .take(window)
        return sharesOf(deaths.map { it.causeCategory!! }, categories)
    }

    fun deathCount(matches: List<MatchLog>): Int = matches.count { it.result == MatchResult.DIED }

    /** 最近几次考试里各模块丢题的占比。 */
    fun examLostShares(
        sittings: List<ExamSitting>,
        sections: List<ExamSection>,
        modules: List<String>,
        window: Int = SITTING_WINDOW,
    ): List<Share> {
        val recent = sittings.sortedByDescending { it.time }.take(window).map { it.id }.toSet()
        val lost = sections.filter { it.sittingId in recent }
        val counts = modules.associateWith { m -> lost.filter { it.module == m }.sumOf { it.lost } }
        val total = counts.values.sum()
        return modules.map { m -> Share(m, counts[m] ?: 0, if (total == 0) 0.0 else (counts[m] ?: 0).toDouble() / total) }
    }

    private fun sharesOf(items: List<String>, categories: List<String>): List<Share> {
        val total = items.size
        return categories.map { c ->
            val n = items.count { it == c }
            Share(c, n, if (total == 0) 0.0 else n.toDouble() / total)
        }
    }

    /** 占比最高的那一类就是建议的当前重点；数据不够时返回 null。 */
    fun suggestion(shares: List<Share>): Share? = shares.filter { it.count > 0 }.maxByOrNull { it.share }

    /** 对抗竞技：是否已经有足够数据给诊断。 */
    fun competitiveReady(matches: List<MatchLog>): Boolean = deathCount(matches) >= MIN_DEATHS

    /** 某模块在若干次考试里的正确率（0–1）。 */
    fun moduleAccuracy(sections: List<ExamSection>, module: String, sittingIds: Set<Long>): Double? {
        val s = sections.filter { it.module == module && it.sittingId in sittingIds && it.total > 0 }
        val total = s.sumOf { it.total }
        return if (total == 0) null else s.sumOf { it.correct }.toDouble() / total
    }

    /** 一次考试的总正确率（0–1）。 */
    fun sittingAccuracy(sections: List<ExamSection>, sittingId: Long): Double? {
        val s = sections.filter { it.sittingId == sittingId && it.total > 0 }
        val total = s.sumOf { it.total }
        return if (total == 0) null else s.sumOf { it.correct }.toDouble() / total
    }
}

object FocusRules {
    /** 重点锁定天数：复测通过之前不换（设计原则 5）。 */
    const val LOCK_DAYS = 7

    /** 复测前至少要练过的天数。 */
    const val MIN_DRILL_DAYS = 3

    private const val DAY = 24 * 60 * 60 * 1000L

    fun lockedUntil(skill: Skill): Long? = skill.focusSince?.let { it + LOCK_DAYS * DAY }

    /** 没有重点，或者锁定期已过，才能换重点。 */
    fun canChange(skill: Skill, now: Long): Boolean {
        val until = lockedUntil(skill) ?: return true
        return skill.focusSubSkillId == null || now >= until
    }

    /** 重点设定以来，练过这个重点的不同日期。 */
    fun drillDates(focusSince: Long?, drillIds: Set<Long>, drillEvents: List<Event>, dateOf: (Long) -> LocalDate): Set<LocalDate> {
        if (focusSince == null) return emptySet()
        return drillEvents.filter { it.refId in drillIds && it.time >= focusSince }.map { dateOf(it.time) }.toSet()
    }
}

/** 实战复测的进度。 */
sealed interface RetestState {
    /** 还在 7 天锁定期里，或者练得不够。 */
    data object NotDue : RetestState

    /** 该复测了，正在攒实战数据。 */
    data class Collecting(val have: Int, val need: Int) : RetestState

    /**
     * 复测有结果了：before / after 是同一个指标（死因占比、模块正确率或达标比例，0–1）。
     * lowerIsBetter：死因占比越低越好；正确率和达标比例越高越好。
     */
    data class Ready(val before: Double?, val after: Double, val lowerIsBetter: Boolean) : RetestState {
        val improved: Boolean
            get() = before == null || (if (lowerIsBetter) after < before else after > before)
    }
}

/** 申论按分数算，不按题量。 */
const val ESSAY = "申论"

object Retest {
    /** 对抗竞技：复测期至少再登记这么多次阵亡。 */
    const val NEED_DEATHS = 10

    /** 复测前用来对比的阵亡次数。 */
    const val BASELINE_DEATHS = 20

    private const val DAY = 24 * 60 * 60 * 1000L

    private fun retestStart(skill: Skill): Long? = skill.focusSince?.let { it + FocusRules.LOCK_DAYS * DAY }

    private fun due(skill: Skill, drillDays: Int, now: Long): Boolean {
        val start = retestStart(skill) ?: return false
        return skill.focusSubSkillId != null && now >= start && drillDays >= FocusRules.MIN_DRILL_DAYS
    }

    fun competitive(skill: Skill, focusName: String, drillDays: Int, matches: List<MatchLog>, now: Long): RetestState {
        if (!due(skill, drillDays, now)) return RetestState.NotDue
        val start = retestStart(skill)!!
        val since = skill.focusSince!!
        val deaths = matches.filter { it.result == MatchResult.DIED && it.causeCategory != null }
        val after = deaths.filter { it.time >= start }
        if (after.size < NEED_DEATHS) return RetestState.Collecting(after.size, NEED_DEATHS)
        val before = deaths.filter { it.time < since }.sortedByDescending { it.time }.take(BASELINE_DEATHS)
        fun share(list: List<MatchLog>) = list.count { it.causeCategory == focusName }.toDouble() / list.size
        return RetestState.Ready(if (before.isEmpty()) null else share(before), share(after), lowerIsBetter = true)
    }

    fun exam(
        skill: Skill,
        focusModule: String,
        drillDays: Int,
        sittings: List<ExamSitting>,
        sections: List<ExamSection>,
        now: Long,
    ): RetestState {
        if (!due(skill, drillDays, now)) return RetestState.NotDue
        val start = retestStart(skill)!!
        val since = skill.focusSince!!
        if (focusModule == ESSAY) {
            // 申论没有题量，用申论分数（满分 100）比较。
            val afterScore = sittings.filter { it.time >= start && it.essayScore != null }.maxByOrNull { it.time }?.essayScore
                ?: return RetestState.Collecting(0, 1)
            val beforeScore = sittings.filter { it.time < since && it.essayScore != null }.maxByOrNull { it.time }?.essayScore
            return RetestState.Ready(beforeScore?.div(100), afterScore / 100, lowerIsBetter = false)
        }
        val after = sittings.filter { it.time >= start }.map { it.id }.toSet()
        val afterAcc = Diagnosis.moduleAccuracy(sections, focusModule, after)
            ?: return RetestState.Collecting(0, 1)
        val before = sittings.filter { it.time < since }.sortedByDescending { it.time }.take(2).map { it.id }.toSet()
        return RetestState.Ready(Diagnosis.moduleAccuracy(sections, focusModule, before), afterAcc, lowerIsBetter = false)
    }

    /** 动作技能：RETEST_DONE 事件的 value 是达标比例（%）。 */
    fun motor(skill: Skill, drillDays: Int, retests: List<Event>, now: Long): RetestState {
        if (!due(skill, drillDays, now)) return RetestState.NotDue
        val start = retestStart(skill)!!
        val after = retests.filter { it.time >= start }.maxByOrNull { it.time }
            ?: return RetestState.Collecting(0, 1)
        val before = retests.filter { it.time < start }.maxByOrNull { it.time }
        return RetestState.Ready(before?.value?.div(100), (after.value ?: 0.0) / 100, lowerIsBetter = false)
    }
}

/** 一个技能当下的训练计划，“今日一件事”据此挑专项练和复测。 */
data class SkillPlan(
    val skill: Skill,
    val focus: SubSkill?,
    val drills: List<Drill>,
    /** 下一个该练的练习：最久没练的那个。 */
    val nextDrill: Drill?,
    val drilledToday: Boolean,
    val drillDates: Set<LocalDate>,
    val retest: RetestState,
) {
    val templateLabel: String get() = Template.label(skill.template)
}

/** 从一组练习里挑最久没练的。 */
fun pickNextDrill(drills: List<Drill>, drillEvents: List<Event>): Drill? =
    drills.minByOrNull { d -> drillEvents.filter { it.refId == d.id }.maxOfOrNull { it.time } ?: Long.MIN_VALUE }

/** 练习记录的当场反馈：和以往的数字比。 */
data class DrillFeedback(val current: Double?, val previous: Double?, val best: Double?, val history: List<Double>, val lowerIsBetter: Boolean) {
    val isBest: Boolean
        get() = current != null && history.size > 1 && current == best
}

/** events 里已经包含刚记下的这一次；按时间排，最后一个就是这次。 */
fun drillFeedback(drill: Drill, events: List<Event>): DrillFeedback {
    val values = events.filter { it.refId == drill.id }.sortedBy { it.time }.mapNotNull { it.value }
    val best = if (values.isEmpty()) null else if (drill.lowerIsBetter) values.min() else values.max()
    return DrillFeedback(
        current = values.lastOrNull(),
        previous = values.dropLast(1).lastOrNull(),
        best = best,
        history = values.takeLast(12),
        lowerIsBetter = drill.lowerIsBetter,
    )
}
