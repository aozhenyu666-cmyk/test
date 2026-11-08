package io.github.ccch1mneyyy.dsh_app.loop.focus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.MainActivity

/**
 * 离开任务应用后立刻盖上的全屏页。只有三个出口：
 * 回到任务 / 休息 5 分钟 / 结束监督。没有答题，也没有「稍后再问」。
 */
class FocusPageActivity : Activity() {
    private var pkg = ""
    private var label = ""
    private var secondsOff = 0L
    private lateinit var p: FocusPalette

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = FocusTheme.of(this)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = p.bgTop
        window.navigationBarColor = p.bgBottom
        applySystemBars()
        read(intent)
        render()
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        setIntent(intent)
        read(intent)
        render()
    }

    override fun onDestroy() {
        FocusRuntime.onPageGone()
        super.onDestroy()
    }

    private fun applySystemBars() {
        @Suppress("DEPRECATION")
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (!p.night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26 && !p.night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    private fun read(intent: Intent?) {
        pkg = intent?.getStringExtra(EXTRA_PACKAGE).orEmpty()
        label = intent?.getStringExtra(EXTRA_LABEL).orEmpty().ifEmpty { FocusRuntime.labelOf(pkg) }
        secondsOff = intent?.getLongExtra(EXTRA_SECONDS, 0L) ?: 0L
    }

    private fun render() {
        val state = FocusRuntime.state()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(p.bgTop, p.bgBottom),
            )
            setPadding(dp(24), dp(40), dp(24), dp(28))
        }

        root.addView(
            TextView(this).apply {
                text = "!"
                textSize = 26f
                setTextColor(p.onAccent)
                gravity = Gravity.CENTER
                background = circle(p.accent)
                layoutParams = LinearLayout.LayoutParams(dp(56), dp(56))
            },
        )

        root.addView(
            TextView(this).apply {
                text = "你离开了正在做的事"
                textSize = 15f
                setTextColor(p.sub)
                gravity = Gravity.CENTER
                setPadding(0, dp(20), 0, dp(8))
            },
        )
        root.addView(
            TextView(this).apply {
                text = "回到「" + state.task.ifBlank { "手头这件事" } + "」"
                textSize = 27f
                setTextColor(p.text)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.15f)
            },
        )
        root.addView(
            TextView(this).apply {
                text = "刚才在「" + label + "」停留了 " + secondsOff.coerceAtLeast(1) + " 秒"
                textSize = 14f
                setTextColor(p.faint)
                gravity = Gravity.CENTER
                setPadding(0, dp(14), 0, 0)
            },
        )

        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.card, dp(18), p.stroke)
            setPadding(dp(18), dp(16), dp(18), dp(16))
            elevation = dp(2).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(28) }
        }
        card.addView(
            TextView(this).apply {
                text = "不用从头想。先回到刚才那一步，再决定要不要休息。"
                textSize = 14f
                setTextColor(p.sub)
                setLineSpacing(0f, 1.35f)
            },
        )
        root.addView(card)

        root.addView(button("回到任务", primary = true) {
            FocusRuntime.onReturned()
            val target = FocusRuntime.taskPackage()
            val launch = if (target.isNotBlank()) packageManager.getLaunchIntentForPackage(target) else null
            if (launch != null) {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        })
        root.addView(button("休息 5 分钟") {
            FocusRuntime.pause(5)
            finish()
        })
        root.addView(button("结束监督", secondary = true) {
            FocusRuntime.stop("user")
            finish()
        })

        root.addView(
            TextView(this).apply {
                text = "监督在后台运行；即使 DSH 引擎被系统清掉，这一页仍然会拦。"
                textSize = 12f
                setTextColor(p.faint)
                gravity = Gravity.CENTER
                setPadding(0, dp(24), 0, 0)
            },
        )

        setContentView(root)
    }

    private fun button(label: String, primary: Boolean = false, secondary: Boolean = false, onTap: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 17f
            isAllCaps = false
            setTextColor(if (primary) p.onAccent else p.text)
            background = rounded(
                when {
                    primary -> p.accent
                    secondary -> p.card
                    else -> p.accentSoft
                },
                dp(16),
                if (secondary) p.stroke else Color.TRANSPARENT,
            )
            stateListAnimator = null
            backgroundTintList = null
            elevation = dp(if (primary) 3 else 1).toFloat()
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(54),
            ).apply { topMargin = dp(12) }
            layoutParams = lp
            setOnClickListener { onTap() }
        }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            setColor(fill)
            cornerRadius = radius.toFloat()
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun circle(fill: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(fill)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    companion object {
        private const val EXTRA_PACKAGE = "focus.package"
        private const val EXTRA_LABEL = "focus.label"
        private const val EXTRA_SECONDS = "focus.seconds"

        fun start(context: Context, pkg: String, label: String, seconds: Long): Boolean = runCatching {
            context.startActivity(
                Intent(context, FocusPageActivity::class.java)
                    .putExtra(EXTRA_PACKAGE, pkg)
                    .putExtra(EXTRA_LABEL, label)
                    .putExtra(EXTRA_SECONDS, seconds)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP,
                    ),
            )
            true
        }.getOrDefault(false)
    }
}
