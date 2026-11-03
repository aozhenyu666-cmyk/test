package com.dshdriver.app

import android.accessibilityservice.AccessibilityService
import android.app.Application
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
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
import android.view.accessibility.AccessibilityEvent

class DriverApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Engine.init(this)
        Protection.ensure(this)
        Protection.schedule(this)
    }
}

/** Window events are only acted on when the active root confirms the same package. */
class WatchService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private val lastBlocked = mutableMapOf<String, Long>()
    private var gatePending = false
    private var expiry: Runnable? = null
    private var exemptions = emptySet<String>()

    override fun onServiceConnected() {
        Engine.watchConnected = true
        exemptions = Engine.store.blocking.exemptPackages()
        Protection.startIfNeeded(this)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || event.eventType !in setOf(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED, AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED)) return
        val pkg = event.packageName?.toString() ?: return
        val root = runCatching { rootInActiveWindow }.getOrNull() ?: return
        val active = root.packageName?.toString()
        @Suppress("DEPRECATION") runCatching { root.recycle() }
        if (active != pkg) return
        Engine.onForeground(this, pkg, event.className?.toString())
        inspect(pkg)
    }

    private fun inspect(pkg: String) {
        expiry?.let(main::removeCallbacks)
        expiry = null
        val rules = Engine.store.blocking
        val now = System.currentTimeMillis()
        if (rules.decide(pkg, now, exemptions) == com.dshdriver.core.BlockRules.Decision.Allow) {
            val until = rules.expiry(pkg)
            if (rules.enabled && pkg in rules.packages && until > now) {
                expiry = Runnable {
                    val root = runCatching { rootInActiveWindow }.getOrNull()
                    val active = root?.packageName?.toString()
                    @Suppress("DEPRECATION") runCatching { root?.recycle() }
                    if (active == pkg) inspect(pkg)
                }.also { main.postDelayed(it, until - now) }
            }
            return
        }
        val elapsed = android.os.SystemClock.elapsedRealtime()
        if (gatePending || elapsed - (lastBlocked[pkg] ?: -1000L) < 1000L) return
        if (!performGlobalAction(GLOBAL_ACTION_HOME)) return
        lastBlocked[pkg] = elapsed
        gatePending = true
        main.postDelayed({
            gatePending = false
            if (rules.decide(pkg, System.currentTimeMillis(), exemptions) == com.dshdriver.core.BlockRules.Decision.Block) {
                runCatching { startActivity(Intent(this, GateActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    .putExtra(GateActivity.EXTRA_PACKAGE, pkg)) }
            }
        }, 100L)
    }

    override fun onInterrupt() = Unit
    override fun onDestroy() {
        Engine.watchConnected = false
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

/** 陪练期间的前台服务：让系统别杀进程，并挂着音频状态监听。 */
class LockService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private val heartbeat = object : Runnable {
        override fun run() { Protection.heartbeat(this@LockService); main.postDelayed(this, 30_000) }
    }
    override fun onCreate() { super.onCreate(); main.post(heartbeat) }
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
        if (signals == null) signals = AudioSignals(this) { rec, play -> Engine.onAudio(rec, play) }.also { it.start() }
        return START_NOT_STICKY // 进程被杀后状态机已经没了，重启服务也接不回去，不假装还在锁定
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
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
