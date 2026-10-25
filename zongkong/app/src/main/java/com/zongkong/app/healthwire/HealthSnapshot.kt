package com.zongkong.app.healthwire

import com.zongkong.app.health.HealthAction
import com.zongkong.app.health.HealthObservation
import com.zongkong.app.health.HealthStatus

/*
 * 本地健康事实的快照（纯数据 + 纯映射）。
 *
 * 每一格都必须来自「真实发生过的事」：
 *   - instanceId：无障碍服务每次连上都换新的
 *   - lastAck*：挑战真的在 a11y 线程上处理完
 *   - lastEvent*：真的收到过可比对的无障碍事件
 * 不读 GuardService.running 当健康凭据（报告 §3.2 / §8）。
 */

data class HealthSnapshot(
    val installed: Boolean = true,
    /** 系统设置里允许无障碍。 */
    val permissionEnabled: Boolean = true,
    /** 已经向哨兵注册成功过（v2 register 回了 ok）。 */
    val registered: Boolean = false,
    /** 当前无障碍实例；null = 没连上。 */
    val instanceId: String? = null,
    val lastAckSeq: Long = 0,
    val lastAckAtMono: Long = 0,
    val lastEventSeq: Long = 0,
    val lastEventAtMono: Long = 0,
    val consecutiveFailures: Int = 0,
    val recoveringSinceMono: Long = 0,
    val recoveriesTried: Int = 0,
    val paused: Boolean = false,
    val lastFailureReason: String = "",
) {
    /** 有没有拿到过真实 dispatcherAck。 */
    val hasRealAck: Boolean get() = lastAckAtMono > 0L

    fun ackAgeMs(nowMono: Long): Long? =
        if (lastAckAtMono <= 0L) null else (nowMono - lastAckAtMono).coerceAtLeast(0L)

    fun eventAgeMs(nowMono: Long): Long? =
        if (lastEventAtMono <= 0L) null else (nowMono - lastEventAtMono).coerceAtLeast(0L)

    /** 喂给共享内核的健康评测器。这里不改任何判定逻辑。 */
    fun toObservation(): HealthObservation = HealthObservation(
        installed = installed,
        permissionEnabled = permissionEnabled,
        registered = registered,
        instanceId = instanceId,
        lastAckSeq = lastAckSeq,
        lastAckAtMono = lastAckAtMono,
        consecutiveFailures = consecutiveFailures,
        recoveringSinceMono = recoveringSinceMono,
        recoveriesTried = recoveriesTried,
        paused = paused,
        lastFailureReason = lastFailureReason,
    )
}

/** 界面文案（报告 §6/§10：宁可显示「未知」，不要涂绿）。 */
object HealthLabels {

    fun status(s: HealthStatus): String = when (s) {
        HealthStatus.UNREGISTERED -> "未接入"
        HealthStatus.HEALTHY -> "真实响应中"
        HealthStatus.SUSPECT -> "可疑：有失败迹象"
        HealthStatus.DISABLED -> "无障碍未开启"
        HealthStatus.UNRESPONSIVE -> "确认失联"
        HealthStatus.RECOVERING -> "正在恢复"
        HealthStatus.UNKNOWN -> "未知：证据不足"
        HealthStatus.PAUSED -> "已暂停"
    }

    /** 只有 HEALTHY 才是「好」；其余一律不能涂绿。 */
    fun isGreen(s: HealthStatus): Boolean = s == HealthStatus.HEALTHY

    fun action(a: HealthAction): String = when (a) {
        HealthAction.NONE -> ""
        HealthAction.PROBE -> "继续探测"
        HealthAction.START_RECOVERY -> "尝试有限恢复"
        HealthAction.ESCALATE -> "按预设失效处理"
        HealthAction.NOTIFY -> "需要你处理"
    }
}
