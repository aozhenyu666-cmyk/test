package com.yishou.app.healthwire

import com.yishou.app.health.Policy
import com.yishou.app.settings.AppPrefs
import com.yishou.app.window.WindowClock
import java.time.ZoneId

/**
 * 把「用户已经配置过的陪练窗口」翻译成本地监管规则（规范 §4）。
 *
 * 为什么从窗口配置来：规范 §4 明确「用户实际监管时段**尚未给出**：实现可配置时段 + 测试用短时窗口，
 * **不要替用户编造每天几点锁屏**」。所以这里不新增任何「几点锁」的猜测，
 * 只用用户自己在设置里填的窗口时段作为监管窗口。
 *
 * 一个窗口 → 一条规则：
 *  - `startsAtWall`/`endsAtWall`：今天（或昨天跨零点）那个窗口；
 *  - `lockAtWall`：**不生成**（规范 §4 说它是可选事件；本轮只做哨兵失联时的兜底，不编造定时锁屏）；
 *  - `requiredApps`：三个包（三体系共同监管，规范 §0 / §4）。
 * 纯函数，可 JVM 单测。
 */
object RuleBuilder {

    /** 三体系共同监管：规则要求这三个应用都在线（规范 §4 / 报告 §3.1）。 */
    val REQUIRED_APPS = setOf("com.zongkong.app", "com.yishou.app", "cn.companion.pet")

    const val POLICY_PREFIX = "yishou-window-"

    /**
     * @param nowWall 现在的墙上时间（用 ClampedClock 压过的值，避免改系统时间延长监管）。
     * @param failureAction LOCK / NOTIFY_ONLY（用户可选）。
     */
    fun build(
        prefs: AppPrefs,
        nowWall: Long,
        zone: ZoneId,
        failureAction: String = "LOCK",
        repairGraceMs: Long = 2 * 60_000L,
    ): List<Policy> {
        if (!prefs.windowEnabled && prefs.manualWindowStart <= 0L) return emptyList()
        val today = WindowClock.today(nowWall, zone)
        // 取「未来 24 小时内会开始或正在进行的」窗口，最多两条（今天 + 明天那一条），
        // 保证跨零点时也有规则可执行。
        val spans = (0..1).flatMap { offset ->
            WindowClock.spans(today.plusDays(offset.toLong()), prefs, zone)
        }.distinct()

        return spans
            .filter { it.end > nowWall - 6 * 3_600_000L }   // 已经过去很久的不再生成
            .map { span ->
                Policy(
                    policyId = "$POLICY_PREFIX${span.start}",
                    revision = 1,
                    issuer = LocalPolicy.ISSUER,
                    requiredApps = REQUIRED_APPS,
                    startsAtWall = span.start,
                    endsAtWall = span.end,
                    // 规范 §4：lockAtWall 是**可选**的到期锁屏事件，0 = 无。
                    // 这里刻意不编造「窗口结束时自动锁屏」——本轮只做「哨兵失联时的有限兜底」，
                    // 所以不生成定时锁屏事件。
                    lockAtWall = null,
                    failureAction = if (failureAction == "NOTIFY_ONLY") "NOTIFY_ONLY" else "LOCK",
                    repairGraceMs = repairGraceMs,
                    emergencyState = "NONE",
                    // 只把「正在进行、或即将开始（30 分钟内）」的那条设为启用：
                    // 远期窗口先存着但 enabled=false，避免一条规则从头管到尾。
                    enabled = span.start - nowWall <= 30 * 60_000L,
                )
            }
    }

    /**
     * 生成并入库。保留已经 STOPPED 的规则的暂停标记（紧急暂停不能被规则刷新悄悄解除）。
     * @return 落盘是否成功（规范 §4：先持久化才算「已就绪」）。
     */
    fun materialize(store: PolicyStore, prefs: AppPrefs, nowWall: Long, zone: ZoneId, failureAction: String): Boolean {
        val existing = store.load()
        val stoppedIds = existing.policies.filter { it.emergencyState == "STOPPED" }.map { it.policyId }.toSet()
        val fresh = build(prefs, nowWall, zone, failureAction).map {
            if (it.policyId in stoppedIds) it.copy(emergencyState = "STOPPED") else it
        }
        // 保留用户已有的 STOPPED 规则里、本次没有重建的那部分（比如这一轮暂停在别的窗口上生效过）。
        val keep = existing.policies.filter { it.policyId in stoppedIds && fresh.none { f -> f.policyId == it.policyId } }
        return store.save(LocalPolicy.Doc(LocalPolicy.SCHEMA_VERSION, fresh + keep))
    }
}
