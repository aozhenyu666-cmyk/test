package com.yishou.app.focus

import android.app.*
import android.content.*
import android.content.pm.ServiceInfo
import android.media.RingtoneManager
import android.os.*
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.yishou.app.MainActivity
import com.yishou.app.R
import com.yishou.app.YishouApp
import com.yishou.app.log.RunLog
import kotlinx.coroutines.*

/** 通知保持可见；用下一段的闹钟补上锁屏时协程可能睡过头的切换。 */
class FocusService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as YishouApp
    private var loop: Job? = null
    private var phaseKey: String? = null
    private var alarmAt = 0L
    private val manager get() = getSystemService(NotificationManager::class.java)
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        channels(this)
        val initial = NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("专注流计时中").setOngoing(true).setContentIntent(open(this)).build()
        try {
            if (Build.VERSION.SDK_INT >= 34) startForeground(ID, initial, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(ID, initial)
        } catch (e: Exception) {
            RunLog.e("专注流", "计时服务没能进入前台", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_MARK) {
            app.focus.mark(intent.getLongExtra("session", -1), intent.getIntExtra("round", -1), intent.getBooleanExtra("done", false), "")
            manager.cancel(ROUND_ID)
        }
        if (loop?.isActive != true) loop = scope.launch {
            while (isActive) {
                tick()
                delay(1000)
            }
        }
        return START_STICKY
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        // 到点时最后一组也需要补记，先取组尾再归档。
        app.focus.claimRounds()?.let { (r, rounds) -> roundNotice(r, rounds.last()) }
        val r = app.focus.active(now)
        app.focus.claimEnd()?.let { ended ->
            val s = ended.session
            notify("专注流结束：做完 ${s.doneRounds} / 共 ${FocusRuntime.totalRounds(s)} 组", "${s.plan.task} · 可回到专注页补记", END_ID)
        }
        if (r == null) {
            cancelAlarm()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            loop?.cancel()
            return
        }
        val p = r.session.phase(now)
        val key = "${r.session.startedAt}:${p.kind}:${p.startedAt}"
        if (phaseKey != null && phaseKey != key) {
            notify(if (p.kind == PhaseKind.REST) "这一组结束，休息一下" else "回到工作，第 ${p.round} 组", r.session.plan.task, TRANSITION_ID)
        }
        phaseKey = key
        val title = "专注中 · ${if (p.kind == PhaseKind.WORK) "工作" else "休息"} · 第 ${p.round} 组 · 还剩 ${FocusRuntime.clock(p.remainingMs(now))}"
        manager.notify(ID, NotificationCompat.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle(title).setContentText(r.session.plan.task).setContentIntent(open(this)).setOngoing(true).setOnlyAlertOnce(true)
            .addAction(0, "打开", open(this)).build())
        if (alarmAt != p.endsAt) schedule(p.endsAt)
    }

    private fun roundNotice(r: FocusRun, round: Int) {
        val b = NotificationCompat.Builder(this, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("第 $round 组：这一组做完了吗？").setContentText("可选一句，打开专注页补充；不点也继续计时")
            .setContentIntent(open(this)).setAutoCancel(true)
        listOf(true to "做完了", false to "没做完").forEach { (done, label) ->
            val i = Intent(this, FocusService::class.java).setAction(ACTION_MARK)
                .putExtra("session", r.session.startedAt).putExtra("round", round).putExtra("done", done)
            b.addAction(0, label, PendingIntent.getForegroundService(this, if (done) 451 else 452, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        }
        manager.notify(ROUND_ID, b.build())
    }

    private fun notify(title: String, text: String, id: Int) {
        manager.notify(id, NotificationCompat.Builder(this, ALERT_CHANNEL).setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle(title).setContentText(text).setContentIntent(open(this)).setAutoCancel(true).build())
        @Suppress("DEPRECATION")
        val vibrator = if (Build.VERSION.SDK_INT >= 31) getSystemService(VibratorManager::class.java).defaultVibrator else getSystemService(Vibrator::class.java)
        vibrator?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 120, 100, 120), -1))
        runCatching { RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION))?.play() }
    }

    private fun alarmIntent() = PendingIntent.getBroadcast(this, 453, Intent(this, FocusAlarmReceiver::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    private fun schedule(at: Long) {
        val alarms = getSystemService(AlarmManager::class.java)
        val pending = alarmIntent()
        try {
            if (Build.VERSION.SDK_INT < 31 || alarms.canScheduleExactAlarms()) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            alarmAt = at
        } catch (e: SecurityException) {
            RunLog.e("专注流", "准点闹钟不可用，改用普通闹钟", e)
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
            alarmAt = at
        }
    }
    private fun cancelAlarm() { getSystemService(AlarmManager::class.java).cancel(alarmIntent()); alarmAt = 0 }
    override fun onDestroy() { scope.cancel(); super.onDestroy() }

    companion object {
        private const val CHANNEL = "focus_timer"
        private const val ALERT_CHANNEL = "focus_changes"
        private const val ID = 45
        private const val ROUND_ID = 46
        private const val END_ID = 47
        private const val TRANSITION_ID = 48
        private const val ACTION_MARK = "focus.mark"
        fun open(context: Context) = PendingIntent.getActivity(context, 450,
            Intent(context, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "focus").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun start(context: Context) = ContextCompat.startForegroundService(context, Intent(context, FocusService::class.java))
        private fun channels(context: Context) {
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(NotificationChannel(CHANNEL, "专注流计时", NotificationManager.IMPORTANCE_LOW))
            // 声音和两下震动在段切换时各播一次，提问通知不再重复响。
            manager.createNotificationChannel(NotificationChannel(ALERT_CHANNEL, "专注流提醒", NotificationManager.IMPORTANCE_DEFAULT).apply { setSound(null, null); enableVibration(false) })
        }
    }
}

class FocusAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        runCatching { FocusService.start(context) }.onFailure { RunLog.e("专注流", "系统没允许恢复计时服务", it) }
    }
}
