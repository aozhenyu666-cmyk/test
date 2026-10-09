package com.zongkong.app.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zongkong.app.R
import com.zongkong.app.ui.MainActivity

/** 两类通知：常驻的状态条，以及到点、超时、沉默、超额的提醒。 */
object Notifications {
    private const val TAG = "Notifications"
    private const val CH_STATUS = "status"
    private const val CH_ALERT = "alert"
    private const val ID_STATUS = 1
    private var alertId = 100

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_STATUS, "总控状态", NotificationManager.IMPORTANCE_LOW).apply {
                description = "常驻显示：严管还是放行、下一关是什么"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_ALERT, "关卡提醒", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "关卡开放、快到截止、已超时、该报到了、娱乐快超额"
            },
        )
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun open(context: Context, route: String?, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showStatus(context: Context, title: String, text: String) {
        val n = NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_zongkong)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context, null, 0))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        post(context, ID_STATUS, n)
    }

    fun cancelStatus(context: Context) = NotificationManagerCompat.from(context).cancel(ID_STATUS)

    fun alert(context: Context, title: String, text: String, route: String?) {
        val id = alertId++
        val n = NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_zongkong)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, route, id))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        post(context, id, n)
    }

    /** 行动提醒的通知 ID：同一个行动的到点和追问用同一条，后一条替换前一条。 */
    private fun taskId(actionKey: String) = 1000 + (actionKey.hashCode() and 0x7fff)

    /** 到点提醒 / 追问。按钮：开始、推迟 30 分钟、卡住了。 */
    fun taskReminder(context: Context, a: com.zongkong.core.work.Rec, thread: com.zongkong.core.work.Rec?, follow: Boolean) {
        val id = taskId(a.key)
        val title = (if (follow) "还没开始：" else "该做了：") + a.title
        val text = (thread?.let { "「${it.title}」 " } ?: "") + reminderText(a, follow)
        val n = NotificationCompat.Builder(context, CH_ALERT)
            .setSmallIcon(R.drawable.ic_stat_zongkong)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, "action/${a.key}", id))
            .addAction(0, "开始", open(context, "start/${a.key}", id + 1))
            .addAction(0, "推迟 30 分钟", Reminders.snoozeIntent(context, a.key, id + 2))
            .addAction(0, "卡住了", open(context, "stuck/${a.key}", id + 3))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        post(context, id, n)
    }

    fun cancelTask(context: Context, actionKey: String) = NotificationManagerCompat.from(context).cancel(taskId(actionKey))

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        if (!canPost(context)) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "没有通知权限", e)
        }
    }
}
