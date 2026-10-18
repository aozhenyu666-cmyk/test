package com.zongkong.core.hq

import com.zongkong.core.DayClock
import com.zongkong.core.Dept
import kotlinx.serialization.Serializable
import java.time.ZoneId

/*
 * 总控时间：每天固定一段（默认 21:30，30 分钟），手机万籁俱寂——只有总控、一手和几个必要的应用能用，
 * 其他一打开就回到总控。这段时间只做一件事：和总控一起把四个部门修一遍。
 *
 *   静（1 分钟）→ 收·清库（高频：一条一条过收集箱）→ 谋·想透一件（深：和参谋来回追问，憋出一个判断）
 *   → 筹·明天（定明天的主线，看本周计划）→ 行·对账（一手、娱乐、被忽略的提醒，定一条明天的约束）→ 封（总结、盖章、解锁）
 *
 * 每个环节有最短时间；时间不到、事没做完，“下一环节”不亮。提前退出只能走紧急退出（写原因，用掉一次紧急放行）。
 *
 * 不理总控怎么办（失联阶梯）：
 *   1 天没交流 → 参谋主动发消息，手机震一下
 *   2 天 → 严管：娱乐应用打开就被弹回，直到你回来交流一次
 *   3 天 → 下次打开任何不在白名单里的应用，直接进入总控时间
 */

@Serializable
enum class HqStep(val seal: String, val title: String, val dept: Dept?, val minMinutes: Int, val hint: String) {
    SETTLE("静", "静一下", null, 1, "放下手里的事。接下来这段时间只做一件事：把四个部门修一遍。"),
    INFO("收", "清收集箱", Dept.INFO, 3, "一条一条过：挂到事项、变成行动、转成问题，或者先放着。脑子里还有什么，也倒出来。"),
    THINK("谋", "想透一件", Dept.THINK, 8, "挑一件卡着的事，和总控来回追问：初判、最强的反驳、修正、下一步。憋也要憋出一个判断。"),
    PLAN("筹", "定明天", Dept.PLAN, 3, "明天只推一样：定主线、定时间、定做到什么算完。看一眼本周计划。"),
    ACT("行", "对账", Dept.ACT, 2, "今天一手练了多少、娱乐多久、被弹回几次、哪些提醒被你忽略了。定一条明天的约束。"),
    SEAL("封", "封存", Dept.HQ, 1, "总结三行，盖章，解锁。"),
}

@Serializable
enum class HqTrigger(val label: String) {
    UNANSWERED("长期未回应，强督导"),
    SCHEDULED("每日总控时间"),
    MANUAL("自己进入"),
    ESCALATION("失联太久，强制进入"),
    AGENT("总控发起"),
}

@Serializable
data class HqSession(
    val id: String,
    val trigger: HqTrigger,
    val startedAt: Long,
    /** 整段的计划时长（分钟）。 */
    val minutes: Int,
    val step: Int = 0,
    val stepStartedAt: Long = startedAt,
    /** 各环节留下的东西（环节名 → 一段话）。 */
    val notes: Map<String, String> = emptyMap(),
    /** 清库处理了几条。 */
    val triaged: Int = 0,
    val endedAt: Long = 0,
    /** 提前退出（紧急）。 */
    val early: Boolean = false,
    val earlyReason: String = "",
    val suspendedUntil: Long = 0,
    val deferredMillis: Long = 0,
) {
    val active: Boolean get() = endedAt == 0L
    val current: HqStep get() = HqStep.entries[step.coerceIn(0, HqStep.entries.lastIndex)]
    val endsAt: Long get() = startedAt + minutes * 60_000L + deferredMillis
}

@Serializable
data class HqBook(val sessions: List<HqSession> = emptyList()) {
    val active: HqSession? get() = sessions.lastOrNull { it.active }
    fun put(s: HqSession): HqBook = copy(sessions = (sessions.filterNot { it.id == s.id } + s).takeLast(60))
}

