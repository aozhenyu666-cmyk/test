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
}

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
    val headline: String
        get() = when {
            paused -> "休假中"
            emergencyUntil != null -> "紧急放行中"
            strict -> "严管"
            else -> "放行"
        }
}

/** 根据配置、当天记录和当前时间算出总控的状态。纯函数，方便测试。 */
object Engine {
    const val DUE_SOON_MILLIS = 30 * 60_000L

    fun evaluate(config: Config, day: DayLog, now: Long, zone: ZoneId): Status {
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
                val blocking = passed == null && when (gate.block) {
                    BlockMode.FROM_OPEN -> now >= openAt
                    BlockMode.AFTER_DEADLINE -> now >= deadlineAt
                }
                GateStatus(gate, phase, openAt, deadlineAt, passed, blocking, day.submissions.count { it.gateId == gate.id })
            }

        val reasons = mutableListOf<Reason>()
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
        }

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
            quotaMin = config.dailyQuotaMin,
        )
    }

    /** 状态栏通知里的一句话。 */
    fun summary(status: Status): String = when {
        status.paused -> "休假中，不拦截"
        status.emergencyUntil != null -> "紧急放行中：${status.reasons.firstOrNull()?.text ?: "到点后恢复检查"}"
        status.strict -> status.reasons.first().text
        else -> {
            val next = status.gates.firstOrNull { !it.done && it.phase != GatePhase.NOT_OPEN }
                ?: status.gates.firstOrNull { !it.done }
            if (next == null) {
                "今天的关卡全部验收 ${status.doneCount}/${status.gates.size}"
            } else {
                "下一关：${next.gate.title}（${DayClock.hhmm(next.gate.deadline)} 截止）· 已验收 ${status.doneCount}/${status.gates.size}"
            }
        }
    }
}
