package com.zongkong.core

/** 对当天记录的修改。纯函数，返回新的 DayLog。 */
object DayOps {

    /** 一般态度下申请紧急放行的最少字数。 */
    const val NORMAL_EMERGENCY_CHARS = 20

    /**
     * 严格态度下申请紧急放行的最少字数（P0-5）。
     *
     * 严格不再是「不给」，而是「说清楚才给」——门槛高 3 倍，且记进日终验收。
     */
    const val STRICT_EMERGENCY_CHARS = 60

    fun submit(day: DayLog, status: GateStatus, text: String, verdict: Verdict, now: Long): DayLog {
        val late = now >= status.deadlineAt
        val sub = Submission(status.gate.id, now, text.trim(), verdict, late = late)
        val outbox = if (verdict.pass) {
            val result = when {
                verdict.degraded -> "降级通过"
                late -> "迟交通过"
                else -> "通过"
            }
            val targets = if (status.gate.dept == Dept.THINK) {
                setOf(LogEntry.TARGET_LOG, LogEntry.TARGET_THINK)
            } else {
                setOf(LogEntry.TARGET_LOG)
            }
            day.outbox + LogEntry(now, status.gate.title, status.gate.dept, result, text.trim(), verdict.score, targets)
        } else {
            day.outbox
        }
        return day.copy(submissions = day.submissions + sub, outbox = outbox)
    }

    fun checkin(day: DayLog, text: String, now: Long): DayLog = day.copy(
        reports = day.reports + Report(now, ReportKind.CHECKIN, text.trim()),
        outbox = day.outbox + LogEntry(now, "报到：" + text.trim().lineSequence().first().take(40), Dept.HQ, "报到", text.trim()),
    )

    fun capture(day: DayLog, text: String, tag: String, now: Long, synced: Boolean): DayLog =
        day.copy(reports = day.reports + Report(now, ReportKind.CAPTURE, text.trim(), tag, synced))

    fun markCaptureSynced(day: DayLog, at: Long): DayLog =
        day.copy(reports = day.reports.map { if (it.at == at && it.kind == ReportKind.CAPTURE) it.copy(synced = true) else it })

    /**
     * 申请紧急放行。不符合条件时返回原因。
     *
     * [strictStance] = 总控现在态度「严格」（P0-5）。这时**照样给**，
     * 只要理由写得足够清楚（[STRICT_EMERGENCY_CHARS] 字），并在日终验收里点名。
     * 以前严格态度直接不发，等于把人锁在门外 —— 见 [Actions.emergency] 的说明。
     */
    fun emergency(
        day: DayLog,
        config: Config,
        status: Status,
        reason: String,
        now: Long,
        strictStance: Boolean = false,
    ): Pair<DayLog?, String> {
        val r = reason.trim()
        val need = if (strictStance) STRICT_EMERGENCY_CHARS else NORMAL_EMERGENCY_CHARS
        return when {
            !status.strict -> null to "现在没有在严管，用不着紧急放行"
            status.emergencyLeft <= 0 -> null to "今天的紧急放行已经用完（每天 ${config.emergencyPerDay} 次）"
            TextCheck.effectiveChars(r) < need -> null to if (strictStance) {
                "总控现在的态度是「严格」，紧急放行照样可以给，但要把为什么必须现在用写清楚——至少 $need 字，会记进日终验收"
            } else {
                "写清楚为什么必须现在用，至少 $need 字"
            }
            else -> {
                val until = now + config.emergencyMinutes * 60_000L
                val done = "已放行 ${config.emergencyMinutes} 分钟，会记进今天的日终验收"
                day.copy(
                    emergencies = day.emergencies + EmergencyPass(now, until, r),
                    outbox = day.outbox + LogEntry(
                        now,
                        "紧急放行 ${config.emergencyMinutes} 分钟",
                        Dept.HQ,
                        if (strictStance) "紧急放行（严格态度）" else "紧急放行",
                        r,
                    ),
                ) to if (strictStance) "$done（严格态度下也给了，这笔账记着）" else done
            }
        }
    }

    fun addPlay(day: DayLog, pkg: String, seconds: Long): DayLog {
        if (seconds <= 0) return day
        return day.copy(playSeconds = day.playSeconds + (pkg to (day.playSeconds[pkg] ?: 0L) + seconds))
    }

    fun bounce(day: DayLog): DayLog = day.copy(bounces = day.bounces + 1)

    fun offline(day: DayLog, from: Long, to: Long): DayLog =
        if (to - from < 5 * 60_000L) day else day.copy(offline = day.offline + OfflineGap(from, to))

    /** 某条日志写到某个库成功了。所有库都写完就从待写列表里去掉。 */
    fun outboxDone(day: DayLog, at: Long, target: String): DayLog = day.copy(
        outbox = day.outbox.mapNotNull { e ->
            if (e.at != at) e else (e.targets - target).takeIf { it.isNotEmpty() }?.let { e.copy(targets = it) }
        },
    )
}
