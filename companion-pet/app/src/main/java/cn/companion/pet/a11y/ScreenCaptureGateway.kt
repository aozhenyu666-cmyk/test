package cn.companion.pet.a11y

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.os.Build
import android.view.Display
import cn.companion.pet.core.vision.CapturedStatus
import cn.companion.pet.core.vision.ScreenCaptureAttempt
import cn.companion.pet.core.vision.ScreenCapturePolicy
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

/**
 * 原生无障碍截图。只在调用方的工作线程执行；成功返回 JPEG，不写磁盘、不打印 Base64。
 * API 30+ 用 takeScreenshot；API 34+ 优先 takeScreenshotOfWindow，避免把桌宠悬浮层拍进去。
 */
object ScreenCaptureGateway {
    fun captureBlocking(
        service: AccessibilityService?,
        executor: Executor,
        timeoutMillis: Long = 5_000,
    ): ScreenCaptureAttempt {
        if (Build.VERSION.SDK_INT < 30) return ScreenCaptureAttempt(CapturedStatus.UNSUPPORTED, error = "系统版本低于 Android 11")
        val svc = service ?: return ScreenCaptureAttempt(CapturedStatus.NOT_CONNECTED, error = "无障碍服务未连接")
        val root = runCatching { svc.rootInActiveWindow }.getOrNull()
            ?: return ScreenCaptureAttempt(CapturedStatus.NO_ACTIVE_WINDOW, error = "没有活动窗口")
        val windowPackage = root.packageName?.toString().orEmpty()
        // 活动窗口是桌宠自己（设置页/能力面板）时不截：那张图不是用户在看的 App（审计 P1-3）。
        if (windowPackage == svc.packageName) {
            return ScreenCaptureAttempt(CapturedStatus.NO_ACTIVE_WINDOW, error = "当前是桌宠自己的界面；请在要看的 App 里点桌宠 → 看一眼",
                packageName = windowPackage)
        }
        val latch = CountDownLatch(1)
        val gaveUp = java.util.concurrent.atomic.AtomicBoolean(false)
        var success: AccessibilityService.ScreenshotResult? = null
        var failure = 0
        val callback = object : AccessibilityService.TakeScreenshotCallback {
            override fun onSuccess(result: AccessibilityService.ScreenshotResult) {
                // 超时之后才到的截图没人接收，要在这里释放，否则 HardwareBuffer 泄漏。
                if (gaveUp.get()) { runCatching { result.hardwareBuffer.close() }; return }
                success = result
                latch.countDown()
            }

            override fun onFailure(errorCode: Int) {
                failure = errorCode
                latch.countDown()
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 34 && root.windowId != -1) {
                svc.takeScreenshotOfWindow(root.windowId, executor, callback)
            } else {
                svc.takeScreenshot(Display.DEFAULT_DISPLAY, executor, callback)
            }
        } catch (t: Throwable) {
            return ScreenCaptureAttempt(CapturedStatus.ERROR, error = t.javaClass.simpleName)
        }
        if (!latch.await(timeoutMillis, TimeUnit.MILLISECONDS)) {
            gaveUp.set(true)
            // 标记和回调之间的竞态：回调若恰好在这之前写入了结果，也一并释放。
            success?.let { runCatching { it.hardwareBuffer.close() } }
            return ScreenCaptureAttempt(CapturedStatus.TIMEOUT, error = "截图超时")
        }
        val buffer = success?.hardwareBuffer ?: return ScreenCaptureAttempt(mapFailure(failure), error = "截图失败码 " + failure)
        return try {
            val bitmap = Bitmap.wrapHardwareBuffer(buffer, success.colorSpace)
                ?: return ScreenCaptureAttempt(CapturedStatus.ERROR, error = "无法包装截图缓冲")
            val scaled = scale(bitmap)
            val width = scaled.width
            val height = scaled.height
            val out = ByteArrayOutputStream()
            scaled.compress(Bitmap.CompressFormat.JPEG, 82, out)
            val jpeg = ScreenCapturePolicy.fitWithin(out.toByteArray())
                ?: return ScreenCaptureAttempt(CapturedStatus.ERROR, error = "截图过大")
            if (scaled !== bitmap) scaled.recycle()
            bitmap.recycle()
            ScreenCaptureAttempt(CapturedStatus.OK, jpeg, width, height, packageName = windowPackage)
        } catch (t: Throwable) {
            ScreenCaptureAttempt(CapturedStatus.ERROR, error = t.javaClass.simpleName)
        } finally {
            runCatching { buffer.close() }
        }
    }

    private fun scale(source: Bitmap): Bitmap {
        val max = maxOf(source.width, source.height)
        if (max <= ScreenCapturePolicy.MAX_DIMENSION) return source
        val ratio = ScreenCapturePolicy.MAX_DIMENSION.toFloat() / max
        return Bitmap.createScaledBitmap(source, (source.width * ratio).toInt().coerceAtLeast(1),
            (source.height * ratio).toInt().coerceAtLeast(1), true)
    }

    private fun mapFailure(code: Int): CapturedStatus = when {
        Build.VERSION.SDK_INT >= 30 && code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT -> CapturedStatus.COOLDOWN
        Build.VERSION.SDK_INT >= 30 && code == AccessibilityService.ERROR_TAKE_SCREENSHOT_NO_ACCESSIBILITY_ACCESS -> CapturedStatus.NO_PERMISSION
        Build.VERSION.SDK_INT >= 30 && code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INVALID_WINDOW -> CapturedStatus.NO_ACTIVE_WINDOW
        Build.VERSION.SDK_INT >= 30 && code == AccessibilityService.ERROR_TAKE_SCREENSHOT_SECURE_WINDOW -> CapturedStatus.SECURE_WINDOW
        else -> CapturedStatus.ERROR
    }
}
