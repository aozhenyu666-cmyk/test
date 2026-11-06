package com.yishou.app.look

import com.yishou.app.settings.AppZone

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
import com.yishou.app.companion.CompanionPrefs
import com.yishou.app.core.proactive.HighFreq
import com.yishou.app.core.proactive.HighFreqConfig
import com.yishou.app.core.proactive.HighFreqSession
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
        // 只有陪练窗口真的开着才挂窗口的前台服务：高频会话是独立的一场，
        // 没必要多一条「陪练窗口进行中」的通知。看屏自己的前台服务已经够保活。
        if (WindowClock.active(System.currentTimeMillis(), app.settings.app.value, AppZone.zone) != null) {
            com.yishou.app.window.WindowService.start(this)
        }
        _status.value = null
        watch()
    }

    /**
     * 平时每分钟检查一次窗口和自动间隔；高频会话里按秒看帧。
     * 高频会话不要求陪练窗口开着——它是一场独立的显式会话。
     */
    private fun watch() {
        watchJob?.cancel()
        watchJob = scope.launch {
            var seconds = 0
            var sinceLook = 0
            while (isActive) {
                delay(1_000)
                val prefs = app.settings.app.value
                val now = System.currentTimeMillis()
                val hfCfg = HighFreqConfig(
                    enabled = prefs.hfStartedAt > 0,
                    lookEverySec = prefs.hfLookSec,
                    minGapSec = prefs.hfGapSec,
                    sessionMinutes = prefs.hfMinutes,
                ).sane()
                val hf = CompanionPrefs(this@ScreenLookService).loadHighFreq(prefs.hfStartedAt, hfCfg.sessionMinutes)
                if (HighFreq.running(hfCfg, hf, now)) {
                    seconds = 0
                    if (HighFreq.dueLook(hfCfg, hf, now)) highFreqLook(hfCfg, hf, now)
                    continue
                }
                if (WindowClock.active(now, prefs, AppZone.zone) == null) {
                    stopAll()
                    break
                }
                if (++seconds < 60) continue
                seconds = 0
                sinceLook++
                val interval = prefs.lookIntervalMinutes
                if (interval > 0 && sinceLook >= interval && prefs.windowPauseUntil <= now) {
                    sinceLook = 0
                    look(delayMs = 0)
                }
            }
        }
    }

    /** 上一帧的采样签名：只在内存里，用来判断这一拍画面变没变。 */
    private var frameSig: IntArray? = null
    private var frameW = 0
    private var frameH = 0

    /**
     * 高频会话的一拍：先在本机比像素，画面没变就到此为止——不识图、不打扰。
     * 变了才用短提示词看一眼，把「他在做什么」写进上下文；开不开口仍由唯一调度器决定。
     */
    private fun highFreqLook(cfg: HighFreqConfig, session: HighFreqSession, now: Long) {
        if (lookJob?.isActive == true) return
        lookJob = scope.launch(Dispatchers.Default) {
            val prefs = CompanionPrefs(this@ScreenLookService)
            val bitmap = captureBitmap() ?: return@launch
            val pixels = IntArray(bitmap.width * bitmap.height)
            bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
            val sig = FrameDelta.signature(pixels, bitmap.width, bitmap.height)
            val resized = bitmap.width != frameW || bitmap.height != frameH
            val moved = resized || FrameDelta.changed(frameSig, sig)
            frameW = bitmap.width
            frameH = bitmap.height
            frameSig = sig
            prefs.saveHighFreq(HighFreq.onLook(session, now))
            if (!moved) {
                bitmap.recycle()
                return@launch
            }
            val dataUrl = encode(bitmap)
            bitmap.recycle()
            // 没配识图模型时不硬调接口：把这一帧的变化交给无障碍读屏那条路。
            val desc = if (app.settings.vision.value.isComplete) {
                when (val r = app.vision.describeShort(dataUrl)) {
                    is LlmResult.Ok -> r.value
                    is LlmResult.Err -> null
                }
            } else null
            if (!desc.isNullOrBlank()) prefs.setLastScreenText(desc)
            prefs.markHighFreqFrame(now)
            _status.value = desc?.let { "看到：$it" }
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
            val dataUrl = com.yishou.app.floating.CoachFloat.withoutPanel { captureDataUrl() }
            if (dataUrl == null) {
                setStatus("没有截到画面，再点一次试试")
                return@launch
            }
            // 看一眼：把屏幕上的题完整转写下来
            val screen = when (val r = app.vision.transcribeQuestion(dataUrl)) {
                is LlmResult.Ok -> r.value
                is LlmResult.Err -> {
                    setStatus("识图失败：${r.error.message}")
                    return@launch
                }
            }
            // 屏幕上的题：整理成一张题卡，交给陪练窗口当下一张（揭晓时点官方答案才计分）
            when (val r = app.cardEngine.import(screen)) {
                is LlmResult.Err -> setStatus("整理题目失败：${r.error.message}")
                is LlmResult.Ok -> {
                    app.stats.event(DayStats.LOOK)
                    app.incomingCard.value = r.value
                    lastLookMove = r.value.card.question
                    setStatus(null)
                    Notifications.showReminder(
                        this@ScreenLookService, ID_RESULT,
                        "教练看了一眼，把这道题做成了下一张",
                        r.value.card.question,
                        WindowActivity::class.java,
                    )
                }
            }
        }
    }

    private fun setStatus(text: String?) {
        _status.value = text
        getSystemService(NotificationManager::class.java).notify(ID_FOREGROUND, foregroundNotification(text))
    }

    /** 复制最新一帧成位图（调用方负责 recycle）；还没有帧时返回 null。 */
    private fun captureBitmap(): Bitmap? = synchronized(lock) {
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

    private fun encode(bitmap: Bitmap): String {
        val out = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
        return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** 把最新一帧转成 JPEG data URL；还没有帧时返回 null。 */
    private fun captureDataUrl(): String? {
        val bitmap = captureBitmap() ?: return null
        val url = encode(bitmap)
        bitmap.recycle()
        return url
    }

    private fun stopAll() {
        watchJob?.cancel()
        lookJob?.cancel()
        frameSig = null
        frameW = 0
        frameH = 0
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
