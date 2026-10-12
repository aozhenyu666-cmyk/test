package com.zongkong.core

/**
 * 改规则的规矩：收紧立即生效，放宽要等 24 小时，期间可以撤回。
 * 冲动想放水的时候改不了，冷静下来还想改再说。
 */
object Policy {
    const val DELAY_MILLIS = 24 * 3600_000L

    data class Outcome(
        val config: Config,
        /** 立即生效的部分。 */
        val now: List<String> = emptyList(),
        /** 24 小时后生效的部分。 */
        val later: List<String> = emptyList(),
    ) {
        val message: String
            get() = buildList {
                if (now.isNotEmpty()) add("已生效：" + now.joinToString("；"))
                if (later.isNotEmpty()) add("放宽的部分 24 小时后生效，期间可以撤回：" + later.joinToString("；"))
                if (isEmpty()) add("没有改动")
            }.joinToString("\n")
    }

    // ---------- 关卡 ----------

    fun putGate(config: Config, gate: Gate, now: Long): Outcome {
        // 同一道关卡再次修改时，以新的为准，旧的待生效改动作废
        val base = config.copy(pending = config.pending.filterNot { it.touchesGate(gate.id) })
        val old = base.gates.firstOrNull { it.id == gate.id }
        if (old == null) {
            return Outcome(base.copy(gates = base.gates + gate), now = listOf("新增关卡「${gate.title}」"))
        }
        if (old == gate) return Outcome(base)
        val loosened = looserParts(old, gate)
        return if (loosened.isEmpty()) {
            Outcome(base.copy(gates = base.gates.map { if (it.id == gate.id) gate else it }), now = listOf("修改关卡「${gate.title}」"))
        } else {
            val summary = "关卡「${gate.title}」：" + loosened.joinToString("、")
            Outcome(base.queue(ChangeOp.PutGate(gate), summary, now), later = listOf(summary))
        }
    }

    fun removeGate(config: Config, gateId: String, now: Long): Outcome {
        val gate = config.gates.firstOrNull { it.id == gateId } ?: return Outcome(config)
        val base = config.copy(pending = config.pending.filterNot { it.touchesGate(gateId) })
        val summary = "删除关卡「${gate.title}」"
        return Outcome(base.queue(ChangeOp.RemoveGate(gateId), summary, now), later = listOf(summary))
    }

    /** 哪些地方变松了。空列表表示没有放宽。 */
    fun looserParts(old: Gate, new: Gate): List<String> = buildList {
        if (!new.days.containsAll(old.days)) add("减少了生效的日子")
        if (DayClock.rank(new.deadline) > DayClock.rank(old.deadline)) add("截止时间推迟到 ${DayClock.hhmm(new.deadline)}")
        if (old.block == BlockMode.FROM_OPEN && new.block == BlockMode.AFTER_DEADLINE) add("改为超时才拦截")
        if (new.block == BlockMode.FROM_OPEN && old.block == BlockMode.FROM_OPEN &&
            DayClock.rank(new.openAt) > DayClock.rank(old.openAt)
        ) {
            add("拦截开始时间推迟到 ${DayClock.hhmm(new.openAt)}")
        }
        if (new.verify.strength < old.verify.strength ||
            (new.verify != old.verify && new.verify.strength == old.verify.strength)
        ) {
            add("验收方式改为${new.verify.label}")
        }
        if (new.minChars < old.minChars) add("最少字数降到 ${new.minChars}")
        if (new.rubric.trim() != old.rubric.trim()) add("改了 AI 验收标准")
        if (new.notionDb.trim() != old.notionDb.trim() && old.notionDb.isNotBlank()) add("换了 Notion 数据库")
        if (new.notionMinPages < old.notionMinPages) add("Notion 条数降到 ${new.notionMinPages}")
        if (new.verify == VerifyMode.EVIDENCE && old.verify == VerifyMode.EVIDENCE && new.evidence != old.evidence) add("换了要看的证据")
        if (new.evidenceMin < old.evidenceMin) add("证据条数降到 ${new.evidenceMin}")
    }

    // ---------- 拦截名单 ----------

    fun setBlocked(config: Config, newSet: Set<String>, labels: (String) -> String, now: Long): Outcome {
        val added = newSet - config.blocked
        val removed = config.blocked - newSet
        // 重新勾上的应用（还在名单里、但有待生效的移除），撤销那条移除
        val kept = config.pending.filter { p -> p.op is ChangeOp.Unblock && (p.op as ChangeOp.Unblock).pkg in newSet }
        var c = config.copy(blocked = config.blocked + added, pending = config.pending - kept.toSet())
        val later = mutableListOf<String>()
        removed.forEach { pkg ->
            if (c.pending.none { it.op == ChangeOp.Unblock(pkg) }) {
                val summary = "拦截名单移除「${labels(pkg)}」"
                c = c.queue(ChangeOp.Unblock(pkg), summary, now)
                later += summary
            }
        }
        val nowList = buildList {
            if (added.isNotEmpty()) add("拦截名单加入 " + added.joinToString("、") { "「${labels(it)}」" })
            kept.forEach { add("撤回：${it.summary}") }
        }
        return Outcome(c, now = nowList, later = later)
    }

