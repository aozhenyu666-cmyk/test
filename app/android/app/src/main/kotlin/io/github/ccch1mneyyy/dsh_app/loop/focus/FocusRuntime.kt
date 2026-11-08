package io.github.ccch1mneyyy.dsh_app.loop.focus

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.inputmethod.InputMethodManager
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.loop.core.VisibleSurface
import io.github.ccch1mneyyy.dsh_app.loop.core.AtomicJsonFile
import io.github.ccch1mneyyy.dsh_app.loop.core.SnapshotStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 原生专注监督（总控的第一块）：从无障碍服务已经读到的前台包名做判断，
 * 离开任务应用超过 5 秒就全屏拉回。它不依赖 DSH 引擎、不依赖模型，
 * 所以引擎被系统清掉时，这一层仍然工作。
 *
 * 主线程 only：前台读数来自无障碍事件，页面也在主线程起。
 */
object FocusRuntime {
    private const val TAG = "DshFocus"
    private const val CHANNEL = "dsh_focus"
    private const val NOTIF_ID = 4310

    private val main = Handler(Looper.getMainLooper())
    private lateinit var app: Context
    private var store: AtomicJsonFile? = null
    @Volatile private var state = FocusState()
    private var deviationPkg = ""
    private var deviationSince = 0L
    private var imePackages: Set<String> = emptySet()
    private var ticker: Runnable? = null

    fun ensure(context: Context): FocusState {
        if (store == null) {
            app = context.applicationContext
            val dir = File(File(app.filesDir, "loop"), "focus")
            val file = AtomicJsonFile(File(dir, "state.json"))
            store = file
            state = when (val r = file.read()) {
                is SnapshotStore.Read.Loaded -> FocusState.fromMap(r.map)
                is SnapshotStore.Read.Unreadable -> {
                    Log.w(TAG, "state unreadable: " + r.error)
                    FocusState()
                }
                SnapshotStore.Read.Empty -> FocusState()
            }
            val imm = app.getSystemService(InputMethodManager::class.java)
            imePackages = imm?.enabledInputMethodList?.mapNotNull { it.packageName }?.toSet().orEmpty()
            restore()
        }
        return state
    }

    fun state(): FocusState = state

    fun isActive(): Boolean = state.active

    fun taskPackage(): String = state.taskPackage

    fun remainingMinutes(now: Long = System.currentTimeMillis()): Int {
        if (!state.active || state.endAt <= 0L) return 0
        return (((state.endAt - now).coerceAtLeast(0L)) / 60_000L).toInt()
    }

    fun elapsedMinutes(now: Long = System.currentTimeMillis()): Int {
        if (state.startedAt <= 0L) return 0
        return (((now - state.startedAt).coerceAtLeast(0L)) / 60_000L).toInt()
    }

    fun isPaused(now: Long = System.currentTimeMillis()): Boolean = state.pausedUntil > now

    fun start(task: String, taskPackage: String, endAt: Long = 0L) {
        val now = System.currentTimeMillis()
        state = FocusState(
            active = true,
            task = task.trim().ifEmpty { "专注" }.take(200),
            taskPackage = taskPackage.trim(),
            allowed = setOfNotNull(taskPackage.trim().takeIf { it.isNotBlank() }),
            startedAt = now,
            endAt = endAt,
        )
        deviationPkg = ""
        deviationSince = 0L
        save()
        notifyState()
        scheduleTick()
        Log.i(TAG, "start: " + state.task + " app=" + state.taskPackage)
    }

    fun stop(reason: String) {
        if (!state.active) return
        state = state.copy(active = false, stoppedReason = reason.take(80))
        save()
        cancelTick()
        cancelNotification()
        deviationPkg = ""
        deviationSince = 0L
        Log.i(TAG, "stop: $reason")
    }

    fun pause(minutes: Int) {
        if (!state.active) return
        val ms = (minutes.coerceIn(1, 240)) * 60_000L
        state = state.copy(pausedUntil = System.currentTimeMillis() + ms)
        save()
        notifyState()
    }

    fun resume() {
        if (!state.active) return
        state = state.copy(pausedUntil = 0L)
        save()
        notifyState()
    }

    /** 页面上的「回到任务」：清掉偏离计时，避免下一跳立刻又弹。 */
    fun onReturned() {
        deviationPkg = ""
        deviationSince = 0L
        if (state.active) {
            state = state.copy(lastBlockAt = System.currentTimeMillis(), lastBlockPkg = "")
            save()
        }
    }

    /** 无障碍掉线：不再有前台读数，通知里要说清楚，不能假装还在监督。 */
    fun onA11yGone() {
        if (!state.active) return
        cancelTick()
        notifyState(gone = true)
    }

    fun onA11yBack() {
        if (!state.active) return
        notifyState()
        scheduleTick()
    }

