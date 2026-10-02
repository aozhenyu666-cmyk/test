package com.behaviordept.app.training

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.ai.AiException
import com.behaviordept.app.ai.ProposedDrill
import com.behaviordept.app.data.Drill
import com.behaviordept.app.data.Event
import com.behaviordept.app.data.EventType
import com.behaviordept.app.data.ExamSection
import com.behaviordept.app.data.ExamSitting
import com.behaviordept.app.data.MatchLog
import com.behaviordept.app.data.MatchResult
import com.behaviordept.app.data.Skill
import com.behaviordept.app.data.SubSkill
import com.behaviordept.app.data.Template
import com.behaviordept.app.util.Time
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/** 趋势图上的一个点。 */
data class TrendPoint(val label: String, val value: Double)

data class SkillDetail(
    val skill: Skill,
    val subs: List<SubSkill>,
    val plan: SkillPlan?,
    val shares: List<Share>,
    val deaths: Int,
    val matches: List<MatchLog>,
    val sittings: List<ExamSitting>,
    val sections: List<ExamSection>,
    val drillEvents: List<Event>,
    val trend: List<TrendPoint>,
    val trendLabel: String,
) {
    val focus: SubSkill? get() = plan?.focus
    val suggestion: Share? get() = Diagnosis.suggestion(shares)
    val diagnosisReady: Boolean
        get() = when (skill.template) {
            Template.COMPETITIVE -> deaths >= Diagnosis.MIN_DEATHS
            Template.EXAM -> sittings.isNotEmpty()
            else -> false
        }
}

