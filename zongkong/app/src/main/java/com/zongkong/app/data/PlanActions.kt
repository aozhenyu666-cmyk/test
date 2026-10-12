package com.zongkong.app.data

import com.zongkong.core.DayClock
import com.zongkong.core.plan.DayPlan
import com.zongkong.core.plan.Goal
import com.zongkong.core.plan.Outcome
import com.zongkong.core.plan.PlanBook
import com.zongkong.core.plan.Plans
import com.zongkong.core.plan.WeekPlan
import com.zongkong.core.work.F
import com.zongkong.core.work.Kind
import com.zongkong.core.work.NoteType
import com.zongkong.core.work.ThreadStatus
import com.zongkong.core.work.WorkOps
import java.time.Instant
import kotlin.random.Random

/**
 * 计划的操作。计划本身存在手机上（plans.json）；立项时把它落到事项和行动上（会同步到 Notion），
 * 再记一条“计划”记录，GPT 读 Notion 也能看到这周定了什么。
 */
class PlanActions(private val store: Store, private val work: WorkActions) {
    private val rnd = Random.Default
    private fun now() = System.currentTimeMillis()

    val book: PlanBook get() = store.plans.value

    fun active(): WeekPlan? = Plans.active(book, now(), store.zone)
    fun next(): WeekPlan? = Plans.nextActive(book, now(), store.zone)
    fun draft(): WeekPlan? = Plans.draft(book, now(), store.zone)

    /** 存一份草稿（向导里每一步都存，退出也不丢）。 */
    fun saveDraft(plan: WeekPlan) {
        store.updatePlans { Plans.putDraft(it, plan) }
    }

    fun discardDraft(id: String) {
        store.updatePlans { b -> b.copy(weeks = b.weeks.filterNot { it.id == id && it.status == com.zongkong.core.plan.PlanStatus.DRAFT }) }
    }

    /**
     * 立项：没有对应事项的方向建成事项；每样交出物在事项下建一步行动（还没排时间，带完成依据）；
     * 记一条“计划”记录；草稿生效。返回生效的计划。
     */
    fun approve(id: String): WeekPlan? {
        val d = book.weeks.firstOrNull { it.id == id } ?: return null
        val t = now()
        var goals = d.goals
        var outcomes = d.outcomes
        store.updateWork { w0 ->
            var w = w0
            goals = goals.map { g ->
                if (g.threadKey.isNotBlank() && w.rec(g.threadKey) != null) g
                else {
                    val (w2, r) = WorkOps.create(w, Kind.THREAD, mapOf(F.TITLE to g.title, F.WHY to g.why, F.STATUS to ThreadStatus.READY.label), t, rnd)
                    w = w2
                    g.copy(threadKey = r.key)
                }
            }
            outcomes = outcomes.map { o ->
                val tk = o.threadKey.ifBlank { goals.firstOrNull { it.id == o.goalId }?.threadKey.orEmpty() }
                if (o.actionKey.isNotBlank() && w.rec(o.actionKey) != null) o.copy(threadKey = tk)
                else {
                    // 事项下已经有同名没做完的行动：直接挂上，不重复建
                    val same = w.actionsOf(tk).firstOrNull { it.title == o.title && it.actionStatus.open }
                    if (same != null) o.copy(threadKey = tk, actionKey = same.key)
                    else {
                        val (w2, r) = WorkOps.create(w, Kind.ACTION, mapOf(F.TITLE to o.title, F.THREAD to tk, F.CRITERIA to o.criteria), t, rnd)
                        w = w2
                        o.copy(threadKey = tk, actionKey = r.key)
                    }
                }
            }
            val firstThread = goals.firstOrNull()?.threadKey.orEmpty()
            w = WorkOps.create(w, Kind.NOTE, mapOf(
                F.TITLE to "本周计划 ${d.weekStart}", F.TYPE to NoteType.PLAN.label, F.THREAD to firstThread,
                F.RAW to text(d.copy(goals = goals, outcomes = outcomes)),
                F.DATE to Instant.ofEpochMilli(t).atZone(store.zone).toLocalDate().toString(),
            ), t, rnd).first
            w
        }
        store.updatePlans { b ->
            val withKeys = b.copy(weeks = b.weeks.map { if (it.id == id) it.copy(goals = goals, outcomes = outcomes) else it })
            Plans.approve(withKeys, id, t)
        }
        work.kick()
        return book.weeks.firstOrNull { it.id == id }
    }

