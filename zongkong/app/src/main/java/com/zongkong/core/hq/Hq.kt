package com.zongkong.core.hq

import com.zongkong.core.DayClock
import com.zongkong.core.Dept
import kotlinx.serialization.Serializable
import java.time.ZoneId

/*
 * 总控时间（公审）：每天固定一段（默认 21:30，30 分钟），手机万籁俱寂——只有总控、一手和几个必要的应用能用，
 * 其他一打开就回到总控。这段时间只做一件事：按四个部门的流水线述职，上一环没过，下一环不放行。
 *
 *   静·查账（上一条《强制执行令》做到没有）→ 收·分流（每条念头分进三个槽位，再把脑子里最占地方的事精简成一句话）
 *   → 谋·对底线（《全局战略底线表》+ 今天的临时起意过一遍预判 + 写下一条判断）
 *   → 筹·防瘫痪（定明天的主线；熔断时只能定行测）→ 行·三层矫正（微习惯点名、防错规避库、五步思维 × 行测）
 *   → 封·签执行令（一条具体、有时限的动作，下次一开场查账）
 *
 * 每个环节有最短时间；时间不到、事没做完，“下一环节”不亮。界面上没有退出：电话、紧急呼叫、闹钟一直能用，
 * 走完六个环节就解锁。（守护服务兜底：超时一小时还没封存会自动收掉，记成提前结束，免得出 bug 锁一整夜。）
 *
 * 不理总控怎么办（失联阶梯）：
 *   1 天没交流 → 参谋主动发消息，手机震一下
 *   2 天 → 严管：娱乐应用打开就被弹回，直到你回来交流一次
 *   3 天 → 下次打开任何不在白名单里的应用，直接进入总控时间
 */

@Serializable
enum class HqStep(val seal: String, val title: String, val dept: Dept?, val minMinutes: Int, val hint: String) {
    SETTLE("静", "查账", null, 1, "先静一下，再查上一条《强制执行令》：做到了没有。没做到就写清楚为什么。"),
    INFO("收", "分流", Dept.INFO, 2, "信息收集部：收集箱里每一条都分进三个槽位——① 战略危机交谋划部、② 日常 / 常识漏洞交行为部、③ 逃避型杂念直接封存。再把脑子里最占地方的事压成一句话。"),
    THINK("谋", "对底线", Dept.THINK, 3, "谋划思考部：拿《全局战略底线表》比一比今天的临时起意——能让行测多拿分，还是能帮你找到下个月的工作？答不上来就枪毙。最后写下一条判断。"),
    PLAN("筹", "防瘫痪", Dept.PLAN, 1, "统筹规划部：明天只推一样，定时间、定做到什么算完。熔断时只能定行测训练。"),
    ACT("行", "三层矫正", Dept.ACT, 2, "行为管理部：① 微习惯点名，一条条看一眼确认；② 今天的踩坑写成“遇到 A 就做 B”；③ 看行测五步做了几道。"),
    SEAL("封", "签执行令", Dept.HQ, 1, "签一条《强制执行令》：一个具体动作、一个时限，不讨价还价。下次总控时间一开场先查它。"),
}

@Serializable
enum class HqTrigger(val label: String) {
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
    /** 提前结束（界面上已经没有退出；只剩超时自动收掉和旧版本的记录）。 */
    val early: Boolean = false,
    val earlyReason: String = "",
) {
    val active: Boolean get() = endedAt == 0L
    val current: HqStep get() = HqStep.entries[step.coerceIn(0, HqStep.entries.lastIndex)]
    val endsAt: Long get() = startedAt + minutes * 60_000L
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

    /**
     * 能不能进下一环节：时间到了，而且这一环节的事做了（[done] 由界面判断：清库清完、判断写了……）。
     * 收集箱是空的、没有要想的事，这些情况界面会传 done = true。
     */
    fun canAdvance(s: HqSession, now: Long, done: Boolean): Boolean = now >= stepReadyAt(s) && done

    fun advance(s: HqSession, now: Long, note: String = ""): HqSession {
        val notes = if (note.isBlank()) s.notes else s.notes + (s.current.name to note)
        return if (s.step >= HqStep.entries.lastIndex) s.copy(notes = notes) else s.copy(step = s.step + 1, stepStartedAt = now, notes = notes)
    }

    /** 正常结束：要走到最后一个环节，并且整段时间到了（时间没到可以回去再修一轮）。 */
    fun canFinish(s: HqSession, now: Long): Boolean = s.current == HqStep.SEAL && now >= finishAt(s)

    /**
     * 最早什么时候能封存：在“封”这一步待满 20 秒（读一眼总结）。
     * 0.10 起不再要求整段时间（30 分钟）用完：每一步做完了就能封，不陪坐。
     */
    fun finishAt(s: HqSession): Long = s.stepStartedAt + 20_000L

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
