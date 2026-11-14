package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.pet.OverlayPalette
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService
import kotlin.math.roundToInt

/**
 * 3 分钟应急通行的纯 Kotlin 倒计时格式化规则（供 JVM 单测直接覆盖）。
 */
object EmergencyCountdown {
    fun isActive(untilAtMs: Long, nowMs: Long = System.currentTimeMillis()): Boolean =
        untilAtMs > nowMs

    fun formatRemaining(
        untilAtMs: Long,
        reason: String? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): String? {
        val leftMs = untilAtMs - nowMs
        if (leftMs <= 0L) return null
        val totalSec = ((leftMs + 999L) / 1000L).coerceAtLeast(1L)
        val min = totalSec / 60L
        val sec = totalSec % 60L
        val clock = "%02d:%02d".format(min, sec)
        val cleanReason = reason?.trim()?.take(12).orEmpty()
        return if (cleanReason.isNotEmpty()) {
            "急事($cleanReason) $clock"
        } else {
            "急事放行 $clock"
        }
    }
}

/**
 * 顶部悬浮倒计时胶囊（3 分钟应急通行激活期间常驻顶部，不抢输入焦点；到点自动撤除并回调回弹）。
 */
object EmergencyCapsule {
    private val main = Handler(Looper.getMainLooper())
    private var capsuleWm: WindowManager? = null
    private var capsuleRoot: View? = null
    private var capsuleLabel: TextView? = null
    private var ticker: Runnable? = null
    private var currentUntil: Long = 0L

    fun show(
        context: Context,
        untilAtMs: Long,
        reason: String? = null,
        onExpired: (() -> Unit)? = null,
    ) {
        if (!EmergencyCountdown.isActive(untilAtMs)) {
            hide()
            return
        }
        main.post {
            if (!EmergencyCountdown.isActive(untilAtMs)) {
                hideInternal()
                return@post
            }
            if (capsuleRoot != null && currentUntil == untilAtMs) {
                capsuleLabel?.text = EmergencyCountdown.formatRemaining(untilAtMs, reason) ?: ""
                return@post
            }
            hideInternal()
            val hosted = host(context) ?: return@post
            val (ctx, type) = hosted
            val wm = ctx.getSystemService(WindowManager::class.java) ?: return@post
            val density = ctx.resources.displayMetrics.density
            fun dp(v: Int) = (v * density).roundToInt()
            val palette = OverlayPalette.of(ctx)
            val glassFill = (palette.fill and 0x00FFFFFF) or 0xEC000000.toInt()
            val dot = View(ctx).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(palette.danger)
                }
            }
            val labelView = TextView(ctx).apply {
                text = EmergencyCountdown.formatRemaining(untilAtMs, reason) ?: ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(palette.ink)
                typeface = Typeface.DEFAULT_BOLD
                fontFeatureSettings = "tnum"
            }
            val pill = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(6), dp(14), dp(6))
                background = GradientDrawable().apply {
                    cornerRadius = dp(999).toFloat()
                    setColor(glassFill)
                    setStroke(dp(1).coerceAtLeast(1), palette.stroke)
                }
                elevation = dp(6).toFloat()
                addView(dot, LinearLayout.LayoutParams(dp(7), dp(7)).apply {
                    marginEnd = dp(8)
                })
                addView(labelView)
            }
            val lp = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = dp(38)
            }
            val added = runCatching { wm.addView(pill, lp) }.isSuccess
            if (!added) return@post
            capsuleWm = wm
            capsuleRoot = pill
            capsuleLabel = labelView
            currentUntil = untilAtMs

            val step = object : Runnable {
                override fun run() {
                    val v = capsuleLabel ?: return
                    val now = System.currentTimeMillis()
                    val label = EmergencyCountdown.formatRemaining(currentUntil, reason, now)
                    if (label == null) {
                        hideInternal()
                        onExpired?.invoke()
                        return
                    }
                    v.text = label
                    main.postDelayed(this, 1_000L)
                }
            }
            ticker = step
            main.postDelayed(step, 1_000L)
        }
    }

    fun hide() {
        main.post { hideInternal() }
    }

    private fun hideInternal() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
        currentUntil = 0L
        val v: View? = capsuleRoot
        val wm = capsuleWm
        capsuleRoot = null
        capsuleLabel = null
        capsuleWm = null
        if (v != null && wm != null) {
            runCatching { wm.removeViewImmediate(v) }
        }
    }

    private fun host(context: Context): Pair<Context, Int>? {
        val service = PhoneA11yService.instance
        if (service != null) {
            runCatching {
                return service.overlayWindowContext() to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            }
        }
        if (!Settings.canDrawOverlays(context)) return null
        val ctx = if (Build.VERSION.SDK_INT >= 30) {
            context.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            context
        }
        return ctx to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }
}
