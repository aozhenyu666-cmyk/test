package com.yishou.app.healthwire

import com.yishou.app.link.ZongkongStatus

/*
 * 「总控状态」的只读缓存（纯逻辑，可在 JVM 单测里推进时间）。
 *
 * 报告 §8 的关键缺陷：
 *   一手 gate/GateService.kt 在**无障碍事件线程**里同步调 ZongkongClient.status()。
 *   一次跨进程调用卡住，整条无障碍事件通道就停了——拦截、计时、锁屏全废（T04/T05）。
 *
 * 修正成三步，本文件负责第 1、3 步的判定（第 2 步在 ZongkongLink 里用后台线程做）：
 *   1. 事件线程只读**缓存快照**，永不阻塞（[read] 是纯内存读）。
 *   2. 后台线程带超时刷新缓存（[beginFetch] / [finishFetch] / [expireFetch]）。
 *   3. 刷新超期 → **失联态**（[Mode.LOST]）：不阻塞、不猜测，按本地规则继续走。
 *
 * 「失联态」和「总控说没在严管」是两件不同的事，绝不能混：
 * 失联时 [StatusRead.status] 为 null，调用方必须走「不知道 → 按本地规则办」，
 * 而不是当成 lockdown=false 去放行。
 */

/** 缓存里的一份快照。ok=false 表示那次读取失败（没装 / 签名不对 / 抛异常）。 */
data class StatusSnapshot(
    val status: ZongkongStatus?,
    val ok: Boolean,
    val atWall: Long,
    val error: String = "",
)

/** 读缓存的结论。 */
enum class StatusMode {
    /** 还新鲜：可以按里面的内容判断。 */
    FRESH,

    /** 过期但没到失联预算：可以继续用，但要同时触发后台刷新。 */
    STALE,

    /** 失联态：读不到、或刷新超期。**不知道**，按本地规则走。 */
    LOST,
}

data class StatusRead(
    val mode: StatusMode,
    /** LOST 时一定是 null。 */
    val status: ZongkongStatus?,
    val ageMs: Long,
    val reason: String,
) {
    /** 总控明确说「正在管整台手机」——只有在 FRESH/STALE 且读到内容时才可能为 true。 */
    val lockdown: Boolean get() = status?.lockdown == true

    /** 这个应用总控会不会弹回去。失联时不猜（返回 false，让本地规则接手）。 */
    fun bounces(pkg: String): Boolean = status?.bounces(pkg) == true

    /** 需不需要去后台刷一次。 */
    val wantsRefresh: Boolean get() = mode != StatusMode.FRESH
}

/**
 * @param ttlMs 多久之内算新鲜。
 * @param lostAfterMs 后台刷新最晚多久必须有结果；超时即判失联。
 */
class StatusCache(
    private val ttlMs: Long = 30_000,
    private val lostAfterMs: Long = 8_000,
) {
    private var snapshot: StatusSnapshot? = null
    private var lastFailureAtWall: Long = 0
    private var lastFailureReason: String = ""
    private var fetchStartedAtWall: Long = 0
    private var everSucceeded: Boolean = false

    /** 后台刷新成功：写入快照并清掉失联标记。 */
    @Synchronized
    fun finishFetch(s: StatusSnapshot) {
        fetchStartedAtWall = 0
        if (s.ok) {
            snapshot = s
            everSucceeded = true
            lastFailureReason = ""
        } else {
            // 读到「没装 / 签名不对」也是一种真实结论：清掉旧快照，别拿旧数据当现状。
            snapshot = null
            lastFailureAtWall = s.atWall
            lastFailureReason = s.error.ifBlank { "总控读不到" }
        }
    }

    /** 后台刷新超期：判失联，清掉快照（旧数据不能让事件线程误以为总控没在严管）。 */
    @Synchronized
    fun expireFetch(nowWall: Long, reason: String) {
        fetchStartedAtWall = 0
        snapshot = null
        lastFailureAtWall = nowWall
        lastFailureReason = reason.ifBlank { "总控没有在预算内响应" }
    }

    /** 事件线程调用：单飞判断。返回 false 表示已有在途刷新且没超期，不必再发。 */
    @Synchronized
    fun beginFetch(nowWall: Long): Boolean {
        if (fetchStartedAtWall > 0 && nowWall - fetchStartedAtWall < lostAfterMs) return false
        fetchStartedAtWall = nowWall
        return true
    }

    /** 事件线程调用：纯内存读，**不做任何跨进程或磁盘操作**。 */
    @Synchronized
    fun read(nowWall: Long): StatusRead {
        val s = snapshot
        if (fetchStartedAtWall > 0 && nowWall - fetchStartedAtWall >= lostAfterMs) {
            // 在途刷新已经超期：先如实报失联，等后台回收。
            expireFetch(nowWall, "总控刷新超过 ${lostAfterMs}ms 没有结果")
            return StatusRead(StatusMode.LOST, null, nowWall - fetchStartedAtWall, lastFailureReason)
        }
        if (s == null) {
            val reason = lastFailureReason.ifBlank {
                if (everSucceeded) "总控状态已清空" else "还没有读到过总控状态"
            }
            return StatusRead(StatusMode.LOST, null, 0, reason)
        }
        if (!s.ok) return StatusRead(StatusMode.LOST, null, nowWall - s.atWall, lastFailureReason)
        val age = (nowWall - s.atWall).coerceAtLeast(0)
        return when {
            age <= ttlMs -> StatusRead(StatusMode.FRESH, s.status, age, "")
            // 过期但没到失联预算：可以用，同时要求刷新。
            nowWall - s.atWall < ttlMs + lostAfterMs -> StatusRead(StatusMode.STALE, s.status, age, "快照 ${age / 1000}s 前")
            else -> StatusRead(StatusMode.LOST, null, age, "快照过期 ${age / 1000}s，没有刷新成功")
        }
    }

    /** 后台刷新失败（没装 / 签名不对 / 抛异常）也算有结果：记下来，别让事件线程一直等。 */
    @Synchronized
    fun failFetch(nowWall: Long, reason: String) {
        fetchStartedAtWall = 0
        snapshot = null
        lastFailureAtWall = nowWall
        lastFailureReason = reason
    }

    @Synchronized
    fun reset() {
        snapshot = null
        lastFailureAtWall = 0
        lastFailureReason = ""
        fetchStartedAtWall = 0
        everSucceeded = false
    }
}
