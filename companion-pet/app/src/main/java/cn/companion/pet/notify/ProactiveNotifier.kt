package cn.companion.pet.notify

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import cn.companion.pet.core.proactive.DeliveryChannel
import cn.companion.pet.core.proactive.DeliveryRecord

/**
 * 主动消息的**独立通知通道**。
 *
 * 为什么这是本次最高优先级的一块：
 * 之前同桌只有悬浮气泡这一条投递路径，而气泡依赖 overlay 与 UI 回调。
 * 页面没初始化、权限被收回、或主线程忙时，消息生成成功也发不出去——
 * 用户看到的就是"它明明在想，但从来不说"。
 *
 * 这里的关键约束：
 * 1. **不依赖 Activity / Compose / 任何 UI 回调**，只依赖 NotificationManager；
 * 2. **每条消息用独立通知 id**，连续几条不会互相覆盖（固定 id 是 focusmate 的真实缺陷）；
 * 3. 返回 true 只代表"通知已发出"，**不代表用户看见**——状态推进由 MessageDispatcher 负责。
 */
class ProactiveNotifier(private val context: Context) : DeliveryChannel {

    override val name: String = "notification"

    private val manager: NotificationManager? =
        context.getSystemService(NotificationManager::class.java)

    /**
     * 发送一条主动消息通知。
     *
     * @return true = 通知真的提交给了系统（未抛异常、通道未被禁用）
     */
    override suspend fun send(record: DeliveryRecord): Boolean {
        val nm = manager ?: return false
        ensureChannel(nm) ?: return false

        // 通道被用户关掉时不再"假装发出去"。
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(CHANNEL_ID) ?: return false
            if (channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }

        val intent = Intent(context, cn.companion.pet.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_MESSAGE_ID, record.id)
        }
        val pi = PendingIntent.getActivity(
            context,
            record.notificationId,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(android.R.drawable.sym_action_chat)
            .setContentTitle("同桌")
            .setContentText(record.text.take(120))
            .setStyle(Notification.BigTextStyle().bigText(record.text.take(400)))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .setWhen(record.createdAt)
            .setShowWhen(true)
            .build()

        return try {
            // 独立 id：每条消息自己一条，绝不覆盖。
            nm.notify(record.notificationId, notification)
            true
        } catch (t: Throwable) {
            // 权限/系统限制导致失败：如实返回 false，由上层留痕并尝试下一条通道。
            false
        }
    }

    /** 通知通道是否可用（用户可能在系统设置里禁用了）。 */
    fun available(): Boolean {
        val nm = manager ?: return false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = nm.getNotificationChannel(CHANNEL_ID)
            if (channel != null && channel.importance == NotificationManager.IMPORTANCE_NONE) return false
        }
        return nm.areNotificationsEnabled()
    }

    /** 建通道；失败返回 null。 */
    private fun ensureChannel(nm: NotificationManager): Unit? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return Unit
        if (nm.getNotificationChannel(CHANNEL_ID) != null) return Unit
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            // HIGH 才会在头顶弹出；用户要的就是"不打开 App 也能看到它主动说话"。
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "同桌主动找你说话时用这个通道"
            enableVibration(true)
            setShowBadge(true)
        }
        return try {
            nm.createNotificationChannel(channel)
            Unit
        } catch (t: Throwable) {
            null
        }
    }

    companion object {
        const val CHANNEL_ID = "companion_proactive"
        const val CHANNEL_NAME = "同桌主动聊天"
        const val EXTRA_MESSAGE_ID = "companion.message.id"

        /** 用户点了通知后，把这个 id 交回运行时标记为已读。 */
        fun messageIdFrom(intent: Intent?): String? =
            intent?.getStringExtra(EXTRA_MESSAGE_ID)
    }
}