    /** 计划写成一段字（存进“计划”记录）。 */
    fun text(p: WeekPlan): String = buildString {
        append("本周计划（${p.weekStart} 起，${p.by}起草）\n")
        p.goals.forEachIndexed { i, g ->
            append("方向${i + 1}：${g.title}")
            if (g.why.isNotBlank()) append("（${g.why}）")
            append('\n')
            p.outcomesOf(g.id).forEach { o -> append("  - 交出：${o.title}" + (o.criteria.takeIf { it.isNotBlank() }?.let { "（做到：$it）" } ?: "") + "\n") }
        }
        append("节奏：每天 ${p.dailyMinutes} 分钟")
        if (p.restDays.isNotEmpty()) append("，休息 " + p.restDays.sorted().joinToString("、") { "周" + "一二三四五六日"[it - 1] })
        append('\n')
        p.ifThens.forEach { append("如果${it.trigger}，就${it.response}\n") }
    }

    fun setOutcomeDone(planId: String, outcomeId: String, done: Boolean) {
        store.updatePlans { Plans.setOutcomeDone(it, planId, outcomeId, done, now()) }
    }

    /** 改生效中的计划（加 / 去掉交出物），留痕。 */
    fun addOutcome(planId: String, goalId: String, title: String, criteria: String, why: String) {
        val p = book.weeks.firstOrNull { it.id == planId } ?: return
        val g = p.goal(goalId) ?: return
        val t = now()
        var actionKey = ""
        if (g.threadKey.isNotBlank()) {
            store.updateWork { w ->
                val (w2, r) = WorkOps.create(w, Kind.ACTION, mapOf(F.TITLE to title, F.THREAD to g.threadKey, F.CRITERIA to criteria), t, rnd)
                actionKey = r.key
                w2
            }
            work.kick()
        }
        val o = Outcome(Plans.newId("o", t, p.outcomes.size), goalId, title, criteria, g.threadKey, actionKey)
        store.updatePlans { Plans.revise(it, planId, "加：$title", why, t) { w -> w.copy(outcomes = w.outcomes + o) } }
    }

    fun dropOutcome(planId: String, outcomeId: String, why: String) {
        val o = book.weeks.firstOrNull { it.id == planId }?.outcomes?.firstOrNull { it.id == outcomeId } ?: return
        store.updatePlans { Plans.revise(it, planId, "去掉：${o.title}", why, now()) { w -> w.copy(outcomes = w.outcomes.filterNot { x -> x.id == outcomeId }) } }
    }

    /** 把一件计划外的事加进本周计划（作为它所在事项的一个方向下的交出物；没有就新开一个方向）。 */
    fun addActionToPlan(actionKey: String, why: String) {
        val p = active() ?: return
        val a = store.work.value.rec(actionKey) ?: return
        val t = now()
        val goal = p.goals.firstOrNull { it.threadKey == a.threadKey }
        store.updatePlans { b ->
            Plans.revise(b, p.id, "加进计划：${a.title}", why, t) { w ->
                val g = goal ?: Goal(Plans.newId("g", t, w.goals.size), store.work.value.rec(a.threadKey)?.title ?: a.title, threadKey = a.threadKey)
                w.copy(
                    goals = if (goal == null) w.goals + g else w.goals,
                    outcomes = w.outcomes + Outcome(Plans.newId("o", t, w.outcomes.size), g.id, a.title, a[F.CRITERIA], a.threadKey, a.key),
                )
            }
        }
    }

    fun close(planId: String, review: String) {
        store.updatePlans { Plans.close(it, planId, review, now()) }
    }

    /** 定今天的主线。 */
    fun setMain(actionKey: String, outcomeId: String = "") {
        val t = now()
        val date = DayClock.dateKey(t, store.zone)
        store.updatePlans { b ->
            val old = Plans.day(b, date)
            val oid = outcomeId.ifBlank {
                active()?.outcomes?.firstOrNull { it.actionKey == actionKey || (it.threadKey.isNotBlank() && it.threadKey == store.work.value.rec(actionKey)?.threadKey && !it.done) }?.id.orEmpty()
            }
            Plans.setDay(b, DayPlan(date, oid, actionKey, old?.extras.orEmpty(), t))
        }
    }

    /** 定某一天（比如明天）的主线。 */
    fun setMainFor(date: String, actionKey: String) {
        val oid = active()?.outcomes?.firstOrNull { it.actionKey == actionKey }?.id.orEmpty()
        store.updatePlans { b -> Plans.setDay(b, DayPlan(date, oid, actionKey, Plans.day(b, date)?.extras.orEmpty(), now())) }
    }

    /** 做完一步：如果它就是某样交出物的那一步，交出物自动打勾。 */
    fun onActionDone(actionKey: String) {
        val p = active() ?: return
        val o = p.outcomes.firstOrNull { it.actionKey == actionKey && !it.done } ?: return
        setOutcomeDone(p.id, o.id, true)
    }
}
