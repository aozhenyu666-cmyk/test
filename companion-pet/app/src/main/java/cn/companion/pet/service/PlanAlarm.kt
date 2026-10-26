package cn.companion.pet.service

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import cn.companion.pet.PetApp
import cn.companion.pet.core.training.PlanLedgerStore
import cn.companion.pet.core.training.PlanOutcome
import cn.companion.pet.core.training.PlanRecord
import cn.companion.pet.core.training.TrainingPlanner
import java.io.File
import java.time.ZoneId

/**
 * S1 计划闹钟：到点叫醒桌宠，让它按本地规则决定开题、发通知还是不打扰。
 *
 * 用 setAndAllowWhileIdle：Doze 下系统可能推迟，推迟多少记在账本里（A18），不假装准时。
 * 桌宠服务在跑：直接交给它处理。不在跑：试着拉起；系统不允许后台拉起时，退回发通知并记账。
 */
object PlanAlarm {
    /** 桌宠服务注册：闹钟到点时立刻处理一次（主线程）。 */
    @Volatile var onFire: (() -> Unit)? = null

    fun schedule(context: Context, at: Long?) {
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        if (at == null) { am.cancel(pending(context)); return }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending(context))
    }

    private fun pending(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, 7, Intent(context, PlanReceiver::class.java),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    /** 下一个计划时间点（按当前设置）。计划关着返回 null。 */
    fun nextDue(context: Context, now: Long): Long? {
        val s = (context.applicationContext as PetApp).settings
        if (!s.trainingPlanEnabled) return null
        val p = TrainingPlanner()
        return p.nextDue(now, p.parseTimes(s.trainingPlanTimes), ZoneId.systemDefault())
    }

    class PlanReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent?) {
            val app = context.applicationContext as PetApp
            val s = app.settings
            if (!s.trainingPlanEnabled) return
            val now = System.currentTimeMillis()
            // 先排好下一次，后面任何一步失败都不影响以后的计划。
            runCatching { schedule(context, nextDue(context, now + 1_000)) }
            val handler = onFire
            if (PetService.running && handler != null) { runCatching { handler() }; return }
            if (s.userStopped) return // 用户主动停止过：不拉起，也不发通知
            if (PetService.startIfAllowed(context)) return // 服务起来后会自己补处理这个时间点
            // 后台不允许拉起桌宠：退回发通知，并如实记账（只记通知已提交，不记看见）。
            val planner = TrainingPlanner()
            val slot = planner.slots(now, planner.parseTimes(s.trainingPlanTimes), ZoneId.systemDefault())
                .lastOrNull { it.dueAt <= now } ?: return
            val store = PlanLedgerStore(File(context.filesDir, "memory"))
            val ledger = store.load()
            if (ledger.get(slot.slotId) != null) return
            val posted = PlanNotifier.post(context, "到点练一题了", "打开同桌，接着做今天的训练。")
            store.save(planner.record(ledger, PlanRecord(slot.slotId, slot.dueAt, now, now - slot.dueAt,
                if (posted) PlanOutcome.NOTIFIED else PlanOutcome.BLOCKED_BY_OS,
                "桌宠没在运行，系统不允许后台拉起" + if (posted) "，已发通知" else "，通知也被关了")))
        }
    }
}

object PlanNotifier {
    private const val CHANNEL = "plan_reminder"
    private const val ID = 3107

    /** 返回通知是否已提交给系统（不代表用户看见）。通知权限被关时返回 false。 */
    fun post(context: Context, title: String, text: String): Boolean = runCatching {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return false
        if (!nm.areNotificationsEnabled()) return false
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "训练计划提醒", NotificationManager.IMPORTANCE_DEFAULT))
        val open = PendingIntent.getActivity(context, 8,
            Intent(context, cn.companion.pet.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = Notification.Builder(context, CHANNEL)
            .setSmallIcon(cn.companion.pet.R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        nm.notify(ID, n)
        true
    }.getOrDefault(false)

    fun cancel(context: Context) {
        runCatching { context.getSystemService(NotificationManager::class.java)?.cancel(ID) }
    }
}
