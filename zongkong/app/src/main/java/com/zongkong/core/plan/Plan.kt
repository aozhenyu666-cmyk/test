package com.zongkong.core.plan

import com.zongkong.core.DayClock
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Rec
import com.zongkong.core.work.ThreadStatus
import com.zongkong.core.work.Work
import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.TemporalAdjusters

/*
 * 计划：给一个抗拒做计划的人用的“参照系”，不是一张排满的日程表。
 *
 *   方向（最多 3 个）→ 本周交出什么（每个方向 1–2 样，能检查）→ 节奏（每天给多少时间、哪天休息）
 *   → 预演（最可能在哪卡住，如果…就…）→ 你点头，立项。
 *
 * 计划可以由参谋起草（草稿），但只有你点头才生效。生效后：
 *   - 首页默认推“今天的主线”，正在做的事标出“计划内 / 计划外”
 *   - 做计划外的事不拦，只记一笔；想改计划就改，写一句为什么（留痕，不追责）
 *   - 周末回看：计划了什么、实际做了什么、时间花在哪，没做完的带到下周
 */

@Serializable
data class Goal(
    val id: String,
    val title: String,
    val why: String = "",
    /** 这件事挂在哪个事项上（空 = 还没有对应的事项）。 */
    val threadKey: String = "",
)

/** 本周要交出的一样东西。 */
@Serializable
data class Outcome(
    val id: String,
    val goalId: String,
    val title: String,
    val criteria: String = "",
    val threadKey: String = "",
    /** 立项时在事项下建的那一步行动。 */
    val actionKey: String = "",
    val done: Boolean = false,
    val doneAt: Long = 0,
)

/** 预演：如果……就……。 */
@Serializable
data class IfThen(val trigger: String, val response: String)

@Serializable
data class Revision(val at: Long, val what: String, val why: String)

@Serializable
enum class PlanStatus(val label: String) { DRAFT("草稿"), ACTIVE("进行中"), CLOSED("已结项") }

@Serializable
data class WeekPlan(
    val id: String,
    /** 这一周的周一，yyyy-MM-dd。 */
    val weekStart: String,
    val status: PlanStatus = PlanStatus.DRAFT,
    val goals: List<Goal> = emptyList(),
    val outcomes: List<Outcome> = emptyList(),
    /** 每天给计划内的事多少分钟（不是排满的日程，是预算）。 */
    val dailyMinutes: Int = 120,
    /** 休息日：1 = 周一 … 7 = 周日。 */
    val restDays: Set<Int> = emptySet(),
    val ifThens: List<IfThen> = emptyList(),
    /** 谁起草的：你 / 参谋。 */
    val by: String = "你",
    val createdAt: Long = 0,
    val approvedAt: Long = 0,
    val closedAt: Long = 0,
    val revisions: List<Revision> = emptyList(),
    /** 结项时的一句话复盘。 */
    val review: String = "",
) {
    val threadKeys: Set<String> get() = (goals.map { it.threadKey } + outcomes.map { it.threadKey }).filter { it.isNotBlank() }.toSet()
    fun goal(id: String) = goals.firstOrNull { it.id == id }
    fun outcomesOf(goalId: String) = outcomes.filter { it.goalId == goalId }
    val doneCount: Int get() = outcomes.count { it.done }
}

/** 一天的主线：今天就推这一样，其他的都是额外。 */
@Serializable
data class DayPlan(
    val date: String,
    val mainOutcomeId: String = "",
    val mainActionKey: String = "",
    val extras: List<String> = emptyList(),
    val setAt: Long = 0,
)

@Serializable
data class PlanBook(
    val weeks: List<WeekPlan> = emptyList(),
    val days: List<DayPlan> = emptyList(),
    /** 远方：周计划之外的长期目标。 */
    val aims: List<Aim> = emptyList(),
    /** 预案（试验）：一天排成几段，每段带 B 方案。 */
    val scripts: List<DayScript> = emptyList(),
)

object Plans {
    const val MAX_GOALS = 3
    const val MAX_OUTCOMES_PER_GOAL = 2

