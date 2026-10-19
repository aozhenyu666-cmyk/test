package com.zongkong.app.system

import android.content.Context
import android.util.Log
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.GuardService

/**
 * 看门狗：常驻服务（[KeepAliveService]）每 60 秒问一句“眼睛还在吗”。
 *
 * 无障碍服务没在运行超过 60 秒 → 每 10 分钟最多大声提醒一次（高优先级通知，点开去权限页），
 * 写一条诊断记录，并让桌面小部件（墨灵）立刻变成“睡着/看不见”。
 * 恢复后发一条“眼睛回来了”，也算一次“接上”。状态记在 SharedPreferences 里，进程被杀重启也能续上。
 *
 * 不抛异常：常驻服务的循环不能被它拖垮。
 */
object Watchdog {
    private const val TAG = "Watchdog"
    private const val PREFS = "watchdog"
    private const val K_FIRST_OFF = "first_off_at"
    private const val K_LAST_ALERT = "last_alert_at"
    private const val K_WIDGET_OFFLINE = "widget_offline"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 掉线多久才认为“真的掉了”（刚启动、系统换绑的几秒抖动不算）。 */
    private const val GRACE_MS = 60_000L

    /** 同样的提醒最多 10 分钟一次。 */
    private const val ALERT_EVERY_MS = 10 * 60_000L

    private const val ROUTE = "settings/perm"

    fun check(context: Context, now: Long = System.currentTimeMillis()) {
        try {
            step(context, now)
        } catch (e: Throwable) {
            Log.w(TAG, "看门狗检查出错", e)
        }
    }

    private fun step(context: Context, now: Long) {
        val p = prefs(context)
        val running = runCatching { GuardService.running }.getOrDefault(false)

        if (running) {
            val firstOff = p.getLong(K_FIRST_OFF, 0L)
            if (firstOff <= 0L) {
                // 进程刚重启时小部件可能还画着“睡着”（那次小部件更新跑在服务连上之前）：刷回来
                if (p.getBoolean(K_WIDGET_OFFLINE, false)) {
                    p.edit().putBoolean(K_WIDGET_OFFLINE, false).apply()
                    runCatching { PetWidget.refresh(context) }
                }
                return
            }
            // 从“掉线”回到“在运行”：记一笔、说一声、让小部件醒过来
            val gap = (now - firstOff).coerceAtLeast(0L)
            p.edit().remove(K_FIRST_OFF).remove(K_LAST_ALERT).putBoolean(K_WIDGET_OFFLINE, false).apply()
            Diag.log(context, "connected", "看门狗：眼睛回来了（离线 ${text(gap)}）")
            try {
                Notifications.hqCall(
                    context,
                    "眼睛回来了",
                    "无障碍服务重新接上了，继续看着你。",
                    fullScreen = false,
                    route = ROUTE,
                )
            } catch (e: Exception) {
                Log.w(TAG, "发“眼睛回来了”失败", e)
            }
            runCatching { PetWidget.refresh(context) }
            return
        }

        val firstOff = p.getLong(K_FIRST_OFF, 0L)
        if (firstOff <= 0L) {
            // 第一次发现它没在运行：先记下时刻，一分钟后还不回来才算掉线
            p.edit().putLong(K_FIRST_OFF, now).apply()
            runCatching { PetWidget.refresh(context) }
            return
        }
        if (now - firstOff <= GRACE_MS) return

        val lastAlert = p.getLong(K_LAST_ALERT, 0L)
        if (lastAlert > 0L && now - lastAlert < ALERT_EVERY_MS) return

        val offText = text(now - firstOff)
        Diag.log(context, "watchdog", "无障碍没在运行已 $offText：发提醒")
        try {
            Notifications.hqCall(
                context,
                "眼睛掉了：无障碍没在运行",
                "拦不住、看不到。已经掉线 $offText。点开去权限页，关掉再打开一次。",
                fullScreen = false,
                route = ROUTE,
            )
        } catch (e: Exception) {
            Log.w(TAG, "发“眼睛掉了”失败", e)
        }
        runCatching { PetWidget.refresh(context) }
        p.edit().putLong(K_LAST_ALERT, now).apply()
    }

    /**
     * 小部件画完自己现在的样子后调一次。记下它画的是“睡着”还是“醒着”：
     * 进程被杀、小部件先被系统刷成“睡着”时，等眼睛回来，看门狗靠这个标记把它刷回去。
     */
    fun noteWidget(context: Context, online: Boolean) {
        runCatching { prefs(context).edit().putBoolean(K_WIDGET_OFFLINE, !online).apply() }
    }

    /** 时长说成人话：`8 分钟` / `1 小时 5 分` / `不到 1 分钟`。 */
    private fun text(ms: Long): String {
        val m = (ms / 60_000).coerceAtLeast(0)
        return when {
            m >= 60 -> "${m / 60} 小时 ${m % 60} 分"
            m == 0L -> "不到 1 分钟"
            else -> "$m 分钟"
        }
    }

    /** 清掉看门狗的离线状态（用户手动处理完、或者诊断页里“清空”之后调用都行）。 */
    fun reset(context: Context) {
        runCatching {
            prefs(context).edit().remove(K_FIRST_OFF).remove(K_LAST_ALERT).remove(K_WIDGET_OFFLINE).apply()
        }
    }
}
