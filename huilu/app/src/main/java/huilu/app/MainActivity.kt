package huilu.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings as SysSettings
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import huilu.core.AppCat
import huilu.core.Assessor
import huilu.core.EnvMode
import huilu.core.Expectation
import huilu.core.Mode
import huilu.core.Outcome

/**
 * 主页只回答两个问题：现在要做什么？（空闲时）／现在在做什么、系统怎么看？（行动中）
 */
class MainActivity : Activity() {
    private val app get() = App.of(this)
    private var key = ""
    private var live: TextView? = null
    private val ticker = object : Runnable {
        override fun run() { render(); app.main.postDelayed(this, 1_000) }
    }
    private val onChange: () -> Unit = { render() }

    // 空闲表单的状态
    private var minutes = 10
    private var mode = EnvMode.ANY
    private var targets = emptySet<String>()
    private var strict = false
    private var draftText = ""
    private var draftWhy = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "回路"
        minutes = app.prefs.lastMinutes
        mode = app.prefs.lastMode
        targets = app.prefs.lastTargets
        strict = app.prefs.lastStrict
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }

    override fun onResume() {
        super.onResume()
        key = ""
        app.addListener(onChange)
        app.tick()
        app.main.post(ticker)
    }

    override fun onPause() {
        app.removeListener(onChange)
        app.main.removeCallbacks(ticker)
        super.onPause()
    }

    private fun render() {
        val s = app.engine.situation
        val usage = app.platform.hasUsageAccess()
        val k = listOf(s.mode, s.action?.id, s.action?.text, s.action?.endAt, s.pending?.id, s.lastClosed?.id, s.nextCheckAt, s.muted, usage).joinToString()
        if (k != key) {
            key = k
            if (s.action == null) idle(usage) else acting(usage)
        }
        updateLive()
    }

    // ------------------------------------------------------------------ 空闲：声明下一个行动

    private fun idle(usage: Boolean) {
        live = null
        page {
            if (!usage) permissionBanner()
            app.engine.situation.lastClosed?.decision?.let { d ->
                if (App.now() - (app.engine.situation.lastClosed?.closedAt ?: 0) < 30 * 60_000) text(d, 14f, C.MUTED)
            }
            text("接下来做什么？", 22f, bold = true, top = 8)
            text("具体到几分钟后能验证：做完一道题、读完一节、写 200 字。", 13f, C.MUTED)
            val what = edit("例如：学习网课第 3 节", draftText)
            val past = app.episodes().reversed().map { it.action }.distinctBy { it.text }.take(4)
            if (past.isNotEmpty()) grid(past.map { a ->
                a.text to {
                    what.setText(a.text); what.setSelection(a.text.length)
                    if (a.why.isNotBlank()) draftWhy = a.why
                    draftText = a.text; minutes = a.plannedMin
                    if (a.expect.targetApps.isNotEmpty()) { targets = a.expect.targetApps; mode = a.expect.mode }
                    key = ""; render()
                }
            })
            val why = edit("为什么（可选）", draftWhy)

            title("多久")
            val mins = listOf(5, 10, 15, 25, 45)
            val btns = mutableListOf<android.widget.Button>()
            row {
                mins.forEach { m ->
                    btns += button("$m", primary = m == minutes, weight = 1f) {
                        minutes = m
                        draftText = what.text.toString(); draftWhy = why.text.toString()
                        key = ""; render()
                    }
                }
            }

            title("怎么判断你真的在做")
            val group = RadioGroup(this@MainActivity)
            EnvMode.values().forEach { m ->
                group.addView(RadioButton(this@MainActivity).apply {
                    text = m.label + when (m) {
                        EnvMode.IN_APPS -> if (targets.isEmpty()) "（未选择）" else "：" + targets.joinToString("、") { app.platform.label(it) }
                        EnvMode.OFF_PHONE -> "（纸上、电脑上做；手机放下）"
                        EnvMode.ANY -> ""
                    }
                    textSize = 15f
                    id = m.ordinal + 100
                    isChecked = m == mode
                })
            }
            group.setOnCheckedChangeListener { _, id ->
                mode = EnvMode.values()[id - 100]
                draftText = what.text.toString(); draftWhy = why.text.toString()
                if (mode == EnvMode.IN_APPS && targets.isEmpty()) pickTargets() else { key = ""; render() }
            }
            addView(group)
            if (mode == EnvMode.IN_APPS) button("选择目标 App（${targets.size}）") {
                draftText = what.text.toString(); draftWhy = why.text.toString()
                pickTargets()
            }

            val cb = CheckBox(this@MainActivity).apply {
                text = "这一轮允许强干预：反复偏离时送回桌面、屏蔽娱乐 App"
                textSize = 14f
                isChecked = strict
                setOnCheckedChangeListener { _, v -> strict = v }
            }
            addView(cb)

            button("开始", primary = true) { start(what, why) }

            recent()
            footer()
        }
    }

    private fun pickTargets() = pickApps("这件事在哪些 App 里做", targets) {
        targets = it
        mode = if (it.isEmpty()) EnvMode.ANY else EnvMode.IN_APPS
        key = ""; render()
    }

    private fun start(what: EditText, why: EditText) {
        val t = what.text.toString().trim()
        if (t.isEmpty()) { what.error = "写一个具体的动作"; return }
        if (mode == EnvMode.IN_APPS && targets.isEmpty()) { pickTargets(); return }
        app.prefs.lastMinutes = minutes; app.prefs.lastMode = mode; app.prefs.lastTargets = targets; app.prefs.lastStrict = strict
        draftText = ""; draftWhy = ""
        app.perform(app.engine.start(t, why.text.toString(), minutes, Expectation(mode, if (mode == EnvMode.IN_APPS) targets else emptySet()), strict))
    }

    // ------------------------------------------------------------------ 行动中：当前局面

    private fun acting(usage: Boolean) {
        val s = app.engine.situation
        val a = s.action ?: return
        page {
            if (!usage) permissionBanner()
            card {
                text("现在", 13f, C.MUTED, bold = true)
                text(a.text, 22f, bold = true)
                if (a.why.isNotBlank()) text("为什么：${a.why}", 14f, C.MUTED)
                text("${huilu.core.Engine.hm(a.startedAt)} 开始 · ${huilu.core.Engine.hm(a.endAt)} 确认 · ${a.expect.effectiveMode.label}" +
                    (if (a.strict) " · 允许强干预" else ""), 13f, C.MUTED)
                live = text("", 15f, top = 10)
            }
            val p = s.pending
            if (p != null) {
                card(0xFFFFF3E6.toInt()) {
                    text("系统在等你回答", 13f, C.WARN, bold = true)
                    text(p.question, 16f)
                    button("回答", primary = true) { startActivity(CheckInActivity.intent(this@MainActivity, p.id)) }
                }
            } else {
                text(when {
                    s.mode == Mode.RESTING -> "休息中，${s.restUntil?.let(huilu.core.Engine::hm)} 叫你回来。"
                    s.muted -> "本轮不主动检查，到点确认结果。"
                    s.nextCheckAt != null -> "下次检查 ${huilu.core.Engine.hm(s.nextCheckAt!!)}：${s.nextCheckReason ?: ""}"
                    else -> "到点时确认结果。"
                }, 14f, C.MUTED, top = 12)
            }
            s.lastClosed?.takeIf { it.actionId == a.id }?.let { c ->
                title("上一次检查")
                text(c.deviation.reality, 14f)
                text("${c.answer?.label ?: "未回答"} → ${c.decision ?: ""}", 14f, C.MUTED)
            }
            grid(listOf(
                "现在检查" to {
                    app.perform(app.engine.checkNow())
                    app.engine.situation.pending?.let { startActivity(CheckInActivity.intent(this@MainActivity, it.id)) }
                    Unit
                },
                "完成了" to { app.perform(app.engine.stop(Outcome.DONE)) },
                "换别的" to { app.perform(app.engine.stop(Outcome.REPLACED)) },
                "放弃" to { app.perform(app.engine.stop(Outcome.ABANDONED)) },
            ))
            footer()
        }
    }

    /** 系统此刻看到的现实：让"它知道什么、不知道什么"始终可见。 */
    private fun updateLive() {
        val v = live ?: return
        val s = app.engine.situation
        val a = s.action ?: return
        val now = App.now()
        val fg = app.platform.foreground(now)
        val seen = when {
            fg.state != huilu.core.SensorState.OK -> "看不到前台 App（没有使用情况访问权限）"
            fg.pkg == null -> "手机息屏 / 锁屏 ${Assessor.dur(now - fg.since)}"
            else -> {
                val cat = when (app.engine.assessor.category(fg.pkg!!, a)) {
                    AppCat.TARGET -> "目标"; AppCat.DISTRACTOR -> "娱乐"; AppCat.NEUTRAL -> "中性"; AppCat.OTHER -> "其他"
                }
                "前台：${app.platform.label(fg.pkg!!)}（$cat）${Assessor.dur(now - fg.since)}"
            }
        }
        val left = a.endAt - now
        v.text = "第 ${Assessor.minuteOf(a, now) + 1} / ${a.plannedMin} 分钟" +
            (if (left > 0) " · 还剩 ${Assessor.dur(left)}" else " · 已到点") + "\n" + seen
    }

    // ------------------------------------------------------------------ 共用

    private fun LinearLayout.permissionBanner() = card(0xFFFFF3E6.toInt()) {
        text("系统现在只能靠你自报", 15f, C.WARN, bold = true)
        text("没有「使用情况访问权限」，就看不到前台 App，也就无法发现偏离。授权后回到这里即可。", 14f)
        button("去授权", primary = true) { startActivity(Intent(SysSettings.ACTION_USAGE_ACCESS_SETTINGS)) }
    }

    private fun LinearLayout.recent() {
        val eps = app.episodes().filter { it.outcome != null }.takeLast(5).reversed()
        if (eps.isEmpty()) return
        title("最近")
        eps.forEach { e ->
            val w = e.whole
            val dev = when {
                w == null -> ""
                e.drifted -> " · 偏到${w.topDistractor?.let(app.platform::label) ?: "娱乐"}" +
                    (e.checkIns.firstOrNull { it.deviation.onsetMin != null }?.deviation?.onsetMin?.let { "（第${it}分钟）" } ?: "")
                else -> " · ${w.kind.label}"
            }
            text("${huilu.core.Engine.hm(e.action.startedAt)} ${e.action.text} · ${e.action.plannedMin}→${e.actualMin}分 · ${e.outcome!!.label}$dev", 14f)
        }
    }

    private fun LinearLayout.footer() = grid(listOf(
        "回顾" to { startActivity(Intent(this@MainActivity, HistoryActivity::class.java)) },
        "设置与权限" to { startActivity(Intent(this@MainActivity, SettingsActivity::class.java)) },
    ))

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (grantResults.firstOrNull() == PackageManager.PERMISSION_DENIED) Toast.makeText(this, "没有通知权限，系统无法主动找你", Toast.LENGTH_LONG).show()
    }
}
