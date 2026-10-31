package com.zongkong.sentinel.watch

import com.zongkong.sentinel.health.HealthConfig
import com.zongkong.sentinel.health.HealthEvaluator
import com.zongkong.sentinel.health.HealthStatus
import com.zongkong.sentinel.health.OverallReadiness
import com.zongkong.sentinel.health.Policy
import com.zongkong.sentinel.health.PolicyClock
import com.zongkong.sentinel.health.Readiness

/**
 * 统计页与首页的**口径**（报告 §10，规范 §7）。纯逻辑、可单测——界面只负责把这里算好的字显示出来。
 *
 * 硬要求：
 *  - 三客户端三行 + 哨兵自己一行；
 *  - `UNREGISTERED` = 「未接入」，没证据 = 「未知」，都**不许涂绿**；
 *  - 首页四态：三体系已就绪 / 部分监管失效 / 哨兵兜底不可用 / 没有生效中的监管窗口；
 *  - 没有观测的数据标「数据缺失」，不塞进分母。
 */
object StatusPage {

    data class Config(
        val health: HealthConfig = HealthConfig(),
        /** 必须在线的应用。空 = 以生效中规则自己写的 requiredApps 为准（默认）。 */
        val requiredApps: Set<String> = emptySet(),
    )

    /** 一行的全部显示字段（报告 §10 列出的七件事）。 */
    data class Row(
        val pkg: String,
        val title: String,
        val status: HealthStatus,
        /** 「正常 / 未接入 / 未知 …」 */
        val statusText: String,
        val versionText: String,
        val registeredText: String,
        val permissionText: String,
        val lastAckText: String,
        val eventText: String,
        val windowText: String,
        val failureText: String,
        val lockText: String,
        /** 这一行是否算「真实健康」。只有 HEALTHY 才是。 */
        val healthy: Boolean,
    )

    data class Page(
        val overall: OverallReadiness,
        val overallText: String,
        val rows: List<Row>,
        val emergencyText: String?,
        val statsText: String,
        val activeWindowsText: String,
        val lockSummaryText: String,
    )

