package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop

import android.graphics.Point
import android.hardware.display.DisplayManager
import android.view.Display
import io.github.ccch1mneyyy.dsh_app.phone.a11y.Box
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ProtectedPages
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenFormat
import io.github.ccch1mneyyy.dsh_app.phone.a11y.ScreenInput
import io.github.ccch1mneyyy.dsh_app.phone.a11y.TreeCapture

/**
 * Produces a PII-masked plain-text summary of the current screen directly via
 * [PhoneA11yService] and [TreeCapture], keeping upstream PhoneContracts/MainScreenDriver clean.
 */
object MaskedSummaryHelper {
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
            return formatted.text.take(budget).ifBlank { null }
        } finally {
            cap.recycle()
        }
    }
}
