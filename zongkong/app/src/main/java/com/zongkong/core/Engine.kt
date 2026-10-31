package com.zongkong.core

import java.time.ZoneId

enum class GatePhase(val label: String) {
    NOT_OPEN("未开放"),
    OPEN("进行中"),
    DUE_SOON("快到点"),
    OVERDUE("已超时"),
    DONE("已验收"),
    DONE_LATE("迟交验收"),
}

data class GateStatus(
    val gate: Gate,
    val phase: GatePhase,
    val openAt: Long,
    val deadlineAt: Long,
    val passed: Submission?,
    /** 这道关卡此刻是否在拦截娱乐应用。 */
    val blocking: Boolean,
    val attempts: Int,
    /**
     * 拦截时长已经用尽（P0-5）：关卡仍未交，但不再拦。
     *
     * 界面要如实说明「还在超时，但拦截到期了」，不要假装它已经完成，
     * 也不要让它继续无期限地挡住整台手机。
     */
    val blockExpired: Boolean = false,
) {
    val done: Boolean get() = passed != null
}

sealed class Reason {
    abstract val text: String

    data class GateDue(val status: GateStatus) : Reason() {
        override val text: String
            get() = if (status.phase == GatePhase.OVERDUE) {
                "${status.gate.dept.label}「${status.gate.title}」已超时未交"
            } else {
                "${status.gate.dept.label}「${status.gate.title}」交了才放行"
            }
    }

    data class Silence(val sinceMillis: Long, val hours: Int) : Reason() {
        override val text: String get() = "已经 $hours 小时没有汇报，先报个到"
    }

    data class Quota(val usedMin: Int, val limitMin: Int) : Reason() {
        override val text: String get() = "今天娱乐 $usedMin 分钟，已到上限 $limitMin 分钟"
    }

    /** 总控时间：除了白名单，什么应用都回到总控。 */
    data class Session(val endsAt: Long) : Reason() {
        override val text: String get() = "总控时间进行中"
    }

    /** 太久没和总控交流。 */
    data class Absent(val days: Int) : Reason() {
        override val text: String get() = "已经 $days 天没和总控交流了，回来报个到"
    }

    /** 今天挣到的娱乐时长用完了。 */
    data class Budget(val usedMin: Int, val allowedMin: Int) : Reason() {
        override val text: String get() = "今天挣到的 $allowedMin 分钟娱乐用完了（已用 $usedMin）。做完一步再挣"
    }

    /** 主线过了点还没开始。 */
    data class Debt(val actionKey: String, val title: String) : Reason() {
        override val text: String get() = "「$title」过了点还没开始：开始做，或者改个时间"
    }

    /** 正在做一步行动，开了专注锁。 */
    data class Focus(val title: String, val until: Long) : Reason() {
        override val text: String get() = "正在做「$title」，专注锁开着"
    }
}

/** 专注锁：做某一步时到点前拦截娱乐应用。 */
data class FocusLock(val title: String, val until: Long)

data class Status(
    val now: Long,
    val date: String,
    /** 严管：拦截名单里的应用一打开就被弹回总控。 */
    val strict: Boolean,
    val reasons: List<Reason>,
    val gates: List<GateStatus>,
    val emergencyUntil: Long?,
    val emergencyLeft: Int,
    val degradedLeft: Int,
    val paused: Boolean,
    val lastActivity: Long?,
    /** 到这个时刻还不汇报就进入严管（不在检查时段内为 null）。 */
    val silenceDueAt: Long?,
    val playMin: Int,
    val quotaMin: Int,
) {
    val doneCount: Int get() = gates.count { it.done }

    /** 有没有关卡「拦到上限了但还欠着」（P0-5）。界面要如实说，不许假装已完成。 */
    val blockExpired: Boolean get() = gates.any { it.blockExpired }

    val headline: String
        get() = when {
            paused -> "休假中"
            emergencyUntil != null -> "紧急放行中"
            strict -> "严管"
            // P0-5：关卡拦到上限、账还欠着 —— 不是「放行」，也不是「严管」。
            blockExpired -> "欠账放行"
            else -> "放行"
        }
}

/** 根据配置、当天记录和当前时间算出总控的状态。纯函数，方便测试。 */
object Engine {
    const val DUE_SOON_MILLIS = 30 * 60_000L

