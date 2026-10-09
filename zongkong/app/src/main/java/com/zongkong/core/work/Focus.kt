package com.zongkong.core.work

import com.zongkong.core.DayClock
import java.time.Instant
import java.time.ZoneId

/** 首页“当前任务卡”该放哪一步、为什么是它。 */
object Focus {
    enum class Why(val label: String) {
        RUNNING("正在做"),
        NOW("现在该做"),
        CONTINUE("上次做到一半"),
        OVERDUE("过了计划时间还没做"),
        LATER_TODAY("今天接下来"),
        UNSCHEDULED("还没排时间"),
        UPCOMING("下一个安排"),
        NONE("没有待做的行动"),
    }

    data class Current(val action: Rec?, val thread: Rec?, val why: Why, val start: Long? = null, val end: Long? = null)

    private fun Rec.open(): Boolean = actionStatus.open

    fun current(work: Work, now: Long, zone: ZoneId): Current {
        fun pack(a: Rec, why: Why): Current {
            val p = a.plan
            return Current(a, work.rec(a.threadKey), why, p?.let { TimeParse.startMillis(it, zone) }, p?.let { TimeParse.endMillis(it, zone) })
        }
        work.session?.let { s -> work.rec(s.actionKey)?.let { return pack(it, Why.RUNNING) } }
        val open = work.actions().filter { it.open() }
        val timed = open.mapNotNull { a ->
            val p = a.plan ?: return@mapNotNull null
            val s = TimeParse.startMillis(p, zone) ?: return@mapNotNull null
            val e = TimeParse.endMillis(p, zone) ?: s
            Triple(a, s, e)
        }
        timed.filter { (_, s, e) -> s <= now && now < e }.minByOrNull { it.second }?.let { return pack(it.first, Why.NOW) }
        open.filter { it.actionStatus == ActionStatus.DOING && it[F.BREAK].isNotBlank() }.maxByOrNull { it.updatedAt }
            ?.let { return pack(it, Why.CONTINUE) }
        timed.filter { (_, _, e) -> e <= now }.minByOrNull { it.second }?.let { return pack(it.first, Why.OVERDUE) }
        val endOfDay = DayClock.at(DayClock.date(now, zone), DayClock.DAY_START_MIN - 1, zone)
        timed.filter { (_, s, _) -> s in (now + 1)..endOfDay }.minByOrNull { it.second }?.let { return pack(it.first, Why.LATER_TODAY) }
        open.filter { it.plan == null }.maxWithOrNull(compareBy<Rec>({ priority(work.rec(it.threadKey)) }, { it.updatedAt }))
            ?.let { return pack(it, Why.UNSCHEDULED) }
        timed.filter { (_, s, _) -> s > now }.minByOrNull { it.second }?.let { return pack(it.first, Why.UPCOMING) }
        return Current(null, null, Why.NONE)
    }

    private fun priority(t: Rec?): Int = when (t?.get(F.PRIORITY)) {
        "高" -> 3
        "中" -> 2
        "低" -> 0
        else -> 1
    }

    /** 接下来要做的（不含当前这一步），按时间排，没时间的放最后。 */
    fun upcoming(work: Work, exclude: String?, now: Long, zone: ZoneId, limit: Int = 4): List<Rec> =
        work.actions().filter { it.open() && it.key != exclude }
            .sortedWith(compareBy({ it.plan?.let { p -> TimeParse.startMillis(p, zone) } ?: Long.MAX_VALUE }, { -it.updatedAt }))
            .take(limit)

    /** 有“下一步”但没有排成行动的事项：提醒你把它排进时间。 */
    fun unplannedThreads(work: Work): List<Rec> = work.threads().filter { t ->
        t.threadStatus.open && t[F.NEXT].isNotBlank() && work.actionsOf(t.key).none { it.open() }
    }

    /** 等你确认结果的、卡住的。 */
    fun needsAttention(work: Work): List<Rec> = work.actions().filter { it.actionStatus == ActionStatus.CONFIRM || it.actionStatus == ActionStatus.STUCK }

    // ---------- 关卡用的证据 ----------

    enum class Evidence(val label: String, val describe: String) {
        PLANNED_TODAY("今天排了行动", "今天有行动排进了时间，并写了完成依据"),
        LINKED_NOTES("材料关联到事项", "今天收集的材料、问题、念头关联到了某件事"),
        CONCLUSIONS("形成了判断", "今天有新的判断或结论记录（GPT 交接导入、Notion 里写的都算）"),
        DONE_ACTIONS("完成了行动", "今天有行动做完并记录了结果"),
    }

    private fun sameDay(t: Long, now: Long, zone: ZoneId) = DayClock.dateKey(t, zone) == DayClock.dateKey(now, zone)

    private fun noteDay(n: Rec, now: Long, zone: ZoneId): Boolean {
        val d = n[F.DATE].take(10)
        return if (d.length == 10) d == DayClock.dateKey(now, zone) || (d == Instant.ofEpochMilli(now).atZone(zone).toLocalDate().toString()) else sameDay(n.createdAt, now, zone)
    }

