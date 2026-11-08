package io.github.ccch1mneyyy.dsh_app.loop.focus

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 专注监督的主界面（总控的第一块）：设任务、抓当前应用、开始/暂停/结束、看偏离。
 * 入口：快捷设置磁贴「专注监督」、进行中的通知。不依赖 DSH 引擎。
 */
class FocusControlActivity : Activity() {
    private lateinit var p: FocusPalette
    private lateinit var status: TextView
    private lateinit var taskInput: EditText
    private lateinit var minutesInput: EditText
    private lateinit var appLine: TextView
    private lateinit var metricElapsed: TextView
    private lateinit var metricDeviations: TextView
    private lateinit var metricRemaining: TextView
    private lateinit var batteryLine: TextView
    private lateinit var guide: TextView
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

    private fun render() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(p.bgTop, p.bgBottom),
            )
            setPadding(dp(20), dp(24), dp(20), dp(36))
        }

        root.addView(text("专注监督", 28f, p.text))
        root.addView(text("离开任务应用 5 秒就全屏拉回 · 不依赖 DSH 引擎", 13f, p.sub).apply {
            setPadding(0, dp(6), 0, dp(18))
        })

        val statusCard = card()
        status = text("", 15f, p.text)
        statusCard.addView(status)
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(14), 0, 0)
        }
        metricElapsed = metric(row, "已进行")
        metricDeviations = metric(row, "偏离")
        metricRemaining = metric(row, "剩余")
        statusCard.addView(row)
        root.addView(statusCard)

        val taskCard = card()
        taskCard.addView(section("这一段只做一件事"))
        taskInput = EditText(this).apply {
            hint = "例：第 3 题图形推理，做到选出答案"
            setTextColor(p.text)
            setHintTextColor(p.faint)
            textSize = 16f
            setSingleLine(false)
            maxLines = 3
            background = rounded(p.input, dp(12), p.stroke)
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        taskCard.addView(taskInput)
        appLine = text("", 13f, p.sub).apply { setPadding(0, dp(10), 0, 0) }
        taskCard.addView(appLine)
        taskCard.addView(button("选择任务应用") { pickApp() })
        taskCard.addView(text("电话、输入法、系统 UI、DSH 自己永远放行。", 12f, p.faint).apply {
            setPadding(0, dp(10), 0, 0)
        })
        root.addView(taskCard)

        val timeCard = card()
        timeCard.addView(section("结束后自动停止"))
        minutesInput = EditText(this).apply {
            setText("0")
            setTextColor(p.text)
            textSize = 16f
            inputType = InputType.TYPE_CLASS_NUMBER
            background = rounded(p.input, dp(12), p.stroke)
            setPadding(dp(14), dp(10), dp(14), dp(10))
        }
        timeCard.addView(minutesInput)
        timeCard.addView(text("分钟，0 = 不设结束时间", 12f, p.faint).apply { setPadding(0, dp(6), 0, 0) })
        root.addView(timeCard)

        root.addView(button("开始监督", primary = true) {
            val task = taskInput.text.toString().trim()
            if (task.isEmpty()) {
                toast("先写这一段的完成条件")
                return@button
            }
            val minutes = minutesInput.text.toString().toIntOrNull() ?: 0
            val endAt = if (minutes > 0) System.currentTimeMillis() + minutes * 60_000L else 0L
            FocusRuntime.start(task, capturedPackage, endAt)
            toast("已开始；现在切走会立刻拉回")
            refresh()
        })
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(4), 0, 0)
        }
        actions.addView(button("暂停 5 分钟 / 继续", weight = 1f) {
            if (FocusRuntime.isPaused()) FocusRuntime.resume() else FocusRuntime.pause(5)
            refresh()
        })
        actions.addView(button("结束", weight = 1f, secondary = true) {
            FocusRuntime.stop("user")
            refresh()
        })
        root.addView(actions)

        val backgroundCard = card()
        backgroundCard.addView(section("后台存活"))
        batteryLine = text("", 13f, p.sub).apply { setPadding(0, dp(8), 0, dp(6)) }
        backgroundCard.addView(batteryLine)
        backgroundCard.addView(button("允许后台运行（忽略电池优化）") {
            FocusRuntime.requestBatteryExemption(this)
        })
        backgroundCard.addView(button("打开应用详情") { FocusRuntime.openAppDetails(this) })
        backgroundCard.addView(button("打开无障碍设置") {
            runCatching { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        root.addView(backgroundCard)

        val guideCard = card()
        guideCard.addView(section("vivo / iQOO"))
        guide = text(VIVO_GUIDE, 12f, p.sub).apply { setLineSpacing(0f, 1.4f) }
        guideCard.addView(guide)
        root.addView(guideCard)

        root.addView(text(
            "离开任务应用时：全屏拉回 → 回到任务 / 休息 5 分钟 / 结束监督。不答题、不等 15 分钟。",
            12f,
            p.faint,
        ).apply { setPadding(0, dp(18), 0, 0) })

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bgTop)
            isFillViewport = true
            addView(root)
        })
    }

    private fun refresh() {
        val now = System.currentTimeMillis()
        val s = FocusRuntime.state()
        status.text = when {
            !s.active -> "未开始 · 设好任务后点下面的开始"
            FocusRuntime.isPaused(now) -> "已暂停到 " + clock(s.pausedUntil)
            else -> "监督中"
        }
        metricElapsed.text = metricText("已进行", FocusRuntime.elapsedMinutes(now).toString() + " 分")
        metricDeviations.text = metricText("偏离", s.deviations.toString() + " 次")
        metricRemaining.text = metricText(
            "剩余",
            if (s.endAt > 0) FocusRuntime.remainingMinutes(now).toString() + " 分" else "不限",
        )
        if (s.active) taskInput.setText(s.task)
        if (capturedPackage.isBlank() && s.taskPackage.isNotBlank()) {
            capturedPackage = s.taskPackage
            capturedLabel = FocusRuntime.labelOf(s.taskPackage)
        }
        appLine.text = if (capturedPackage.isBlank()) {
            "任务应用：未指定（只允许 DSH / 电话 / 输入法）"
        } else {
            "任务应用：" + capturedLabel
        }
        batteryLine.text = if (FocusRuntime.isIgnoringBattery(this)) {
            "后台优化：已忽略电池优化"
        } else {
            "后台优化：仍会被系统限制，点下面允许"
        }
    }

    private fun metric(row: LinearLayout, label: String): TextView {
        val cell = TextView(this).apply {
            text = metricText(label, "0")
            textSize = 14f
            setTextColor(p.sub)
            gravity = Gravity.CENTER
            setLineSpacing(0f, 1.25f)
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        row.addView(cell)
        return cell
    }

    private fun metricText(label: String, value: String) = label + "\n" + value

    private fun card(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = rounded(p.card, dp(18), p.stroke)
        setPadding(dp(16), dp(16), dp(16), dp(16))
        elevation = dp(2).toFloat()
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { topMargin = dp(14) }
    }

    private fun section(title: String): TextView = text(title, 15f, p.text).apply {
        setPadding(0, 0, 0, dp(10))
    }

    private fun clock(at: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(at))

    private fun text(value: String, size: Float, color: Int): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
    }

    private fun button(
        label: String,
        primary: Boolean = false,
        secondary: Boolean = false,
        weight: Float = 0f,
        onTap: () -> Unit,
    ): Button = Button(this).apply {
        text = label
        textSize = 15f
        isAllCaps = false
        setTextColor(if (primary) p.onAccent else p.text)
        background = rounded(
            when {
                primary -> p.accent
                secondary -> p.card
                else -> p.accentSoft
            },
            dp(14),
            if (secondary) p.stroke else Color.TRANSPARENT,
        )
        stateListAnimator = null
        backgroundTintList = null
        elevation = dp(if (primary) 2 else 0).toFloat()
        val lp = if (weight > 0f) {
            LinearLayout.LayoutParams(0, dp(48), weight).apply {
                topMargin = dp(10)
                marginEnd = dp(5)
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