@OptIn(ExperimentalCoroutinesApi::class)
class SkillViewModel(private val c: AppContainer, private val skillId: Long) : ViewModel() {
    val state: StateFlow<SkillDetail?> = combine(
        c.training.observeSkill(skillId),
        c.training.observeSubSkills(skillId),
        c.training.observeMatches(skillId),
        c.training.observeSittings(skillId),
        c.training.observeSections(skillId),
    ) { skill, subs, matches, sittings, sections -> Quint(skill, subs, matches, sittings, sections) }
        .combine(c.training.observeDrillEvents()) { q, events -> q to events }
        .combine(c.training.observeAllActiveDrills()) { pair, _ -> pair }
        .mapLatest { (q, events) ->
            val skill = q.skill ?: return@mapLatest null
            build(skill, q.subs, q.matches, q.sittings, q.sections, events)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private data class Quint(
        val skill: Skill?,
        val subs: List<SubSkill>,
        val matches: List<MatchLog>,
        val sittings: List<ExamSitting>,
        val sections: List<ExamSection>,
    )

    private suspend fun build(
        skill: Skill,
        subs: List<SubSkill>,
        matches: List<MatchLog>,
        sittings: List<ExamSitting>,
        sections: List<ExamSection>,
        events: List<Event>,
    ): SkillDetail {
        val plan = c.training.plans().firstOrNull { it.skill.id == skill.id }
        val names = subs.map { it.name }
        val shares = when (skill.template) {
            Template.COMPETITIVE -> Diagnosis.causeShares(matches, names)
            Template.EXAM -> Diagnosis.examLostShares(sittings, sections, names.filter { it != ESSAY })
            else -> emptyList()
        }
        val focus = plan?.focus
        val (trend, label) = when {
            focus == null -> emptyList<TrendPoint>() to ""
            skill.template == Template.COMPETITIVE -> weeklyShare(matches, focus.name) to "「${focus.name}」死因占比 · 每周"
            skill.template == Template.EXAM -> sittingTrend(sittings, sections, focus.name) to "「${focus.name}」${if (focus.name == ESSAY) "得分" else "正确率"} · 每次考试"
            else -> events.filter { it.type == EventType.RETEST_DONE && it.refId == focus.id }.sortedBy { it.time }.takeLast(8)
                .map { TrendPoint(Time.md(it.time), (it.value ?: 0.0) / 100) } to "「${focus.name}」实战达标比例"
        }
        return SkillDetail(
            skill = skill,
            subs = subs,
            plan = plan,
            shares = shares,
            deaths = Diagnosis.deathCount(matches),
            matches = matches,
            sittings = sittings,
            sections = sections,
            drillEvents = events.filter { it.type == EventType.DRILL_DONE },
            trend = trend,
            trendLabel = label,
        )
    }

    private fun weeklyShare(matches: List<MatchLog>, focus: String): List<TrendPoint> {
        val thisWeek = Time.weekStart()
        return (5 downTo 0).mapNotNull { back ->
            val ws = thisWeek.minusWeeks(back.toLong())
            val from = Time.startOf(ws)
            val to = Time.startOf(ws.plusDays(7))
            val deaths = matches.filter { it.result == MatchResult.DIED && it.causeCategory != null && it.time in from until to }
            if (deaths.isEmpty()) null else TrendPoint(Time.md(ws), deaths.count { it.causeCategory == focus }.toDouble() / deaths.size)
        }
    }

    private fun sittingTrend(sittings: List<ExamSitting>, sections: List<ExamSection>, focus: String): List<TrendPoint> =
        sittings.sortedBy { it.time }.takeLast(8).mapNotNull { st ->
            val v = if (focus == ESSAY) st.essayScore?.div(100) else Diagnosis.moduleAccuracy(sections, focus, setOf(st.id))
            v?.let { TrendPoint(Time.md(st.time), it) }
        }

    // —— 操作 ——
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var proposals by mutableStateOf<List<ProposedDrill>>(emptyList())
        private set
    var standardProposals by mutableStateOf<List<Pair<String, List<String>>>>(emptyList())
        private set

    fun clearMessage() {
        message = null
    }

    fun setFocus(sub: SubSkill) {
        val skill = state.value?.skill ?: return
        viewModelScope.launch {
            val ok = c.training.setFocus(skill, sub)
            message = if (ok) "当前重点改为「${sub.name}」，锁定 7 天" else {
                val until = FocusRules.lockedUntil(skill)?.let { Time.md(it) } ?: ""
                "当前重点锁定到 $until。一次只练一个短板，复测之前不换"
            }
        }
    }

    fun continueFocus() {
        val skill = state.value?.skill ?: return
        viewModelScope.launch {
            c.training.continueFocus(skill)
            message = "继续练这一项，重新计 7 天"
        }
    }

    fun passFocus() {
        val skill = state.value?.skill ?: return
        viewModelScope.launch {
            c.training.passFocus(skill)
            message = "这一项先放下。按最新诊断选下一个短板"
        }
    }

    fun proposeDrills() {
        val d = state.value ?: return
        val focus = d.focus ?: return
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                val existing = d.plan?.drills.orEmpty().map { it.title }
                val data = buildString {
                    if (d.shares.isNotEmpty()) appendLine("诊断：" + d.shares.joinToString("、") { "${it.name} ${(it.share * 100).roundToInt()}%" })
                    d.plan?.drills.orEmpty().forEach { dr ->
                        val vals = d.drillEvents.filter { it.refId == dr.id }.sortedBy { it.time }.mapNotNull { it.value }.takeLast(5)
                        if (vals.isNotEmpty()) appendLine("${dr.title}（${dr.metric}）最近：${vals.joinToString(" → ") { v -> v.roundToInt().toString() }}")
                    }
                }
                proposals = c.coach.proposeDrills(d.skill.name, focus.name, focus.standard.lines(), existing, data)
            } catch (e: AiException) {
                message = e.message
            } catch (e: Exception) {
                message = "出错了：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun acceptProposal(p: ProposedDrill) {
        val focus = state.value?.focus ?: return
        proposals = proposals - p
        viewModelScope.launch { c.training.addDrill(focus, p.title, p.method, p.minutes, p.metric, "ai") }
    }

    fun rejectProposal(p: ProposedDrill) {
        proposals = proposals - p
    }

    fun addDrill(sub: SubSkill, title: String, method: String, minutes: Int, metric: String) {
        viewModelScope.launch { c.training.addDrill(sub, title, method, minutes, metric, "manual") }
    }

    fun removeDrill(drill: Drill) {
        viewModelScope.launch { c.training.removeDrill(drill) }
    }

    fun saveStandard(sub: SubSkill, text: String) {
        viewModelScope.launch { c.training.updateStandard(sub, text) }
    }

    fun regenerateStandards(goal: String) {
        val d = state.value ?: return
        if (busy) return
        busy = true
        message = null
        viewModelScope.launch {
            try {
                standardProposals = c.coach.decomposeSkill(d.skill.name, Template.label(d.skill.template), d.subs.map { it.name }, goal)
            } catch (e: AiException) {
                message = e.message
            } catch (e: Exception) {
                message = "出错了：${e.message}"
            } finally {
                busy = false
            }
        }
    }

    fun applyStandards() {
        val list = standardProposals
        standardProposals = emptyList()
        viewModelScope.launch {
            c.training.replaceStandards(skillId, list)
            message = "标准已更新"
        }
    }

    fun discardStandards() {
        standardProposals = emptyList()
    }

    fun motorRetest(sub: SubSkill, met: Int, total: Int, note: String) {
        viewModelScope.launch {
            c.training.logMotorRetest(sub, met, total, note)
            message = "复测已记录：达标 $met/$total"
        }
    }

    fun deleteMatch(m: MatchLog) {
        viewModelScope.launch { c.training.deleteMatch(m.id) }
    }

    fun deleteSitting(s: ExamSitting) {
        viewModelScope.launch { c.training.deleteSitting(s.id) }
    }

    fun deleteSkill(done: () -> Unit) {
        viewModelScope.launch {
            c.training.deleteSkill(skillId)
            done()
        }
    }
}
