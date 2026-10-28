package com.zongkong.app.healthwire

/*
 * 修复宽限（T15）—— 纯状态机，无 Android 依赖，可在 JVM 单测里用假时钟推进。
 *
 * 要防的场景（报告 §8）：
 *   「解锁后给出明确的修复界面与短暂宽限；在恢复无障碍、检查权限和系统必要界面时，
 *     不立即再次锁屏。」
 *
 * 也就是说：我们刚把屏幕锁上、用户解锁后正要去系统设置里修无障碍，
 * 这时**不能**每 15 秒又把他锁回去。
 *
 * 和 LockArbiter 的分工（两件事，不能互相替代）：
 *   - LockArbiter：同一 incidentId 的次数上限 + 冷却 + 每事件去重 → 防"同一故障反复锁"；
 *   - RepairGrace（这里）：锁屏之后的**时间窗宽限** → 防"用户正在修权限时被打扰"。
 *
 * 关于「解锁信号」的诚实说明：
 *   理想信号是 KeyguardManager 的 locked→unlocked 跳变 + ACTION_USER_PRESENT 广播。
 *   但总控的巡检是 15 秒一个 tick，锁屏/解锁很可能整个发生在一个 tick 间隙里，
 *   **观测不到**。所以这里有两层：
 *     1) [onLockConfirmed]：确认锁上之后**立即**开始计宽限（退化策略，宁可早给宽限）；
 *     2) [observe]：如果真观测到 locked→unlocked 跳变，就重新起算一个完整宽限。
 *   第 1 条是退化策略，真机必须验证（见 ZONGKONG-V2-NOTES.md 的 NOT_RUN）。
 */

/** Keyguard 的一个观测结果（解耦 android.app.KeyguardManager，便于纯单测）。 */
data class LockObserved(
    val locked: Boolean,
    val atMono: Long,
)

/**
 * 修复宽限状态机。
 *
 * @param defaultGraceMs 默认宽限（`HealthConfig().repairGraceMs`，与一手 / 同桌同口径）。
 */
class RepairGrace(
    private val defaultGraceMs: Long = 120_000L,
) {
    /** 宽限到期时刻（单调时钟）；0 = 没有宽限。 */
    @Volatile
    var untilMono: Long = 0L
        private set

    /** 上一次观测到「在锁屏」——用来识别「刚解锁」。 */
    @Volatile
    var wasLocked: Boolean = false
        private set

    /** 现在是否处于宽限期。 */
    fun active(nowMono: Long): Boolean = untilMono > 0L && nowMono < untilMono

    /** 剩余宽限毫秒（不在宽限内 = 0）。 */
    fun remainingMs(nowMono: Long): Long = (untilMono - nowMono).coerceAtLeast(0L)

    /**
     * 确认发生过一次锁屏之后调用：**立即**开始计宽限（退化的解锁感知）。
     *
     * 为什么要在「锁上」就开始、而不是等「解锁」：tick 间隔 15 秒，
     * 锁屏→解锁→进设置这一串动作经常整个落在两个 tick 之间，等解锁信号就会漏。
     * 早给宽限的代价是「本该锁的时候少锁一次」，远小于「把用户挡在修复界面外面」。
     *
     * @return 新的到期时刻。
     */
    fun onLockConfirmed(nowMono: Long, graceMs: Long = defaultGraceMs): Long {
        val g = if (graceMs > 0L) graceMs else defaultGraceMs
        untilMono = nowMono + g
        wasLocked = true
        return untilMono
    }

    /**
     * 观测一次 Keyguard 事实。
     *
     * @return true = 这次真的观测到 locked→unlocked 跳变，并重新起算了一个完整宽限。
     */
    fun observe(lockedNow: Boolean, nowMono: Long, graceMs: Long = defaultGraceMs): Boolean {
        val justUnlocked = wasLocked && !lockedNow
        wasLocked = lockedNow
        if (!justUnlocked) return false
        untilMono = nowMono + (if (graceMs > 0L) graceMs else defaultGraceMs)
        return true
    }

    /** 把 [LockObserved] 版本转成 [observe]（方便调用方统一）。 */
    fun observe(o: LockObserved, graceMs: Long = defaultGraceMs): Boolean = observe(o.locked, o.atMono, graceMs)

    /** 到期就清 0（要求：宽限到期后必须恢复可执行状态，不能永久豁免）。 */
    fun expire(nowMono: Long): Boolean {
        if (untilMono > 0L && nowMono >= untilMono) {
            untilMono = 0L
            return true
        }
        return false
    }

    /** 用户主动恢复监管 / 紧急暂停解除 / 仲裁重置时清掉。 */
    fun clear() {
        untilMono = 0L
        wasLocked = false
    }

    /** 一句话（诊断 / 状态页用，不涂绿）。 */
    fun describe(nowMono: Long): String = when {
        untilMono <= 0L -> "没有修复宽限"
        active(nowMono) -> "修复宽限中，剩余 ${remainingMs(nowMono) / 1000}s（锁屏后不重锁，别挡着修权限）"
        else -> "修复宽限已过期（可再次执行）"
    }

    /**
     * 从 Android 的 KeyguardFacts 转成纯数据的桥（放在这里，避免纯单测依赖 Android）。
     */
    fun observeAndroidFacts(keyguardShowing: Boolean, deviceLocked: Boolean, nowMono: Long, graceMs: Long = defaultGraceMs): Boolean =
        observe(keyguardShowing || deviceLocked, nowMono, graceMs)

    companion object {
        /**
         * 哪些锁屏回执状态算「这次锁屏确实发生过」，值得开始计宽限。
         *
         * - `OBSERVED_LOCKED`：观测到锁上了 → 一定要给宽限；
         * - `API_CALLED` / `UNVERIFIED`：系统接口调过（可能只是熄屏，没观测到）→ 仍然给宽限：
         *   用户很可能正因为屏幕黑了才去解锁，这时再过 15 秒把他锁回去是最糟的体验；
         * - `REQUESTED` / `FAILED`：连调用都没发出去 / 失败 → **不给**宽限，
         *   否则「一直锁不上」会变成「永远不锁」。
         */
        fun armAfterReceipt(state: com.zongkong.app.health.LockState): Boolean = when (state) {
            com.zongkong.app.health.LockState.OBSERVED_LOCKED,
            com.zongkong.app.health.LockState.API_CALLED,
            com.zongkong.app.health.LockState.UNVERIFIED,
            -> true

            com.zongkong.app.health.LockState.REQUESTED,
            com.zongkong.app.health.LockState.FAILED,
            -> false
        }

        /** 把 android 的 KeyguardFacts 映射成纯观测（供调用方复用，避免各处重复写 ||）。 */
        fun fromFacts(facts: com.zongkong.app.health.KeyguardFacts?, nowMono: Long): LockObserved? {
            if (facts == null) return null
            return LockObserved(locked = facts.deviceLocked || facts.keyguardShowing, atMono = nowMono)
        }
    }
}
