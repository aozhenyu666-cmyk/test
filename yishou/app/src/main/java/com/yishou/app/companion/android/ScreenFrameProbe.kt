package com.yishou.app.companion.android

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import com.yishou.app.companion.core.FrameCadence
import com.yishou.app.companion.core.FrameDecision
import com.yishou.app.log.RunLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 画面关键帧探针：同一次投屏授权下开一路低分辨率镜像，只在「画面真的变了」的时候留帧。
 *
 * 旋转时宽高要对调重建（不然会拉成一条）；系统收回授权时立刻释放并记账。
 * 这里只数帧、不发模型——首版门槛是「能不能拿到有变化的帧」，不是「模型能不能看懂」。
 */
class ScreenFrameProbe(private val context: Context) {

    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var thread: HandlerThread? = null
    private var latest: Image? = null
    private val lock = Any()

    private val cadence = FrameCadence(minIntervalMs = 1_000, windowMs = 60_000, maxPerWindow = 45)

    private val _stats = MutableStateFlow("还没开镜像")
    val stats: StateFlow<String> = _stats.asStateFlow()

    var seen: Int = 0
        private set
    var kept: Int = 0
        private set

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {
            if (displayId == android.view.Display.DEFAULT_DISPLAY) rebuild()
        }
    }
    private var listening = false

    /** 开镜像；成功返回 null，失败给原因。一个投屏授权只开一路 VirtualDisplay。 */
    fun start(projection: MediaProjection): String? {
        if (this.projection != null) return null
        this.projection = projection
        val err = rebuild()
        if (err == null) {
            context.getSystemService(DisplayManager::class.java)
                .registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
            listening = true
        } else {
            this.projection = null
        }
        return err
    }

    /** 画面尺寸变了：拆掉重开，宽高按当前屏幕重新算 */
    private fun rebuild(): String? {
        val p = projection ?: return "没有投屏授权"
        releaseSurface()
        val metrics = context.resources.displayMetrics
        val w = 720
        val h = (metrics.heightPixels.toLong() * w / metrics.widthPixels).toInt().coerceAtLeast(1)
        val t = HandlerThread("yishou-probe-frame").also { it.start() }
        thread = t
        val r = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 3)
        r.setOnImageAvailableListener({ ir ->
            val img = try {
                ir.acquireLatestImage()
            } catch (e: Exception) {
                null
            } ?: return@setOnImageAvailableListener
            onFrame(img)
            synchronized(lock) {
                latest?.close()
                latest = img
            }
        }, Handler(t.looper))
        reader = r
        display = try {
            p.createVirtualDisplay(
                "yishou-probe", w, h, metrics.densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, r.surface, null, null,
            )
        } catch (e: Exception) {
            RunLog.e("陪练探针", "镜像开不起来", e)
            releaseSurface()
            return "镜像开不起来：${e.message ?: e.javaClass.simpleName}"
        }
        _stats.value = "镜像 ${w}×$h"
        return null
    }

    private fun onFrame(img: Image) {
        seen++
        val at = System.currentTimeMillis()
        if (cadence.offer(fingerprint(img), at) == FrameDecision.KEEP) kept++
        _stats.value = "看到 $seen 帧，留下 $kept 帧"
    }

    /** 抽几百个字节算个指纹：画面没变就是同一个数，不用逐像素比 */
    private fun fingerprint(img: Image): Long {
        val buf = img.planes[0].buffer
        var h = 1125899906842597L
        val step = (buf.limit() / 512).coerceAtLeast(1)
        var i = 0
        while (i < buf.limit()) {
            h = 31 * h + buf.get(i).toLong()
            i += step
        }
        return h
    }

    fun stop() {
        if (listening) {
            try {
                context.getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
            } catch (e: Exception) {
                RunLog.e("陪练探针", "摘显示监听出错", e)
            }
            listening = false
        }
        releaseSurface()
        projection = null
        _stats.value = "已停"
    }

    private fun releaseSurface() {
        synchronized(lock) {
            latest?.close()
            latest = null
        }
        display?.release()
        display = null
        reader?.close()
        reader = null
        thread?.quitSafely()
        thread = null
    }
}
