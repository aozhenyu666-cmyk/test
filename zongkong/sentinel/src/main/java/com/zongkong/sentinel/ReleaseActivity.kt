package com.zongkong.sentinel

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.InputType
import android.util.AttributeSet
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.Instant
import java.time.ZoneId

/**
 * 解除闸门（0.15）：要停用总控 / 改松 / 紧急退出，在这里走——
 * 手写分析（禁粘贴）→ 冷静期 → 手写二次核验 → 解除一段时间。
 * 每一步都重画整页，一秒刷一次倒计时。
 */
class ReleaseActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private val rule get() = Store.releaseRule
    private val tick = object : Runnable {
        override fun run() { render(); main.postDelayed(this, 1_000) }
    }

    override fun onResume() { super.onResume(); main.post(tick) }
    override fun onPause() { super.onPause(); main.removeCallbacks(tick) }

    private fun now() = System.currentTimeMillis()
    private fun mono() = SystemClock.elapsedRealtime()
    private fun hm(t: Long) = Instant.ofEpochMilli(t).atZone(ZONE).let { "%02d:%02d".format(it.hour, it.minute) }

    private fun render() {
        val req = Store.release(this)
        if (req == null) { renderPick(); return }
        when (Release.phase(req, now(), mono(), rule)) {
            Release.Phase.COOLING -> renderCooling(req)
            Release.Phase.VERIFY -> renderVerify(req)
            Release.Phase.GRANTED -> renderGranted(req)
            Release.Phase.EXPIRED -> renderExpired()
            Release.Phase.ENDED -> { Store.putRelease(this, null); renderPick() }
        }
    }

    // ---------- 选要解除什么 ----------
    private fun renderPick() {
        val ui = Ui(this)
        ui.text("解除总控", 24f, bold = true)
        ui.text(
            "哨兵是这套自律的底：总控崩了，只剩它拦着。所以解除要慢、要本人：\n" +
                "① 手写一段分析（约 ${rule.stage1MinChars} 字，不能粘贴）→ ② 冷静 ${minutes(rule.coolingMs)} → ③ 再手写一次核验 → 解除 ${minutes(rule.grantMs)}。\n" +
                "中途随时可以撤销（撤销是立刻的）。",
            14f, Ui.MUTED,
        )
        Release.Kind.entries.forEach { k ->
            ui.button(k.label, primary = k == Release.Kind.RELAX) { start(k) }
        }
        ui.button("算了，不解除", primary = false) { finish() }
        setContentView(ui.scroll())
    }

    private var pendingKind: Release.Kind? = null

    private fun start(kind: Release.Kind) {
        pendingKind = kind
        renderStage1()
    }

    // ---------- 第一关：手写分析 ----------
    private fun renderStage1() {
        val kind = pendingKind ?: return renderPick()
        val ui = Ui(this)
        ui.text("① 手写分析", 22f, bold = true)
        ui.text("要做的：${kind.label}。写清楚为什么现在必须解除、有没有别的办法、解除后打算怎样。认真写满约 ${rule.stage1MinChars} 字。不能粘贴。", 14f, Ui.MUTED)
        val input = handwriteField("在这里手写……")
        val counter = ui.text("0 / ${rule.stage1MinChars}", 13f, Ui.FAINT)
        ui.root.addView(input, ui.root.childCount - 1)
        val submit = ui.button("提交，开始冷静期", primary = true) {}
        input.addTextChangedListener(simpleWatcher {
            val n = Release.effectiveChars(input.text.toString())
            counter.text = "$n / ${rule.stage1MinChars}"
            counter.setTextColor(if (n >= rule.stage1MinChars) Ui.GREEN else Ui.FAINT)
        })
        submit.setOnClickListener {
            val text = input.text.toString()
            when (val c = Release.checkStage1(text, rule)) {
                is Release.Check.No -> toast(c.why)
                Release.Check.Ok -> {
                    val req = Release.file(kind, text, now(), mono(), rule)
                    if (req == null) { toast("写得不够"); return@setOnClickListener }
                    Store.putRelease(this, req)
                    Store.log(this, "解除申请：${kind.label}，进入冷静期")
                    pendingKind = null
                    render()
                }
            }
        }
        ui.button("返回", primary = false) { pendingKind = null; render() }
        setContentView(ui.scroll())
    }

    // ---------- 冷静期 ----------
    private fun renderCooling(req: Release.Request) {
        val ui = Ui(this)
        ui.text("② 冷静中", 22f, bold = true)
        val left = Release.coolingLeft(req, now(), mono(), rule)
        ui.text("要做的：${req.kind.label}", 15f)
        ui.text("还剩 ${Decide.ago(left)}", 30f, Ui.RED, bold = true)
        ui.text("到点后会开一个 ${minutes(rule.stage2WindowMs)} 的窗口，要在窗口内再手写一次核验才真正解除。错过就得从头来。", 14f, Ui.MUTED)
        ui.text("你第一关写的：\n“${req.analysis}”", 13f, Ui.FAINT)
        ui.button("撤销申请（立刻恢复严管）", primary = false) { cancel() }
        setContentView(ui.scroll())
    }

    // ---------- 第二关：手写核验 ----------
    private fun renderVerify(req: Release.Request) {
        val ui = Ui(this)
        ui.text("③ 二次核验", 22f, bold = true)
        val closeAt = Release.verifyOpensWall(req, rule) + rule.stage2WindowMs
        ui.text("窗口还剩 ${Decide.ago((closeAt - now()).coerceAtLeast(0))}。错过就作废。", 15f, Ui.RED, bold = true)
        ui.text("再手写一段（约 ${rule.stage2MinChars} 字，不能跟第一段一样，也不能粘贴）：现在还确认要「${req.kind.label}」吗？为什么。", 14f, Ui.MUTED)
        val input = handwriteField("再写一次你现在的判断……")
        val counter = ui.text("0 / ${rule.stage2MinChars}", 13f, Ui.FAINT)
        ui.root.addView(input, ui.root.childCount - 1)
        input.addTextChangedListener(simpleWatcher {
            val n = Release.effectiveChars(input.text.toString())
            counter.text = "$n / ${rule.stage2MinChars}"
            counter.setTextColor(if (n >= rule.stage2MinChars) Ui.GREEN else Ui.FAINT)
        })
        ui.button("核验，解除", primary = true) {
            val text = input.text.toString()
            when (val c = Release.checkStage2(text, req.analysis, rule)) {
                is Release.Check.No -> toast(c.why)
                Release.Check.Ok -> {
                    val granted = Release.verify(req, text, now(), mono(), rule)
                    if (granted == null) { toast("窗口过了或者没通过"); render(); return@button }
                    Store.putRelease(this, granted)
                    Store.log(this, "二次核验通过：${req.kind.label}，解除到 ${hm(granted.grantedUntil)}")
                    DeviceLock.sync(this) // 「准备卸载」的话，现在解锁卸载
                    render()
                }
            }
        }
        ui.button("撤销申请", primary = false) { cancel() }
        setContentView(ui.scroll())
    }

    // ---------- 解除生效中 ----------
    private fun renderGranted(req: Release.Request) {
        val ui = Ui(this)
        ui.text("已解除", 24f, Ui.RED, bold = true)
        ui.text("${req.kind.label}。到 ${hm(req.grantedUntil)} 自动恢复（还剩 ${Decide.ago((req.grantedUntil - now()).coerceAtLeast(0))}）。", 15f)
        if (req.kind == Release.Kind.UNINSTALL) ui.text("要卸载 / 停用，趁现在去系统设置操作。设成设备所有者的话，卸载仍然被锁——先用 adb 撤销：\n${DeviceLock.releaseOwnerCommand()}", 13f, Ui.MUTED)
        ui.button("立刻恢复严管", primary = true) {
            Store.putRelease(this, null)
            Store.log(this, "本人提前恢复严管")
            finish()
        }
        setContentView(ui.scroll())
    }

    private fun renderExpired() {
        val ui = Ui(this)
        ui.text("窗口过了", 22f, bold = true)
        ui.text("冷静期后的核验窗口错过了，这次申请作废。要解除就重新来一遍。", 14f, Ui.MUTED)
        ui.button("重新申请", primary = true) { Store.putRelease(this, null); render() }
        ui.button("返回", primary = false) { Store.putRelease(this, null); finish() }
        setContentView(ui.scroll())
    }

    private fun cancel() {
        Store.putRelease(this, null)
        Store.log(this, "撤销解除申请")
        finish()
    }

    // ---------- 工具 ----------
    private fun handwriteField(hint: String): EditText = NoPasteEditText(this).apply {
        this.hint = hint
        setHintTextColor(Ui.FAINT)
        setTextColor(Ui.INK)
        textSize = 15f
        gravity = Gravity.TOP or Gravity.START
        minLines = 4
        setPadding(dp(12), dp(12), dp(12), dp(12))
        setBackgroundColor(Color.WHITE)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(8); bottomMargin = dp(4) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun toast(s: String) = android.widget.Toast.makeText(this, s, android.widget.Toast.LENGTH_SHORT).show()
    private fun minutes(ms: Long) = if (ms >= 3_600_000) "${ms / 3_600_000} 小时" else "${ms / 60_000} 分钟"

    companion object {
        private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")
    }
}

/** 禁止粘贴 / 自动填充的输入框：解除必须手打。长按菜单、粘贴项都挡掉。 */
class NoPasteEditText : EditText {
    constructor(c: Context) : super(c)
    constructor(c: Context, a: AttributeSet?) : super(c, a)

    init {
        isLongClickable = false
        customSelectionActionModeCallback = BlockMenu
        customInsertionActionModeCallback = BlockMenu
    }

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.paste, android.R.id.pasteAsPlainText, android.R.id.autofill -> {
            android.widget.Toast.makeText(context, "解除要手写，不能粘贴", android.widget.Toast.LENGTH_SHORT).show()
            true
        }
        else -> super.onTextContextMenuItem(id)
    }

    private object BlockMenu : android.view.ActionMode.Callback {
        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?) = false
        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?) = false
        override fun onDestroyActionMode(mode: android.view.ActionMode?) {}
    }
}

private fun simpleWatcher(onChange: () -> Unit) = object : android.text.TextWatcher {
    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
    override fun afterTextChanged(s: android.text.Editable?) = onChange()
}
