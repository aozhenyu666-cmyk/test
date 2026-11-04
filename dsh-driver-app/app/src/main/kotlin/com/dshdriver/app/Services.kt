package com.dshdriver.app

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.AudioPlaybackConfiguration
import android.media.AudioRecordingConfiguration
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import com.dshdriver.core.Verdict

class DriverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
    }
}

/**
 * 无障碍服务：拦下娱乐 App、记录最近前台（诊断用）、顺手给自己续命。
 *
 * 拦截这一段照抄 Curbox（GPL-3.0，提交 b06520f4dc35018c184b65341fbc4ffe31b2eb08）的
 * `blockers/AppBlocker.kt` 里 `doAppBlockerCheck` / `showWarningScreen`，以及 `utils/PackageFinderTools.kt`
 * 的 `isActivePackage`：
 *
 *   只看 WINDOW_STATE_CHANGED / WINDOW_CONTENT_CHANGED   → 事件包名再用 rootInActiveWindow 复核
 *   要拦 → performGlobalAction(GLOBAL_ACTION_HOME) 把目标 App 送走 → 100ms 后再 startActivity(拦截页)
 *   同一个包 1 秒内只处理一次（防连拦）→ 拦截页在前台时完全不拦（否则自己弹自己）
 *
 * 复核不一致就不拦：宁可漏拦一个，也别在别人正在用的界面上弹我们的页。
 * 只读包名和窗口类名，不读屏幕文字。
 */
class WatchService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastBlockedPkg = ""
    private var lastSeenPkg = ""
    private var lastActionAt = 0L
    private var lastBeatAt = 0L
    private var lastCheckedAt = 0L

    override fun onServiceConnected() {
        Engine.watchConnected = true
        ObservationShots.connected(this)
        val initial=runCatching {rootInActiveWindow}.getOrNull()
        Engine.onForeground(this,initial?.packageName?.toString(),null)
        @Suppress("DEPRECATION") runCatching {initial?.recycle()}
        A11yGuard.clearDownNotice(this)
        WatchdogJob.schedule(this)
        GuardService.sync(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        heartbeat()
        if (event.eventType and TARGET_EVENTS == 0) return
        if(!AwarenessRuntime.screenAvailable()) {Engine.onForeground(this,null,null);return}
        val pkg = event.packageName?.toString() ?: return
        // 自己弹的页（拦截页 / 断点卡）不算"打开了娱乐 App"
        // Own pages also occupy time; only interception ignores them.

        // 事件包名和当前活动窗口不一致时不当真（快速切换、输入法浮层都会产生这种事件）
        val root = runCatching { rootInActiveWindow }.getOrNull()
        val active = root?.packageName?.toString()
        @Suppress("DEPRECATION") runCatching { root?.recycle() }
        if (active.isNullOrBlank() || active != pkg) {Engine.onForeground(this,null,null);return}

        // 同一个 App 内部的内容变化不重复判断：加了内容变化事件后这类事件很密，全判一遍没必要也费电。
        // 但每 5 秒要复查一次：放行到期后用户如果一直停在 App 里，也要重新拦，不能等他切走。
        Engine.onForeground(this,active,event.className?.toString())
        val now = SystemClock.uptimeMillis()
        if (active == lastSeenPkg && now - lastCheckedAt < RECHECK_MS) return
        lastSeenPkg = active
        lastCheckedAt = now
        if(active!=packageName) maybeBlock(active)
    }

    private fun maybeBlock(pkg: String) {
        if (GateActivity.visible) return
        if (Intercept.decide(this, pkg, System.currentTimeMillis()) != Verdict.BLOCK) return
        val now = SystemClock.uptimeMillis()
        if (pkg == lastBlockedPkg && now - lastActionAt < COOLDOWN_MS) return
        val wall = System.currentTimeMillis()
        if (!Intercept.claimTrigger(pkg, wall)) return
        lastBlockedPkg = pkg
        lastActionAt = now
        // 先把 App 送走，再从桌面拉起拦截页：顺序反了会被系统的"后台启动"限制挡住，或者盖不上去
        performGlobalAction(GLOBAL_ACTION_HOME)
        main.postDelayed({
            val ok = runCatching { startActivity(GateActivity.intent(this, pkg)) }.isSuccess
            lastSeenPkg = "" // 拦完清掉，用户再打开时要重新判断
            lastCheckedAt = 0L
            if (!ok) lastBlockedPkg = "" // 拉不起来就允许下一次重试
            // 后台启动被系统静默丢弃时不会抛异常，1.2 秒后还没看到拦截页就补一条可点通知
            main.postDelayed({ if (!GateActivity.visible) UsageMonitor.notifyBlocked(this, pkg) }, 1_200L)
        }, LAND_DELAY_MS)
    }

    /** 每 30 秒一次：确认看门狗还排着（用户清过调度的话会被清掉）。 */
    private fun heartbeat() {
        val now = SystemClock.uptimeMillis()
        if (now - lastBeatAt < 30_000L) return
        lastBeatAt = now
        WatchdogJob.schedule(this)
        GuardService.sync(this)
    }

    override fun onInterrupt() {Engine.onForeground(this,null,null);lastSeenPkg=""}

    override fun onDestroy() {
        Engine.watchConnected = false
        ObservationShots.connected(null)
        Engine.onForeground(this,null,null)
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private companion object {
        const val TARGET_EVENTS = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        const val LAND_DELAY_MS = 100L
        const val COOLDOWN_MS = 1_000L
        const val RECHECK_MS = 5_000L
    }
}

/**
 * 看门狗：无障碍被系统关掉、进程又被杀时，靠它定期把服务写回去。
 * JobScheduler 最短周期 15 分钟；`setPersisted` 让它在重启后还在。
 * 抄自 Curbox 的 `services/ServiceWatchdogJob.kt`，去掉了协程和它的通知文案。
 *
 * 它打不过"强制停止"——那会把这个任务一起清掉，只能等用户下次打开 APP 再写回。
 */
class WatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        val ctx = applicationContext
        Thread {
            runCatching {
                if (A11yGuard.isOn(ctx)) {
                    A11yGuard.clearDownNotice(ctx)
                } else {
                    val healed = A11yGuard.ensure(ctx)
                    if (!healed && !A11yGuard.canHeal(ctx)) A11yGuard.notifyDown(ctx)
                }
                // 看门狗也负责把常驻守护拉回来：服务被清掉时，光写回无障碍还不够
                GuardService.sync(ctx)
            }
            jobFinished(params, false)
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean = true

    companion object {
        private const val JOB_ID = 7711
        private const val PERIOD_MS = 15 * 60_000L

        fun schedule(context: Context) {
            runCatching {
                val scheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
                scheduler.schedule(
                    JobInfo.Builder(JOB_ID, ComponentName(context, WatchdogJob::class.java))
                        .setPersisted(true)
                        .setPeriodic(PERIOD_MS)
                        .build(),
                )
            }
        }
    }
}

/** 开机 / 覆盖安装后把无障碍写回来，并把看门狗排上。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        val ctx = context.applicationContext
        val pending = goAsync()
        Thread {
            runCatching {
                if (!A11yGuard.isOn(ctx)) A11yGuard.ensure(ctx)
                WatchdogJob.schedule(ctx)
                GuardService.sync(ctx)
            }
            pending.finish()
        }.start()
    }
}

/** 陪练期间的前台服务：让系统别杀进程，并挂着音频状态监听。 */
class LockService : Service() {
    private val screen = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            Engine.onForeground(context,null,null)
        }
    }
    override fun onCreate() {
        super.onCreate()
        val filter=android.content.IntentFilter().apply {addAction(Intent.ACTION_SCREEN_OFF);addAction(Intent.ACTION_SCREEN_ON);addAction(Intent.ACTION_USER_PRESENT)}
        if(Build.VERSION.SDK_INT>=33) registerReceiver(screen,filter,Context.RECEIVER_NOT_EXPORTED) else registerReceiver(screen,filter)
    }
    private var signals: AudioSignals? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val label = intent?.getStringExtra(EXTRA_LABEL).orEmpty()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "陪练中", NotificationManager.IMPORTANCE_LOW))
        val pi = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_driver)
            .setContentTitle("思维驾驶员 · 陪练中").setContentText(label.ifBlank { "顶部小卡会追问你" })
            .setOngoing(true).setContentIntent(pi).build()
        if (Build.VERSION.SDK_INT >= 34) startForeground(NOTE_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        else startForeground(NOTE_ID, n)
        if (Engine.locked && signals == null) signals = AudioSignals(this) { rec, play -> Engine.onAudio(rec, play) }.also { it.start() }
        if(AwarenessRuntime.active) Engine.ensureTicker()
        return if(AwarenessRuntime.active) START_STICKY else START_NOT_STICKY
    }

    override fun onDestroy() {
        unregisterReceiver(screen)
        signals?.stop()
        signals = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "lock"
        private const val NOTE_ID = 1
        private const val EXTRA_LABEL = "label"

        fun start(context: Context, label: String) {
            context.startForegroundService(Intent(context, LockService::class.java).putExtra(EXTRA_LABEL, label))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, LockService::class.java))
        }
    }
}

