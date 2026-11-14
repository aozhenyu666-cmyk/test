package io.github.ccch1mneyyy.dsh_app.loop.focus

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.MainActivity

/**
 * 离开任务应用后立刻盖上的全屏页（DSH Mist Glass 风格）。
 * 回到任务一键直达；临时休息与提前结束需长按确认，防止冲动放弃。
 */
class FocusPageActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var pkg = ""
    private var label = ""
    private var secondsOff = 0L
    private lateinit var p: FocusPalette
    private var hintView: TextView? = null

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
        main.removeCallbacksAndMessages(null)
        FocusRuntime.onPageGone()
        super.onDestroy()
    }

    @Deprecated("Activity back handling; targetSdk 28")
    override fun onBackPressed() {
        hintView?.text = "专注监督中：请点击「立即回到任务」或长按下方按钮后再退出。"
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
        main.removeCallbacksAndMessages(null)
        val state = FocusRuntime.state()
        val scroll = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(p.bgTop, p.bgBottom),
            )
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(22), dp(36), dp(22), dp(28))
        }

        val glass = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            background = rounded(p.card, dp(24), p.stroke)
            setPadding(dp(20), dp(24), dp(20), dp(22))
            elevation = dp(4).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
        }

        glass.addView(
            TextView(this).apply {
                text = "DSH Go · 专注监督拉回"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.accent)
                setPadding(dp(12), dp(5), dp(12), dp(5))
                background = rounded(p.accentSoft, 999, Color.TRANSPARENT)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = dp(18) }
            },
        )

        glass.addView(
            TextView(this).apply {
                text = "你离开了正在做的事"
                textSize = 14f
                setTextColor(p.sub)
                gravity = Gravity.CENTER
            },
        )
        glass.addView(
            TextView(this).apply {
                text = "回到「" + state.task.ifBlank { "手头这件事" } + "」"
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(p.text)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.2f)
                setPadding(0, dp(8), 0, dp(14))
            },
        )

        val statsCard = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            background = rounded(p.input, dp(16), p.stroke)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(16) }
        }
        statsCard.addView(statColumn("刚才停留在", label.ifBlank { "其他应用" }))
        statsCard.addView(statColumn("离开时长", secondsOff.coerceAtLeast(1).toString() + " 秒"))
        statsCard.addView(statColumn("累计偏离", state.deviations.coerceAtLeast(1).toString() + " 次"))
        glass.addView(statsCard)

        glass.addView(
            TextView(this).apply {
                text = "不用重新进入状态。一键直接切回当前任务；休息或停止需持续长按以防冲动退出。"
                textSize = 13.5f
                setTextColor(p.sub)
                gravity = Gravity.CENTER
                setLineSpacing(0f, 1.35f)
                setPadding(dp(4), 0, dp(4), dp(14))
            },
        )

        val target = FocusRuntime.taskPackage()
        val targetLabel = if (target.isNotBlank()) FocusRuntime.labelOf(target) else "DSH Go"
        glass.addView(button("立即回到「$targetLabel」", primary = true) {
            FocusRuntime.onReturned()
            val launch = if (target.isNotBlank()) packageManager.getLaunchIntentForPackage(target) else null
            if (launch != null) {
                startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } else {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            finish()
        })

        glass.addView(holdButton("长按 3 秒 · 临时休息 5 分钟", holdMs = 3_000L, secondary = false) {
            FocusRuntime.pause(5)
            finish()
        })
        glass.addView(holdButton("长按 5 秒 · 提前结束监督", holdMs = 5_000L, secondary = true) {
            FocusRuntime.stop("user")
            finish()
        })

        hintView = TextView(this).apply {
            text = "原生护栏独立运行中；即使 DSH 引擎被清理，此页面仍会继续守护。"
            textSize = 12f
            setTextColor(p.faint)
            gravity = Gravity.CENTER
            setPadding(0, dp(16), 0, 0)
        }
        glass.addView(hintView)

        root.addView(glass)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun statColumn(label: String, value: String): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        addView(TextView(context).apply {
            text = label
            textSize = 11.5f
            setTextColor(p.faint)
        })
        addView(TextView(context).apply {
            text = value
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(p.text)
            setPadding(0, dp(3), 0, 0)
            maxLines = 1
        })
    }

    private fun holdButton(label: String, holdMs: Long, secondary: Boolean, onConfirmed: () -> Unit): Button =
        Button(this).apply {
            text = label
            textSize = 15f
            isAllCaps = false
            setTextColor(p.text)
            background = rounded(
                if (secondary) p.card else p.accentSoft,
                dp(16),
                if (secondary) p.stroke else Color.TRANSPARENT,
            )
            stateListAnimator = null
            backgroundTintList = null
            elevation = dp(1).toFloat()
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(50),
            ).apply { topMargin = dp(10) }

            var downAt = 0L
            var ticker: Runnable? = null
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downAt = SystemClock.elapsedRealtime()
                        hintView?.text = "正在确认：请持续按住不要松手…"
                        val r = object : Runnable {
                            override fun run() {
                                if (downAt == 0L) return
                                val elapsed = SystemClock.elapsedRealtime() - downAt
                                val leftMs = (holdMs - elapsed).coerceAtLeast(0L)
                                if (leftMs <= 0L) {
                                    downAt = 0L
                                    text = label
                                    onConfirmed()
                                } else {
                                    text = String.format(java.util.Locale.US, "继续按住… 还剩 %.1f 秒", leftMs / 1000f)
                                    main.postDelayed(this, 100L)
                                }
                            }
                        }
                        ticker = r
                        main.post(r)
                        v.isPressed = true
                        true
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        val incomplete = downAt != 0L
                        downAt = 0L
                        ticker?.let { main.removeCallbacks(it) }
                        ticker = null
                        text = label
                        v.isPressed = false
                        if (incomplete && event.actionMasked == MotionEvent.ACTION_UP) {
                            hintView?.text = "按住时间不足，为防冲动仍保持当前监督。"
                        }
                        true
                    }
                    else -> false
                }
            }
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
