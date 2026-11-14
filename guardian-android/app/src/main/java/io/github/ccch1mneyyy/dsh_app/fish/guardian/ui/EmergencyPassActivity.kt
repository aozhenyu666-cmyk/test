package io.github.ccch1mneyyy.dsh_app.fish.guardian.ui

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.WatchProvider
import java.util.concurrent.Executors

/**
 * Independent 3-minute emergency pass UI enforced with monotonically bounded quota
 * (max 2 times per Asia/Shanghai calendar day, unaffected by wall clock rollbacks).
 */
class EmergencyPassActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var quotaView: TextView
    private lateinit var reasonInput: EditText
    private lateinit var errorView: TextView
    private var darkMode: Boolean = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = GuardianTheme.isDark(this)
        GuardianTheme.applyEdgeBars(window, darkMode)
        buildLayout()
        loadQuota()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun buildLayout() {
        val dp = { v: Int -> GuardianTheme.dp(this, v) }
        val scroll = ScrollView(this).apply {
            background = GuardianTheme.screenBackground(darkMode)
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(24))
        }

        val card = GuardianTheme.makeCard(this, darkMode).apply {
            setPadding(dp(20), dp(22), dp(20), dp(22))
        }

        val title = TextView(this).apply {
            text = "独立 3 分钟急事通行"
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GuardianTheme.textPrimary(darkMode))
            setPadding(0, 0, 0, dp(8))
        }

        val desc = GuardianTheme.makeBodyText(
            this,
            darkMode,
            "当大肥鱼主程序暂时无法操作而你确实有急事时，哨兵可提供 3 分钟只读放行窗口。" +
                "放行时长使用单调时钟计时（修改系统时间无效），每天最多 2 次。",
        ).apply { setPadding(0, 0, 0, dp(12)) }

        quotaView = TextView(this).apply {
            text = "正在查询今日剩余次数..."
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GuardianTheme.warnColor())
            background = GuardianTheme.badgeDrawable(this@EmergencyPassActivity, GuardianTheme.warnColor(), darkMode)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }

        reasonInput = EditText(this).apply {
            hint = "请填写急事原因（至少 2 个字，例如：接外卖/扫码支付）"
            setHintTextColor(GuardianTheme.textSecondary(darkMode))
            setTextColor(GuardianTheme.textPrimary(darkMode))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 2
            background = GuardianTheme.badgeDrawable(this@EmergencyPassActivity, GuardianTheme.accent(), darkMode)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }

        val baseGrantText = "按住 2 秒启用 3 分钟急事放行"
        val grantBtn = GuardianTheme.makePrimaryButton(
            this,
            baseGrantText,
            GuardianTheme.accent(),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            var downAt = 0L
            var ticker: Runnable? = null
            setOnTouchListener { v, event ->
                when (event.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN -> {
                        val candidate = reasonInput.text?.toString().orEmpty().trim()
                        if (candidate.length < 2) {
                            errorView.text = "请先填写至少 2 个字的急事原因，再按住确认。"
                            return@setOnTouchListener true
                        }
                        errorView.text = ""
                        downAt = android.os.SystemClock.elapsedRealtime()
                        val r = object : Runnable {
                            override fun run() {
                                if (downAt == 0L) return
                                val leftMs = (2_000L - (android.os.SystemClock.elapsedRealtime() - downAt)).coerceAtLeast(0L)
                                if (leftMs <= 0L) {
                                    downAt = 0L
                                    text = baseGrantText
                                    submitPass()
                                } else {
                                    text = String.format(java.util.Locale.US, "继续按住 %.1f 秒确认放行…", leftMs / 1000f)
                                    handler.postDelayed(this, 100L)
                                }
                            }
                        }
                        ticker = r
                        handler.post(r)
                        v.isPressed = true
                        true
                    }
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                        val premature = downAt != 0L
                        downAt = 0L
                        ticker?.let { handler.removeCallbacks(it) }
                        ticker = null
                        text = baseGrantText
                        v.isPressed = false
                        if (premature && event.actionMasked == android.view.MotionEvent.ACTION_UP) {
                            errorView.text = "按住时间不足 2 秒，未启用急事通行。"
                        }
                        true
                    }
                    else -> false
                }
            }
        }

        val cancelBtn = GuardianTheme.makeSecondaryButton(this, darkMode, "返回").apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener { finish() }
        }

        errorView = GuardianTheme.makeBodyText(this, darkMode).apply {
            setTextColor(GuardianTheme.errColor())
            setPadding(0, dp(10), 0, 0)
        }

        card.addView(title)
        card.addView(desc)
        card.addView(quotaView)
        card.addView(reasonInput)
        card.addView(grantBtn)
        card.addView(cancelBtn)
        card.addView(errorView)

        root.addView(card)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun loadQuota() {
        io.execute {
            val b = runCatching { contentResolver.call(WatchProvider.URI, "status", null, null) }.getOrNull()
            val remain = b?.getInt("passRemainingToday", 2) ?: 2
            val active = b?.getBoolean("passActive", false) ?: false
            val remainMs = b?.getLong("passRemainingMs", 0L) ?: 0L
            handler.post {
                quotaView.text = if (active) {
                    "当前通行中（剩 ${remainMs / 1000}s） · 今日剩余 $remain / 2 次"
                } else {
                    "今日剩余急事通行次数：$remain / 2 次"
                }
            }
        }
    }

    private fun submitPass() {
        val reason = reasonInput.text?.toString().orEmpty()
        errorView.text = ""
        io.execute {
            val extras = Bundle().apply { putString("reason", reason) }
            val res = runCatching {
                contentResolver.call(WatchProvider.URI, "grantPass", null, extras)
            }.getOrNull()
            handler.post {
                val ok = res?.getBoolean("ok", false) ?: false
                if (ok) {
                    finish()
                } else {
                    errorView.text = res?.getString("error") ?: "申请通行失败"
                    loadQuota()
                }
            }
        }
    }
}
