package cn.companion.pet.overlay

import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.Rect
import android.view.ViewTreeObserver
import cn.companion.pet.core.focus.BlockObservation
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import cn.companion.pet.core.focus.OverlayResult

/**
 * STRICT 全屏拦截层（移植自 deepseek/c3-focus-guard，按审计 F-05 修正）：
 * - TYPE_ACCESSIBILITY_OVERLAY 只能由无障碍服务自己的窗口加；原实现用桌宠服务的 Context 加，会抛异常且被吞掉，拦截层其实不出现。
 *   现在优先用无障碍服务的 WindowManager，没有时退到悬浮窗（TYPE_APPLICATION_OVERLAY），都不行就如实返回失败原因。
 * - 始终保留“返回目标 / 暂停 5 分钟 / 结束守护（紧急退出）”；不挡系统电源键、通知栏等安全界面。
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class FocusOverlay(
    private val context: Context,
    private val accessibility: () -> AccessibilityService?,
    private val onReturn: () -> Unit,
    private val onPause: () -> Unit,
    private val onAbort: () -> Unit,
) {
    private var root: LinearLayout? = null
    private var title: TextView? = null
    private var detail: TextView? = null
    private var attachedTo: WindowManager? = null

    private var drawn = false
    val isShown: Boolean get() = root?.isAttachedToWindow == true

    fun observe(): BlockObservation {
        val view = root ?: return BlockObservation(false, false, false)
        val bounds = attachedTo?.currentWindowMetrics?.bounds ?: return BlockObservation(view.isAttachedToWindow, drawn, false)
        val visible = Rect()
        val fills = view.getGlobalVisibleRect(visible) && visible.width() >= bounds.width() * 0.9 && visible.height() >= bounds.height() * 0.9
        return BlockObservation(view.isAttachedToWindow && view.isShown, drawn, fills)
    }

    fun show(targetLabel: String, reason: String): OverlayResult {
        if (root?.isAttachedToWindow == true) {
            title?.text = "回到「$targetLabel」"
            detail?.text = reason
            return OverlayResult(true)
        }
        attachedTo = null; root = null; drawn = false
        val a11y = accessibility()
        val (host, type) = when {
            a11y != null -> a11y to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            Settings.canDrawOverlays(context) -> context to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> return OverlayResult(false, "没有无障碍服务连接，也没有悬浮窗权限")
        }
        val wm = host.getSystemService(WindowManager::class.java) ?: return OverlayResult(false, "拿不到 WindowManager")
        val view = build(host, targetLabel, reason)
        view.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() { if (root === view) drawn = true }
        })
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            type,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.CENTER }
        return try {
            wm.addView(view, params)
            attachedTo = wm
            root = view
            OverlayResult(true)
        } catch (e: Exception) {
            OverlayResult(false, "拦截层显示失败：" + e.javaClass.simpleName)
        }
    }

    fun hide(): Boolean {
        val wm = attachedTo ?: return true
        val view = root
        val removed = view == null || runCatching { wm.removeViewImmediate(view) }.isSuccess || !view.isAttachedToWindow
        if (removed) { attachedTo = null; root = null; drawn = false }
        return removed
    }

    private fun build(host: Context, targetLabel: String, reason: String): LinearLayout {
        val box = LinearLayout(host)
        box.orientation = LinearLayout.VERTICAL
        box.gravity = Gravity.CENTER
        box.setBackgroundColor(Color.argb(242, 18, 17, 14))
        box.setPadding(dp(28), dp(32), dp(28), dp(32))
        // 吞掉返回键，避免以为已经退出；退出走下面明确的按钮。
        box.isFocusableInTouchMode = true
        box.setOnKeyListener { _, keyCode, event -> event.action == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_BACK }
        val t = TextView(host).apply {
            setTextColor(Color.WHITE); setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
            typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); text = "回到「$targetLabel」"
        }
        val d = TextView(host).apply {
            setTextColor(Color.parseColor("#C8C1B4")); setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            setLineSpacing(dp(4).toFloat(), 1.2f); text = reason
        }
        box.addView(t)
        box.addView(d, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(14) })
        box.addView(button(host, "返回目标应用", primary = true) { onReturn() }, lp(26))
        box.addView(button(host, "暂停 5 分钟", primary = false) { onPause() }, lp(12))
        box.addView(button(host, "结束守护（紧急退出）", primary = false, danger = true) { onAbort() }, lp(12))
        title = t; detail = d
        return box
    }

    private fun button(host: Context, label: String, primary: Boolean, danger: Boolean = false, onClick: () -> Unit) = Button(host).apply {
        text = label
        isAllCaps = false
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        setTextColor(if (primary) Color.parseColor("#1E1C18") else if (danger) Color.parseColor("#FF8A7A") else Color.WHITE)
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (primary) Color.parseColor("#E8FF6A") else Color.parseColor("#2A2721"))
        }
        setOnClickListener { onClick() }
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52)).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
