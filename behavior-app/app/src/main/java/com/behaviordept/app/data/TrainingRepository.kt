package com.behaviordept.app.data

import com.behaviordept.app.training.Diagnosis
import com.behaviordept.app.training.FocusRules
import com.behaviordept.app.training.Retest
import com.behaviordept.app.training.RetestState
import com.behaviordept.app.training.SeedSkill
import com.behaviordept.app.training.Seeds
import com.behaviordept.app.training.SkillPlan
import com.behaviordept.app.training.pickNextDrill
import com.behaviordept.app.util.Time
import kotlinx.coroutines.flow.Flow

/** 技能、子能力、练习、对局、考试的读写，以及每个技能当下的训练计划。 */
class TrainingRepository(
    private val dao: SkillDao,
    private val eventDao: EventDao,
    private val events: EventLog,
) {
    fun observeSkills(): Flow<List<Skill>> = dao.observeSkills()
    fun observeSkill(id: Long): Flow<Skill?> = dao.observeSkill(id)
    fun observeSubSkills(skillId: Long): Flow<List<SubSkill>> = dao.observeSubSkills(skillId)
    fun observeAllSubSkills(): Flow<List<SubSkill>> = dao.observeAllSubSkills()
    fun observeActiveDrills(subSkillId: Long): Flow<List<Drill>> = dao.observeActiveDrills(subSkillId)
    fun observeAllActiveDrills(): Flow<List<Drill>> = dao.observeAllActiveDrills()
    fun observeMatches(skillId: Long): Flow<List<MatchLog>> = dao.observeMatches(skillId)
    fun observeAllMatches(): Flow<List<MatchLog>> = dao.observeAllMatches()
    fun observeSittings(skillId: Long): Flow<List<ExamSitting>> = dao.observeSittings(skillId)
    fun observeSections(skillId: Long): Flow<List<ExamSection>> = dao.observeSections(skillId)
    fun observeDrillEvents(): Flow<List<Event>> = eventDao.observeRecent(5000)

    suspend fun skills() = dao.skills()
    suspend fun skill(id: Long) = dao.skill(id)
    suspend fun subSkills(skillId: Long) = dao.subSkills(skillId)
    suspend fun subSkill(id: Long) = dao.subSkill(id)
    suspend fun drill(id: Long) = dao.drill(id)
    suspend fun drillEvents(drillId: Long) = eventDao.ofRef(EventType.DRILL_DONE, drillId)

    /** 第一次打开 v2 时放进三角洲和考公两个技能。 */
    suspend fun seedIfEmpty() {
        if (dao.skills().isNotEmpty()) return
        create(Seeds.DELTA_FORCE)
        create(Seeds.CIVIL_EXAM)
    }

    suspend fun create(seed: SeedSkill): Long {
        val id = dao.insertSkill(Skill(name = seed.name, template = seed.template))
        seed.categories.forEach { c ->
            val subId = dao.insertSubSkill(SubSkill(skillId = id, name = c.name, standard = c.standards.joinToString("\n")))
            c.drills.forEach { d ->
                dao.insertDrill(Drill(subSkillId = subId, title = d.title, method = d.method, minutes = d.minutes, metric = d.metric, source = "builtin"))
            }
        }
        return id
    }

    suspend fun deleteSkill(id: Long) {
        dao.deleteDrillsOf(id)
        dao.deleteSubSkillsOf(id)
        dao.deleteMatchesOf(id)
        dao.deleteSkill(id)
    }

    suspend fun updateStandard(sub: SubSkill, standard: String) {
        dao.updateSubSkill(sub.copy(standard = standard.lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")))
    }

    /** 用 AI 重写的标准替换：同名类别更新标准，新类别新增。 */
    suspend fun replaceStandards(skillId: Long, categories: List<Pair<String, List<String>>>) {
        val existing = dao.subSkills(skillId).associateBy { it.name }
        categories.forEach { (name, standards) ->
            val text = standards.joinToString("\n")
            val old = existing[name]
            if (old != null) dao.updateSubSkill(old.copy(standard = text))
            else dao.insertSubSkill(SubSkill(skillId = skillId, name = name, standard = text))
        }
    }

    /** 设当前重点。锁定期内不能换（返回 false）。 */
    suspend fun setFocus(skill: Skill, sub: SubSkill, now: Long = Time.now()): Boolean {
        if (skill.focusSubSkillId != sub.id && !FocusRules.canChange(skill, now)) return false
        skill.focusSubSkillId?.let { oldId ->
            if (oldId != sub.id) dao.subSkill(oldId)?.let { dao.updateSubSkill(it.copy(status = SubSkillStatus.NOT_STARTED)) }
        }
        dao.updateSkill(skill.copy(focusSubSkillId = sub.id, focusSince = now))
        dao.updateSubSkill(sub.copy(status = SubSkillStatus.PRACTICING))
        events.log(EventType.FOCUS_SET, skill.id, sub.id.toDouble(), "${skill.name}：${sub.name}")
        return true
    }

    /** 复测后决定：继续练同一个重点，重新计 7 天。 */
    suspend fun continueFocus(skill: Skill) {
        val subId = skill.focusSubSkillId ?: return
        val sub = dao.subSkill(subId) ?: return
        setFocus(skill, sub)
    }

    /** 复测通过：这一项标为已通过，清掉重点，等下一次诊断。 */
    suspend fun passFocus(skill: Skill) {
        val subId = skill.focusSubSkillId ?: return
        dao.subSkill(subId)?.let { dao.updateSubSkill(it.copy(status = SubSkillStatus.PASSED)) }
        dao.updateSkill(skill.copy(focusSubSkillId = null, focusSince = null))
        events.log(EventType.FOCUS_SET, skill.id, null, "${skill.name}：复测通过，换下一个短板")
    }

    suspend fun addDrill(sub: SubSkill, title: String, method: String, minutes: Int, metric: String, source: String): Long =
        dao.insertDrill(Drill(subSkillId = sub.id, title = title.trim(), method = method.trim(), minutes = minutes, metric = metric.trim(), source = source))

    suspend fun removeDrill(drill: Drill) = dao.deactivateDrill(drill.id)

    suspend fun logDrill(drill: Drill, value: Double?, note: String) {
        events.log(EventType.DRILL_DONE, drill.id, value, (drill.title + if (note.isNotBlank()) "｜" + note.trim() else "").take(80))
    }

    suspend fun logMatch(skillId: Long, result: String, cause: String?, note: String): Long {
        val id = dao.insertMatch(MatchLog(skillId = skillId, time = Time.now(), result = result, causeCategory = cause, note = note.trim()))
        val label = if (result == MatchResult.DIED) "阵亡 · ${cause.orEmpty()}" else "撤离"
        events.log(EventType.MATCH_LOGGED, id, if (result == MatchResult.DIED) 1.0 else 0.0, label + if (note.isNotBlank()) "｜$note" else "")
        return id
    }

    suspend fun deleteMatch(id: Long) = dao.deleteMatch(id)

    suspend fun logExam(skillId: Long, kind: String, score: Double?, essay: Double?, sections: List<ExamSection>, note: String): Long {
        val id = dao.insertSitting(ExamSitting(skillId = skillId, time = Time.now(), kind = kind, score = score, essayScore = essay, note = note.trim()))
        dao.insertSections(sections.map { it.copy(id = 0, sittingId = id) })
        val total = sections.sumOf { it.total }
        val acc = if (total == 0) null else sections.sumOf { it.correct } * 100.0 / total
        events.log(EventType.EXAM_LOGGED, id, acc, ExamKind.label(kind) + (score?.let { " · 行测 ${it}" } ?: ""))
        return id
    }

    suspend fun deleteSitting(id: Long) {
        dao.deleteSectionsOf(id)
        dao.deleteSitting(id)
    }

    /** 上一次登记时每个模块填的题量，下次登记直接预填。 */
    suspend fun lastTotals(skillId: Long): Map<String, Int> =
        dao.sectionsOf(skillId).groupBy { it.module }.mapValues { (_, list) -> list.first().total }

    suspend fun logMotorRetest(sub: SubSkill, metCount: Int, total: Int, note: String) {
        val pct = if (total == 0) 0.0 else metCount * 100.0 / total
        events.log(EventType.RETEST_DONE, sub.id, pct, "${sub.name} 达标 $metCount/$total" + if (note.isNotBlank()) "｜$note" else "")
    }

    /**
     * 每个技能当下的计划：当前重点、下一个练习、今天练没练、复测进度。
     * 学习模板走学习单元那套，不在这里。
     */
    suspend fun plans(now: Long = Time.now()): List<SkillPlan> {
        val today = Time.dateOf(now)
        val skillList = dao.skills().filter { it.template != Template.LEARNING }
        if (skillList.isEmpty()) return emptyList()
        val since = Time.startOf(today.minusDays(60))
        val recent = eventDao.since(since)
        val drillEvents = recent.filter { it.type == EventType.DRILL_DONE }
        return skillList.map { skill ->
            val focus = skill.focusSubSkillId?.let { dao.subSkill(it) }
            val drills = focus?.let { dao.activeDrills(it.id) }.orEmpty()
            val ids = drills.map { it.id }.toSet()
            val dates = FocusRules.drillDates(skill.focusSince, ids, drillEvents, Time::dateOf)
            val retest = if (focus == null) RetestState.NotDue else when (skill.template) {
                Template.COMPETITIVE -> Retest.competitive(skill, focus.name, dates.size, matchesOf(skill.id), now)
                Template.EXAM -> Retest.exam(skill, focus.name, dates.size, sittingsOf(skill.id), dao.sectionsOf(skill.id), now)
                else -> Retest.motor(skill, dates.size, recent.filter { it.type == EventType.RETEST_DONE && it.refId == focus.id }, now)
            }
            SkillPlan(
                skill = skill,
                focus = focus,
                drills = drills,
                nextDrill = pickNextDrill(drills, drillEvents),
                drilledToday = today in dates,
                drillDates = dates,
                retest = retest,
            )
        }
    }

    private suspend fun matchesOf(skillId: Long): List<MatchLog> = dao.matchesOf(skillId)

    private suspend fun sittingsOf(skillId: Long): List<ExamSitting> = dao.sittingsOf(skillId)

    /** 竞技技能当前的诊断（死因占比）。 */
    fun causeShares(matches: List<MatchLog>, subs: List<SubSkill>) = Diagnosis.causeShares(matches, subs.map { it.name })
}
