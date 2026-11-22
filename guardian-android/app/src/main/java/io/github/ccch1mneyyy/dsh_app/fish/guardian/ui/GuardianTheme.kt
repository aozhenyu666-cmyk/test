package io.github.ccch1mneyyy.dsh_app.fish.guardian.ui

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.View
import android.view.Window
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Mist Glass theme primitives matching DSH Go's Flutter palette (`#EBF1FE` -> `#F6F7FC` light
 * and `#1D222C` -> `#1B1C23` dark, `#2D5CF6` cobalt accent) without external UI bloat.
 */
object GuardianTheme {

    fun isDark(context: Context): Boolean =
        (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun textPrimary(dark: Boolean): Int =
        if (dark) Color.parseColor("#E6E9F0") else Color.parseColor("#1E232E")

    fun textSecondary(dark: Boolean): Int =
        if (dark) Color.parseColor("#A3AAB8") else Color.parseColor("#5B6475")

    fun accent(): Int = Color.parseColor("#2D5CF6")
    fun okColor(): Int = Color.parseColor("#34C759")
    fun warnColor(): Int = Color.parseColor("#FF9500")
    fun errColor(): Int = Color.parseColor("#FF3B30")

    fun screenBackground(dark: Boolean): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        if (dark) {
            intArrayOf(Color.parseColor("#1D222C"), Color.parseColor("#1B1C23"))
        } else {
            intArrayOf(Color.parseColor("#EBF1FE"), Color.parseColor("#F6F7FC"))
        },
    )

    fun glassCard(context: Context, dark: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, 20).toFloat()
        setColor(if (dark) Color.argb(235, 34, 40, 52) else Color.argb(228, 255, 255, 255))
        setStroke(
            dp(context, 1),
            if (dark) Color.argb(36, 255, 255, 255) else Color.argb(140, 210, 224, 248),
        )
    }

    fun badgeDrawable(context: Context, color: Int, dark: Boolean): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(context, 999).toFloat()
        val alphaBg = if (dark) 56 else 28
        setColor(Color.argb(alphaBg, Color.red(color), Color.green(color), Color.blue(color)))
        setStroke(dp(context, 1), Color.argb(120, Color.red(color), Color.green(color), Color.blue(color)))
    }

    fun buttonBackground(context: Context, fillColor: Int, pressedFill: Int, strokeColor: Int = Color.TRANSPARENT): StateListDrawable {
        val radius = dp(context, 14).toFloat()
        val normal = GradientDrawable().apply {
            cornerRadius = radius
            setColor(fillColor)
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(context, 1), strokeColor)
        }
        val pressed = GradientDrawable().apply {
            cornerRadius = radius
            setColor(pressedFill)
            if (strokeColor != Color.TRANSPARENT) setStroke(dp(context, 1), strokeColor)
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), pressed)
            addState(intArrayOf(), normal)
        }
    }

    fun applyEdgeBars(window: Window, dark: Boolean) {
        @Suppress("DEPRECATION")
        window.statusBarColor = if (dark) Color.parseColor("#1D222C") else Color.parseColor("#EBF1FE")
        @Suppress("DEPRECATION")
        window.navigationBarColor = if (dark) Color.parseColor("#1B1C23") else Color.parseColor("#F6F7FC")
        if (!dark) {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        }
    }

    fun makeCard(context: Context, dark: Boolean): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = glassCard(context, dark)
        val p = dp(context, 16)
        setPadding(p, p, p, p)
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply {
            bottomMargin = dp(context, 12)
        }
    }

    fun makeSectionTitle(context: Context, dark: Boolean, title: String): TextView = TextView(context).apply {
        text = title
        textSize = 15f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(textPrimary(dark))
        setPadding(0, 0, 0, dp(context, 8))
    }

    fun makeBodyText(context: Context, dark: Boolean, content: String = ""): TextView = TextView(context).apply {
        text = content
        textSize = 13.5f
        setTextColor(textSecondary(dark))
        setLineSpacing(dp(context, 3).toFloat(), 1.0f)
    }

    fun makePrimaryButton(context: Context, label: String, color: Int = accent()): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 14f
        setTypeface(typeface, Typeface.BOLD)
        setTextColor(Color.WHITE)
        stateListAnimator = null
        val darker = Color.rgb(
            (Color.red(color) * 0.85f).toInt(),
            (Color.green(color) * 0.85f).toInt(),
            (Color.blue(color) * 0.85f).toInt(),
        )
        background = buttonBackground(context, color, darker)
        val pv = dp(context, 11)
        val ph = dp(context, 14)
        setPadding(ph, pv, ph, pv)
    }

    fun makeSecondaryButton(context: Context, dark: Boolean, label: String): Button = Button(context).apply {
        text = label
        isAllCaps = false
        textSize = 13.5f
        setTextColor(textPrimary(dark))
        stateListAnimator = null
        val fill = if (dark) Color.argb(46, 255, 255, 255) else Color.argb(220, 238, 243, 255)
        val pressed = if (dark) Color.argb(72, 255, 255, 255) else Color.argb(255, 224, 232, 252)
        val stroke = if (dark) Color.argb(45, 255, 255, 255) else Color.argb(120, 195, 210, 240)
        background = buttonBackground(context, fill, pressed, stroke)
        val pv = dp(context, 10)
        val ph = dp(context, 12)
        setPadding(ph, pv, ph, pv)
    }

    fun stateColor(stateName: String): Int = when (stateName) {
        "HEALTHY" -> okColor()
        "STARTING_GRACE", "SUSPECT", "NEEDS_PAIRING", "UNKNOWN" -> warnColor()
        else -> errColor()
    }

    fun stateLabel(stateName: String): String = when (stateName) {
        "HEALTHY" -> "双引擎健康运行"
        "STARTING_GRACE" -> "引擎冷启动中"
        "SUSPECT" -> "状态复核中"
        "NEEDS_PAIRING" -> "待互信配对"
        "NOT_INSTALLED" -> "未安装大肥鱼"
        "A11Y_DISABLED" -> "无障碍已被关闭"
        "ENGINE_DOWN" -> "引擎已关闭或退出"
        "UNRESPONSIVE" -> "引擎/无障碍假死失联"
        else -> "等待探测"
    }
}
