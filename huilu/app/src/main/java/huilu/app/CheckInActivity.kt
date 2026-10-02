package huilu.app

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Toast
import huilu.core.AnswerKind
import huilu.core.Assessor
import huilu.core.CheckIn
import huilu.core.DevKind
import huilu.core.Trigger
import huilu.core.Via

/**
 * 一次检查就是一个独立的小交互：当时的局面 → 问题 → 回答 → 判断 → 后续动作。
 * 它不是聊天窗口，回答完就关闭；长期状态由引擎维护。
 */
class CheckInActivity : Activity() {
    private val app get() = App.of(this)
    private var thinking = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "检查"
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        current = this
        app.engine.situation.pending?.let { app.shownCheckIn = it.id to App.now() }
        render()
    }

    override fun onPause() {
        if (current === this) current = null
        super.onPause()
    }

    fun onClosedElsewhere(id: String) {
        if (app.engine.situation.pending?.id != id && !isFinishing) render()
    }

    private fun render() {
        val c = app.engine.situation.pending
        if (c == null) {
            val last = app.engine.situation.lastClosed
            page {
                text("这次检查已经结束", 20f, bold = true, top = 8)
                if (last != null) {
                    text(last.question, 15f, C.MUTED)
                    text("${last.answer?.label ?: "未回答"} → ${last.decision ?: ""}", 15f)
                }
                button("好", primary = true) { finish() }
            }
            return
        }
        page {
            text("${c.trigger.label} · 第 ${c.minuteOfAction} 分钟", 13f, C.MUTED, bold = true, top = 8)
            text(c.question, 21f, bold = true)
            observed(c)
            c.notes.forEach { text("· $it", 14f, C.MUTED) }
            Judge.lastReply?.takeIf { it.first == c.id && it.second.isNotBlank() }?.let { text(it.second, 16f, C.ACCENT, top = 10) }

            title(if (c.trigger == Trigger.REST_OVER) "回去吗" else "选一个")
            grid(c.choices.map { k -> label(k, c) to { choose(c, k) } }, primaryFirst = true)

            title("或者说一句")
            val box = edit(if (app.prefs.llmReady) "发生了什么？AI 判断层会理解成下一步" else "发生了什么？会记进这次检查", lines = 2)
            button(if (thinking) "判断中…" else "发送") { send(c, box) }
        }
    }

    private fun LinearLayout.observed(c: CheckIn) = card {
        val d = c.deviation
        text("系统观察到", 13f, C.MUTED, bold = true)
        text(when (d.kind) {
            DevKind.UNKNOWN -> "无法观察：只能依靠你的回答"
            else -> "判定：${d.kind.label}"
        }, 15f, if (d.kind == DevKind.ON_TRACK) C.ACCENT else C.WARN, bold = true)
        val o = c.observation
        val rows = o.apps.entries.sortedByDescending { it.value }.take(4).map { (p, ms) -> "${app.platform.label(p)}  ${Assessor.dur(ms)}" }
        if (rows.isNotEmpty()) text(rows.joinToString("\n"), 14f)
        if (d.kind != DevKind.UNKNOWN && o.idleMs >= 30_000) text("没在用手机  ${Assessor.dur(o.idleMs)}", 14f)
        text("观察窗口 ${huilu.core.Engine.hm(o.from)}–${huilu.core.Engine.hm(o.to)}", 12f, C.MUTED)
    }

    private fun label(k: AnswerKind, c: CheckIn) = when {
        k == AnswerKind.ON_TRACK && c.trigger == Trigger.REST_OVER -> "回去做"
        k == AnswerKind.ON_TRACK && c.trigger == Trigger.DRIFT -> "其实在做"
        else -> k.label
    }

    private fun choose(c: CheckIn, k: AnswerKind) {
        when (k) {
            AnswerKind.SHRINK -> shrink(c)
            AnswerKind.REST, AnswerKind.EXTEND -> {
                val opts = intArrayOf(5, 10, 15)
                AlertDialog.Builder(this).setTitle(if (k == AnswerKind.REST) "休息多久" else "再给多久")
                    .setItems(opts.map { "$it 分钟" }.toTypedArray()) { _, i -> answer(c, k, minutes = opts[i]) }
                    .show()
            }
            else -> answer(c, k)
        }
    }

    private fun shrink(c: CheckIn) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), 0) }
        val what = EditText(this).apply { setText("先只做 2 分钟：${c.actionText}"); textSize = 16f }
        val mins = EditText(this).apply { setText("5"); inputType = InputType.TYPE_CLASS_NUMBER; hint = "分钟" }
        box.addView(what); box.addView(mins)
        AlertDialog.Builder(this).setTitle("改成一个更小、马上能验证的动作").setView(box)
            .setPositiveButton("就这样") { _, _ ->
                answer(c, AnswerKind.SHRINK, minutes = mins.text.toString().toIntOrNull()?.coerceIn(1, 60) ?: 5, newText = what.text.toString())
            }
            .setNegativeButton("取消", null).show()
    }

    private fun answer(c: CheckIn, k: AnswerKind, minutes: Int? = null, newText: String? = null) {
        val target = app.engine.situation.action?.expect?.targetApps?.firstOrNull()
        app.perform(app.engine.answer(c.id, k, via = Via.SCREEN, minutes = minutes, newText = newText))
        app.engine.situation.lastClosed?.decision?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        when {
            k == AnswerKind.DONE || k == AnswerKind.SWITCH || k == AnswerKind.ABANDON ->
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            // "现在回去"：直接把目标 App 打开，回到目标环境的成本降到一次点击
            (k == AnswerKind.RECOMMIT || (k == AnswerKind.ON_TRACK && c.trigger == Trigger.REST_OVER)) && target != null ->
                packageManager.getLaunchIntentForPackage(target)?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
        finish()
    }

    private fun send(c: CheckIn, box: EditText) {
        val t = box.text.toString().trim()
        if (t.isEmpty() || thinking) return
        app.perform(app.engine.note(c.id, t, Via.SCREEN))
        if (app.prefs.llmReady) {
            thinking = true
            render()
            Judge.interpret(app, c, t) {
                thinking = false
                if (app.engine.situation.pending?.id != c.id) finish() else render()
            }
        } else {
            Toast.makeText(this, "已记下。再选一个选项，系统才知道下一步怎么做。", Toast.LENGTH_LONG).show()
            render()
        }
    }

    companion object {
        @Volatile var current: CheckInActivity? = null

        fun intent(ctx: Context, id: String?): Intent = Intent(ctx, CheckInActivity::class.java)
            .putExtra("id", id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    }
}
