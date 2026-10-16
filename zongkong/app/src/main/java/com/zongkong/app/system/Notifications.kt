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
    private const val CH_HQ = "hq"
    private const val CH_AI = "ai"
    private const val ID_HQ = 50
    private const val ID_AI = 60
    const val ID_STATUS = 1
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
        nm.createNotificationChannel(
            NotificationChannel(CH_HQ, "总控时间", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "每日总控时间到了、失联太久：全屏叫你，像闹钟一样"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_AI, "总控", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "总控主动发来的消息"
            },
        )
    }

    /** 总控时间到了：全屏通知（锁屏上也会亮起来），点开进总控时间。 */
    fun hqCall(context: Context, title: String, text: String, fullScreen: Boolean, route: String = "hq") {
        val n = NotificationCompat.Builder(context, CH_HQ)
            .setSmallIcon(R.drawable.ic_stat_zongkong)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, route, ID_HQ))
            .addAction(0, if (route == "hq") "现在进入" else "去处理", open(context, route, ID_HQ + 1))
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .apply { if (fullScreen) setFullScreenIntent(open(context, route, ID_HQ + 2), true) }
            .build()
        post(context, ID_HQ, n)
    }

    fun cancelHq(context: Context) = NotificationManagerCompat.from(context).cancel(ID_HQ)

    const val KEY_REPLY = "reply"

    /** 总控主动发来的一句话：点开进对话，或者直接在通知里回复（回答也会回到通知里）。 */
    fun advisor(context: Context, text: String) {
        val replyIntent = PendingIntent.getBroadcast(
            context, ID_AI + 5, Intent(context, ReplyReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
        )
        val input = androidx.core.app.RemoteInput.Builder(KEY_REPLY).setLabel("回总控…").build()
        val reply = NotificationCompat.Action.Builder(0, "回复", replyIntent).addRemoteInput(input).setAllowGeneratedReplies(false).build()
        val n = NotificationCompat.Builder(context, CH_AI)
            .setSmallIcon(R.drawable.ic_stat_zongkong)
            .setContentTitle("总控")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(open(context, "chat", ID_AI))
            .addAction(reply)
            .addAction(0, "说话", open(context, "voice", ID_AI + 6))
            .setCategory(NotificationCompat.CATEGORY_MESSAGE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        post(context, ID_AI, n)
    }

    /** 回复发出去了：先显示“在想”。 */
    fun advisorThinking(context: Context, said: String) {
        val n = NotificationCompat.Builder(context, CH_AI)
            .setSmallIcon(R.drawable.ic_stat_zongkong)
            .setContentTitle("总控")
            .setContentText("你：$said · 在想…")
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context, "chat", ID_AI))
            .build()
        post(context, ID_AI, n)
    }

    fun canPost(context: Context): Boolean = NotificationManagerCompat.from(context).areNotificationsEnabled()

    private fun open(context: Context, route: String?, code: Int): PendingIntent = PendingIntent.getActivity(
        context, code,
        Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply { if (route != null) putExtra(MainActivity.EXTRA_ROUTE, route) },
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun showStatus(context: Context, title: String, text: String) = post(context, ID_STATUS, statusNotification(context, title, text))

    /** 常驻状态条（常驻服务的前台通知也是这一条）。 */
    fun statusNotification(context: Context, title: String, text: String): android.app.Notification {
        ensureChannels(context)
        return NotificationCompat.Builder(context, CH_STATUS)
            .setSmallIcon(R.drawable.ic_stat_zongkong)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open(context, null, 0))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
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

/** 通知里直接回复总控。 */
class ReplyReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val text = androidx.core.app.RemoteInput.getResultsFromIntent(intent)?.getCharSequence(Notifications.KEY_REPLY)?.toString()?.trim().orEmpty()
        if (text.isEmpty()) return
        Notifications.advisorThinking(context, text)
        com.zongkong.app.zkApp(context).chat.send(text, echo = true)
    }
}