/**
 * 系统音频状态：只看"有没有人在录"和"有没有助手/语音类声音在播"，不碰声音内容，不需要麦克风权限。
 * 锁定期间前台只有 Live，所以"有人在录"≈ Live 在听，"有语音在播"≈ Gemini 在说。**这两条推断还没有真机证据。**
 */
class AudioSignals(context: Context, private val onChange: (recording: Boolean, assistantPlaying: Boolean) -> Unit) {
    private val am = context.getSystemService(AudioManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val rec = object : AudioManager.AudioRecordingCallback() {
        override fun onRecordingConfigChanged(configs: MutableList<AudioRecordingConfiguration>?) = report()
    }
    private val play = object : AudioManager.AudioPlaybackCallback() {
        override fun onPlaybackConfigChanged(configs: MutableList<AudioPlaybackConfiguration>?) = report()
    }

    fun start() {
        am?.registerAudioRecordingCallback(rec, handler)
        am?.registerAudioPlaybackCallback(play, handler)
        report()
    }

    fun stop() {
        am?.unregisterAudioRecordingCallback(rec)
        am?.unregisterAudioPlaybackCallback(play)
    }

    private fun report() {
        val a = am ?: return
        val recording = runCatching { a.activeRecordingConfigurations.isNotEmpty() }.getOrDefault(false)
        val playing = runCatching { a.activePlaybackConfigurations.any { it.audioAttributes.usage in VOICE_USAGES } }.getOrDefault(false)
        onChange(recording, playing)
    }

    private companion object {
        val VOICE_USAGES = setOf(
            AudioAttributes.USAGE_ASSISTANT,
            AudioAttributes.USAGE_VOICE_COMMUNICATION,
            AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE,
            AudioAttributes.USAGE_MEDIA, // 部分版本的 Live 走媒体流；锁定期间前台只有 Live，误判空间小
        )
    }
}
