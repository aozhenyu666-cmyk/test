package com.dshdriver.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dshdriver.core.Cog
import com.dshdriver.core.CogState
import com.dshdriver.core.ComposeRequest
import com.dshdriver.core.Gate
import com.dshdriver.core.Similar

/**
 * Curbox 拦下娱乐 App 后，用户在 Curbox 警告页里选"需要另一个 App"，Curbox 就以 for-result 方式拉起这里。
 *
 * 契约（Curbox API v3，CURBOX_API.md「Request and response contract」）：
 *   收：action = CHECK_UNLOCK_CONDITION，extra condition_id、request_id（一次性，10 分钟内有效）
 *   放行：setResult(RESULT_OK, Intent().putExtra("request_id", 原样))
 *   不放行：RESULT_CANCELED（默认）
 * 放行多久、什么时候重拦，全由 Curbox 决定；这里只管出题和判卷。
 *
 * 出题：配对了大肥鱼就请它出（8 秒内），否则用议程 / 上次结论 / 主线目标兜底（[Gate.pick]）。
 * 判卷：只认"有依据"的回答（[Gate.judge]）。
 */
class CheckActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var questionView: TextView
    private lateinit var feedback: TextView
    private lateinit var input: EditText
    private lateinit var submit: Button
    private var question: Gate.Question? = null
    private var requestId: String? = null
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)

        val conditionId = intent.getStringExtra("condition_id")
        val rid = intent.getStringExtra("request_id")
        val caller = callingPackage
        val curboxes = Curbox.installations(this)
        val expected = Curbox.selected(this)
        val valid = intent.action == Curbox.ACTION_CHECK &&
            caller != null && caller in curboxes && (expected == null || caller == expected) &&
            conditionId == Curbox.CONDITION_ID &&
            rid != null && rid.isNotEmpty() && rid.length <= 100
        log("open", mapOf("valid" to valid, "caller" to caller))
        if (!valid) { finish(); return }
        requestId = rid

        render()
        loadQuestion()
    }

    private fun render() {
        val p = Palette.dark
        ui = Ui(this, p)
        window.statusBarColor = p.bg

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            // 靠上一点：拦截 / 提醒页是要立刻读的，居中的话上半屏会空掉
            setPadding(ui.px(22), ui.px(88), ui.px(22), ui.px(28))
            setBackgroundColor(p.bg)
        }
        ui.reset(page)

        ui.kicker("拦截中")
        ui.headline("答一道题再进", 24f)
        ui.lead("说依据就行：因为什么、题干哪一句、什么规律。")

        ui.card(muted = true) {
            ui.kicker("这一问")
            questionView = ui.text("正在出题…", 17f, p.text, top = 2f, bottom = 0f)
        }

        ui.spacer(14f)
        input = ui.field("写下你的依据", multiline = true, lines = 3)
        feedback = ui.text("", 13f, p.warn, top = 6f, bottom = 0f)
        submit = ui.primary("提交") { check() }.also {
            it.isEnabled = false
            it.alpha = 0.45f
        }
        ui.quiet("不打开了") { log("give_up"); finish() }

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
    }

    private fun loadQuestion() {
        Thread {
            val store = Engine.store
            val agenda = store.agenda()
            val last = store.last()
            val profile = store.profile()
            val recent = store.recentGateQuestions()
            val fallback = Gate.pick(agenda, last?.conclusion, last?.task, profile, seed = recent.size)
            var q = fallback
            store.pairing()?.let { p ->
                val req = ComposeRequest(
                    intent = "gate", cog = CogState(Cog.ACTIVE_FLOW, "gate"), die = null,
                    topic = last?.topic.orEmpty(), task = last?.task.orEmpty(), minute = 1, minutes = 1,
                    breakpoint = fallback.anchor, lastQuestion = fallback.text, missing = fallback.missing,
                    recentReplies = emptyList(), asked = recent.takeLast(3),
                    goals = profile.goals.take(3), habits = profile.habits.take(4), patterns = store.history().patterns(),
                    unresolved = agenda.open().take(3).map { it.text },
                )
                val d = runCatching { DshLink.brain(p).compose(req) }.getOrNull()
                if (Gate.acceptsLlm(d) && Similar.maxSimilarity(d!!.text, recent) < 0.6) {
                    q = Gate.Question(d.text.take(80), d.anchor ?: fallback.anchor, d.missing ?: fallback.missing, fallback.agendaId, "llm")
                }
            }
            runCatching { store.pushGateQuestion(q.text) }
            main.post {
                if (isFinishing) return@post
                question = q
                questionView.text = q.text
                submit.isEnabled = true
                submit.alpha = 1f
                log("question", mapOf("source" to q.source, "text" to q.text))
            }
        }.start()
    }

    private fun check() {
        val q = question ?: return
        if (done) return
        val answer = input.text?.toString().orEmpty()
        val v = Gate.judge(answer, q)
        log("answer", mapOf("pass" to v.pass, "why" to v.why, "chars" to v.features.chars))
        if (!v.pass) {
            feedback.text = v.why
            return
        }
        done = true
        submit.isEnabled = false
        submit.alpha = 0.45f
        q.agendaId?.let { id ->
            runCatching {
                val agenda = Engine.store.agenda()
                agenda.done(id, System.currentTimeMillis())
                Engine.store.saveAgenda(agenda)
            }
        }
        setResult(RESULT_OK, Intent().putExtra("request_id", requestId))
        finish()
    }

    private fun log(kind: String, data: Map<String, Any?> = emptyMap()) {
        runCatching { Engine.store.appender("gate")(mapOf("at" to System.currentTimeMillis(), "kind" to kind) + data) }
    }
}
