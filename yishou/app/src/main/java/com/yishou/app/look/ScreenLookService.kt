package com.yishou.app.look

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.util.Base64
import android.util.Log
import androidx.core.app.NotificationCompat
import com.yishou.app.R
import com.yishou.app.YishouApp
import com.yishou.app.llm.LlmResult
import com.yishou.app.stats.DayStats
import com.yishou.app.system.Notifications
import com.yishou.app.window.Speaker
import com.yishou.app.window.WindowActivity
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

/**
 * “让陪练看屏”：用户在系统弹窗里同意后，保持一个低分辨率的屏幕镜像，
 * 只在用户点“看一眼”（或设置的自动间隔）时取一帧，交给识图模型描述，再让陪练针对屏幕出一手。
 * 截图不保存。陪练窗口结束、用户点“停止”或系统收回授权时自动停止。
 */
class ScreenLookService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val app get() = application as YishouApp

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var imageThread: HandlerThread? = null
    /** 最新的一帧，一直持有，取图时直接用（屏幕不动时系统不会再送新帧） */
    private var latest: Image? = null
    private val lock = Any()

    private var speaker: Speaker? = null
    private var lookJob: Job? = null
    private var watchJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start(intent)
            ACTION_LOOK -> look()
            ACTION_STOP -> stopAll()
        }
        return START_NOT_STICKY
    }

    private fun start(intent: Intent) {
        ensureChannel(this)
        val n = foregroundNotification(null)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(ID_FOREGROUND, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(ID_FOREGROUND, n)
        }
        if (projection != null) return

        val code = intent.getIntExtra(EXTRA_CODE, 0)
        @Suppress("DEPRECATION")
        val data = intent.getParcelableExtra<Intent>(EXTRA_DATA)
        val mpm = getSystemService(MediaProjectionManager::class.java)
        val p = try {
            data?.let { mpm.getMediaProjection(code, it) }
        } catch (e: Exception) {
            Log.e(TAG, "无法获取屏幕授权", e)
            com.yishou.app.log.RunLog.e("看屏", "无法获取屏幕授权", e)
            null
        }
        if (p == null) {
            _status.value = "没有拿到看屏授权"
            stopAll()
            return
        }
        projection = p
        p.registerCallback(object : MediaProjection.Callback() {
            override fun onStop() {
                scope.launch { stopAll() }
            }
        }, Handler(Looper.getMainLooper()))

        // 镜像宽 720 像素就够识图用，省内存也省流量
        val metrics = resources.displayMetrics
        val w = 720
        val h = (metrics.heightPixels.toLong() * w / metrics.widthPixels).toInt()
        val thread = HandlerThread("yishou-look").also { it.start() }
        imageThread = thread
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            synchronized(lock) {
                latest?.close()
                latest = img
            }
        }, Handler(thread.looper))
        reader = r
        display = p.createVirtualDisplay(
            "yishou-look", w, h, metrics.densityDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
        )
        speaker = Speaker(this)
        _running.value = true
        _status.value = null
        watch()
    }

    /** 每分钟检查一次：窗口结束就停；设了自动间隔就按时看一眼。 */
    private fun watch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            var sinceLook = 0
            while (isActive) {
                delay(60_000)
                val prefs = app.settings.app.value
                val now = System.currentTimeMillis()
                if (WindowClock.active(now, prefs, java.time.ZoneId.systemDefault()) == null) {
                    stopAll()
                    break
                }
                sinceLook++
                val interval = prefs.lookIntervalMinutes
                if (interval > 0 && sinceLook >= interval && prefs.windowPauseUntil <= now) {
                    sinceLook = 0
                    look(delayMs = 0)
                }
            }
        }
    }

    private fun look(delayMs: Long = 900) {
        if (projection == null) {
            _status.value = "看屏没有开启"
            return
        }
        if (lookJob?.isActive == true) return
        lookJob = scope.launch {
            setStatus("正在看……")
            // 从通知栏点的“看一眼”：等通知栏收起，截到的才是底下的应用
            delay(delayMs)
            val dataUrl = withContext(Dispatchers.Default) { captureDataUrl() }
            if (dataUrl == null) {
                setStatus("没有截到画面，再点一次试试")
                return@launch
            }
            val dao = app.database.dao()
            val task = dao.getCurrentTask()
            if (task == null) {
                setStatus("还没有当前任务")
                return@launch
            }
            val screen = when (val r = app.vision.describeScreen(task.title, dataUrl)) {
                is LlmResult.Ok -> r.value
                is LlmResult.Err -> {
                    setStatus("识图失败：${r.error.message}")
                    return@launch
                }
            }
            when (val r = app.engine.observe(task, screen)) {
                is LlmResult.Err -> setStatus("出题失败：${r.error.message}")
                is LlmResult.Ok -> {
                    app.stats.event(DayStats.LOOK)
                    lastLookMove = r.value.coachMove
                    setStatus(null)
                    Notifications.showReminder(
                        this@ScreenLookService, ID_RESULT,
                        "陪练看了一眼",
                        listOf(r.value.observation, r.value.coachMove).filter { it.isNotBlank() }.joinToString("\n"),
                        WindowActivity::class.java,
                    )
                    if (app.settings.app.value.ttsEnabled && !WindowActivity.isVisible) {
                        speaker?.speak(com.yishou.app.speech.Spoken.of(r.value.coachMove, r.value.say))
                    }
                }
            }
        }
    }

    private fun setStatus(text: String?) {
        _status.value = text
        getSystemService(NotificationManager::class.java).notify(ID_FOREGROUND, foregroundNotification(text))
    }

    /** 把最新一帧转成 JPEG data URL；还没有帧时返回 null。 */
    private fun captureDataUrl(): String? {
        val bitmap = synchronized(lock) {
            val img = latest ?: return null
            val plane = img.planes[0]
            val pixelStride = plane.pixelStride
            val rowPadding = plane.rowStride - pixelStride * img.width
            val padded = Bitmap.createBitmap(img.width + rowPadding / pixelStride, img.height, Bitmap.Config.ARGB_8888)
            plane.buffer.rewind()
            padded.copyPixelsFromBuffer(plane.buffer)
            val cropped = Bitmap.createBitmap(padded, 0, 0, img.width, img.height)
            if (cropped !== padded) padded.recycle()
            cropped
        }
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        bitmap.recycle()
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    private fun stopAll() {
        watchJob?.cancel()
        lookJob?.cancel()
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        display?.release()
        display = null
        reader?.close()
        reader = null
        imageThread?.quitSafely()
        imageThread = null
        val p = projection
        projection = null
        try {
            p?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "停止看屏出错", e)
        }
        speaker?.shutdown()
        speaker = null
        _running.value = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        if (projection != null) stopAll()
        scope.cancel()
        super.onDestroy()
    }

    private fun foregroundNotification(status: String?): Notification {
        val lookPi = PendingIntent.getService(
            this, 1, Intent(this, ScreenLookService::class.java).setAction(ACTION_LOOK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val stopPi = PendingIntent.getService(
            this, 2, Intent(this, ScreenLookService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val open = PendingIntent.getActivity(
            this, 3, Intent(this, WindowActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_yishou)
            .setContentTitle(status ?: "陪练可以看屏")
            .setContentText("点“看一眼”，陪练就看你正在做的这一步。只在你点的时候截屏，图片不保存。")
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, "看一眼", lookPi)
            .addAction(0, "停止看屏", stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    companion object {
        private const val TAG = "ScreenLookService"
        private const val CHANNEL = "look"
        private const val ID_FOREGROUND = 5
        private const val ID_RESULT = 6
        private const val ACTION_START = "start"
        private const val ACTION_LOOK = "look"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_CODE = "code"
        private const val EXTRA_DATA = "data"

        private val _running = MutableStateFlow(false)
        val running: StateFlow<Boolean> = _running.asStateFlow()
        private val _status = MutableStateFlow<String?>(null)
        val status: StateFlow<String?> = _status.asStateFlow()

        /** 最近一次看屏出的一手。陪练窗口页据此避免和这里重复朗读。 */
        @Volatile
        var lastLookMove: String? = null
            private set

        private fun ensureChannel(context: Context) {
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL, "陪练看屏", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "看屏开启时的常驻通知，带“看一眼”按钮"
                    setShowBadge(false)
                },
            )
        }

        /** 用户在系统弹窗里同意之后调用。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            context.startForegroundService(
                Intent(context, ScreenLookService::class.java)
                    .setAction(ACTION_START)
                    .putExtra(EXTRA_CODE, resultCode)
                    .putExtra(EXTRA_DATA, data),
            )
        }

        fun look(context: Context) {
            context.startService(Intent(context, ScreenLookService::class.java).setAction(ACTION_LOOK))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, ScreenLookService::class.java).setAction(ACTION_STOP))
        }
    }
}
