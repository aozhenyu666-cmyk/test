package com.yishou.app.companion.core

/**
 * 投屏（MediaProjection）在一手内部只能有一个主人。
 * 系统给的是「本次会话」的授权，两个服务同时持有会互相判定失效，还会各开一路镜像白耗电。
 * 这里只管账，不碰 Android：谁能拿、拿不到时说什么话，都由纯逻辑定。
 */
enum class CaptureHolder(val label: String) {
    NONE("空闲"),
    SCREEN_LOOK("看屏"),
    SCROLL("刷着练"),
    COMPANION("陪练探针"),
}

/** 记账结果：成功为 null，失败给一句能直接显示给用户的原因。 */
class CaptureLease {

    private var holder: CaptureHolder = CaptureHolder.NONE

    fun holder(): CaptureHolder = holder

    /** 现在能不能由 [who] 拿；能拿返回 null。纯查询，不改状态。 */
    fun blockReason(who: CaptureHolder): String? = when {
        who == CaptureHolder.NONE -> "没有要拿投屏的东西"
        holder == who -> null
        holder == CaptureHolder.NONE -> null
        else -> "${holder.label}正占着投屏，先把它停掉"
    }

    /** 拿一笔。已经在自己手上也当成功，方便重入。 */
    fun acquire(who: CaptureHolder): String? {
        blockReason(who)?.let { return it }
        holder = who
        return null
    }

    /** 放手。不是自己的账不误清，免得 A 停了把 B 的账抹掉。 */
    fun release(who: CaptureHolder): Boolean {
        if (who == CaptureHolder.NONE || holder != who) return false
        holder = CaptureHolder.NONE
        return true
    }

    /** 系统把授权收回、或进程被清：不管谁的账都清空。 */
    fun reset() {
        holder = CaptureHolder.NONE
    }
}