    // ---------- 规则 ----------

    data class Rules(
        val silenceHours: Int,
        val activeStart: Int,
        val activeEnd: Int,
        val dailyQuotaMin: Int,
        val emergencyPerDay: Int,
        val emergencyMinutes: Int,
        val degradedPerDay: Int,
    )

    fun rulesOf(c: Config) = Rules(
        c.silenceHours, c.activeStart, c.activeEnd, c.dailyQuotaMin, c.emergencyPerDay, c.emergencyMinutes, c.degradedPerDay,
    )

    /** “0 表示不限”的上限：0 最松，其余越大越松。 */
    private fun limitLooser(new: Int, old: Int) = old > 0 && (new == 0 || new > old)

    fun setRules(config: Config, r: Rules, now: Long): Outcome {
        val old = rulesOf(config)
        var c = config
        val nowParts = mutableListOf<String>()
        val laterParts = mutableListOf<String>()
        var op = ChangeOp.Rules()

        fun field(name: String, changed: Boolean, looser: Boolean, applyNow: (Config) -> Config, queue: () -> Unit) {
            if (!changed) return
            if (looser) {
                queue(); laterParts += name
            } else {
                c = applyNow(c); nowParts += name
            }
        }

        field(
            "沉默检查 ${if (r.silenceHours == 0) "关闭" else "${r.silenceHours} 小时"}",
            r.silenceHours != old.silenceHours, limitLooser(r.silenceHours, old.silenceHours),
            { it.copy(silenceHours = r.silenceHours) }, { op = op.copy(silenceHours = r.silenceHours) },
        )
        field(
            "检查时段开始 ${DayClock.hhmm(r.activeStart)}",
            r.activeStart != old.activeStart, DayClock.rank(r.activeStart) > DayClock.rank(old.activeStart),
            { it.copy(activeStart = r.activeStart) }, { op = op.copy(activeStart = r.activeStart) },
        )
        field(
            "检查时段结束 ${DayClock.hhmm(r.activeEnd)}",
            r.activeEnd != old.activeEnd, DayClock.rank(r.activeEnd) < DayClock.rank(old.activeEnd),
            { it.copy(activeEnd = r.activeEnd) }, { op = op.copy(activeEnd = r.activeEnd) },
        )
        field(
            "每日娱乐上限 ${if (r.dailyQuotaMin == 0) "不限" else "${r.dailyQuotaMin} 分钟"}",
            r.dailyQuotaMin != old.dailyQuotaMin, limitLooser(r.dailyQuotaMin, old.dailyQuotaMin),
            { it.copy(dailyQuotaMin = r.dailyQuotaMin) }, { op = op.copy(dailyQuotaMin = r.dailyQuotaMin) },
        )
        field(
            "紧急放行每天 ${r.emergencyPerDay} 次",
            r.emergencyPerDay != old.emergencyPerDay, r.emergencyPerDay > old.emergencyPerDay,
            { it.copy(emergencyPerDay = r.emergencyPerDay) }, { op = op.copy(emergencyPerDay = r.emergencyPerDay) },
        )
        field(
            "紧急放行每次 ${r.emergencyMinutes} 分钟",
            r.emergencyMinutes != old.emergencyMinutes, r.emergencyMinutes > old.emergencyMinutes,
            { it.copy(emergencyMinutes = r.emergencyMinutes) }, { op = op.copy(emergencyMinutes = r.emergencyMinutes) },
        )
        field(
            "降级验收每天 ${r.degradedPerDay} 次",
            r.degradedPerDay != old.degradedPerDay, r.degradedPerDay > old.degradedPerDay,
            { it.copy(degradedPerDay = r.degradedPerDay) }, { op = op.copy(degradedPerDay = r.degradedPerDay) },
        )

        if (laterParts.isNotEmpty()) {
            // 新的规则改动取代旧的待生效规则改动
            c = c.copy(pending = c.pending.filterNot { it.op is ChangeOp.Rules })
            c = c.queue(op, laterParts.joinToString("、"), now)
        }
        return Outcome(c, now = nowParts, later = laterParts)
    }

    // ---------- 总控管一手 ----------

