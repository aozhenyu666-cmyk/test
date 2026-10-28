package com.zongkong.app.system

import android.content.Context
import android.util.Log
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.GuardService
import com.zongkong.app.health.HealthAction
import com.zongkong.app.health.HealthStatus
import com.zongkong.app.health.HealthVerdict
import com.zongkong.app.healthwire.GuardHealth

/**
 * 看门狗：常驻服务（[KeepAliveService]）每 60 秒问一次「你还真的在响应吗」。
 *
 * v2 变更（报告 §8 / 规范 §8）：
 *   **不再主要依赖 `GuardService.running`**。那个布尔是进程内静态量，进程还活着就是 true，
 *   恰好盖住「无障碍线程已经卡死、服务其实没在干活」这种最危险的情况（T05）。
 *
 * 现在依赖两件真实证据：
 *   1. 真实 dispatcherAck 的新鲜度（哨兵挑战在 a11y 线程上处理完的时刻）；
 *   2. 真实窗口事件进度（`eventSeq/eventAtMono`，判断事件通道是否失灵；T03）。
 * 判断本身交给共享内核 `HealthEvaluator`，这里只负责「什么时候吵你」与写诊断。
 *
 * 不抛异常：常驻服务的循环不能被它拖垮。
 */
object Watchdog {
    private const val TAG = "Watchdog"
    private const val PREFS = "watchdog"
    private const val K_FIRST_OFF = "first_off_at"
    private const val K_LAST_ALERT = "last_alert_at"
    private const val K_WIDGET_OFFLINE = "widget_offline"
    private const val K_LAST_STATUS = "last_health_status"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** 掉线多久才认为「真的掉了」：不是 ack 多久没来，而是进入失败态的时长。 */
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

    /** v2 的健康结论（真实 ack + 事件进度）。给界面和诊断共用。 */
    fun verdict(context: Context): HealthVerdict {
        val ok = runCatching { GuardHealth.init(context) }.getOrNull() != null
        if (!ok) return HealthVerdict(HealthStatus.UNKNOWN, HealthAction.NONE, "健康协议还没起来")
        return GuardHealth.verdict(GuardHealth.permissionEnabled(context))
    }

    private fun step(context: Context, now: Long) {
        val p = prefs(context)
        val v = verdict(context)
        val status = v.status
        val lastStatus = p.getString(K_LAST_STATUS, "")
        if (lastStatus != status.name) {
            p.edit().putString(K_LAST_STATUS, status.name).apply()
            // 状态变化只记一条，避免每分钟刷满时间线。
            runCatching {
                Diag.log(
                    context,
                    when (status) {
                        HealthStatus.HEALTHY -> "health_ok"
                        HealthStatus.DISABLED -> "health_disabled"
                        HealthStatus.UNRESPONSIVE -> "health_lost"
                        HealthStatus.RECOVERING -> "health_recovering"
                        else -> "health_state"
                    },
                    "${status.name}：${v.reason}",
                )
            }
        }

        // 健康：把之前记下的掉线时段收尾、说一声、让小部件醒过来。
        if (status == HealthStatus.HEALTHY) {
            val firstOff = p.getLong(K_FIRST_OFF, 0L)
            if (firstOff <= 0L) {
                if (p.getBoolean(K_WIDGET_OFFLINE, false)) {
                    p.edit().putBoolean(K_WIDGET_OFFLINE, false).apply()
                    runCatching { PetWidget.refresh(context) }
                }
                return
            }
            val gap = (now - firstOff).coerceAtLeast(0L)
            p.edit().remove(K_FIRST_OFF).remove(K_LAST_ALERT).putBoolean(K_WIDGET_OFFLINE, false).apply()
            Diag.log(context, "connected", "看门狗：真实 ack 回来了（之前失联 ${text(gap)}）")
            try {
                Notifications.hqCall(
                    context,
                    "眼睛回来了",
                    "无障碍执行线程重新在响应（真实挑战 ack 通过）。",
                    fullScreen = false,
                    route = ROUTE,
                )
            } catch (e: Exception) {
                Log.w(TAG, "发“眼睛回来了”失败", e)
            }
            runCatching { PetWidget.refresh(context) }
            return
        }

        // 「证据不足 / 已暂停 / 正在恢复」不是失联：不提醒，也不清掉正在等的状态。
        if (status == HealthStatus.UNKNOWN || status == HealthStatus.PAUSED || status == HealthStatus.RECOVERING) {
            runCatching { PetWidget.refresh(context) }
            return
        }

        val firstOff = p.getLong(K_FIRST_OFF, 0L)
        if (firstOff <= 0L) {
            // 第一次发现异常：先记时刻，一分钟后还不好才算真掉线（刚开机、刚休眠唤醒不算）。
            p.edit().putLong(K_FIRST_OFF, now).apply()
            runCatching { PetWidget.refresh(context) }
            return
        }
        if (now - firstOff <= GRACE_MS) return

        val lastAlert = p.getLong(K_LAST_ALERT, 0L)
        if (lastAlert > 0L && now - lastAlert < ALERT_EVERY_MS) return

        val offText = text(now - firstOff)
        val why = when (status) {
            HealthStatus.DISABLED -> "无障碍在系统设置里是关着的：拦不住、看不到。"
            HealthStatus.UNRESPONSIVE -> "无障碍执行线程不再响应真实挑战已 $offText。"
            else -> "健康状态异常（${status.name}）已 $offText：${v.reason}"
        }
        Diag.log(context, "watchdog", "健康异常已 $offText（${status.name}）：发提醒")
        try {
            Notifications.hqCall(
                context,
                if (status == HealthStatus.DISABLED) "无障碍被关掉了" else "眼睛不动了：执行线程没有响应",
                why + "点开去权限页，关掉再打开一次。",
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
     * 小部件画完自己现在的样子后调一次。记下它画的是「睡着」还是「醒着」：
     * 进程被杀、小部件先被系统刷成「睡着」时，等眼睛回来，看门狗靠这个标记把它刷回去。
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
            prefs(context).edit()
                .remove(K_FIRST_OFF).remove(K_LAST_ALERT).remove(K_WIDGET_OFFLINE)
                .remove(K_LAST_STATUS).apply()
        }
    }

    /** 小部件/首页要显示的一句话（不涂绿）。 */
    fun headline(context: Context): String = verdict(context).let { "${it.status.name}：${it.reason}" }

    /** 兼容旧调用点：小部件只需要「醒着/睡着」，这里用真实健康状态而不是 running。 */
    fun guardUsableNow(context: Context): Boolean = runCatching { GuardService.running }.getOrDefault(false) &&
        verdict(context).status == HealthStatus.HEALTHY
}
