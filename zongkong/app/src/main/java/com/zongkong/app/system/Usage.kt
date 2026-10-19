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

    /** [from, to] 的设备事件：有授权用系统的，否则用总控自己记的。 */
    fun events(context: Context, from: Long, to: Long): List<DevEvent> =
        system(context, from, to) ?: context.zk.store.switches(from, to)

    // UsageEvents.Event.SCREEN_INTERACTIVE / SCREEN_NON_INTERACTIVE（API 28）
    private const val SCREEN_INTERACTIVE = 15
    private const val SCREEN_NON_INTERACTIVE = 16
}
