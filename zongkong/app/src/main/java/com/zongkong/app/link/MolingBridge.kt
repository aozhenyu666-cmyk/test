package com.zongkong.app.link

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.zongkong.app.R
import com.zongkong.app.director.PhoneFacts
import com.zongkong.app.guard.SentinelLink
import com.zongkong.app.guard.SentinelService
import com.zongkong.app.zk
import com.zongkong.app.zkApp
import com.zongkong.core.DayClock
import com.zongkong.core.moling.Grants
import com.zongkong.core.moling.Holds
import com.zongkong.core.moling.Moling
import com.zongkong.core.moling.MolingOrder
import com.zongkong.core.moling.MolingStatus
import com.zongkong.core.plan.Plans
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock

/**
 * 总控这边接墨灵的胶水：给墨灵看的状态、墨灵建议的通知、用户点“执行 / 不用”之后的处理。
 * 规矩在 core/moling/Moling.kt；这里只管 Android。
 */
object MolingBridge {
    private const val CHANNEL = "moling"
    const val ACTION_RUN = "com.zongkong.app.moling.RUN"
    const val ACTION_DECLINE = "com.zongkong.app.moling.DECLINE"
    const val ACTION_VETO = "com.zongkong.app.moling.VETO"

    /** 同一时间只执行一条，防止倒计时和每分钟的巡检把同一条做两次。 */
    private val runLock = kotlinx.coroutines.sync.Mutex()
    const val EXTRA_ID = "id"

    /** 总控此刻的状态（墨灵每分钟读一次）。拿不到的字段留默认值。 */
    fun status(context: Context, now: Long): MolingStatus {
        val store = context.zk.store
        val zone = store.zone
        val f = PhoneFacts.of(context, now)
        val st = runCatching { store.status(now) }.getOrNull()
        val work = store.work.value
        val from = DayClock.startOf(DayClock.date(now, zone), zone)
        val workMin = runCatching { Plans.minutesByAction(work, from, now).values.sum() }.getOrDefault(0)
        val spend = runCatching {
            com.zongkong.core.ledger.Ledger.total(com.zongkong.core.ledger.Ledger.day(store.ledger.value, store.dateToday(now), zone))
        }.getOrDefault(0L)
        val book = store.moling.value
        val left = st?.takeIf { it.quotaMin > 0 }?.let { (it.quotaMin - it.playMin).coerceAtLeast(0) } ?: -1
        return MolingStatus(
            at = now,
            accessibilityOn = f.health?.permission ?: false,
            heartbeatAt = runCatching { store.heartbeat }.getOrDefault(0L),
            userDisabled = f.health?.userDisabled ?: false,
            strict = st?.strict ?: false,
            strictReason = st?.reasons?.firstOrNull()?.text.orEmpty(),
            focus = f.inFocus,
            hqActive = f.hqActive,
            quiet = f.quiet,
            mainTitle = f.mainTitle,
            mainKey = f.mainKey,
            mainPlannedAt = f.mainPlannedAt,
            mainStarted = f.mainStarted,
            mainDone = f.mainDone,
            lastProgressAt = f.lastProgressAt,
            playMinutesToday = f.playMinutesToday,
            workMinutesToday = workMin,
            playLeftMin = left,
            spendTodayCents = spend,
            credit = f.credit,
            stance = f.stance,
            aims = f.aims,
            weekDeliverables = f.weekDeliverables,
            upcoming = f.upcoming,
            blocked = store.config.value.blocked.toList(),
            sentinelOn = runCatching { SentinelLink.enabled(context, SentinelService::class.java) }.getOrDefault(false),
            bounces = runCatching { store.today.value.bounces }.getOrDefault(0),
            emergencies = runCatching { store.today.value.emergencies.size }.getOrDefault(0),
            gatesDone = st?.doneCount ?: 0,
            gatesTotal = st?.gates?.size ?: 0,
            openCommitments = runCatching { store.dossier.value.commitments.count { it.status == com.zongkong.core.boss.CommitStatus.OPEN } }.getOrDefault(0),
            holdUntil = Holds.strict(book.holds, now)?.until ?: 0L,
            cutToday = Holds.cutToday(book.holds, now, zone),
            grants = Grants.describe(Grants.applyDue(book.grants, now)),
        )
    }

