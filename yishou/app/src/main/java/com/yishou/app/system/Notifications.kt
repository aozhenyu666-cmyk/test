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
    const val ID_IDLE = 4
    const val ID_CAPSULE = 5
    const val ID_IMPULSE = 6
    private const val CH_CAPSULE = "capsule"

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
            NotificationChannel(CH_CAPSULE, "回来第一步", NotificationManager.IMPORTANCE_LOW).apply {
                description = "放行时说好的“回来第一步”，切出去时挂在通知栏里"
                setShowBadge(false)
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
            .addAction(0, "急刹", route(context, "brake", 20))
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

    /**
     * 岔路的出口胶囊：放行时说好的“回来第一步”挂在通知栏，切回来一眼就知道从哪接上。
     * 下次过入口答了“做了没有”就收掉；也可以直接划掉。
     */
    fun showCapsule(context: Context, firstStep: String) {
        val open = PendingIntent.getActivity(
            context, 5, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(context, CH_CAPSULE)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("回来第一步")
            .setContentText(firstStep)
            .setStyle(NotificationCompat.BigTextStyle().bigText(firstStep))
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        notify(context, ID_CAPSULE, n)
    }

    /** 打开主界面的某一页（急刹、残局） */
    private fun route(context: Context, to: String, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, to).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 急刹 30 分钟后：最坏后果出现了吗 */
    fun showImpulseCheck(context: Context, worst: String) {
        val n = NotificationCompat.Builder(context, CH_NUDGE)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("30 分钟到了：最坏的后果出现了吗？")
            .setContentText("当时押的：$worst")
            .setStyle(NotificationCompat.BigTextStyle().bigText("当时押的：$worst"))
            .setContentIntent(route(context, "brake", 21))
            .setAutoCancel(true)
            .build()
        notify(context, ID_IMPULSE, n)
    }

    fun cancelCapsule(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_CAPSULE)
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