    fun build(
        snapshot: HealthStore.Snapshot,
        policies: List<Policy>,
        nowWall: Long,
        nowMono: Long,
        cfg: Config = Config(),
        /** `:watch` 进程是否在跑：由调用方按「记录新鲜度」判断，不猜。 */
        selfAvailable: Boolean = false,
        /** 哨兵自己的无障碍开关（调用方从系统设置读）；null = 不知道，按「未开启」显示。 */
        selfAccessibilityEnabled: Boolean? = null,
    ): Page {
        val active = policies.filter { it.effective(nowWall) }
        val required = if (cfg.requiredApps.isEmpty()) active.flatMap { it.requiredApps }.toSet() else cfg.requiredApps
        val statuses = LinkedHashMap<String, HealthStatus>()
        val rows = ArrayList<Row>()
        val watchAlive = selfAvailable || watchFresh(snapshot, nowWall)

        for (pkg in HealthStore.ROWS) {
            val isSelf = pkg == HealthStore.SELF
            var rec = snapshot.clients[pkg] ?: HealthStore.ClientRecord(pkg = pkg, installed = isSelf)
            if (isSelf) {
                // 哨兵自己不向自己发挑战。它的这一行只写**可观测事实**：
                //  - :watch 最近有没有在写记录（这是「进程活着」的直接证据，不是 running 布尔）；
                //  - 无障碍开没开（调用方从系统设置读）。
                rec = rec.copy(
                    installed = true,
                    registered = watchAlive,
                    permissionEnabled = selfAccessibilityEnabled ?: rec.permissionEnabled,
                    // 记录新鲜等价于「刚刚还在推进」：写成 now 以免显示「多少秒前」误导。
                    lastAckAtMono = if (watchAlive) nowMono else rec.lastAckAtMono,
                    lastFailureReason = if (watchAlive) "" else "没读到 :watch 的最新记录",
                )
            }
            val verdict = HealthEvaluator.evaluate(nowMono, rec.observation(), cfg.health)
            statuses[pkg] = verdict.status
            val win = windowText(pkg, active, nowWall)
            // 哨兵自己那行不用「未接入」这种给客户端用的词：它不向自己注册，说的应该是兜底在不在。
            val selfText = when {
                !isSelf -> null
                watchAlive && (selfAccessibilityEnabled != false) -> "在岗（:watch 记录在更新，无障碍已开启）"
                watchAlive -> "在岗，但哨兵自己的无障碍没开：拦不住应用，只剩锁屏兜底"
                else -> "兜底不可用：:watch 没有在写记录"
            }
            val failure = if (verdict.status == HealthStatus.HEALTHY) {
                if (rec.recoveriesTried > 0) "恢复过 ${rec.recoveriesTried} 次（当前正常）" else "无"
            } else {
                buildString {
                    append(verdict.reason)
                    if (rec.consecutiveFailures > 0) append("；连续失败 ${rec.consecutiveFailures} 次")
                    if (rec.recoveriesTried > 0) append("；已试恢复 ${rec.recoveriesTried} 次")
                    if (rec.lastRecoveryNote.isNotBlank()) append("；${rec.lastRecoveryNote}")
                }
            }
            rows += Row(
                pkg = pkg,
                title = title(pkg),
                status = verdict.status,
                statusText = selfText ?: HealthStore.label(verdict.status, nowMono, rec.lastAckAtMono),
                versionText = versionText(rec, isSelf),
                registeredText = if (rec.registered) "已接入" else "未接入",
                permissionText = when {
                    !rec.installed && !isSelf -> "未安装"
                    rec.permissionEnabled -> "无障碍已开启"
                    else -> "无障碍未开启"
                },
                lastAckText = if (rec.lastAckAtMono <= 0) "还没有真实响应"
                else "${(nowMono - rec.lastAckAtMono).coerceAtLeast(0) / 1000}s 前",
                eventText = if (isSelf) "不需要（哨兵自己收事件）" else rec.eventChannel,
                windowText = win,
                failureText = failure,
                lockText = lockText(snapshot.locks, rec),
                healthy = verdict.status == HealthStatus.HEALTHY,
            )
        }

        val selfRec = snapshot.clients[HealthStore.SELF]
        // 哨兵自己不发挑战给自己：只看「:watch 最近有没有在写记录」+ 无障碍开没开。
        // 记录不新鲜 → 不认为兜底可用（宁可说不可用，也不涂绿）。
        val sentinelHealthy = selfAvailable || (selfRec != null &&
            selfRec.registered &&
            selfRec.permissionEnabled &&
            watchFresh(snapshot, nowWall))
        val overall = Readiness.overall(policies, nowWall, required, statuses, sentinelHealthy)

        return Page(
            overall = overall,
            overallText = overallText(overall),
            rows = rows,
            emergencyText = snapshot.emergency?.let { Emergency.describe(it, HealthStore.ROWS) },
            statsText = statsText(snapshot),
            activeWindowsText = activeWindowsText(active, nowWall),
            lockSummaryText = "锁屏尝试 ${snapshot.locks.attempts} 次：确认成功 ${snapshot.locks.confirmed}，" +
                "未验证 ${snapshot.locks.unverified}，失败 ${snapshot.locks.failed}" +
                (if (snapshot.locks.lastAtWall > 0) "；最近一次 ${snapshot.locks.lastState}（${snapshot.locks.lastNote}）" else ""),
        )
    }

    fun overallText(r: OverallReadiness): String = when (r) {
        OverallReadiness.ALL_READY -> "三体系已就绪"
        OverallReadiness.PARTIAL_FAILURE -> "部分监管失效"
        OverallReadiness.SENTINEL_UNAVAILABLE -> "哨兵兜底不可用"
        OverallReadiness.NO_ACTIVE_WINDOW -> "没有生效中的监管窗口"
    }