    /** 数一数证据。返回数量和前几条的标题。 */
    fun evidence(work: Work, kind: Evidence, now: Long, zone: ZoneId): Pair<Int, List<String>> {
        val today = DayClock.date(now, zone)
        val items = when (kind) {
            Evidence.PLANNED_TODAY -> work.actions().filter { a ->
                val p = a.plan ?: return@filter false
                val s = TimeParse.startMillis(p, zone) ?: return@filter false
                DayClock.date(s, zone) == today && a[F.CRITERIA].isNotBlank() && a.actionStatus != ActionStatus.CANCELLED
            }
            Evidence.LINKED_NOTES -> work.notes().filter {
                it.threadKey.isNotBlank() && it.noteType in setOf(NoteType.MATERIAL, NoteType.QUESTION, NoteType.IDEA) && noteDay(it, now, zone)
            }
            Evidence.CONCLUSIONS -> work.notes().filter { it.noteType == NoteType.CONCLUSION && noteDay(it, now, zone) }
            Evidence.DONE_ACTIONS -> work.actions().filter {
                (it.actionStatus == ActionStatus.DONE || it.actionStatus == ActionStatus.CONFIRM) && sameDay(it.updatedAt, now, zone)
            }
        }
        return items.size to items.take(5).map { it.title }
    }

    /** 给日终验收的 AI 看的今日行动摘要。 */
    fun daySummary(work: Work, now: Long, zone: ZoneId): String {
        val today = work.actions().filter { a ->
            val s = a.plan?.let { TimeParse.startMillis(it, zone) }
            (s != null && DayClock.date(s, zone) == DayClock.date(now, zone)) || sameDay(a.updatedAt, now, zone)
        }
        if (today.isEmpty()) return "今天没有安排或更新任何行动。"
        return buildString {
            append("今天涉及的行动：\n")
            today.forEach { a ->
                append("· ${a.title}：${a[F.STATUS]}")
                if (a[F.RESULT].isNotBlank()) append("，结果：${a[F.RESULT].take(60)}")
                if (a.actionStatus == ActionStatus.STUCK && a[F.STUCK].isNotBlank()) append("，卡点：${a[F.STUCK]}")
                append('\n')
            }
        }
    }

    // ---------- 提醒 ----------

    data class Reminder(val key: String, val action: Rec, val follow: Boolean, val at: Long)

    const val FOLLOW_MINUTES = 15

    /**
     * 现在该发的提醒：到点提醒一次；到点 15 分钟后还没开始，再追问一次（问卡在哪）。
     * 超过 2 小时的旧提醒不再补发。key 用来去重。
     */
    fun dueReminders(work: Work, now: Long, zone: ZoneId): List<Reminder> = work.actions().filter { a ->
        a.actionStatus == ActionStatus.TODO || a.actionStatus == ActionStatus.RESCHEDULED
    }.flatMap { a ->
        val p = a.plan ?: return@flatMap emptyList()
        if (!p.hasTime) return@flatMap emptyList()
        val s = TimeParse.startMillis(p, zone) ?: return@flatMap emptyList()
        val started = work.events.any { it.actionKey == a.key && it.type == "start" && it.at >= s - 30 * 60_000 }
        if (started || work.session?.actionKey == a.key) return@flatMap emptyList()
        buildList {
            if (now >= s && now - s < 2 * 3600_000) add(Reminder("start:${a.key}:${p.start}", a, false, s))
            val f = s + FOLLOW_MINUTES * 60_000L
            if (now >= f && now - f < 2 * 3600_000) add(Reminder("follow:${a.key}:${p.start}", a, true, f))
        }
    }

    /** 未来 48 小时内的提醒时刻（给系统闹钟兜底，App 没在运行时也能响）。 */
    fun alarmTimes(work: Work, now: Long, zone: ZoneId): List<Long> = work.actions().filter {
        it.actionStatus == ActionStatus.TODO || it.actionStatus == ActionStatus.RESCHEDULED
    }.flatMap { a ->
        val s = a.plan?.takeIf { it.hasTime }?.let { TimeParse.startMillis(it, zone) } ?: return@flatMap emptyList()
        listOf(s, s + FOLLOW_MINUTES * 60_000L)
    }.filter { it > now && it < now + 48 * 3600_000L }.sorted()

    // ---------- 什么对你有效 ----------

    data class Insight(val reminders: Int, val startedAfterReminder: Int, val stuck: Map<String, Int>, val byPeriod: Map<String, Pair<Int, Int>>, val reschedules: Int)

    /** 从事件里看：提醒后 30 分钟内开始的比例（按上午/下午/晚上分）、卡点原因分布、改期次数。 */
    fun insight(work: Work, zone: ZoneId): Insight {
        val reminds = work.events.filter { it.type == "remind" }
        var ok = 0
        val period = mutableMapOf<String, Pair<Int, Int>>()
        reminds.forEach { r ->
            val started = work.events.any { it.type == "start" && it.actionKey == r.actionKey && it.at in r.at..(r.at + 30 * 60_000) }
            if (started) ok++
            val h = Instant.ofEpochMilli(r.at).atZone(zone).hour
            val p = when (h) {
                in 5..11 -> "上午"
                in 12..17 -> "下午"
                else -> "晚上"
            }
            val (a, b) = period[p] ?: (0 to 0)
            period[p] = (a + if (started) 1 else 0) to (b + 1)
        }
        val stuck = work.events.filter { it.type.startsWith("stuck:") }
            .groupingBy { StuckReason.valueOf(it.type.removePrefix("stuck:")).label }.eachCount()
        return Insight(reminds.size, ok, stuck, period, work.events.count { it.type == "reschedule" })
    }
}
