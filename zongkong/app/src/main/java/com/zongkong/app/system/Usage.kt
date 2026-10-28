package com.zongkong.app.system

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.boss.DevEvent
import com.zongkong.core.boss.Timeline

/**
 * 系统的“使用情况访问”：总控没在运行的时段也查得到（系统自己记的，大约保留一周）。
 * 没授权时退回无障碍服务自己记的切换。
 *
 * 无障碍掉了之后这份记录就是唯一还看得见的东西（见 [Eye]）：它能说“用了多久哪些应用”，
 * 但没有拦的动作，也没有应用内的窗口细节——降级不是等价替换。
 */
object Usage {
    private const val TAG = "Usage"

    fun granted(context: Context): Boolean = try {
        val ops = context.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ops.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        } else {
            @Suppress("DEPRECATION")
            ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName)
        }
        mode == AppOpsManager.MODE_ALLOWED
    } catch (e: Exception) {
        false
    }

    /** 系统记录里的切换：应用到前台、屏幕亮、屏幕灭。查不到返回 null。 */
    private fun system(context: Context, from: Long, to: Long): List<DevEvent>? {
        if (!granted(context)) return null
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return null
            // 往前多查 6 小时，知道开始时前台是什么
            val events = usm.queryEvents(from - 6 * 3600_000L, to)
            val e = UsageEvents.Event()
            val out = mutableListOf<DevEvent>()
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    UsageEvents.Event.MOVE_TO_FOREGROUND -> out += DevEvent(e.timeStamp, e.packageName)
                    SCREEN_INTERACTIVE -> out += DevEvent(e.timeStamp, Timeline.ON)
                    SCREEN_NON_INTERACTIVE -> out += DevEvent(e.timeStamp, Timeline.OFF)
                }
            }
            if (out.isEmpty()) null else out
        } catch (e: Exception) {
            Log.w(TAG, "读使用情况失败", e)
            null
        }
    }

    /**
     * [from, to] 的设备事件。
     *
     * 无障碍在跑：用总控自己记的（逐窗口，最细）；查不到再退系统记录。
     * 无障碍没跑：优先系统记录（这段总控自己根本没记），没授权才退回自己那份空记录。
     * 为什么这么分：[Eye.systemRecordFirst] 一句话说清，两处必须用同一个判断，否则界面说“只读”、
     * 实际却在读一份空白，对账就假了。
     */
    fun events(context: Context, from: Long, to: Long): List<DevEvent> {
        val running = runCatching { com.zongkong.app.guard.GuardService.running }.getOrDefault(false)
        val granted = granted(context)
        return if (Eye.systemRecordFirst(running, granted)) {
            system(context, from, to) ?: context.zk.store.switches(from, to)
        } else {
            val own = context.zk.store.switches(from, to)
            if (own.isNotEmpty()) own else system(context, from, to).orEmpty()
        }
    }

    // UsageEvents.Event.SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE（API 28）
    private const val SCREEN_INTERACTIVE = 15
    private const val SCREEN_NON_INTERACTIVE = 16
}