    /**
     * `:watch` 是否还在推进：只看**记录文件的更新时间**（每个 tick 都会写），
     * 不看任何 `running` 布尔。超过 [WATCH_STALE_MS] 没更新就当兜底不可用。
     */
    fun watchFresh(snapshot: HealthStore.Snapshot, nowWall: Long): Boolean {
        val at = snapshot.updatedAtWall
        if (at <= 0) return false
        val age = nowWall - at
        return age in 0..WATCH_STALE_MS
    }

    const val WATCH_STALE_MS = 60_000L

    fun title(pkg: String): String = when (pkg) {
        HealthStore.ZONGKONG -> "总控"
        HealthStore.YISHOU -> "一手"
        HealthStore.PET -> "同桌"
        else -> "哨兵自己"
    }

    private fun versionText(rec: HealthStore.ClientRecord, isSelf: Boolean): String = when {
        rec.versionName.isBlank() && isSelf -> "本机哨兵"
        rec.versionName.isBlank() -> "未安装"
        else -> "${rec.versionName}（${rec.versionCode}）"
    }

    private fun windowText(pkg: String, active: List<Policy>, nowWall: Long): String {
        val mine = active.filter { pkg in it.requiredApps }
        if (mine.isEmpty()) return "当前没有要求它必须在线的窗口"
        return mine.joinToString("；") { p ->
            val left = PolicyClock.remainingMs(p, nowWall)
            val next = PolicyClock.nextDeadline(p, nowWall)
            "窗口 ${p.policyId} 还剩 ${fmt(left)}" + (next?.let { "，下次截止 ${fmt(it - nowWall)} 后" } ?: "，无到期锁屏")
        }
    }

    private fun lockText(l: HealthStore.LockLog, rec: HealthStore.ClientRecord): String = when {
        l.attempts <= 0 -> "还没有锁屏尝试"
        rec.pkg != HealthStore.SELF -> "最近回执：${rec.lastReceiptState.ifBlank { "无" }}"
        else -> "最近：${l.lastState}（${l.lastNote}）"
    }

    /** 统计口径：没观测就写「数据缺失」，不塞分母（报告 §10）。 */
    fun statsText(snapshot: HealthStore.Snapshot): String {
        val parts = ArrayList<String>()
        for (pkg in HealthStore.ROWS) {
            val rec = snapshot.clients[pkg] ?: continue
            val s = rec.stats
            val avail = s.availability?.let { String.format("%.1f%%", it * 100) } ?: "数据缺失"
            val rec2 = s.recoveryRate?.let { String.format("%.0f%%", it * 100) } ?: "数据缺失"
            val latency = s.avgDetectLatencyMs?.let { "${it}ms" } ?: "数据缺失"
            parts += "${title(pkg)}：有效监测 ${s.monitoredMs / 1000}s，失效 ${s.outageCount} 次 / ${s.outageTotalMs / 1000}s，" +
                "可用率 $avail，恢复成功率 $rec2，故障发现延迟 $latency"
        }
        return if (parts.isEmpty()) "还没有任何观测数据" else parts.joinToString("\n")
    }

    private fun activeWindowsText(active: List<Policy>, nowWall: Long): String =
        if (active.isEmpty()) "没有生效中的监管窗口（未启用 / 不在时段）"
        else active.joinToString("；") { p ->
            "${p.policyId}（${p.issuer}，rev ${p.revision}）到 ${fmt(PolicyClock.remainingMs(p, nowWall))} 后结束" +
                if (p.emergencyState == PolicyStore.EMERGENCY_STOPPED) "【已紧急暂停】" else ""
        }

    fun fmt(ms: Long): String = when {
        ms < 60_000 -> "${ms / 1000} 秒"
        ms < 3_600_000 -> "${ms / 60_000} 分钟"
        else -> "${ms / 3_600_000} 小时 ${(ms % 3_600_000) / 60_000} 分钟"
    }
}
