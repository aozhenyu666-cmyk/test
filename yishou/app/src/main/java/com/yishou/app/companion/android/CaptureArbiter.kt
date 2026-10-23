package com.yishou.app.companion.android

import com.yishou.app.companion.core.CaptureHolder
import com.yishou.app.companion.core.CaptureLease
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 投屏账本的进程内入口。看屏、刷着练、陪练探针都从这里拿，谁都不自己闷头开投影。
 * 系统只给一份「本次会话」的授权，两份同时开的结果是双双失效，还会多耗一路镜像。
 */
object CaptureArbiter {

    private val lease = CaptureLease()

    private val _holder = MutableStateFlow(CaptureHolder.NONE)
    val holder: StateFlow<CaptureHolder> = _holder.asStateFlow()

    /** 拿到返回 null；拿不到返回一句能直接显示的原因 */
    fun acquire(who: CaptureHolder): String? {
        val reason = lease.acquire(who)
        if (reason == null) _holder.value = who
        return reason
    }

    fun release(who: CaptureHolder) {
        if (lease.release(who)) _holder.value = CaptureHolder.NONE
    }

    /** 系统收回授权、进程回收：整本账清空 */
    fun reset() {
        lease.reset()
        _holder.value = CaptureHolder.NONE
    }
}
