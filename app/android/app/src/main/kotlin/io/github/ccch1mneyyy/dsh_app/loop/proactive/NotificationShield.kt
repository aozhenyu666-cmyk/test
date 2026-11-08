package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

/**
 * 通知防波堤：忙时 / 任务模式里（插件 /plan 回的 shield.until），非白名单的通知一弹出来就从通知栏拿掉，
 * 只把"哪个 App、标题、是不是群聊"报给插件攒着（不传正文），任务结束时鲸澜 / 鱼丸一次性简报。
 *
 * 放行：电话、短信、闹钟、日历、系统、DSH 自己、常驻 / 前台服务通知；微信 / QQ 的私聊（可能是急事），只拦群聊。
 * 用户要在系统设置"通知使用权"里打开它；没打开就什么都不做。
 */
class NotificationShield : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        val n = sbn ?: return
        if (!ProactiveRuntime.shieldActive()) return
        val pkg = n.packageName ?: return
        if (pkg in ALLOW || ALLOW_PREFIX.any { pkg.startsWith(it) } || pkg == packageName) return
        val notification = n.notification ?: return
        if (n.isOngoing || (notification.flags and Notification.FLAG_FOREGROUND_SERVICE) != 0) return
        val category = notification.category
        if (category == Notification.CATEGORY_CALL || category == Notification.CATEGORY_ALARM ||
            category == Notification.CATEGORY_REMINDER || category == Notification.CATEGORY_EVENT
        ) return
        val extras = notification.extras
        val title = extras?.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras?.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        val chat = pkg in CHAT_APPS
        // 群聊的正文通常是"某人: 内容"或带"[N条]"；私聊没有发言人前缀。
        val group = chat && (GROUP_TEXT.containsMatchIn(text) || text.startsWith("["))
        if (chat && !group) return
        runCatching { cancelNotification(n.key) }.onFailure { Log.w(TAG, "cancel", it) }
        val label = runCatching { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
        ProactiveRuntime.onNotificationSwallowed(pkg, label, title.take(40), group)
    }

    companion object {
        private const val TAG = "DshShield"
        private val GROUP_TEXT = Regex("^[^:：\\n]{1,24}[:：]")
        private val CHAT_APPS = setOf("com.tencent.mm", "com.tencent.mobileqq", "com.tencent.tim")
        private val ALLOW = setOf(
            "android", "com.android.systemui", "com.android.dialer", "com.google.android.dialer", "com.android.phone",
            "com.android.server.telecom", "com.android.mms", "com.google.android.apps.messaging", "com.android.deskclock",
            "com.google.android.deskclock", "com.android.calendar", "com.google.android.calendar", "com.android.incallui",
        )
        /** 各家厂商的电话 / 短信 / 时钟 / 日历。 */
        private val ALLOW_PREFIX = listOf("com.miui.", "com.huawei.contacts", "com.huawei.message", "com.oplus.", "com.coloros.", "com.vivo.", "com.samsung.android.dialer", "com.samsung.android.messaging", "io.github.ccch1mneyyy.dsh_app")
    }
}
