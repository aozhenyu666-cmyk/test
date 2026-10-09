package com.yishou.app.system

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.yishou.app.MainActivity
import com.yishou.app.R
import com.yishou.app.window.WindowActivity

/** 应用的两类通知：常驻的“一手正在运行”，以及“陪练窗口开始”。 */
object Notifications {
    private const val TAG = "Notifications"
    private const val CH_RUNNING = "running"
    private const val CH_WINDOW = "window"
    private const val CH_NUDGE = "nudge"
    private const val ID_RUNNING = 1
    const val ID_WINDOW = 2
    const val ID_NUDGE = 3

    fun ensureChannels(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_RUNNING, "一手正在运行", NotificationManager.IMPORTANCE_LOW).apply {
                description = "入口思考页开启时的常驻通知"
                setShowBadge(false)
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WINDOW, "陪练窗口", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "陪练窗口开始、还没走第一手、休息时间到"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_NUDGE, "每日提醒", NotificationManager.IMPORTANCE_DEFAULT).apply {
                description = "当天还没应一手时提醒"
            },
        )
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** 无障碍服务连上时显示，断开时取消。透明原则：运行时始终有这条通知。 */
    fun showRunning(context: Context) {
        val open = PendingIntent.getActivity(
            context, 0, Intent(context, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_RUNNING)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("一手正在运行")
            .setContentText("打开关注的应用前会先走一手。随时可在系统无障碍设置里关闭。")
            .setOngoing(true)
            .setContentIntent(open)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notify(context, ID_RUNNING, n)
    }

    fun cancelRunning(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_RUNNING)
    }

    fun showWindowStart(context: Context, minutes: Int) {
        val open = PendingIntent.getActivity(
            context, 1, Intent(context, WindowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_WINDOW)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("陪练窗口开始")
            .setContentText("接下来 $minutes 分钟，一轮接一轮。点开进入窗口。")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        notify(context, ID_WINDOW, n)
    }

    /** 普通提醒，点开进入 target 页面。窗口类提醒走高优先级频道。 */
    fun showReminder(context: Context, id: Int, title: String, text: String, target: Class<*>) {
        val open = PendingIntent.getActivity(
            context, 10 + id, Intent(context, target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, if (id == ID_WINDOW) CH_WINDOW else CH_NUDGE)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .build()
        notify(context, id, n)
    }

    private fun notify(context: Context, id: Int, n: android.app.Notification) {
        if (!canPost(context)) {
            Log.w(TAG, "通知权限未开启，通知 $id 没有显示")
            return
        }
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (e: SecurityException) {
            Log.w(TAG, "没有通知权限", e)
        }
    }
}