    /** 收下一条墨灵的建议：要你确认的发“执行 / 不用”；授权内倒计时的发“取消”。 */
    fun notify(context: Context, o: MolingOrder) {
        val nm = context.getSystemService(android.app.NotificationManager::class.java)
        nm.createNotificationChannel(
            android.app.NotificationChannel(CHANNEL, "墨灵的建议", android.app.NotificationManager.IMPORTANCE_HIGH).apply {
                description = "墨灵建议总控做的事：你点“执行”才做"
            },
        )
        val id = notifyId(o.id)
        if (o.state == com.zongkong.core.moling.OrderState.SCHEDULED) {
            val text = o.text + "\n授权书内的事：${Moling.VETO_MS / 1000} 秒后执行。不想要就点“取消”。"
            val v = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_zongkong)
                .setContentTitle("墨灵马上要做")
                .setContentText(o.text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .addAction(0, "取消", action(context, ACTION_VETO, o.id, id + 3))
                .setTimeoutAfter(Moling.VETO_MS + 5_000)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .build()
            post(context, id, v)
            return
        }
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zongkong)
            .setContentTitle("墨灵建议")
            .setContentText(o.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(o.text + "\n点“执行”总控才会做；3 小时不处理就作废。"))
            .setAutoCancel(true)
            .addAction(0, "执行", action(context, ACTION_RUN, o.id, id + 1))
            .addAction(0, "不用", action(context, ACTION_DECLINE, o.id, id + 2))
            .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        post(context, id, n)
    }

    private fun post(context: Context, id: Int, n: android.app.Notification) {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        try {
            NotificationManagerCompat.from(context).notify(id, n)
        } catch (_: SecurityException) {
        }
    }

    /**
     * 执行一条：用户点了“执行”，或授权倒计时到了。临时开关写进记录，别的通过参谋的工具箱去做，
     * 结果写回记录，并用同一条通知告诉用户。
     */
    suspend fun run(context: Context, id: String) = runLock.withLock {
        val app = zkApp(context)
        val store = app.store
        val now = System.currentTimeMillis()
        val snap = store.moling.value
        val (after, r) = Moling.execute(snap, id, app.chat.agent.tools, { store.work.value }, store.config.value, now, store.zone)
        val o = after.orders.firstOrNull { it.id == id }
        // 执行期间墨灵可能又交来新的：只把这一条的结果和新开的临时开关合进当前记录，不整份覆盖
        store.updateMoling { cur ->
            val added = after.holds.filter { h -> cur.holds.none { it.id == h.id } }
            val c = cur.copy(holds = cur.holds + added)
            if (o != null) c.copy(orders = c.orders.map { if (it.id == id) o else it }) else c
        }
        done(context, id, (if (r.ok) "已执行：" else "没执行成：") + r.summary.ifBlank { r.text })
    }

    /** 倒计时到了的都执行（倒计时本身和每分钟的巡检都会调）。 */
    suspend fun runDue(context: Context) {
        val store = zkApp(context).store
        Moling.due(store.moling.value, System.currentTimeMillis()).forEach { run(context, it.id) }
    }

    /** 倒计时里点了“取消”。 */
    fun veto(context: Context, id: String) {
        zkApp(context).store.updateMoling { Moling.veto(it, id, System.currentTimeMillis()) }
        done(context, id, "取消了，这条不做（墨灵会知道）")
    }

    fun decline(context: Context, id: String) {
        zkApp(context).store.updateMoling { Moling.decline(it, id, System.currentTimeMillis()) }
        NotificationManagerCompat.from(context).cancel(notifyId(id))
    }

    /** 给墨灵看的一条建议后来怎么样了。 */
    fun result(context: Context, id: String): MolingOrder? = zkApp(context).store.moling.value.orders.firstOrNull { it.id == id }

    private fun done(context: Context, id: String, text: String) {
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_zongkong)
            .setContentTitle("墨灵的建议")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(notifyId(id), n)
        } catch (_: SecurityException) {
        }
    }

    private fun notifyId(orderId: String) = 3000 + (orderId.hashCode() and 0x7fff)

    private fun action(context: Context, what: String, id: String, code: Int): PendingIntent = PendingIntent.getBroadcast(
        context, code,
        Intent(context, MolingReceiver::class.java).setAction(what).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}

/** 通知上的“执行 / 不用”。不导出：只有总控自己的通知能触发。 */
class MolingReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra(MolingBridge.EXTRA_ID) ?: return
        when (intent.action) {
            MolingBridge.ACTION_DECLINE -> MolingBridge.decline(context, id)
            MolingBridge.ACTION_VETO -> MolingBridge.veto(context, id)
            MolingBridge.ACTION_RUN -> {
                val pending = goAsync()
                // 和参谋执行工具时一样，在应用协程里跑（主线程调度，工具自己不阻塞）
                zkApp(context).appScope.launch {
                    try {
                        MolingBridge.run(context, id)
                    } finally {
                        pending.finish()
                    }
                }
            }
        }
    }
}
