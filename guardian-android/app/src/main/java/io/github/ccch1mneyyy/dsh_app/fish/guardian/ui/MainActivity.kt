package io.github.ccch1mneyyy.dsh_app.fish.guardian.ui

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.text.InputFilter
import android.text.InputType
import android.view.View
import android.view.accessibility.AccessibilityManager
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.fish.guardian.a11y.GuardianA11yService
import io.github.ccch1mneyyy.dsh_app.fish.guardian.health.GuardianJson
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.FishClientBridge
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.GuardianDeviceAdminReceiver
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.GuardianStores
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.LockExecutor
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.WatchProvider
import io.github.ccch1mneyyy.dsh_app.fish.guardian.watch.WatchService
import java.util.concurrent.Executors

/**
 * Mist Glass control center for `大肥鱼哨兵`.
 */
class MainActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()

    private lateinit var heroBadge: TextView
    private lateinit var heroSummary: TextView
    private lateinit var pairInfoView: TextView
    private lateinit var pairCodeInput: EditText
    private lateinit var pairHintView: TextView
    private lateinit var permSummaryView: TextView
    private lateinit var modalCheck: CheckBox
    private lateinit var lockCheck: CheckBox
    private lateinit var rebindCheck: CheckBox
    private lateinit var mirrorSummaryView: TextView
    private lateinit var logsView: TextView

    private var darkMode: Boolean = false
    private var suppressCheckEvents: Boolean = false
    private var cachedModal = true
    private var cachedLock = false
    private var cachedRebind = true

    private val refreshRunnable = object : Runnable {
        override fun run() {
            refreshStatus()
            handler.postDelayed(this, 2_500L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        darkMode = GuardianTheme.isDark(this)
        GuardianTheme.applyEdgeBars(window, darkMode)
        WatchService.start(this)
        requestNotificationPermissionIfNeeded()
        buildLayout()
    }

    override fun onResume() {
        super.onResume()
        WatchService.start(this)
        handler.post(refreshRunnable)
    }

    override fun onPause() {
        handler.removeCallbacks(refreshRunnable)
        super.onPause()
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
            setPadding(dp(16), dp(18), dp(16), dp(24))
        }

        // 1. Header & Hero Card
        val hero = GuardianTheme.makeCard(this, darkMode)
        val headerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        val appTitle = TextView(this).apply {
            text = "大肥鱼哨兵"
            textSize = 22f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(GuardianTheme.textPrimary(darkMode))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        heroBadge = TextView(this).apply {
            text = "初始化中"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(10), dp(4), dp(10), dp(4))
        }
        headerRow.addView(appTitle)
        headerRow.addView(heroBadge)

        heroSummary = GuardianTheme.makeBodyText(this, darkMode, "正在连接 `:watch` 独立守护进程...").apply {
            setPadding(0, 0, 0, dp(12))
        }

        val quickRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val probeBtn = GuardianTheme.makePrimaryButton(this, "立即探测").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { callWatchAction("probeNow") }
        }
        val wakeBtn = GuardianTheme.makeSecondaryButton(this, darkMode, "一键唤醒大肥鱼").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
            setOnClickListener { callWatchAction("wakeNow") }
        }
        val passBtn = GuardianTheme.makeSecondaryButton(this, darkMode, "急事 3 分钟").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener { startActivity(Intent(this@MainActivity, EmergencyPassActivity::class.java)) }
        }
        quickRow.addView(probeBtn)
        quickRow.addView(wakeBtn)
        quickRow.addView(passBtn)

        hero.addView(headerRow)
        hero.addView(heroSummary)
        hero.addView(quickRow)
        root.addView(hero)

        // 2. Mutual Trust & Pairing Card
        val pairCard = GuardianTheme.makeCard(this, darkMode)
        pairCard.addView(GuardianTheme.makeSectionTitle(this, darkMode, "互信配对（大肥鱼 <-> 哨兵）"))
        pairInfoView = GuardianTheme.makeBodyText(this, darkMode, "同签名安装时自动配对；若跨签名（本地/CI 不同证书），请输入大肥鱼中显示的 6 位配对码。").apply {
            setPadding(0, 0, 0, dp(10))
        }
        val pairRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp(8) }
        }
        pairCodeInput = EditText(this).apply {
            hint = "6 位配对码（同签名可留空）"
            setHintTextColor(GuardianTheme.textSecondary(darkMode))
            setTextColor(GuardianTheme.textPrimary(darkMode))
            inputType = InputType.TYPE_CLASS_NUMBER
            filters = arrayOf(InputFilter.LengthFilter(6))
            background = GuardianTheme.badgeDrawable(this@MainActivity, GuardianTheme.accent(), darkMode)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(8)
            }
        }
        val pairBtn = GuardianTheme.makePrimaryButton(this, "验证并配对").apply {
            setOnClickListener { runPairing() }
        }
        pairRow.addView(pairCodeInput)
        pairRow.addView(pairBtn)
        pairHintView = GuardianTheme.makeBodyText(this, darkMode)

        pairCard.addView(pairInfoView)
        pairCard.addView(pairRow)
        pairCard.addView(pairHintView)
        root.addView(pairCard)

        // 3. Safeguard Options & Permissions Card
        val permCard = GuardianTheme.makeCard(this, darkMode)
        permCard.addView(GuardianTheme.makeSectionTitle(this, darkMode, "保险策略与系统权限"))
        permSummaryView = GuardianTheme.makeBodyText(this, darkMode).apply {
            setPadding(0, 0, 0, dp(10))
        }

        modalCheck = CheckBox(this).apply {
            text = "引擎异常或失联时弹出最高优先级拦截模态"
            setTextColor(GuardianTheme.textPrimary(darkMode))
            setOnCheckedChangeListener { _, checked ->
                if (suppressCheckEvents) return@setOnCheckedChangeListener
                cachedModal = checked
                pushSettings()
            }
        }
        lockCheck = CheckBox(this).apply {
            text = "引擎彻底失联且唤醒冷却失效时尝试锁屏（可选）"
            setTextColor(GuardianTheme.textPrimary(darkMode))
            setOnCheckedChangeListener { _, checked ->
                if (suppressCheckEvents) return@setOnCheckedChangeListener
                cachedLock = checked
                pushSettings()
                if (checked && !LockExecutor(this@MainActivity).adminActive()) {
                    requestDeviceAdmin()
                }
            }
        }
        rebindCheck = CheckBox(this).apply {
            text = "授权 WRITE_SECURE_SETTINGS 时自动拉起大肥鱼无障碍"
            setTextColor(GuardianTheme.textPrimary(darkMode))
            setOnCheckedChangeListener { _, checked ->
                if (suppressCheckEvents) return@setOnCheckedChangeListener
                cachedRebind = checked
                pushSettings()
            }
        }

        val permBtnRow1 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(10)
                bottomMargin = dp(8)
            }
        }
        val btnA11y = GuardianTheme.makeSecondaryButton(this, darkMode, "无障碍设置").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            setOnClickListener { runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) } }
        }
        val btnOverlay = GuardianTheme.makeSecondaryButton(this, darkMode, "悬浮窗权限").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                val i = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                runCatching { startActivity(i) }
            }
        }
        permBtnRow1.addView(btnA11y)
        permBtnRow1.addView(btnOverlay)

        val permBtnRow2 = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        val btnBattery = GuardianTheme.makeSecondaryButton(this, darkMode, "忽略电池优化").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(8) }
            setOnClickListener { requestIgnoreBattery() }
        }
        val btnAdmin = GuardianTheme.makeSecondaryButton(this, darkMode, "设备管理/测试锁屏").apply {
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            setOnClickListener {
                if (LockExecutor(this@MainActivity).adminActive()) {
                    callWatchAction("testLock")
                } else {
                    requestDeviceAdmin()
                }
            }
        }
        permBtnRow2.addView(btnBattery)
        permBtnRow2.addView(btnAdmin)

        permCard.addView(permSummaryView)
        permCard.addView(modalCheck)
        permCard.addView(lockCheck)
        permCard.addView(rebindCheck)
        permCard.addView(permBtnRow1)
        permCard.addView(permBtnRow2)
        root.addView(permCard)

        // 4. Mirror & Audit Logs Card
        val logCard = GuardianTheme.makeCard(this, darkMode)
        logCard.addView(GuardianTheme.makeSectionTitle(this, darkMode, "镜像管控快照与哨兵日志"))
        mirrorSummaryView = GuardianTheme.makeBodyText(this, darkMode).apply {
            setPadding(0, 0, 0, dp(8))
        }
        logsView = GuardianTheme.makeBodyText(this, darkMode, "等待日志...").apply {
            textSize = 11.5f
            typeface = Typeface.MONOSPACE
        }
        logCard.addView(mirrorSummaryView)
        logCard.addView(logsView)
        root.addView(logCard)

        scroll.addView(root)
        setContentView(scroll)
    }

    private fun refreshStatus() {
        io.execute {
            val b = runCatching { contentResolver.call(WatchProvider.URI, "status", null, null) }.getOrNull()
            val selfA11y = isGuardianA11yConnected()
            val overlayOk = Settings.canDrawOverlays(this)
            val batteryOk = (getSystemService(PowerManager::class.java))?.isIgnoringBatteryOptimizations(packageName) == true
            val adminOk = LockExecutor(this).adminActive()

            if (b == null) {
                handler.post {
                    heroBadge.text = "连接中"
                    heroSummary.text = "`:watch` 进程尚未就绪，正在拉起 WatchService..."
                }
                return@execute
            }

            val json = b.getString("snapshotJson").orEmpty()
            val snap = if (json.isNotBlank()) GuardianJson.map(GuardianJson.parse(json)) else emptyMap()
            val stateName = GuardianJson.str(snap, "stateName", "UNKNOWN")
            val statusReason = GuardianJson.str(snap, "statusReason", "等待探测")
            val targetPkg = GuardianJson.str(snap, "targetPkg", "")
            val enginePhase = GuardianJson.str(snap, "enginePhase", "unknown")
            val paired = GuardianJson.bool(snap, "paired", false)
            val sameCert = GuardianJson.bool(snap, "sameCert", false)
            val writeSecure = GuardianJson.bool(snap, "writeSecureGranted", false)
            val pendingOffline = GuardianJson.lng(snap, "pendingOfflineCount", 0L)
            val blockedCount = b.getInt("blockedCount", 0)
            val mirrorActive = b.getBoolean("mirrorActive", false)
            val primaryWhy = b.getString("primaryWhy").orEmpty()
            val logs = b.getString("logsText").orEmpty()
            val watchRunning = b.getBoolean("watchRunning", false)
            val modal = b.getBoolean("modalOnEngineDown", true)
            val lock = b.getBoolean("lockOnEngineDown", false)
            val rebind = b.getBoolean("autoRebindA11y", true)

            handler.post {
                val badgeColor = GuardianTheme.stateColor(stateName)
                heroBadge.text = GuardianTheme.stateLabel(stateName)
                heroBadge.setTextColor(badgeColor)
                heroBadge.background = GuardianTheme.badgeDrawable(this, badgeColor, darkMode)

                heroSummary.text = buildString {
                    appendLine("说明：$statusReason")
                    appendLine("监控进程：${if (watchRunning) ":watch 运行中" else "待启动"}  |  引擎阶段：$enginePhase")
                    append("目标包名：${targetPkg.ifBlank { "未发现 DSH Go 客户端" }}")
                }

                pairInfoView.text = buildString {
                    append("配对状态：${if (paired) "已互信绑定" else "未配对"}")
                    append("  |  签名一致：${if (sameCert) "是（免码自动绑定）" else "否（需 6 位配对码）"}")
                }

                permSummaryView.text = buildString {
                    appendLine("哨兵无障碍：${yesNo(selfA11y)}  |  悬浮窗权限：${yesNo(overlayOk)}")
                    append("忽略电池优化：${yesNo(batteryOk)}  |  设备管理锁屏：${yesNo(adminOk)}  |  WRITE_SECURE_SETTINGS：${yesNo(writeSecure)}")
                }

                suppressCheckEvents = true
                cachedModal = modal
                cachedLock = lock
                cachedRebind = rebind
                modalCheck.isChecked = modal
                lockCheck.isChecked = lock
                rebindCheck.isChecked = rebind
                suppressCheckEvents = false

                mirrorSummaryView.text = "镜像封锁包数：$blockedCount  |  规则生效中：${yesNo(mirrorActive)}  |  待补报事件：$pendingOffline" +
                    if (primaryWhy.isNotBlank()) "\n当前理由：$primaryWhy" else ""
                logsView.text = logs.ifBlank { "暂无守护日志" }
            }
        }
    }

    private fun callWatchAction(method: String) {
        io.execute {
            val res = runCatching { contentResolver.call(WatchProvider.URI, method, null, null) }.getOrNull()
            handler.post {
                val msg = res?.getString("detail")
                    ?: res?.getString("statusReason")
                    ?: res?.getString("note")
                    ?: "已发送指令 $method"
                pairHintView.text = msg
                refreshStatus()
            }
        }
    }

    private fun runPairing() {
        val code = pairCodeInput.text?.toString().orEmpty().trim()
        pairHintView.text = "正在与大肥鱼验证互信凭证..."
        io.execute {
            val targetPkg = FishClientBridge.resolveInstalledTarget(this)
            if (targetPkg == null) {
                handler.post { pairHintView.text = "未检测到已安装的大肥鱼（io.github.ccch1mneyyy.dsh_app.fish）" }
                return@execute
            }
            val result = FishClientBridge.pair(this, targetPkg, code)
            if (result.ok) {
                contentResolver.call(WatchProvider.URI, "probeNow", null, null)
            }
            handler.post {
                pairHintView.text = if (result.ok) {
                    "配对成功（${if (result.sameCert) "同签名" else "配对码固定证书"}），探针已激活。"
                } else {
                    "配对失败：${result.error}"
                }
                refreshStatus()
            }
        }
    }

    private fun pushSettings() {
        val extras = Bundle().apply {
            putBoolean("modalOnEngineDown", cachedModal)
            putBoolean("lockOnEngineDown", cachedLock)
            putBoolean("autoRebindA11y", cachedRebind)
        }
        io.execute {
            runCatching { contentResolver.call(WatchProvider.URI, "saveSettings", null, extras) }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }
    }

    private fun requestIgnoreBattery() {
        val i = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
        runCatching { startActivity(i) }
    }

    private fun requestDeviceAdmin() {
        val comp = ComponentName(this, GuardianDeviceAdminReceiver::class.java)
        val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
            putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
            putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "用于在 DSH Go 引擎彻底失联且唤醒失败时执行可选安全锁屏。")
        }
        runCatching { startActivity(i) }
    }

    private fun isGuardianA11yConnected(): Boolean {
        if (GuardianA11yService.instance != null) return true
        val am = getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager ?: return false
        val me = "$packageName/${GuardianA11yService::class.java.name}"
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo?.serviceInfo?.let { si -> "${si.packageName}/${si.name}" } == me }
    }

    private fun yesNo(v: Boolean): String = if (v) "已开启" else "未开启"
}
