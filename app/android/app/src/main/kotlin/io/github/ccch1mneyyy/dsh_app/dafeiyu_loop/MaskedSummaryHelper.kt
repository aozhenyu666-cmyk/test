package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop

import android.accessibilityservice.AccessibilityService
import android.graphics.Bitmap
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.SystemClock
import android.view.Display
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.LocalOcrResult
import io.github.ccch1mneyyy.dsh_app.phone.a11y.MlKitLocalOcr
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ProtectedPages
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenFormat
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenInput
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenMask
import io.github.ccch1mneyyy.dsh_app.phone.a11y.TreeCapture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Produces a PII-masked plain-text summary of the current screen directly via
 * [PhoneA11yService] and [TreeCapture], falling back to screenshot + [MlKitLocalOcr]
 * when accessible CJK text is sparse (< 15 chars), keeping upstream files untouched.
 */
object MaskedSummaryHelper {
    internal const val OCR_FALLBACK_CJK_THRESHOLD = 15
    private val ocrExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "dafeiyu-ocr-summary") }

    internal fun countCjkChars(text: String): Int =
        text.count { ch -> ch in '\u4e00'..'\u9fff' }

    internal fun needsOcrFallback(accessibleText: String, minCjkChars: Int = OCR_FALLBACK_CJK_THRESHOLD): Boolean =
        countCjkChars(accessibleText) < minCjkChars

    internal fun mergeAccessibleAndOcr(accessibleText: String, ocrLines: List<String>, maxChars: Int = 1500): String? {
        val budget = maxChars.coerceIn(200, 1500)
        val base = accessibleText.trim()
        val cleanedOcr = ocrLines
            .map { ScreenMask.mask(it.trim()) }
            .filter { it.isNotBlank() }
            .distinct()
            .joinToString("\n")
        val merged = when {
            cleanedOcr.isBlank() -> base
            base.isBlank() -> "[OCR]\n$cleanedOcr"
            else -> "$base\n[OCR]\n$cleanedOcr"
        }
        return merged.take(budget).ifBlank { null }
    }

    private fun captureOcrLines(service: AccessibilityService, timeoutMs: Long = 1800L): List<String> {
        if (Build.VERSION.SDK_INT < 30) return emptyList()
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        val latch = CountDownLatch(1)
        var bitmap: Bitmap? = null
        try {
            service.takeScreenshot(Display.DEFAULT_DISPLAY, ocrExecutor, object : AccessibilityService.TakeScreenshotCallback {
                override fun onSuccess(screenshot: AccessibilityService.ScreenshotResult) {
                    try {
                        val buffer = screenshot.hardwareBuffer
                        try {
                            val hw = Bitmap.wrapHardwareBuffer(buffer, screenshot.colorSpace)
                            if (hw != null) {
                                try {
                                    bitmap = hw.copy(Bitmap.Config.ARGB_8888, false)
                                } finally {
                                    hw.recycle()
                                }
                            }
                        } finally {
                            buffer.close()
                        }
                    } catch (_: Throwable) {
                        bitmap = null
                    } finally {
                        latch.countDown()
                    }
                }

                override fun onFailure(errorCode: Int) {
                    latch.countDown()
                }
            })
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) return emptyList()
            val owned = bitmap ?: return emptyList()
            val ocr = MlKitLocalOcr.get(service.applicationContext)
            val res = ocr.recognize(owned, deadline) { SystemClock.uptimeMillis() >= deadline }
            return when (res) {
                is LocalOcrResult.Recognized -> res.lines.map { it.text }
                else -> emptyList()
            }
        } catch (_: Throwable) {
            return emptyList()
        } finally {
            bitmap?.recycle()
        }
    }

    fun maskedSummary(maxChars: Int = 1500): String? {
        val service = PhoneA11yService.instance ?: return null
        if (!PhoneA11yService.isHealthy()) return null
        val dm = service.getSystemService(DisplayManager::class.java)
        val p = Point()
        @Suppress("DEPRECATION")
        dm?.getDisplay(Display.DEFAULT_DISPLAY)?.getRealSize(p)
        val screen = Box(0, 0, p.x.coerceAtLeast(1), p.y.coerceAtLeast(1))
        val cap = TreeCapture.capture(service, screen, service.packageName, 1200L)
        try {
            if (cap.hiddenWindowTitle != null || cap.windows.isEmpty() || cap.ownAppInFront) return null
            val pkg = cap.packageName
            val titles = cap.windows.mapNotNull { it.title } +
                ProtectedPages.titleTexts(cap.windows.firstOrNull()?.root, cap.screen.height)
            if (ProtectedPages.isProtected(pkg, titles)) return null
            val budget = maxChars.coerceIn(200, 1500)
            val appLabel = pkg?.let {
                runCatching {
                    val pm = service.packageManager
                    pm.getApplicationLabel(pm.getApplicationInfo(it, 0)).toString()
                }.getOrNull()
            }
            val formatted = ScreenFormat.format(
                ScreenInput(
                    screenId = "summary",
                    appLabel = appLabel,
                    packageName = pkg,
                    screenWidth = cap.screen.width,
                    screenHeight = cap.screen.height,
                    keyboardShown = cap.keyboardShown,
                    windows = cap.windows,
                    detail = false,
                    charBudget = budget,
                ),
            )
            val baseText = formatted.text
            if (needsOcrFallback(baseText)) {
                val ocrLines = captureOcrLines(service)
                if (ocrLines.isNotEmpty()) {
                    return mergeAccessibleAndOcr(baseText, ocrLines, budget)
                }
            }
            return baseText.take(budget).ifBlank { null }
        } finally {
            cap.recycle()
        }
    }
}