    /** 开着、放行上限更短是收紧（立即）；关掉、上限变长是放宽（24 小时后）。 */
    fun setYishou(config: Config, on: Boolean, cap: Int, now: Long): Outcome {
        val c = cap.coerceIn(1, 30)
        val base = config.copy(pending = config.pending.filterNot { it.op is ChangeOp.Yishou })
        if (on == config.yishouInfluence && c == config.yishouPassCap) return Outcome(base)
        val looser = (!on && config.yishouInfluence) || (on && c > config.yishouPassCap)
        return if (!looser) {
            Outcome(base.copy(yishouInfluence = on, yishouPassCap = c), now = listOf(if (on) "总控收紧一手：落后时一手每次最多放行 $c 分钟" else "总控不再收紧一手"))
        } else {
            val summary = if (!on) "总控不再收紧一手" else "一手收紧时的放行放宽到 $c 分钟"
            Outcome(base.queue(ChangeOp.Yishou(on, c), summary, now), later = listOf(summary))
        }
    }

    // ---------- 总控时间 ----------

    /** 总控时间的设置：哪些地方变松了。 */
    fun looserHq(old: com.zongkong.core.hq.HqConfig, new: com.zongkong.core.hq.HqConfig): List<String> = buildList {
        if (old.enabled && !new.enabled) add("关掉每日总控时间")
        if (new.minutes < old.minutes) add("总控时间缩短到 ${new.minutes} 分钟")
        if (old.autoLock && !new.autoLock) add("到点不再自动进入")
        if (!old.allowed.containsAll(new.allowed)) add("总控时间里多放开了 " + (new.allowed - old.allowed).size + " 个应用")
        if (old.vibrate && !new.vibrate) add("关掉震动")
        if (old.escalation && !new.escalation) add("关掉失联阶梯")
        if (old.proactive && !new.proactive) add("总控不再主动找你")
        if (DayClock.rank(new.at) > DayClock.rank(old.at)) add("总控时间推迟到 ${DayClock.hhmm(new.at)}")
    }

    /** 两份设置取更严的那份（放宽的部分保持原样）。 */
    fun stricterHq(old: com.zongkong.core.hq.HqConfig, new: com.zongkong.core.hq.HqConfig): com.zongkong.core.hq.HqConfig = new.copy(
        enabled = old.enabled || new.enabled,
        minutes = maxOf(old.minutes, new.minutes),
        autoLock = old.autoLock || new.autoLock,
        allowed = old.allowed intersect new.allowed,
        vibrate = old.vibrate || new.vibrate,
        escalation = old.escalation || new.escalation,
        proactive = old.proactive || new.proactive,
        at = if (DayClock.rank(new.at) > DayClock.rank(old.at)) old.at else new.at,
    )

    /** 排队中的总控时间设置（24 小时后会变成这样）；没有就是现在的。设置页从这里开始改，免得把排队的改动冲掉。 */
    fun desiredHq(config: Config): com.zongkong.core.hq.HqConfig =
        (config.pending.lastOrNull { it.op is ChangeOp.Hq }?.op as? ChangeOp.Hq)?.hq ?: config.hq

    /**
     * 改总控时间的设置：收紧的部分立即生效，放宽的部分（关掉、缩短、推迟、放开应用……）24 小时后生效。
     * 一次保存里既有收紧又有放宽时，收紧的照样马上生效——不会被放宽的那部分拖住。
     */
    fun setHq(config: Config, hq: com.zongkong.core.hq.HqConfig, now: Long): Outcome {
        val base = config.copy(pending = config.pending.filterNot { it.op is ChangeOp.Hq })
        val tight = stricterHq(config.hq, hq)
        val nowList = if (tight != config.hq) listOf("总控时间的设置") else emptyList()
        if (tight == hq) return Outcome(base.copy(hq = hq), now = nowList)
        val summary = looserHq(tight, hq).joinToString("、").ifBlank { "总控时间的设置" }
        return Outcome(base.copy(hq = tight).queue(ChangeOp.Hq(hq), summary, now), now = nowList, later = listOf(summary))
    }

    // ---------- 总控者 ----------

    /** 排队中的总控者设置；没有就是现在的。 */
    fun desiredBoss(config: Config): com.zongkong.core.boss.BossConfig =
        (config.pending.lastOrNull { it.op is ChangeOp.Boss }?.op as? ChangeOp.Boss)?.boss ?: config.boss

    /** 总控者的眼睛：多看、多查立即生效；少看、少查、关掉、解开模型锁 24 小时后生效。收紧的部分不被放宽的拖住。 */
    fun setBoss(config: Config, boss: com.zongkong.core.boss.BossConfig, now: Long): Outcome {
        val base = config.copy(pending = config.pending.filterNot { it.op is ChangeOp.Boss })
        val tight = com.zongkong.core.boss.BossConfig.stricter(config.boss, boss)
        val nowList = if (tight != config.boss) listOf("总控者的设置") else emptyList()
        if (tight == boss) return Outcome(base.copy(boss = boss), now = nowList)
        val summary = tight.looser(boss).joinToString("、").ifBlank { "总控者的设置" }
        return Outcome(base.copy(boss = tight).queue(ChangeOp.Boss(boss), summary, now), now = nowList, later = listOf(summary))
    }

