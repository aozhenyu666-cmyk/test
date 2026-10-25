package cn.companion.pet.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * 全屏守护层：只在本应用有悬浮窗权限时出现，不覆盖系统安全界面。
 * 提供明确原因、返回目标、暂停和紧急结束；不提供“忽略”或静默关闭。
 */
@SuppressLint("ViewConstructor", "SetTextI18n")
class FocusOverlay(
    private val context: Context,
    private val onReturn: () -> Unit,
    private val onPause: () -> Unit,
    private val onAbort: () -> Unit,
) {
    private val wm = context.getSystemService(WindowManager::class.java)
    private var view: View? = null
    private val root = LinearLayout(context)
    private val title = TextView(context)
    private val detail = TextView(context)
    private var shown = false

    val isShown: Boolean get() = shown

    init {
        root.orientation = LinearLayout.VERTICAL
        root.gravity = Gravity.CENTER
        root.setBackgroundColor(Color.argb(242, 18, 17, 14))
        root.setPadding(dp(28), dp(32), dp(28), dp(32))
        // 覆盖层自己吞掉返回键，避免用户误以为已经退出。
        root.isFocusableInTouchMode = true
        root.setOnKeyListener { _, keyCode, event ->
            event.action == android.view.KeyEvent.ACTION_DOWN && keyCode == android.view.KeyEvent.KEYCODE_BACK
        }

        title.setTextColor(Color.WHITE)
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        title.setTypeface(Typeface.create(Typeface.SERIF, Typeface.BOLD))
        root.addView(title)

        detail.setTextColor(Color.parseColor("#C8C1B4"))
        detail.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        detail.setLineSpacing(dp(4).toFloat(), 1.2f)
        root.addView(detail, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) })

        root.addView(button("返回目标应用", primary = true) { onReturn() }, lp(top = 26))
        root.addView(button("暂停 5 分钟", primary = false) { onPause() }, lp(top = 12))
        root.addView(button("结束守护（紧急退出）", primary = false, danger = true) { onAbort() }, lp(top = 12))
    }

    fun show(targetLabel: String, reason: String) {
        title.text = "回到「$targetLabel」"
        detail.text = reason
        if (shown) return
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        )
        params.gravity = Gravity.CENTER
        runCatching { wm.addView(root, params) }.onSuccess { shown = true }
    }

    fun hide() {
        if (!shown) return
        runCatching { wm.removeView(root) }
        shown = false
    }

    fun destroy() = hide()

    private fun button(label: String, primary: Boolean, danger: Boolean = false, onClick: () -> Unit): Button {
        val b = Button(context)
        b.text = label
        b.isAllCaps = false
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        b.setTextColor(if (primary) Color.parseColor("#1E1C18") else if (danger) Color.parseColor("#FF8A7A") else Color.WHITE)
        b.background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(16).toFloat()
            setColor(if (primary) Color.parseColor("#E8FF6A") else Color.parseColor("#2A2721"))
        }
        b.setOnClickListener { onClick() }
        return b
    }

    private fun lp(top: Int) = LinearLayout.LayoutParams(
        LinearLayout.LayoutParams.MATCH_PARENT, dp(52),
    ).apply { topMargin = dp(top) }

    private fun dp(v: Int) = (v * context.resources.displayMetrics.density).toInt()
}