@Serializable
data class HqConfig(
    val enabled: Boolean = true,
    /** 每天几点开始（分钟数）。 */
    val at: Int = 21 * 60 + 30,
    val minutes: Int = 30,
    /** 到点 5 分钟还没进来，自动进入（锁住其他应用）。 */
    val autoLock: Boolean = true,
    /** 总控时间里还能用的应用（总控自己、电话、系统界面总是能用）。 */
    val allowed: Set<String> = DEFAULT_ALLOWED,
    /** 震动催你。 */
    val vibrate: Boolean = true,
    /** 失联阶梯。 */
    val escalation: Boolean = true,
    /** 参谋定时主动找你。 */
    val proactive: Boolean = true,
    /** 早上参谋第一条消息的时间。 */
    val morningAt: Int = 8 * 60,
    /** 总控时间里参谋的话读出来。 */
    val speak: Boolean = false,
    /** 睡觉时段：不连续震动。 */
    val quietStart: Int = 23 * 60 + 30,
    val quietEnd: Int = 7 * 60,
) {
    companion object {
        val DEFAULT_ALLOWED = setOf("com.yishou.app", "com.openai.chatgpt", "notion.id")
    }
}

/** 总控时间里总是能用的：电话、紧急呼叫、系统界面、权限弹窗、时钟（闹钟响了要能关）。 */
val ALWAYS_ALLOWED: Set<String> = setOf(
    "com.android.systemui",
    "com.android.dialer", "com.google.android.dialer", "com.android.incallui", "com.android.server.telecom",
    "com.android.phone", "com.android.emergency", "com.google.android.apps.safetyhub",
    "com.android.permissioncontroller", "com.google.android.permissioncontroller",
    "com.android.deskclock", "com.google.android.deskclock",
)

object Hq {
    /** 失联阶梯：几天没交流算几级。 */
    enum class Absence(val days: Int, val label: String) {
        NONE(0, "正常"),
        NUDGE(1, "一天没交流：总控主动找你"),
        STRICT(2, "两天没交流：严管到你回来"),
        FORCE(3, "三天没交流：下次打开别的应用直接进总控时间"),
    }

    fun absence(lastContact: Long, now: Long, cfg: HqConfig): Absence {
        if (!cfg.escalation || lastContact <= 0) return Absence.NONE
        val days = ((now - lastContact) / 86_400_000L).toInt()
        return when {
            days >= 3 -> Absence.FORCE
            days >= 2 -> Absence.STRICT
            days >= 1 -> Absence.NUDGE
            else -> Absence.NONE
        }
    }

    /** 总控时间里这个应用能不能用。 */
    fun allows(pkg: String, own: String, cfg: HqConfig, extra: Set<String> = emptySet()): Boolean =
        pkg == own || pkg in ALWAYS_ALLOWED || pkg in cfg.allowed || pkg in extra

    fun minutesOf(time: Int) = time.mod(24 * 60)

    /** 今天（逻辑日）总控时间的开始时刻。 */
    fun todayStart(cfg: HqConfig, now: Long, zone: ZoneId): Long = DayClock.at(DayClock.date(now, zone), cfg.at, zone)

    /**
     * 今天这一次（定时的那次）已经做过了：到点前 3 小时以内开始、已经结束的一段（提前退出也算，免得退出后又被拉回去）。
     * 下午自己点进去试一下的那次不算——不然晚上到点就不叫了。
     */
    fun doneToday(cfg: HqConfig, sessions: List<HqSession>, now: Long, zone: ZoneId): Boolean {
        val today = DayClock.dateKey(now, zone)
        val from = todayStart(cfg, now, zone) - 3 * 60 * 60_000L
        return sessions.any { DayClock.dateKey(it.startedAt, zone) == today && !it.active && it.startedAt >= from }
    }

    enum class Due { NONE, SOON, NOW, OVERDUE }

    /**
     * 每日总控时间到哪一步了：
     * SOON = 开始前 10 分钟；NOW = 到点（5 分钟内）；OVERDUE = 到点 5 分钟后还没进来（自动进入）。
     * 今天做过了、正在做、或者已经过了 3 小时，都是 NONE。
     */
    fun due(cfg: HqConfig, sessions: List<HqSession>, now: Long, zone: ZoneId): Due {
        if (!cfg.enabled) return Due.NONE
        if (sessions.any { it.active }) return Due.NONE
        if (doneToday(cfg, sessions, now, zone)) return Due.NONE
        val start = todayStart(cfg, now, zone)
        return when {
            now < start - 10 * 60_000 -> Due.NONE
            now < start -> Due.SOON
            now < start + 5 * 60_000 -> Due.NOW
            now < start + 3 * 3600_000 -> Due.OVERDUE
            else -> Due.NONE
        }
    }