    /**
     * 换 AI 接口。模型锁开着、现在的接口能用时，换地址 / 密钥 / 模型要 24 小时；
     * 第一次填、现在的接口坏了、只改看图模型或 JSON 模式，立即生效。
     */
    fun setAi(config: Config, ai: AiConfig, currentWorks: Boolean, now: Long): Outcome {
        val base = config.copy(pending = config.pending.filterNot { it.op is ChangeOp.Ai })
        val old = config.ai
        val core = old.baseUrl.trim() != ai.baseUrl.trim() || old.apiKey.trim() != ai.apiKey.trim() || old.model.trim() != ai.model.trim()
        if (!core || !config.boss.modelLock || !old.ready || !currentWorks) {
            return Outcome(base.copy(ai = ai), now = listOf("AI 接口"))
        }
        val summary = "AI 接口换成 ${ai.model.ifBlank { "（空）" }}"
        return Outcome(base.queue(ChangeOp.Ai(ai), summary, now), later = listOf(summary))
    }

    // ---------- 休假 ----------

    /** 申请休假：24 小时后开始，休 [days] 天。 */
    fun requestPause(config: Config, days: Int, now: Long): Outcome {
        val until = now + DELAY_MILLIS + days * 24 * 3600_000L
        val summary = "休假 $days 天（不拦截）"
        val c = config.copy(pending = config.pending.filterNot { it.op is ChangeOp.Pause })
        return Outcome(c.queue(ChangeOp.Pause(until), summary, now), later = listOf(summary))
    }

    /** 提前结束休假：收紧，立即生效。 */
    fun endPause(config: Config, now: Long): Outcome =
        Outcome(config.copy(pausedUntil = minOf(config.pausedUntil, now)), now = listOf("结束休假"))

    // ---------- 待生效 ----------

    fun cancel(config: Config, id: String): Config = config.copy(pending = config.pending.filterNot { it.id == id })

    /** 把到点的放宽改动应用上。没有到点的原样返回。 */
    fun applyDue(config: Config, now: Long): Config {
        val due = config.pending.filter { it.effectiveAt <= now }
        if (due.isEmpty()) return config
        var c = config.copy(pending = config.pending - due.toSet())
        due.sortedBy { it.effectiveAt }.forEach { c = apply(c, it.op) }
        return c
    }

    fun apply(c: Config, op: ChangeOp): Config = when (op) {
        is ChangeOp.PutGate -> c.copy(
            gates = if (c.gates.any { it.id == op.gate.id }) c.gates.map { if (it.id == op.gate.id) op.gate else it } else c.gates + op.gate,
        )
        is ChangeOp.RemoveGate -> c.copy(gates = c.gates.filterNot { it.id == op.gateId })
        is ChangeOp.Unblock -> c.copy(blocked = c.blocked - op.pkg)
        is ChangeOp.Rules -> c.copy(
            silenceHours = op.silenceHours ?: c.silenceHours,
            activeStart = op.activeStart ?: c.activeStart,
            activeEnd = op.activeEnd ?: c.activeEnd,
            dailyQuotaMin = op.dailyQuotaMin ?: c.dailyQuotaMin,
            emergencyPerDay = op.emergencyPerDay ?: c.emergencyPerDay,
            emergencyMinutes = op.emergencyMinutes ?: c.emergencyMinutes,
            degradedPerDay = op.degradedPerDay ?: c.degradedPerDay,
        )
        is ChangeOp.Pause -> c.copy(pausedUntil = op.until)
        is ChangeOp.Yishou -> c.copy(yishouInfluence = op.on, yishouPassCap = op.cap)
        is ChangeOp.Hq -> c.copy(hq = op.hq)
        is ChangeOp.Boss -> c.copy(boss = op.boss)
        is ChangeOp.Ai -> c.copy(ai = op.ai)
    }

    private fun Config.queue(op: ChangeOp, summary: String, now: Long): Config {
        val id = "p" + now.toString(36) + pending.size
        return copy(pending = pending + PendingChange(id, now, now + DELAY_MILLIS, summary, op))
    }

    private fun PendingChange.touchesGate(gateId: String) = when (val o = op) {
        is ChangeOp.PutGate -> o.gate.id == gateId
        is ChangeOp.RemoveGate -> o.gateId == gateId
        else -> false
    }
}