    /** 逻辑日（凌晨 4 点换日）所在那一周的周一。 */
    fun weekStart(date: LocalDate): LocalDate = date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))

    fun weekStart(now: Long, zone: ZoneId): LocalDate = weekStart(DayClock.date(now, zone))

    /** 这一周（周日的时候也看下一周）生效中的计划。 */
    fun active(book: PlanBook, now: Long, zone: ZoneId): WeekPlan? {
        val ws = weekStart(now, zone).toString()
        return book.weeks.lastOrNull { it.status == PlanStatus.ACTIVE && it.weekStart == ws }
    }

    /** 下一周已经立项（周日提前定好了）。 */
    fun nextActive(book: PlanBook, now: Long, zone: ZoneId): WeekPlan? {
        val ws = weekStart(now, zone).plusWeeks(1).toString()
        return book.weeks.lastOrNull { it.status == PlanStatus.ACTIVE && it.weekStart == ws }
    }

    fun draft(book: PlanBook, now: Long, zone: ZoneId): WeekPlan? {
        val cur = weekStart(now, zone)
        val keys = setOf(cur.toString(), cur.plusWeeks(1).toString())
        return book.weeks.lastOrNull { it.status == PlanStatus.DRAFT && it.weekStart in keys }
    }

    /** 上一份结项或还开着的计划（新计划从它的遗留开始）。 */
    fun previous(book: PlanBook, weekStart: String): WeekPlan? =
        book.weeks.filter { it.weekStart < weekStart && it.status != PlanStatus.DRAFT }.maxByOrNull { it.weekStart }

    /** 放进一份草稿（同一周的旧草稿替换掉）。 */
    fun putDraft(book: PlanBook, plan: WeekPlan): PlanBook =
        book.copy(weeks = book.weeks.filterNot { it.status == PlanStatus.DRAFT && it.weekStart == plan.weekStart } + plan.copy(status = PlanStatus.DRAFT))

    /**
     * 立项：草稿生效。同一周原来生效的计划结项（记一笔“被新计划替换”）。
     * 只有你能立项（界面上的按钮），参谋只能起草。
     */
    fun approve(book: PlanBook, id: String, now: Long): PlanBook {
        val p = book.weeks.firstOrNull { it.id == id } ?: return book
        return book.copy(weeks = book.weeks.map {
            when {
                it.id == id -> it.copy(status = PlanStatus.ACTIVE, approvedAt = now)
                it.status == PlanStatus.ACTIVE && it.weekStart == p.weekStart -> it.copy(
                    status = PlanStatus.CLOSED, closedAt = now,
                    revisions = it.revisions + Revision(now, "被新计划替换", "重新立项"),
                )
                else -> it
            }
        })
    }

    /** 改生效中的计划：留一笔为什么。 */
    fun revise(book: PlanBook, id: String, what: String, why: String, now: Long, f: (WeekPlan) -> WeekPlan): PlanBook =
        book.copy(weeks = book.weeks.map { if (it.id == id) f(it).copy(revisions = it.revisions + Revision(now, what, why.ifBlank { "没写" })) else it })

    fun close(book: PlanBook, id: String, review: String, now: Long): PlanBook =
        book.copy(weeks = book.weeks.map { if (it.id == id) it.copy(status = PlanStatus.CLOSED, closedAt = now, review = review) else it })

    fun setOutcomeDone(book: PlanBook, planId: String, outcomeId: String, done: Boolean, now: Long): PlanBook =
        book.copy(weeks = book.weeks.map { p ->
            if (p.id != planId) p else p.copy(outcomes = p.outcomes.map { if (it.id == outcomeId) it.copy(done = done, doneAt = if (done) now else 0) else it })
        })

    fun day(book: PlanBook, date: String): DayPlan? = book.days.lastOrNull { it.date == date }

    fun setDay(book: PlanBook, day: DayPlan): PlanBook =
        book.copy(days = (book.days.filterNot { it.date == day.date } + day).takeLast(60))

    /** 今天的主线行动（还没做完的）。 */
    fun mainAction(book: PlanBook, work: Work, now: Long, zone: ZoneId): Rec? {
        val d = day(book, DayClock.dateKey(now, zone)) ?: return null
        return work.rec(d.mainActionKey)?.takeIf { it.actionStatus.open }
    }

    /** 这一步在不在本周计划里（按事项算：计划里的事下面的行动都算计划内）。 */
    fun inPlan(plan: WeekPlan, action: Rec): Boolean = action.threadKey.isNotBlank() && action.threadKey in plan.threadKeys

    fun isRestDay(plan: WeekPlan, now: Long, zone: ZoneId): Boolean = DayClock.weekday(now, zone) in plan.restDays

    // ---------- 实际情况 ----------

    /** 每一步做了多少分钟：从“开始”到下一次停下 / 做完 / 卡住 / 取消 / 开始别的，最多算 3 小时。 */
    fun minutesByAction(work: Work, from: Long, to: Long): Map<String, Int> {
        val out = mutableMapOf<String, Long>()
        var openKey: String? = null
        var openAt = 0L
        val stops = setOf("pause", "cancel")
        for (e in work.events.sortedBy { it.at }) {
            val isStop = e.type in stops || e.type.startsWith("done:") || e.type.startsWith("stuck:")
            if (openKey != null && (e.type == "start" || (isStop && e.actionKey == openKey))) {
                val s = maxOf(openAt, from)
                val t = minOf(e.at, to, openAt + 3 * 3600_000L)
                if (t > s) out[openKey!!] = (out[openKey!!] ?: 0) + (t - s)
                openKey = null
            }
            if (e.type == "start") {
                openKey = e.actionKey
                openAt = e.at
            }
        }
        // 还在做的那一步算到现在
        val s = work.session
        if (s != null && openKey == s.actionKey) {
            val st = maxOf(openAt, from)
            val t = minOf(to, openAt + 3 * 3600_000L)
            if (t > st) out[s.actionKey] = (out[s.actionKey] ?: 0) + (t - st)
        }
        return out.mapValues { (it.value / 60_000).toInt() }
    }

    data class GoalProgress(val goal: Goal, val outcomesDone: Int, val outcomes: Int, val actionsDone: Int, val minutes: Int)

    data class Progress(
        val goals: List<GoalProgress>,
        /** 本周计划内的分钟数。 */
        val planMinutes: Int,
        /** 本周计划外（做了但不在计划里的）分钟数。 */
        val offMinutes: Int,
        val todayPlanMinutes: Int,
        /** 本周做完的计划外行动。 */
        val offDone: List<String>,
        val day: Int,
    )

    fun progress(plan: WeekPlan, work: Work, now: Long, zone: ZoneId): Progress {
        val ws = LocalDate.parse(plan.weekStart)
        val from = DayClock.startOf(ws, zone)
        val to = minOf(now, DayClock.startOf(ws.plusWeeks(1), zone))
        val mins = minutesByAction(work, from, to)
        val todayFrom = DayClock.startOf(DayClock.date(now, zone), zone)
        val todayMins = minutesByAction(work, todayFrom, now)
        val threads = plan.threadKeys
        fun threadOf(k: String) = work.rec(k)?.threadKey.orEmpty()
        val doneThisWeek = work.actions().filter { it.actionStatus == ActionStatus.DONE || it.actionStatus == ActionStatus.CONFIRM }
            .filter { it.updatedAt in from until to }
        val goals = plan.goals.map { g ->
            val gThreads = (listOf(g.threadKey) + plan.outcomesOf(g.id).map { it.threadKey }).filter { it.isNotBlank() }.toSet()
            GoalProgress(
                g,
                plan.outcomesOf(g.id).count { it.done },
                plan.outcomesOf(g.id).size,
                doneThisWeek.count { it.threadKey in gThreads },
                mins.filterKeys { threadOf(it) in gThreads }.values.sum(),
            )
        }
        val day = (DayClock.date(now, zone).toEpochDay() - ws.toEpochDay()).toInt() + 1
        return Progress(
            goals = goals,
            planMinutes = mins.filterKeys { threadOf(it) in threads }.values.sum(),
            offMinutes = mins.filterKeys { threadOf(it) !in threads }.values.sum(),
            todayPlanMinutes = todayMins.filterKeys { threadOf(it) in threads }.values.sum(),
            offDone = doneThisWeek.filter { it.threadKey !in threads }.map { it.title },
            day = day.coerceIn(1, 7),
        )
    }

    // ---------- 起草时的建议 ----------

    /** 可以当方向的事：开着的事项，优先级高的、最近动过的在前。 */
    fun goalCandidates(work: Work, limit: Int = 8): List<Rec> = work.threads().filter { it.threadStatus.open }
        .sortedWith(compareByDescending<Rec> { it[F.PRIORITY] == "高" }.thenByDescending { it.threadStatus == ThreadStatus.ACTIVE }.thenByDescending { it.updatedAt })
        .take(limit)

    /** 一件事这周可以交出什么：它的下一步、没做完的行动、完成依据。 */
    fun outcomeCandidates(work: Work, threadKey: String): List<String> {
        val t = work.rec(threadKey) ?: return emptyList()
        return buildList {
            if (t[F.NEXT].isNotBlank()) add(t[F.NEXT])
            work.actionsOf(threadKey).filter { it.actionStatus.open }.forEach { add(it.title) }
            if (t[F.CRITERIA].isNotBlank()) add(t[F.CRITERIA])
        }.map { it.trim() }.filter { it.isNotEmpty() }.distinct().take(5)
    }

    /** 上一份计划没做完的，带到这一份。 */
    fun leftovers(prev: WeekPlan?): List<Outcome> = prev?.outcomes?.filterNot { it.done }.orEmpty()

    /** 预演的常见卡点和对策。 */
    val COMMON_TRAPS: List<Pair<String, List<String>>> = listOf(
        "晚上刷手机停不下来" to listOf("20:00 开专注锁做主线", "手机放到另一个房间", "先去一手应一手再刷"),
        "不知道从哪开始" to listOf("只做 5 分钟：打开文件写一行", "让总控拆成能马上开始的一步"),
        "时间被别的事占了" to listOf("主线挪到第二天早上第一件", "把主线缩成 25 分钟版本"),
        "状态差、不想动" to listOf("只做最小一步：打开就算", "换个地方做 25 分钟"),
        "被消息打断" to listOf("开专注锁 45 分钟", "做完一段再回消息"),
        "做到一半想换别的事" to listOf("先记断点再换", "问自己：这是计划内的吗"),
    )

    // ---------- 给参谋和复盘看的文字 ----------

    fun describe(plan: WeekPlan, work: Work, book: PlanBook, now: Long, zone: ZoneId): String = buildString {
        val pr = progress(plan, work, now, zone)
        val ws = LocalDate.parse(plan.weekStart)
        append("本周计划（${ws.monthValue}.${ws.dayOfMonth} 起，第 ${pr.day} 天，${plan.status.label}，${plan.by}起草）：\n")
        plan.goals.forEachIndexed { i, g ->
            val gp = pr.goals.getOrNull(i)
            append("· 方向${i + 1}：${g.title}")
            if (g.why.isNotBlank()) append("（为什么：${g.why}）")
            gp?.let { append("｜交出 ${it.outcomesDone}/${it.outcomes}，做完 ${it.actionsDone} 步，${it.minutes} 分钟") }
            append('\n')
            plan.outcomesOf(g.id).forEach { o -> append("    - [${if (o.done) "✓" else " "}] ${o.title}" + (o.criteria.takeIf { it.isNotBlank() }?.let { "（做到：$it）" } ?: "") + " id=${o.id}\n") }
        }
        append("节奏：每天 ${plan.dailyMinutes} 分钟给计划内的事" + (if (plan.restDays.isNotEmpty()) "，休息：" + plan.restDays.sorted().joinToString("、") { "周" + "一二三四五六日"[it - 1] } else "") + "\n")
        append("本周计划内 ${pr.planMinutes} 分钟、计划外 ${pr.offMinutes} 分钟；今天计划内 ${pr.todayPlanMinutes}/${plan.dailyMinutes} 分钟\n")
        if (plan.ifThens.isNotEmpty()) append("预演：").append(plan.ifThens.joinToString("；") { "如果${it.trigger}，就${it.response}" }).append('\n')
        val main = mainAction(book, work, now, zone)
        append("今天的主线：").append(main?.let { "「${it.title}」[${it.key}]" } ?: "还没定").append('\n')
        if (pr.offDone.isNotEmpty()) append("本周做完的计划外：").append(pr.offDone.take(5).joinToString("、")).append('\n')
        if (plan.revisions.isNotEmpty()) append("改过 ${plan.revisions.size} 次，最近：${plan.revisions.last().what}（${plan.revisions.last().why}）\n")
    }

    /** 首页 / 计划页上的一行：本周第几天、交出几样。 */
    fun headline(plan: WeekPlan, now: Long, zone: ZoneId): String {
        val day = (DayClock.date(now, zone).toEpochDay() - LocalDate.parse(plan.weekStart).toEpochDay()).toInt() + 1
        return "本周第 ${day.coerceIn(1, 7)} 天 · 交出 ${plan.doneCount}/${plan.outcomes.size}"
    }

    fun newId(prefix: String, now: Long, n: Int) = prefix + now.toString(36) + n
}
