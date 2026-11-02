package com.zongkong.app.system

import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.ExitLog
import com.zongkong.core.DayClock
import com.zongkong.core.Pause

/**
 * 心跳：发现「总控停了一段时间」并说清是什么停法。
 *
 * - 同一个进程里，两次心跳之间隔了很久（常驻服务本该每分钟一次）：进程被冻结 / 挂起过。
 *   醒来后查系统的使用记录：那段时间你在用手机 → 记成「被冻结」；屏幕一直灭着 → 正常休眠，不记。
 * - 进程换了一个（被清掉后重新起来）：上一个进程最后一次心跳到现在，系统有没有死因记录；没有就记成「无声被清」。
 *
 * 冻结时总控自己什么也做不了，所以只能在醒来那一刻回头补记。
 */
object Pulse {
    private const val TAG = "Pulse"
    private const val PREFS = "zk_pulse"

    @Volatile private var lastElapsed = 0L
    @Volatile private var lastWall = 0L
    private val lock = Any()

    /** 每次心跳调一次（常驻服务每分钟、无障碍每 20 秒）。很便宜，可以随便调。 */
    fun beat(context: Context, now: Long = System.currentTimeMillis()) {
        val elapsed = SystemClock.elapsedRealtime()
        var gapFrom = 0L
        synchronized(lock) {
            if (lastElapsed > 0 && elapsed - lastElapsed >= Pause.MIN_GAP_MS) gapFrom = lastWall
            lastElapsed = elapsed
            lastWall = now
        }
        prefs(context).edit().putLong("wall", now).putInt("pid", Process.myPid()).apply()
        if (gapFrom > 0) report(context, gapFrom, now, sameProcess = true, exitRecorded = false)
    }

    /**
     * 进程刚起来时调一次（后台线程）：上一个进程最后的心跳到现在，系统有没有给死因。
     * 先让死因日志读一遍系统记录，再判断。
     */
    fun onProcessStart(context: Context) {
        try {
            val p = prefs(context)
            val lastWall = p.getLong("wall", 0L)
            val lastPid = p.getInt("pid", 0)
            val now = System.currentTimeMillis()
            if (lastWall <= 0 || lastPid == Process.myPid() || now - lastWall < Pause.MIN_GAP_MS) return
            ExitLog.collect(context, "进程启动")
            val recorded = ExitLog.recent(context).any { it.at in (lastWall - 60_000)..now }
            report(context, lastWall, now, sameProcess = false, exitRecorded = recorded)
        } catch (e: Throwable) {
            Log.w(TAG, "检查上次停顿失败", e)
        }
    }

    private fun report(context: Context, from: Long, to: Long, sameProcess: Boolean, exitRecorded: Boolean) {
        val usage = usage(context, from, to)
        val kind = Pause.classify(to - from, sameProcess, exitRecorded, usage)
        val text = Pause.text(kind, from, to, usage, DayClock.BEIJING) ?: return
        Diag.log(context, if (kind == Pause.Kind.KILLED_SILENT) "died_silent" else "paused", text)
    }

    /** 系统使用记录里，这段时间亮屏多久、切换了几次应用（总控被冻结时系统照样在记）。 */
    fun usage(context: Context, from: Long, to: Long): Pause.Usage {
        if (!Usage.granted(context)) return Pause.Usage(known = false)
        return try {
            val usm = context.getSystemService(UsageStatsManager::class.java) ?: return Pause.Usage(known = false)
            val events = usm.queryEvents(from, to)
            val e = UsageEvents.Event()
            var screenOn = 0L
            var onSince = -1L
            var switches = 0
            val screenOnType = if (Build.VERSION.SDK_INT >= 28) UsageEvents.Event.SCREEN_INTERACTIVE else -1
            val screenOffType = if (Build.VERSION.SDK_INT >= 28) UsageEvents.Event.SCREEN_NON_INTERACTIVE else -2
            @Suppress("DEPRECATION")
            val resumed = UsageEvents.Event.MOVE_TO_FOREGROUND
            while (events.hasNextEvent()) {
                events.getNextEvent(e)
                when (e.eventType) {
                    screenOnType -> if (onSince < 0) onSince = e.timeStamp
                    screenOffType -> if (onSince >= 0) { screenOn += e.timeStamp - onSince; onSince = -1 }
                    resumed -> if (e.packageName != context.packageName) switches++
                }
            }
            if (onSince >= 0) screenOn += to - onSince
            // 旧系统没有亮屏事件：用切换次数估
            Pause.Usage(screenOnMs = screenOn, appSwitches = switches, known = true)
        } catch (e: Throwable) {
            Log.w(TAG, "读使用记录失败", e)
            Pause.Usage(known = false)
        }
    }

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