    /** 每一次验证过的前台读数（null/空 = 读不到，不判定）。 */
    fun onForeground(packageName: String?) {
        if (!state.active) return
        val now = System.currentTimeMillis()
        if (FocusPolicy.isExpired(state, now)) {
            stop("time_up")
            return
        }
        val pkg = packageName?.trim().orEmpty()
        if (pkg.isEmpty()) return
        if (pkg != deviationPkg) {
            deviationPkg = pkg
            deviationSince = now
        }
        val decision = FocusPolicy.evaluate(
            state, pkg, now, deviationSince, app.packageName, imePackages,
        )
        if (decision is FocusDecision.Block) showBlock(decision, now)
    }

    private fun showBlock(block: FocusDecision.Block, now: Long) {
        val token = "focus:" + block.packageName + ":" + now
        if (!VisibleSurface.claim(VisibleSurface.OWNER_FOCUS, token, now)) {
            Log.i(TAG, "surface busy (" + VisibleSurface.holder() + "); focus page skipped")
            return
        }
        state = state.copy(
            deviations = state.deviations + 1,
            lastBlockAt = now,
            lastBlockPkg = block.packageName,
        )
        save()
        notifyState()
        vibrate()
        val label = labelOf(block.packageName)
        val ok = FocusPageActivity.start(app, block.packageName, label, block.secondsOff)
        if (!ok) {
            VisibleSurface.release(VisibleSurface.OWNER_FOCUS, token)
            Log.w(TAG, "could not start focus page for " + block.packageName)
        } else {
            deviationSince = now
        }
    }

    fun onPageGone() {
        VisibleSurface.release(VisibleSurface.OWNER_FOCUS, null)
    }

    fun labelOf(pkg: String): String = pkg.takeIf { it.isNotBlank() }?.let { p ->
        runCatching {
            app.packageManager.getApplicationLabel(app.packageManager.getApplicationInfo(p, 0)).toString()
        }.getOrNull()
    } ?: pkg

    fun requestBatteryExemption(context: Context) {
        val power = context.getSystemService(PowerManager::class.java)
        if (power?.isIgnoringBatteryOptimizations(context.packageName) == true) return
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }.onFailure { Log.w(TAG, "battery exemption", it) }
    }

    fun isIgnoringBattery(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true

    fun openBatterySettings(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    fun openAppDetails(context: Context) {
        runCatching {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    // ---------------------------------------------------------------- 持久化与通知

    private fun save() {
        store?.write(state.toMap())
    }

    private fun restore() {
        val now = System.currentTimeMillis()
        when {
            !state.active -> cancelNotification()
            FocusPolicy.isExpired(state, now) -> stop("time_up")
            else -> {
                notifyState()
                scheduleTick()
            }
        }
    }

    private fun ensureChannel(): NotificationManager? {
        val nm = app.getSystemService(NotificationManager::class.java) ?: return null
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val channel = NotificationChannel(CHANNEL, "专注监督", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "专注监督进行中的状态与快速操作"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            nm.createNotificationChannel(channel)
        }
        return nm
    }

    private fun notifyState(gone: Boolean = false) {
        val nm = ensureChannel() ?: return
        val open = PendingIntent.getActivity(
            app, 1,
            Intent(app, FocusControlActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val paused = state.pausedUntil > System.currentTimeMillis()
        val title = if (gone) "专注监督 · 无障碍掉线" else "专注 · " + state.task
        val text = when {
            gone -> "看不到前台应用了：请到无障碍设置重新打开 DSH"
            paused -> "已暂停到 " + clock(state.pausedUntil)
            else -> "已 " + elapsedMinutes() + " 分钟 · 偏离 " + state.deviations + " 次"
        }
        val builder = Notification.Builder(app, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PRIVATE)
        if (state.active) {
            builder.addAction(
                Notification.Action.Builder(
                    null,
                    if (paused) "继续" else "暂停 5 分钟",
                    action(if (paused) FocusActionReceiver.ACTION_RESUME else FocusActionReceiver.ACTION_PAUSE),
                ).build(),
            )
            builder.addAction(
                Notification.Action.Builder(null, "结束监督", action(FocusActionReceiver.ACTION_STOP)).build(),
            )
        }
        val notification = builder.build()
        runCatching { nm.notify(NOTIF_ID, notification) }
    }

    private fun action(name: String): PendingIntent = PendingIntent.getBroadcast(
        app, name.hashCode(),
        Intent(app, FocusActionReceiver::class.java).setAction(name),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun cancelNotification() {
        runCatching { app.getSystemService(NotificationManager::class.java)?.cancel(NOTIF_ID) }
    }

    private fun scheduleTick() {
        cancelTick()
        val r = object : Runnable {
            override fun run() {
                if (!state.active) return
                if (FocusPolicy.isExpired(state, System.currentTimeMillis())) {
                    stop("time_up")
                    return
                }
                notifyState()
                main.postDelayed(this, 30_000L)
            }
        }
        ticker = r
        main.postDelayed(r, 30_000L)
    }

    private fun cancelTick() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
    }

    private fun vibrate() {
        runCatching {
            val v = app.getSystemService(android.os.Vibrator::class.java) ?: return
            if (!v.hasVibrator()) return
            v.vibrate(android.os.VibrationEffect.createWaveform(longArrayOf(0, 120, 80, 120), -1))
        }
    }

    private fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))
}
