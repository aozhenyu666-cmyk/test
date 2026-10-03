package huilu.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import huilu.core.CheckIn
import huilu.core.Engine
import huilu.core.Mode
import huilu.core.Situation

/** 两类通知：常驻的"当前局面"（外置工作记忆），和每次检查的提问。 */
class Notifier(private val ctx: Context) {
    private val nm = ctx.getSystemService(NotificationManager::class.java)

    init {
        nm.createNotificationChannel(NotificationChannel(CH_NOW, "当前行动", NotificationManager.IMPORTANCE_LOW).apply {
            description = "常驻显示你正在做什么、为什么、下一次检查在什么时候"
            setShowBadge(false)
        })
        nm.createNotificationChannel(NotificationChannel(CH_CHECK, "检查", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "系统重新观察现实后向你提出的问题"
            enableVibration(true)
        })
    }

    fun ongoing(s: Situation): Notification {
        val a = s.action
        val b = Notification.Builder(ctx, CH_NOW)
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_PROGRESS)
            .setContentIntent(activity(Intent(ctx, MainActivity::class.java), 10))
        if (a == null) return b.setContentTitle("回路").setContentText("正在评估刚才的干预效果").build()
        val elapsed = ((App.now() - a.startedAt) / 60_000).toInt()
        val pending = s.pending
        val next = s.nextCheckAt
        val base = when {
            pending != null -> "等待回答：${pending.question}"
            s.mode == Mode.RESTING -> "休息中，${s.restUntil?.let(Engine::hm)} 回来"
            s.muted -> "本轮不主动检查，${Engine.hm(a.endAt)} 确认结果"
            next != null -> "下次检查 ${Engine.hm(next)}" + (if (a.why.isNotBlank()) " · 为什么：${a.why}" else "")
            else -> "${Engine.hm(a.endAt)} 确认结果" + (if (a.why.isNotBlank()) " · 为什么：${a.why}" else "")
        }
        val line = if (s.locked.isEmpty()) base else "$base · 已暂停 ${s.locked.size} 个娱乐 App"
        b.setContentTitle("现在：${a.text}（$elapsed/${((a.endAt - a.startedAt) / 60_000)} 分钟）")
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(line))
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setWhen(a.endAt)
            .setShowWhen(true)
        if (pending != null) {
            b.addAction(action("回答", activity(CheckInActivity.intent(ctx, pending.id), 11)))
        } else {
            b.addAction(action("现在检查", broadcast(AnswerReceiver.CHECK_NOW, null, null, 12)))
        }
        b.addAction(action("完成", broadcast(AnswerReceiver.DONE, null, null, 13)))
        return b.build()
    }

    fun showCheckIn(c: CheckIn, fullScreen: Boolean, extra: String? = null) {
        val open = activity(CheckInActivity.intent(ctx, c.id), c.id.hashCode())
        val body = buildString {
            if (extra != null) append(extra).append("\n")
            append(c.question)
            if (c.deviation.reality.isNotBlank()) append("\n").append(c.deviation.reality)
        }
        val b = Notification.Builder(ctx, CH_CHECK)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("${c.trigger.label} · ${c.actionText}")
            .setContentText(extra ?: c.question)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_REMINDER)
            .setContentIntent(open)
            .setAutoCancel(false)
            .setOnlyAlertOnce(extra != null)
        if (fullScreen) b.setFullScreenIntent(open, true)
        c.choices.take(2).forEachIndexed { i, k ->
            b.addAction(action(k.label, broadcast(AnswerReceiver.ANSWER, c.id, k.name, c.id.hashCode() * 8 + i)))
        }
        val input = RemoteInput.Builder(AnswerReceiver.KEY_TEXT).setLabel("发生了什么？").build()
        b.addAction(Notification.Action.Builder(null, "说一句…", broadcast(AnswerReceiver.TEXT, c.id, null, c.id.hashCode() * 8 + 7, mutable = true))
            .addRemoteInput(input).build())
        nm.notify(TAG_CHECK, c.id.hashCode(), b.build())
    }

    fun cancelCheckIn(id: String) = nm.cancel(TAG_CHECK, id.hashCode())

    fun cancelAllCheckIns() {
        nm.activeNotifications.filter { it.tag == TAG_CHECK }.forEach { nm.cancel(TAG_CHECK, it.id) }
    }

    private fun action(title: String, pi: PendingIntent) = Notification.Action.Builder(null, title, pi).build()

    private fun activity(i: Intent, code: Int): PendingIntent =
        PendingIntent.getActivity(ctx, code, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

    private fun broadcast(op: String, id: String?, kind: String?, code: Int, mutable: Boolean = false): PendingIntent {
        val i = Intent(ctx, AnswerReceiver::class.java).setAction(op).putExtra(AnswerReceiver.KEY_ID, id).putExtra(AnswerReceiver.KEY_KIND, kind)
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE)
        return PendingIntent.getBroadcast(ctx, code, i, flags)
    }

    companion object {
        const val CH_NOW = "now"
        const val CH_CHECK = "checkin"
        const val TAG_CHECK = "checkin"
        const val ID_ONGOING = 1
    }
}
