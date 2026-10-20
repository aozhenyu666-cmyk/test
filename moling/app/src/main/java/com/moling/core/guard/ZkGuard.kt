package com.moling.core.guard

import kotlinx.serialization.Serializable

/*
 * 墨灵盯着总控：总控的无障碍掉了，墨灵是第三道防线（第一道是总控自愈，第二道是 0.11 的哨兵）。
 *
 * 墨灵自己不用无障碍：它用“使用情况访问”看前台、用自己的悬浮窗说话，所以和总控不在同一个故障点上。
 * 判断分四种情况：
 *   正常 → 不动
 *   用户自己关的 → 尊重：只记一笔，第二天问一句；不修、不冻
 *   意外掉线 → 先修（用 WRITE_SECURE_SETTINGS 把总控的无障碍关了再开），有退避
 *   修不好 → 上报：小窗 + 通知；权柄够就冻结娱乐 App（Shizuku），总控回来后解冻
 */

@Serializable
enum class ZkState(val label: String) {
    OK("总控正常"),
    UNREACHABLE("连不上总控（没装、被杀或版本太旧）"),
    STALE("总控开着但没在跑"),
    OFF("总控的无障碍被关了"),
    USER_OFF("用户自己关掉了总控"),
}

@Serializable
enum class GuardAction { NONE, NOTE, REPAIR, ESCALATE, UNFREEZE }

/** 一次观察。[zkReachable]：能连上总控的接口；[secureSettingsOn]：系统设置里总控的无障碍开着。 */
data class GuardSample(
    val now: Long,
    val zkReachable: Boolean,
    val secureSettingsOn: Boolean,
    val heartbeatAt: Long,
    val userDisabled: Boolean,
    val screenOn: Boolean,
    /** 墨灵有没有 WRITE_SECURE_SETTINGS（adb 授权一次）。 */
    val canRepair: Boolean,
)

@Serializable
data class GuardMemory(
    val state: ZkState = ZkState.OK,
    val since: Long = 0,
    val repairs: List<Long> = emptyList(),
    val escalatedAt: Long = 0,
    val frozen: Boolean = false,
    val downtimeMsToday: Long = 0,
    val day: String = "",
    /** 上一次观察的时间（算掉线时长用）。 */
    val lastAt: Long = 0,
)

data class GuardDecision(val memory: GuardMemory, val action: GuardAction, val message: String)

object ZkGuard {
    private const val MIN = 60_000L
    /** 心跳超过这么久没更新、且亮屏，算“开着但没在跑”。 */
    const val STALE_MS = 3 * MIN
    /** 10 分钟内最多修 3 次。 */
    const val REPAIR_WINDOW = 10 * MIN
    const val REPAIR_MAX = 3
    /** 掉线多久还没好就上报。 */
    const val ESCALATE_AFTER = 4 * MIN

    fun classify(s: GuardSample): ZkState = when {
        !s.zkReachable -> ZkState.UNREACHABLE
        s.userDisabled -> ZkState.USER_OFF
        !s.secureSettingsOn -> ZkState.OFF
        s.screenOn && s.heartbeatAt > 0 && s.now - s.heartbeatAt > STALE_MS -> ZkState.STALE
        else -> ZkState.OK
    }

    fun step(m: GuardMemory, s: GuardSample, day: String): GuardDecision {
        val state = classify(s)
        val base = if (m.day == day) m else m.copy(day = day, downtimeMsToday = 0)
        val since = if (state == base.state) base.since.takeIf { it > 0 } ?: s.now else s.now
        val down = state == ZkState.OFF || state == ZkState.STALE || state == ZkState.UNREACHABLE
        // 掉线时长：两次观察之间的间隔（最多算 2 分钟，防止手机睡着时一下子记很久）
        val gap = if (base.lastAt > 0) (s.now - base.lastAt).coerceIn(0, 2 * MIN) else 0
        val downtime = base.downtimeMsToday + if (down) gap else 0
        var mem = base.copy(state = state, since = since, downtimeMsToday = downtime, lastAt = s.now, repairs = base.repairs.filter { s.now - it < REPAIR_WINDOW })

        if (state == ZkState.OK) {
            val action = if (mem.frozen) GuardAction.UNFREEZE else GuardAction.NONE
            val msg = if (base.state != ZkState.OK && base.since > 0) "总控回来了（离线 ${(s.now - base.since) / MIN} 分钟）" else ""
            return GuardDecision(mem.copy(frozen = false, escalatedAt = 0), action, msg)
        }
        if (state == ZkState.USER_OFF) {
            val first = base.state != ZkState.USER_OFF
            return GuardDecision(mem, if (first) GuardAction.NOTE else GuardAction.NONE, if (first) "你自己关掉了总控。我不去动它，明天问你一句为什么。" else "")
        }
        // 意外掉线：先修，有退避
        if (s.canRepair && state != ZkState.UNREACHABLE && mem.repairs.size < REPAIR_MAX) {
            mem = mem.copy(repairs = mem.repairs + s.now)
            return GuardDecision(mem, GuardAction.REPAIR, "总控${state.label.removePrefix("总控")}，我在把它重新打开（第 ${mem.repairs.size} 次）")
        }
        // 修不了 / 修了还不行：掉线够久就上报（每 30 分钟最多一次）
        if (s.now - since >= ESCALATE_AFTER && s.now - mem.escalatedAt >= 30 * MIN) {
            val how = if (s.canRepair) "我修了 ${REPAIR_MAX} 次没修好" else "我没有修复权限（要在电脑上执行一次 adb 授权）"
            return GuardDecision(mem.copy(escalatedAt = s.now, frozen = true), GuardAction.ESCALATE, "${state.label}，$how。娱乐 App 先冻着，帮我开一下总控？")
        }
        return GuardDecision(mem, GuardAction.NONE, "")
    }
}
