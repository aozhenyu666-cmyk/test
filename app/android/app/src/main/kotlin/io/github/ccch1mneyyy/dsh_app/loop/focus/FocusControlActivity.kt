package io.github.ccch1mneyyy.dsh_app.loop.focus

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import io.github.ccch1mneyyy.dsh_app.MainActivity
import io.github.ccch1mneyyy.dsh_app.loop.LoopRuntime
import io.github.ccch1mneyyy.dsh_app.loop.core.InterceptRules
import io.github.ccch1mneyyy.dsh_app.loop.intercept.InterceptRuntime
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 专注监督的主界面（DSH Mist Glass 风格）：设任务、选预设时长、联动娱乐严格锁机、控制与保活诊断。
 * 不依赖 DSH 引擎，手机本地独立持久运行。
 */
class FocusControlActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var p: FocusPalette
    private lateinit var stateBadge: TextView
    private lateinit var status: TextView
    private lateinit var linkedStatus: TextView
    private lateinit var linkedButton: Button
    private lateinit var taskInput: EditText
    private lateinit var minutesInput: EditText
    private lateinit var appLine: TextView
    private lateinit var metricElapsed: TextView
    private lateinit var metricDeviations: TextView
    private lateinit var metricRemaining: TextView
    private lateinit var batteryLine: TextView
    private lateinit var a11yLine: TextView
    private lateinit var holdStatus: TextView
    private lateinit var guide: TextView
    private val durationChips = LinkedHashMap<Int, TextView>()
    private var capturedPackage = ""
    private var capturedLabel = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = FocusTheme.of(this)
        window.statusBarColor = p.bgTop
        window.navigationBarColor = p.bgBottom
        applySystemBars()
        FocusRuntime.ensure(this)
        render()
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    /**
     * 返回不是退出整个 App：这个页面常常是被磁贴或通知单独拉起来的，背后没有 Flutter 页面。
     * 回到 DSH 主界面（已经开着就提到前面），由那里继续设置。
     */
    @Deprecated("Activity back handling; targetSdk 28")
    override fun onBackPressed() {
        runCatching {
            startActivity(
                Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
        finish()
    }

    private data class AppChoice(val pkg: String, val label: String)

    /** The launcher entry points only: anything with an icon in the drawer can be a task app. */
    private fun launcherApps(): List<AppChoice> = runCatching {
        val pm = packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .mapNotNull { it.activityInfo?.applicationInfo }
            .distinctBy { it.packageName }
            .filter { it.packageName != packageName }
            .map { AppChoice(it.packageName, runCatching { pm.getApplicationLabel(it).toString() }.getOrDefault(it.packageName)) }
            .sortedBy { it.label.lowercase(Locale.getDefault()) }
    }.getOrDefault(emptyList())

    /**
     * P4: the old button read LoopRuntime.lastForeground while this very page was in front, so the
     * "front app" was always DSH itself and it could never succeed. Pick from the launcher instead,
     * with a search box and the app last in front first.
     */
    private fun pickApp() {
        val all = launcherApps()
        if (all.isEmpty()) {
            toast("读不到应用列表：系统可能限制了应用可见性")
            return
        }
        val recent = LoopRuntime.lastForeground
        val ordered = all.sortedWith(compareBy({ it.pkg != recent }, { it.label.lowercase(Locale.getDefault()) }))
        var shown = ordered

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(8))
            background = rounded(p.card, dp(18), p.stroke)
        }
        root.addView(text("选择任务应用", 18f, p.text).apply {
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, 0, 0, dp(10))
        })
        val search = EditText(this).apply {
            hint = "搜索应用"
            isSingleLine = true
            textSize = 15f
            setTextColor(p.text)
            setHintTextColor(p.faint)
            background = rounded(p.input, dp(12), p.stroke)
            setPadding(dp(12), dp(8), dp(12), dp(8))
        }
        val list = ListView(this)
        // The dialog's own item layout is the system's black/white; paint it with the page palette
        // so the picker does not flip colour on a dark page.
        val adapter = object : ArrayAdapter<String>(this, android.R.layout.simple_list_item_1, shown.map { it.label }) {
            override fun getView(position: Int, convertView: View?, parent: android.view.ViewGroup): View {
                val v = super.getView(position, convertView, parent)
                (v as TextView).apply {
                    setTextColor(p.text)
                    textSize = 15f
                    setPadding(dp(8), dp(12), dp(8), dp(12))
                }
                return v
            }
        }
        list.adapter = adapter
        list.divider = null
        val dialog = AlertDialog.Builder(this)
            .setView(root)
            .setNegativeButton("取消", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.setTextColor(p.accent)
        }
        list.setOnItemClickListener { _, _, position, _ ->
            shown.getOrNull(position)?.let {
                capturedPackage = it.pkg
                capturedLabel = it.label
                dialog.dismiss()
                refresh()
            }
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                shown = if (q.isEmpty()) ordered else ordered.filter { it.label.contains(q, ignoreCase = true) || it.pkg.contains(q, ignoreCase = true) }
                adapter.clear()
                adapter.addAll(shown.map { it.label })
                adapter.notifyDataSetChanged()
            }
        })
        root.addView(search, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(340)).apply {
            topMargin = dp(8)
        })
        dialog.show()
    }

    private fun applySystemBars() {
        @Suppress("DEPRECATION")
        var flags = View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        if (!p.night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        if (Build.VERSION.SDK_INT >= 26 && !p.night) flags = flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = flags
    }

    override fun onDestroy() {
        main.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun render() {
        val scrollRoot = ScrollView(this).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(p.bgTop, p.bgBottom),
            )
            isFillViewport = true
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(22), dp(18), dp(36))
        }

        // Top Mist Glass Header Row
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(4))
        }
        val headerLeft = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        headerLeft.addView(text("DSH Go · 专注监督", 12f, p.accent).apply {
            setTypeface(typeface, Typeface.BOLD)
        })
        headerLeft.addView(text("原生任务守护", 26f, p.text).apply {
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, dp(2), 0, 0)
        })
        header.addView(headerLeft)
        stateBadge = TextView(this).apply {
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(12), dp(6), dp(12), dp(6))
            setOnClickListener { onBackPressed() }
        }
        header.addView(stateBadge)
        root.addView(header)
        root.addView(text("离开任务应用超过 5 秒即全屏拉回 · 引擎清理或断网仍可独立运行", 13f, p.sub).apply {
            setPadding(0, dp(4), 0, dp(14))
        })

        // Status & Metrics Hero Card
        val statusCard = card()
        status = text("", 15.5f, p.text).apply {
            setTypeface(typeface, Typeface.BOLD)
        }
        statusCard.addView(status)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, dp(12))
        }
        metricElapsed = metric(row, "已进行")
        metricDeviations = metric(row, "偏离")
        metricRemaining = metric(row, "剩余")
        statusCard.addView(row)

        // Entertainment Strict Lock Linkage Banner
        val linkBox = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = rounded(p.input, dp(14), p.stroke)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        linkedStatus = text("", 13f, p.sub).apply { setLineSpacing(0f, 1.3f) }
        linkBox.addView(linkedStatus)
        linkedButton = holdControlButton(
            "",
            holdMs = 2_500L,
            requiresHold = {
                val cfg = InterceptRuntime.config()
                cfg.enabled && cfg.lockMode == InterceptRules.MODE_LINKED
            },
            secondary = true,
        ) {
            toggleLinkedLockMode()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                dp(42),
            ).apply { topMargin = dp(8) }
        }
        linkBox.addView(linkedButton)
        statusCard.addView(linkBox)
        root.addView(statusCard)

        // Task & App Card
        val taskCard = card()
        taskCard.addView(section("这一段只做一件事"))
        taskInput = EditText(this).apply {
            hint = "例：第 3 题图形推理，做到选出答案"
            setTextColor(p.text)
            setHintTextColor(p.faint)
            textSize = 15.5f
            setSingleLine(false)
            maxLines = 3
            background = rounded(p.input, dp(14), p.stroke)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        taskCard.addView(taskInput)
        appLine = text("", 13.5f, p.sub).apply { setPadding(0, dp(12), 0, 0) }
        taskCard.addView(appLine)
        taskCard.addView(button("选择 / 更换任务应用") { pickApp() })
        taskCard.addView(text("电话、输入法、系统 UI 与 DSH 本体始终自动放行。", 12f, p.faint).apply {
            setPadding(0, dp(8), 0, 0)
        })
        root.addView(taskCard)

        // Duration Presets + Custom Input Card
        val timeCard = card()
        timeCard.addView(section("定时结束（分钟）"))
        val presetRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, dp(10))
        }
        val presets = listOf(25 to "25分", 45 to "45分", 60 to "60分", 90 to "90分", 0 to "不限")
        presets.forEachIndexed { idx, (mins, chipLabel) ->
            val chip = TextView(this).apply {
                text = chipLabel
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding(dp(6), dp(9), dp(6), dp(9))
                setOnClickListener {
                    minutesInput.setText(mins.toString())
                    syncDurationChips()
                }
            }
            durationChips[mins] = chip
            presetRow.addView(chip, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                if (idx < presets.lastIndex) marginEnd = dp(6)
            })
        }
        timeCard.addView(presetRow)
        minutesInput = EditText(this).apply {
            setText("45")
            setTextColor(p.text)
            textSize = 15.5f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = rounded(p.input, dp(14), p.stroke)
            setPadding(dp(14), dp(10), dp(14), dp(10))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) = syncDurationChips()
            })
        }
        timeCard.addView(minutesInput)
        timeCard.addView(text("输入 0 表示不设自动停止；可随时点上方预设切换。", 12f, p.faint).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(timeCard)

        // Primary Start & Anti-Impulse Pause/Stop Card
        val ctrlCard = card()
        ctrlCard.addView(section("执行控制"))
        ctrlCard.addView(button("开始监督", primary = true) {
            val task = taskInput.text.toString().trim()
            if (task.isEmpty()) {
                toast("先写这一段的完成条件")
                return@button
            }
            val minutes = minutesInput.text.toString().toIntOrNull() ?: 0
            val endAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
            FocusRuntime.start(task, capturedPackage, endAt)
            toast("已开始；离开任务应用 5 秒将全屏拉回")
            refresh()
        }.apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(52))
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
        }
        actions.addView(holdControlButton("暂停 5 分 / 继续", holdMs = 1_500L, requiresHold = { FocusRuntime.isActive() && !FocusRuntime.isPaused() }, weight = 1f) {
            if (FocusRuntime.isPaused()) FocusRuntime.resume() else FocusRuntime.pause(5)
            refresh()
        }.apply {
            (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6)
        })
        actions.addView(holdControlButton("长按结束监督", holdMs = 2_500L, requiresHold = { FocusRuntime.isActive() }, secondary = true, weight = 1f) {
            FocusRuntime.stop("user")
            refresh()
        })
        ctrlCard.addView(actions)
        holdStatus = text("", 12f, p.faint).apply {
            setPadding(0, dp(8), 0, 0)
            visibility = View.GONE
        }
        ctrlCard.addView(holdStatus)
        root.addView(ctrlCard)

        // System Keep-Alive & Accessibility Status Card
        val backgroundCard = card()
        backgroundCard.addView(section("后台保活与系统权限"))
        batteryLine = text("", 13f, p.sub).apply { setPadding(0, dp(2), 0, dp(4)) }
        a11yLine = text("", 13f, p.sub).apply { setPadding(0, 0, 0, dp(8)) }
        backgroundCard.addView(batteryLine)
        backgroundCard.addView(a11yLine)
        backgroundCard.addView(button("允许后台高耗电（忽略电池优化）") {
            FocusRuntime.requestBatteryExemption(this)
        })
        val sysRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        sysRow.addView(button("打开无障碍设置", secondary = true, weight = 1f) {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        }.apply { (layoutParams as LinearLayout.LayoutParams).marginEnd = dp(6) })
        sysRow.addView(button("打开应用详情", secondary = true, weight = 1f) {
            FocusRuntime.openAppDetails(this)
        })
        backgroundCard.addView(sysRow)
        root.addView(backgroundCard)

        val guideCard = card()
        guideCard.addView(section("后台防冻结指南 · vivo / iQOO"))
        guide = text(VIVO_GUIDE, 12.5f, p.sub).apply { setLineSpacing(0f, 1.4f) }
        guideCard.addView(guide)
        root.addView(guideCard)

        root.addView(text(
            "离开任务应用时：全屏拉回 → 一键回到任务 / 长按 3 秒休息 5 分钟 / 长按 5 秒提前结束。建议与娱乐锁机「专注联动」搭配使用。",
            12f,
            p.faint,
        ).apply { setPadding(dp(4), dp(16), dp(4), 0) })

        scrollRoot.addView(root)
        setContentView(scrollRoot)
        syncDurationChips()
    }

    private fun toggleLinkedLockMode() {
        InterceptRuntime.ensure(this)
        val cfg = InterceptRuntime.config()
        val nextMode = when (cfg.lockMode) {
            InterceptRules.MODE_STRICT -> {
                toast("当前已是全局「严格锁机」，请在 DSH Go → 娱乐锁机页面调整")
                return
            }
            InterceptRules.MODE_LINKED -> InterceptRules.MODE_TRAINING
            else -> InterceptRules.MODE_LINKED
        }
        InterceptRuntime.setConfig(this, cfg.copy(enabled = true, lockMode = nextMode))
        refresh()
    }

    private fun syncDurationChips() {
        val current = minutesInput.text.toString().toIntOrNull()
        durationChips.forEach { (mins, chip) ->
            val selected = current == mins
            chip.setTextColor(if (selected) p.accent else p.sub)
            chip.setTypeface(null, if (selected) Typeface.BOLD else Typeface.NORMAL)
            chip.background = rounded(
                if (selected) p.accentSoft else p.input,
                dp(12),
                if (selected) p.accent else p.stroke,
            )
        }
    }

    private fun refresh() {
        InterceptRuntime.ensure(this)
        val now = System.currentTimeMillis()
        val s = FocusRuntime.state()
        val paused = s.active && FocusRuntime.isPaused(now)
        stateBadge.text = when {
            !s.active -> "待命 · 回 DSH"
            paused -> "暂停中"
            else -> "监督中"
        }
        stateBadge.setTextColor(if (s.active && !paused) p.onAccent else p.accent)
        stateBadge.background = rounded(if (s.active && !paused) p.accent else p.accentSoft, 999, p.stroke)

        status.text = when {
            !s.active -> "未启动专注 · 写明本段结束条件后点击下方开始"
            paused -> "专注已暂停，将于 " + clock(s.pausedUntil) + " 自动恢复"
            else -> "正在专注：「" + s.task + "」"
        }
        metricElapsed.text = metricText("已进行", FocusRuntime.elapsedMinutes(now).toString() + " 分")
        metricDeviations.text = metricText("偏离", s.deviations.toString() + " 次")
        metricRemaining.text = metricText(
            "剩余",
            if (s.endAt > 0) FocusRuntime.remainingMinutes(now).toString() + " 分" else "不限",
        )

        val icfg = InterceptRuntime.config()
        linkedStatus.text = when {
            !icfg.enabled -> "娱乐锁机未开启：可一键开启「专注联动」（专注中禁用数字放行）"
            icfg.lockMode == InterceptRules.MODE_STRICT -> "娱乐锁机已为全局「严格模式」：所有娱乐应用均硬锁"
            icfg.lockMode == InterceptRules.MODE_LINKED ->
                if (s.active) "专注联动严格锁机生效中：期间娱乐应用禁用数字与白名单放行"
                else "已设为「专注联动」：开始专注后将自动升级为严格锁机"
            else -> "娱乐锁机当前为独立训练模式：点击可启用「专注联动」"
        }
        linkedButton.text = when {
            icfg.lockMode == InterceptRules.MODE_STRICT -> "全局严格锁机中"
            icfg.enabled && icfg.lockMode == InterceptRules.MODE_LINKED -> "长按 2.5 秒关闭专注联动"
            else -> "开启「专注时联动娱乐严格锁机」"
        }
        linkedButton.isEnabled = icfg.lockMode != InterceptRules.MODE_STRICT
        linkedButton.alpha = if (linkedButton.isEnabled) 1f else 0.55f

        if (s.active) taskInput.setText(s.task)
        if (capturedPackage.isBlank() && s.taskPackage.isNotBlank()) {
            capturedPackage = s.taskPackage
            capturedLabel = FocusRuntime.labelOf(s.taskPackage)
        }
        appLine.text = if (capturedPackage.isBlank()) {
            "任务应用：未指定（仅允许 DSH / 电话 / 输入法）"
        } else {
            "任务应用：$capturedLabel（$capturedPackage）"
        }
        batteryLine.text = if (FocusRuntime.isIgnoringBattery(this)) {
            "电池优化：已忽略（后台存活正常）"
        } else {
            "电池优化：仍受系统省电限制，请点击下方允许"
        }
        val a11yOk = isAccessibilityEnabled()
        a11yLine.text = if (a11yOk) {
            "无障碍感知：已连接，实时监测前台应用"
        } else {
            "无障碍感知：未开启！不开启将无法检测切屏与拉回"
        }
        a11yLine.setTextColor(if (a11yOk) p.sub else p.accent)
    }

    private fun isAccessibilityEnabled(): Boolean = runCatching {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        !enabled.isNullOrBlank() && enabled.contains(packageName)
    }.getOrDefault(false)

    private fun metric(row: LinearLayout, label: String): TextView {
        val cell = TextView(this).apply {
            text = metricText(label, "0")
            textSize = 13.5f
            setTextColor(p.text)
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.3f)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = rounded(p.input, dp(14), p.stroke)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(6)
            }
        }
        row.addView(cell)
        return cell
    }

    private fun metricText(label: String, value: String) = label + "\n" + value

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.card, dp(22), p.stroke)
        setPadding(dp(18), dp(18), dp(18), dp(18))
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) }
    }

    private fun section(title: String): TextView = text(title, 15f, p.text).apply {
        setTypeface(typeface, Typeface.BOLD)
        setPadding(0, 0, 0, dp(10))
    }

    private fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))

    private fun text(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun holdControlButton(
        label: String,
        holdMs: Long,
        requiresHold: () -> Boolean,
        secondary: Boolean = false,
        weight: Float = 0f,
        onConfirmed: () -> Unit,
    ): Button = button(label, secondary = secondary, weight = weight, onTap = {}).apply {
        var downAt = 0L
        var baseLabel = label
        var ticker: Runnable? = null
        setOnTouchListener { v, event ->
            if (!requiresHold()) {
                if (event.actionMasked == MotionEvent.ACTION_UP) {
                    v.performClick()
                    onConfirmed()
                }
                return@setOnTouchListener true
            }
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    baseLabel = text.toString().ifEmpty { label }
                    downAt = SystemClock.elapsedRealtime()
                    holdStatus.visibility = View.VISIBLE
                    holdStatus.text = "防冲动保护生效中：请持续按住不要松手…"
                    val r = object : Runnable {
                        override fun run() {
                            if (downAt == 0L) return
                            val leftMs = (holdMs - (SystemClock.elapsedRealtime() - downAt)).coerceAtLeast(0L)
                            if (leftMs <= 0L) {
                                downAt = 0L
                                text = baseLabel
                                holdStatus.visibility = View.GONE
                                onConfirmed()
                            } else {
                                text = String.format(Locale.US, "继续按住 %.1fs", leftMs / 1000f)
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
                    val canceled = downAt != 0L
                    downAt = 0L
                    ticker?.let { main.removeCallbacks(it) }
                    ticker = null
                    text = baseLabel
                    v.isPressed = false
                    if (canceled && event.actionMasked == MotionEvent.ACTION_UP) {
                        holdStatus.visibility = View.VISIBLE
                        holdStatus.text = "按住时间不足，为防冲动误触仍保持原设置。"
                    }
                    true
                }
                else -> false
            }
        }
    }

    private fun button(
        label: String,
        primary: Boolean = false,
        secondary: Boolean = false,
        weight: Float = 0f,
        onTap: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 14.5f
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
        elevation = dp(if (primary) 2 else 0).toFloat()
        val lp = if (weight > 0f) {
            LinearLayout.LayoutParams(0, dp(48), weight).apply {
                topMargin = dp(8)
            }
        } else {
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(10) }
        }
        layoutParams = lp
        setOnClickListener { onTap() }
    }

    private fun rounded(fill: Int, radius: Int, stroke: Int): GradientDrawable = GradientDrawable().apply {
        setColor(fill)
        cornerRadius = radius.toFloat()
        if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
    }

    private fun dp(v: Int): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()

    private fun toast(value: String) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val VIVO_GUIDE =
            "i 管家 → 应用管理 → 权限管理 → 自启动、后台高耗电、关联启动全部打开；\n" +
                "设置 → 电池 → 后台耗电管理 → DSH Go → 允许后台高耗电。\n" +
                "无障碍不会因为引擎被清而停；整个应用被系统冻住时，前台读数也会停。"
    }
}
