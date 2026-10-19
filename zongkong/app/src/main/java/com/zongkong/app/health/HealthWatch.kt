package com.zongkong.app.health

import android.content.Context
import android.provider.Settings
import android.util.Log
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.GuardService
import com.zongkong.app.sense.SenseHub
import com.zongkong.core.director.Health

/**
 * 施工队 A 的 A5：无障碍健康，分四层看（只看开关是开着，不能说明它在工作）。
 *
 *   1 权限开没开       —— 系统设置里这个服务在不在启用列表
 *   2 服务连没连上     —— GuardService.running
 *   3 事件来不来       —— 最近一次真的收到窗口事件的时刻（SenseHub.lastEventAt）
 *   4 目标页面读不读得到 —— C 档要读演员字幕；还没做 P2，如实报“未知”（null）
 *
 * 还要分清两种掉线：用户自己在设置里关的（尊重，只记录），和意外掉线（要修）。
 * 现在能确定的是“从开着变成关了”，按用户关的记；其余按意外，交给 SelfHeal 的老逻辑。
 */
object HealthWatch {
    private const val TAG = "HealthWatch"

    /** 上一次的四层状态，用来判断“变化”。 */
    @Volatile private var sig = ""

    /** 上一次看到的是“开着”。 */
    @Volatile private var wasEnabled = true

    /** 用户自己关掉的：从开着变关着就是这一类（无法百分百确定，宁可记成用户关的，不乱改系统设置）。 */
    @Volatile private var userDisabled = false

    /** 页面读得到吗：没做 P2 之前一律“未知”。 */
    private val readable: Boolean? = null

    /** 权限那一层：启用列表里有没有我们。 */
    fun permission(context: Context): Boolean = try {
        val cr = context.contentResolver
        val enabled = Settings.Secure.getInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1
        val list = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        enabled && list.contains(context.packageName)
    } catch (e: Exception) {
        false
    }

    fun health(context: Context, now: Long): Health {
        val perm = permission(context)
        if (!perm && wasEnabled) userDisabled = true
        if (perm) userDisabled = false
        wasEnabled = perm
        return Health(
            permission = perm,
            connected = GuardService.running,
            lastEventAt = SenseHub.lastEventAt,
            readable = readable,
            userDisabled = userDisabled,
        )
    }

    /** 每次 tick 调一次；四层里有变化才回调（不变化就不打扰导演）。 */
    fun check(context: Context, now: Long, emit: (Health) -> Unit) {
        val h = try {
            health(context, now)
        } catch (e: Exception) {
            Log.w(TAG, "算健康状态失败", e)
            return
        }
        val next = listOf(h.permission, h.connected, h.lastEventAt > 0, h.readable, h.userDisabled).joinToString("|")
        if (next == sig) return
        sig = next
        runCatching {
            val t = java.time.Instant.ofEpochMilli(h.lastEventAt).atZone(com.zongkong.core.DayClock.BEIJING)
            Diag.log(
                context,
                "health",
                "权限=${if (h.permission) "开" else "关"} 服务=${if (h.connected) "连着" else "没连"} " +
                    "最近事件=${if (h.lastEventAt > 0) "%d:%02d".format(t.hour, t.minute) else "没有"} " +
                    "用户关的=${if (h.userDisabled) "是" else "否"}",
            )
        }
        emit(h)
    }

    /** 给设置页 / 调试页看的一句话。 */
    fun text(context: Context, now: Long): String {
        val h = health(context, now)
        val layer3 = if (h.lastEventAt <= 0) "事件没来过"
        else "最近事件 ${((now - h.lastEventAt) / 60_000L).coerceAtLeast(0)} 分钟前"
        return "权限${if (h.permission) "开" else "关"} · 服务${if (h.connected) "在跑" else "没跑"} · $layer3 · 页面读取=未知"
    }
}
