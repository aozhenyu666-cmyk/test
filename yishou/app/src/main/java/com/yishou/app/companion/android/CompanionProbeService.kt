package com.yishou.app.companion.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.yishou.app.MainActivity
import com.yishou.app.R
import com.yishou.app.companion.core.ProbeReport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 探针的前台服务。
 *
 * 为什么非要一个服务：Android 14 起，`getMediaProjection()` 要求 App 已经有一个
 * `mediaProjection` 类型的前台服务在跑，否则直接抛 SecurityException。
 * 之前探针页在 Activity 里建投屏，系统弹窗同意后照样拿不到，界面只剩一句「没拿到授权」——
 * 根因不是用户没点对，是少了这个服务。
 *
 * 看屏、刷着练早就是这么做的，探针是漏了。
 */
class CompanionProbeService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var engine: CompanionProbe? = null
    private var bindings: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_FINISH -> engine?.finishCapture()
            ACTION_STOP -> stopAll()
            ACTION_SELF_START -> engine?.startSelfPlayer()?.let { _note.value = it }
            ACTION_SELF_STOP -> { engine?.stopSelfPlayer(); _note.value = "自控播放器关了" }
            ACTION_SELF_PAUSE -> engine?.pauseSelfPlayer()
            ACTION_TARGET_PAUSE -> engine?.pauseTargetApp()
            else -> start(intent)
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent?) {
        // 先起前台服务，再碰投屏：顺序反了系统会拒绝
        ensureChannel(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID, notification("正在打开投屏"), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID, notification("正在打开投屏"))
        }
        if (engine != null) return

        val code = intent?.getIntExtra(EXTRA_CODE, 0) ?: 0
        @Suppress("DEPRECATION")
        val data = intent?.getParcelableExtra<Intent>(EXTRA_DATA)
        val window = intent?.getLongExtra(EXTRA_WINDOW, 20) ?: 20

        val e = CompanionProbe(this)
        engine = e
        bindings = scope.launch {
            launch { e.report.collect { _report.value = it } }
            launch { e.status.collect { _status.value = it } }
            launch { e.note.collect { _note.value = it } }
            launch { e.audioLevel.collect { _level.value = it } }
            launch { e.audioSummary.collect { _audioSummary.value = it } }
            launch { e.frameSummary.collect { _frames.value = it } }
            launch { e.playerNote.collect { _playerNote.value = it } }
        }

        val err = if (data == null || code == 0) "没有拿到投屏授权（系统回执丢了）" else e.startCapture(code, data, window)
        if (err != null) {
            _note.value = err
            stopAll()
            return
        }
        refresh("正在观察 ${window} 秒，切到目标 App 让它出声")
    }

    private fun stopAll() {
        bindings?.cancel()
        bindings = null
        val e = engine
        engine = null
        e?.shutdown()
        _status.value = com.yishou.app.companion.android.ProbeStatus()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        bindings?.cancel()
        engine?.shutdown()
        engine = null
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 73, Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_ROUTE, "probe"),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stop = PendingIntent.getService(
            this, 74, Intent(this, CompanionProbeService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle("一手 · 陪练探针")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .addAction(0, "停", stop)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun refresh(text: String) {
        try {
            getSystemService(NotificationManager::class.java).notify(ID, notification(text))
        } catch (e: SecurityException) { }
    }

    companion object {
        private const val CHANNEL = "probe"
        private const val ID = 82
        private const val ACTION_FINISH = "finish"
        private const val ACTION_STOP = "stop"
        private const val ACTION_SELF_START = "self_start"
        private const val ACTION_SELF_STOP = "self_stop"
        private const val ACTION_SELF_PAUSE = "self_pause"
        private const val ACTION_TARGET_PAUSE = "target_pause"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"
        private const val EXTRA_WINDOW = "window"

        private val _report = MutableStateFlow(ProbeReport())
        val report: StateFlow<ProbeReport> = _report.asStateFlow()
        private val _status = MutableStateFlow(ProbeStatus())
        val status: StateFlow<ProbeStatus> = _status.asStateFlow()
        private val _note = MutableStateFlow<String?>(null)
        val note: StateFlow<String?> = _note.asStateFlow()
        private val _level = MutableStateFlow(0f)
        val level: StateFlow<Float> = _level.asStateFlow()
        private val _audioSummary = MutableStateFlow("还没开始读音频")
        val audioSummary: StateFlow<String> = _audioSummary.asStateFlow()
        private val _frames = MutableStateFlow("还没开镜像")
        val frameSummary: StateFlow<String> = _frames.asStateFlow()
        private val _playerNote = MutableStateFlow("没开")
        val playerNote: StateFlow<String> = _playerNote.asStateFlow()

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "陪练探针", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) },
            )
        }

        /** 用户在系统弹窗里同意之后调用 */
        fun start(context: Context, code: Int, data: Intent, windowSec: Long = 20) {
            context.startForegroundService(
                Intent(context, CompanionProbeService::class.java)
                    .putExtra(EXTRA_CODE, code)
                    .putExtra(EXTRA_DATA, data)
                    .putExtra(EXTRA_WINDOW, windowSec),
            )
        }

        private fun action(context: Context, a: String) {
            context.startService(Intent(context, CompanionProbeService::class.java).setAction(a))
        }

        fun finish(context: Context) = action(context, ACTION_FINISH)
        fun stop(context: Context) = action(context, ACTION_STOP)
        fun startSelfPlayer(context: Context) = action(context, ACTION_SELF_START)
        fun stopSelfPlayer(context: Context) = action(context, ACTION_SELF_STOP)
        fun pauseSelfPlayer(context: Context) = action(context, ACTION_SELF_PAUSE)
        fun pauseTargetApp(context: Context) = action(context, ACTION_TARGET_PAUSE)

        fun say(text: String) {
            _note.value = text
        }
    }
}