    fun evaluate(
        config: Config, day: DayLog, now: Long, zone: ZoneId, lock: FocusLock? = null,
        session: com.zongkong.core.hq.HqSession? = null,
        absence: com.zongkong.core.hq.Hq.Absence = com.zongkong.core.hq.Hq.Absence.NONE,
        /** 今天挣到的娱乐分钟（不含底数）；null = 不用挣来的娱乐。 */
        earned: Int? = null,
        /** 过了点还没开始的那一步（行动键 to 标题）；null = 没欠。 */
        debt: Pair<String, String>? = null,
    ): Status {
        val date = DayClock.date(now, zone)
        val weekday = date.dayOfWeek.value

        val gates = config.gates
            .filter { weekday in it.days }
            .sortedBy { DayClock.rank(it.deadline) }
            .map { gate ->
                val openAt = DayClock.at(date, gate.openAt, zone)
                val deadlineAt = DayClock.at(date, gate.deadline, zone).let { if (it <= openAt) it + 24 * 3600_000L else it }
                val passed = day.passed(gate.id)
                val phase = when {
                    passed != null -> if (passed.late) GatePhase.DONE_LATE else GatePhase.DONE
                    now < openAt -> GatePhase.NOT_OPEN
                    now >= deadlineAt -> GatePhase.OVERDUE
                    deadlineAt - now <= DUE_SOON_MILLIS -> GatePhase.DUE_SOON
                    else -> GatePhase.OPEN
                }
                // P0-5：拦截必须有尽头。
                // 从「开始拦」的时刻起最多拦 config.blockMaxHours 小时，超时后
                // blocking=false（只保留 OVERDUE 的提示），避免「交不了/放不了/休不了」的死锁。
                // blockMaxHours <= 0 表示不设上限（旧行为）。
                val blockFrom = when (gate.block) {
                    BlockMode.FROM_OPEN -> openAt
                    BlockMode.AFTER_DEADLINE -> deadlineAt
                }
                val blockUntil = if (config.blockMaxHours > 0) {
                    blockFrom + config.blockMaxHours * 3600_000L
                } else {
                    Long.MAX_VALUE
                }
                val blocking = passed == null && now >= blockFrom && now < blockUntil
                val blockExpired = passed == null && now >= blockUntil
                GateStatus(gate, phase, openAt, deadlineAt, passed, blocking, day.submissions.count { it.gateId == gate.id }, blockExpired)
            }

        val reasons = mutableListOf<Reason>()
        if (session != null && session.active) reasons += Reason.Session(session.endsAt)
        if (absence >= com.zongkong.core.hq.Hq.Absence.STRICT) reasons += Reason.Absent(absence.days)
        gates.filter { it.blocking }.forEach { reasons += Reason.GateDue(it) }

        val lastActivity = (day.submissions.map { it.at } + day.reports.map { it.at }).maxOrNull()
        var silenceDueAt: Long? = null
        if (config.silenceHours > 0) {
            val winStart = DayClock.at(date, config.activeStart, zone)
            val winEnd = DayClock.at(date, config.activeEnd, zone).let { if (it <= winStart) it + 24 * 3600_000L else it }
            if (now in winStart until winEnd) {
                val base = maxOf(lastActivity ?: 0L, winStart)
                val due = base + config.silenceHours * 3600_000L
                silenceDueAt = due
                if (now >= due) reasons += Reason.Silence(base, config.silenceHours)
            }
        }

        val playMin = day.playMinutes
        if (config.dailyQuotaMin > 0 && playMin >= config.dailyQuotaMin) {
            reasons += Reason.Quota(playMin, config.dailyQuotaMin)
        } else if (earned != null) {
            val allowed = allowedMinutes(config, earned)
            if (playMin >= allowed) reasons += Reason.Budget(playMin, allowed)
        }
        if (debt != null) reasons += Reason.Debt(debt.first, debt.second)

        if (lock != null && lock.until > now) reasons += Reason.Focus(lock.title, lock.until)

        val emergency = day.emergencies.lastOrNull { now < it.until }
        val paused = config.pausedUntil > now
        return Status(
            now = now,
            date = date.toString(),
            strict = reasons.isNotEmpty() && emergency == null && !paused,
            reasons = reasons,
            gates = gates,
            emergencyUntil = emergency?.until,
            emergencyLeft = (config.emergencyPerDay - day.emergencies.size).coerceAtLeast(0),
            degradedLeft = (config.degradedPerDay - day.submissions.count { it.verdict.pass && it.verdict.degraded }).coerceAtLeast(0),
            paused = paused,
            lastActivity = lastActivity,
            silenceDueAt = silenceDueAt,
            playMin = playMin,
            quotaMin = if (earned != null) allowedMinutes(config, earned) else config.dailyQuotaMin,
        )
    }

    /** 今天能玩多久：底数 + 挣到的，不超过每天上限。 */
    fun allowedMinutes(config: Config, earned: Int): Int {
        val v = config.earn.baseMinutes + earned
        return if (config.dailyQuotaMin > 0) minOf(v, config.dailyQuotaMin) else v
    }

    /** 状态栏通知里的一句话。 */
    fun summary(status: Status): String = when {
        status.paused -> "休假中，不拦截"
        status.emergencyUntil != null -> "紧急放行中：${status.reasons.firstOrNull()?.text ?: "到点后恢复检查"}"
        status.strict -> status.reasons.first().text
        // P0-5：拦截到期了，但账没消。说清楚是「放你出去」而不是「你做到了」。
        status.blockExpired -> {
            val g = status.gates.first { it.blockExpired }
            "「${g.gate.title}」还欠着，拦截已到上限 —— 今天先放行，这笔账记着"
        }
        else -> {
            val next = status.gates.firstOrNull { !it.done && it.phase != GatePhase.NOT_OPEN }
                ?: status.gates.firstOrNull { !it.done }
            val play = if (status.quotaMin > 0) "娱乐 ${status.playMin}/${status.quotaMin} 分 · " else ""
            play + if (next == null) {
                "今天的关卡全部验收 ${status.doneCount}/${status.gates.size}"
            } else {
                "下一关：${next.gate.title}（${DayClock.hhmm(next.gate.deadline)} 截止）· 已验收 ${status.doneCount}/${status.gates.size}"
            }
        }
    }
}