    fun start(id: String, trigger: HqTrigger, cfg: HqConfig, now: Long, minutes: Int = cfg.minutes): HqSession =
        HqSession(id, trigger, now, minutes.coerceIn(15, 120))

    /** 这一环节最早什么时候能进下一环节。 */
    fun stepReadyAt(s: HqSession): Long = s.stepStartedAt + s.current.minMinutes * 60_000L

    fun suspend(s: HqSession, now: Long, until: Long): HqSession {
        if (!s.active || s.suspendedUntil > now || until <= now) return s
        val delay = until - now
        return s.copy(suspendedUntil = until, deferredMillis = s.deferredMillis + delay, stepStartedAt = s.stepStartedAt + delay)
    }

    fun resume(s: HqSession, now: Long): HqSession {
        val unused = (s.suspendedUntil - now).coerceAtLeast(0)
        return s.copy(suspendedUntil = 0, deferredMillis = s.deferredMillis - unused, stepStartedAt = s.stepStartedAt - unused)
    }

    /**
     * 能不能进下一环节：时间到了，而且这一环节的事做了（[done] 由界面判断：清库清完、判断写了……）。
     * 收集箱是空的、没有要想的事，这些情况界面会传 done = true。
     */
    fun canAdvance(s: HqSession, now: Long, done: Boolean): Boolean = now >= s.suspendedUntil && now >= stepReadyAt(s) && done

    fun advance(s: HqSession, now: Long, note: String = ""): HqSession {
        val notes = if (note.isBlank()) s.notes else s.notes + (s.current.name to note)
        return if (s.step >= HqStep.entries.lastIndex) s.copy(notes = notes) else s.copy(step = s.step + 1, stepStartedAt = now, notes = notes)
    }

    /** 正常结束：要走到最后一个环节，并且整段时间到了（时间没到可以回去再修一轮）。 */
    fun canFinish(s: HqSession, now: Long): Boolean = now >= s.suspendedUntil && s.current == HqStep.SEAL && now >= finishAt(s)

    /** 最早什么时候能封存：整段时间到了，而且在“封”这一步至少待了 20 秒（读一眼总结）。 */
    fun finishAt(s: HqSession): Long = if (s.trigger == HqTrigger.UNANSWERED) s.stepStartedAt + 20_000L else maxOf(s.endsAt, s.stepStartedAt + 20_000L)

    /** 回到某个环节再修一轮（时间还没到的时候）。 */
    fun revisit(s: HqSession, step: HqStep, now: Long): HqSession = s.copy(step = step.ordinal, stepStartedAt = now - step.minMinutes * 60_000L)

    fun finish(s: HqSession, now: Long, note: String = ""): HqSession =
        s.copy(endedAt = now, notes = if (note.isBlank()) s.notes else s.notes + (HqStep.SEAL.name to note))

    fun exitEarly(s: HqSession, now: Long, reason: String): HqSession = s.copy(endedAt = now, early = true, earlyReason = reason)

    /** 震动静音时段（睡觉）。 */
    fun quiet(cfg: HqConfig, now: Long, zone: ZoneId): Boolean {
        val t = java.time.Instant.ofEpochMilli(now).atZone(zone).let { it.hour * 60 + it.minute }
        val s = minutesOf(cfg.quietStart)
        val e = minutesOf(cfg.quietEnd)
        return if (s <= e) t in s until e else t >= s || t < e
    }

    /** 这次总控时间的文字记录（存进 Notion、交给日终验收）。 */
    fun report(s: HqSession): String = buildString {
        append("总控时间（${s.trigger.label}，${s.minutes} 分钟）")
        if (s.early) append("，提前退出：${s.earlyReason}")
        append('\n')
        HqStep.entries.forEach { st -> s.notes[st.name]?.takeIf { it.isNotBlank() }?.let { append("${st.seal} · ${st.title}：$it\n") } }
        if (s.triaged > 0) append("清库处理 ${s.triaged} 条\n")
    }
}
