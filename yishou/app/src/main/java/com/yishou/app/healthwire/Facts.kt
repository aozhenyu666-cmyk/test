package com.yishou.app.healthwire

/**
 * 一手的本地事实（进程内共享）。
 *
 * 一手只有一个进程，所以这里用 volatile 字段就够了；跨进程（哨兵）一律走 ContentProvider。
 *
 * 每一格都必须来自「真实发生过的事」：
 *   - [instanceId]：无障碍服务每次连上都换新的（重建必须换新）
 *   - [eventSeq]/[eventAtMono]：真的收到过可比对的无障碍窗口事件
 *   - [receiptState]/[receiptAtMono]：本地锁屏真的执行过并分类过
 * 不读「服务在跑」这类布尔当健康凭据（规范 §0 铁律 1）。
 */
data class FactsSnapshot(
    val instanceId: String? = null,
    val eventSeq: Long = 0,
    val eventAtMono: Long = 0,
    val receiptState: String = "",
    val receiptAtMono: Long = 0,
    val ruleRevision: Int = 0,
    /** 最近一次「启动页面」回执的状态（T18）。 */
    val launchState: String = "",
    val launchAtMono: Long = 0,
    val paused: Boolean = false,
) {
    /** 无障碍连接实例在不在（≠ 健康，只是「能不能投递挑战」）。 */
    val connected: Boolean get() = !instanceId.isNullOrBlank()
}

/** 进程内的事实仓库。纯 Kotlin，可在 JVM 单测里直接推进。 */
object Facts {

    @Volatile private var instanceId: String? = null
    @Volatile private var eventSeq: Long = 0
    @Volatile private var eventAtMono: Long = 0
    @Volatile private var receiptState: String = ""
    @Volatile private var receiptAtMono: Long = 0
    @Volatile private var ruleRevision: Int = 0
    @Volatile private var launchState: String = ""
    @Volatile private var launchAtMono: Long = 0
    @Volatile private var pausedFlag: Boolean = false

    /** 用户按了紧急暂停（规范 §6）：本地立即生效，不依赖网络与模型。 */
    val paused: Boolean get() = pausedFlag

    fun setPaused(value: Boolean) {
        pausedFlag = value
    }

    fun onConnected(newInstanceId: String) {
        instanceId = newInstanceId
    }

    fun onDisconnected() {
        instanceId = null
    }

    /** 收到一个可比对的窗口事件，返回递增后的序号。 */
    @Synchronized
    fun onEvent(nowMono: Long): Long {
        eventSeq += 1
        eventAtMono = nowMono
        return eventSeq
    }

    fun onReceipt(state: String, nowMono: Long) {
        receiptState = state
        receiptAtMono = nowMono
    }

    fun onRuleRevision(revision: Int) {
        ruleRevision = revision
    }

    /** T18：启动页面的回执（REQUESTED / API_ACCEPTED / VERIFIED_FOREGROUND / NOT_FOREGROUND / FAILED）。 */
    fun onLaunch(state: String, nowMono: Long) {
        launchState = state
        launchAtMono = nowMono
    }

    fun snapshot(): FactsSnapshot = FactsSnapshot(
        instanceId = instanceId,
        eventSeq = eventSeq,
        eventAtMono = eventAtMono,
        receiptState = receiptState,
        receiptAtMono = receiptAtMono,
        ruleRevision = ruleRevision,
        launchState = launchState,
        launchAtMono = launchAtMono,
        paused = pausedFlag,
    )

    /** 供 [A11yChallengeHandler] 在 a11y 线程回话之后读取。 */
    fun ackFacts(): AckFacts = AckFacts(
        ruleRevision = ruleRevision,
        lastReceiptState = receiptState,
        lastReceiptAtMono = receiptAtMono,
        eventSeq = eventSeq,
        eventAtMono = eventAtMono,
    )

    /** 单测用：清空，模拟「进程刚起来 / 服务重新连接」。 */
    @Synchronized
    fun reset() {
        instanceId = null
        eventSeq = 0
        eventAtMono = 0
        receiptState = ""
        receiptAtMono = 0
        ruleRevision = 0
        launchState = ""
        launchAtMono = 0
        pausedFlag = false
    }
}
