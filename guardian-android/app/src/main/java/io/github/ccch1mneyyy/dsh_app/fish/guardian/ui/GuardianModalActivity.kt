package io.github.ccch1mneyyy.dsh_app.fish.guardian.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianJson
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.GuardianSettings
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.WatchProvider
import java.util.concurrent.Executors

/**
 * High-priority Mist Glass protective overlay shown whenever DSH Go's engine stops,
 * freezes, loses Accessibility, or receives a Settings tamper attempt.
 */
class GuardianModalActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var badgeView: TextView
    private lateinit var titleView: TextView
    private lateinit var reasonView: TextView
    private lateinit var liveHintView: TextView
    private lateinit var actionStatusView: TextView

    private var currentMode: String = MODE_ENGINE_DOWN
    private var currentReason: String = ""
    private var darkMode: Boolean = false

    private val pollRunnable = object : Runnable {
        override fun run() {
            refreshLiveState()
            handler.postDelayed(this, 2_000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = GuardianTheme.isDark(this)
        GuardianTheme.applyEdgeBars(window, darkMode)
        buildLayout()
        applyIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        handler.post(pollRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(pollRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    @Suppress("OVERRIDE_DEPRECATION")
    override fun onBackPressed() {
        // Require either recovery or explicit 3-min pass during engine outage / mirror enforcement.
        if (currentMode == MODE_ENGINE_DOWN || currentMode == MODE_MIRROR_BLOCK) {
            actionStatusView.text = "当前受「大肥鱼哨兵」保护，请先唤醒大肥鱼或申请 3 分钟急事放行。"
            return
        }
        @Suppress("DEPRECATION")
        super.onBackPressed()
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
            setPadding(dp(20), dp(28), dp(20), dp(28))
        }

        val card = GuardianTheme.makeCard(this, darkMode).apply {
            setPadding(dp(20), dp(22), dp(20), dp(22))
        }

        badgeView = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(5), dp(12), dp(5))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(14) }
        }

        titleView = TextView(this).apply {
            textSize = 21f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GuardianTheme.textPrimary(darkMode))
            setPadding(0, 0, 0, dp(10))
        }

        reasonView = GuardianTheme.makeBodyText(this, darkMode).apply {
            textSize = 14.5f
            setPadding(0, 0, 0, dp(14))
        }

        liveHintView = GuardianTheme.makeBodyText(this, darkMode).apply {
            textSize = 12.5f
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = GuardianTheme.badgeDrawable(this@GuardianModalActivity, GuardianTheme.accent(), darkMode)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(16) }
        }

        val wakeBtn = GuardianTheme.makePrimaryButton(
            this,
            "一键唤醒大肥鱼并恢复引擎",
            GuardianTheme.accent(),
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            setOnClickListener { triggerWake() }
        }

        val a11yBtn = GuardianTheme.makeSecondaryButton(
            this,
            darkMode,
            "打开系统无障碍设置",
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }
        }

        val passBtn = GuardianTheme.makeSecondaryButton(
            this,
            darkMode,
            "紧急急事放行（3 分钟 · 每日限 2 次）",
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(10) }
            setOnClickListener {
                startActivity(Intent(this@GuardianModalActivity, EmergencyPassActivity::class.java))
            }
        }

        val consoleBtn = GuardianTheme.makeSecondaryButton(
            this,
            darkMode,
            "查看哨兵控制台",
        ).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            setOnClickListener {
                startActivity(Intent(this@GuardianModalActivity, MainActivity::class.java))
                if (currentMode == MODE_TAMPER) finish()
            }
        }

        actionStatusView = GuardianTheme.makeBodyText(this, darkMode).apply {
            textSize = 12f
            setPadding(0, dp(12), 0, 0)
        }

        card.addView(badgeView)
        card.addView(titleView)
        card.addView(reasonView)
        card.addView(liveHintView)
        card.addView(wakeBtn)
        card.addView(a11yBtn)
        card.addView(passBtn)
        card.addView(consoleBtn)
        card.addView(actionStatusView)

        root.addView(card)
        scroll.addView(root)
        setContentView(scroll)
    }

    private fun applyIntent(intent: Intent?) {
        currentMode = intent?.getStringExtra(EXTRA_MODE) ?: MODE_ENGINE_DOWN
        val title = intent?.getStringExtra(EXTRA_TITLE).orEmpty().ifBlank { "大肥鱼守护双保险介入" }
        currentReason = intent?.getStringExtra(EXTRA_REASON).orEmpty().ifBlank { "检测到异常或引擎未运行。" }
        val targetPkg = intent?.getStringExtra(EXTRA_TARGET_PKG).orEmpty()
        val phase = intent?.getStringExtra(EXTRA_ENGINE_PHASE).orEmpty()

        val badgeColor = when (currentMode) {
            MODE_TAMPER -> GuardianTheme.errColor()
            MODE_MIRROR_BLOCK -> GuardianTheme.warnColor()
            else -> GuardianTheme.errColor()
        }
        val badgeText = when (currentMode) {
            MODE_TAMPER -> "防篡改拦截"
            MODE_MIRROR_BLOCK -> "离线镜像管控"
            else -> "双保险 · 引擎异常守护"
        }
        badgeView.text = badgeText
        badgeView.setTextColor(badgeColor)
        badgeView.background = GuardianTheme.badgeDrawable(this, badgeColor, darkMode)
        titleView.text = title
        reasonView.text = currentReason
        liveHintView.text = "目标客户端：${targetPkg.ifBlank { "大肥鱼" }}  |  上次引擎阶段：${phase.ifBlank { "unknown" }}"
    }

    private fun triggerWake() {
        actionStatusView.text = "正在通过大肥鱼哨兵跨进程通道唤醒大肥鱼..."
        io.execute {
            val out = runCatching {
                contentResolver.call(WatchProvider.URI, "wakeNow", null, null)
            }.getOrNull()
            val probe = runCatching {
                contentResolver.call(WatchProvider.URI, "probeNow", null, null)
            }.getOrNull()
            handler.post {
                val ok = out?.getBoolean("ok", false) ?: false
                val detail = out?.getString("detail").orEmpty()
                val phase = probe?.getString("enginePhase").orEmpty()
                actionStatusView.text = if (ok) {
                    "已发送唤醒信号：$detail（当前阶段：${phase.ifBlank { "starting" }}）"
                } else {
                    "唤醒尚未成功：$detail"
                }
            }
        }
    }

    private fun refreshLiveState() {
        io.execute {
            val b = runCatching {
                contentResolver.call(WatchProvider.URI, "status", null, null)
            }.getOrNull() ?: return@execute

            val json = b.getString("snapshotJson").orEmpty()
            val map = if (json.isNotBlank()) GuardianJson.map(GuardianJson.parse(json)) else emptyMap()
            val stateName = GuardianJson.str(map, "stateName", "UNKNOWN")
            val phase = GuardianJson.str(map, "enginePhase", "unknown")
            val passActive = b.getBoolean("passActive", false)
            val passRemainingMs = b.getLong("passRemainingMs", 0L)

            handler.post {
                liveHintView.text = if (passActive) {
                    "独立急事放行生效中 · 剩余 ${passRemainingMs / 1000} 秒"
                } else {
                    "哨兵状态：${GuardianTheme.stateLabel(stateName)}  |  引擎阶段：$phase"
                }

                // Auto-dismiss modal once engine recovers cleanly or emergency pass is active.
                if (passActive || (currentMode == MODE_ENGINE_DOWN && stateName == "HEALTHY")) {
                    finish()
                }
            }
        }
    }

    companion object {
        const val MODE_ENGINE_DOWN = "engine_down"
        const val MODE_TAMPER = "tamper"
        const val MODE_MIRROR_BLOCK = "mirror_block"

        private const val EXTRA_MODE = "mode"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_REASON = "reason"
        private const val EXTRA_TARGET_PKG = "targetPkg"
        private const val EXTRA_ENGINE_PHASE = "enginePhase"

        fun buildIntent(
            context: Context,
            mode: String,
            title: String,
            reason: String,
            targetPkg: String,
            enginePhase: String,
        ): Intent = Intent(context, GuardianModalActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(EXTRA_MODE, mode)
            putExtra(EXTRA_TITLE, title)
            putExtra(EXTRA_REASON, reason)
            putExtra(EXTRA_TARGET_PKG, targetPkg)
            putExtra(EXTRA_ENGINE_PHASE, enginePhase)
        }
    }
}
