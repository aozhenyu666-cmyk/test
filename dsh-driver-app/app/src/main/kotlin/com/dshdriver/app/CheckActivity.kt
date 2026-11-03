package com.dshdriver.app

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.dshdriver.core.CogState
import com.dshdriver.core.Cog
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
abstract class QuestionActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var questionView: TextView
    private lateinit var feedback: TextView
    private lateinit var input: EditText
    private lateinit var submit: Button
    private var question: Gate.Question? = null
    private var done = false
    protected abstract fun acceptLaunch(): Boolean
    protected abstract fun passed()
    protected open fun giveUp() { finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        if (!acceptLaunch()) { finish(); return }
        render()
        loadQuestion()
    }

    private fun render() {
        val dp = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding((24 * dp).toInt(), (48 * dp).toInt(), (24 * dp).toInt(), (32 * dp).toInt())
            setBackgroundColor(0xFF1B2230.toInt())
        }
        root.addView(TextView(this).apply { text = "要打开它，先答一道题"; setTextColor(0xFFB8C2D9.toInt()); textSize = 14f })
        questionView = TextView(this).apply {
            text = "大肥鱼出题中…"
            setTextColor(0xFFFFFFFF.toInt()); textSize = 21f; setTypeface(typeface, Typeface.BOLD)
            setPadding(0, (12 * dp).toInt(), 0, (20 * dp).toInt())
        }
        root.addView(questionView)
        input = EditText(this).apply {
            hint = "说出依据：因为什么、题干哪句、什么规律"
            setTextColor(0xFFFFFFFF.toInt()); setHintTextColor(0xFF7F8AA3.toInt()); minLines = 3
        }
        root.addView(input)
        feedback = TextView(this).apply { setTextColor(0xFFFFC36B.toInt()); textSize = 14f; setPadding(0, (8 * dp).toInt(), 0, (8 * dp).toInt()) }
        root.addView(feedback)
        submit = Button(this).apply { text = "提交"; isAllCaps = false; textSize = 18f; isEnabled = false; setOnClickListener { check() } }
        root.addView(submit)
        root.addView(Button(this).apply { text = "不打开了"; isAllCaps = false; setOnClickListener { log("give_up"); giveUp() } })
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(root) })
    }

    private fun loadQuestion() {
        Thread {
            val store = Engine.store
            val agenda = store.agenda()
            val last = store.last()
            val profile = store.profile()
            val recent = store.recentGateQuestions()
            val kind = store.nextInteractionKind()
            val fallback = Gate.pick(agenda, last?.conclusion, last?.task, profile, seed = recent.size, kind = kind)
            var q = fallback
            store.pairing()?.let { p ->
                val req = ComposeRequest(
                    intent = "gate", kind = kind, cog = CogState(Cog.ACTIVE_FLOW, "gate"), die = null,
                    topic = last?.topic.orEmpty(), task = last?.task.orEmpty(), minute = 1, minutes = 1,
                    breakpoint = fallback.anchor, lastQuestion = fallback.text, missing = fallback.missing,
                    recentReplies = emptyList(), asked = recent.takeLast(3),
                    goals = profile.goals.take(3), habits = profile.habits.take(4), patterns = store.history().patterns(),
                    unresolved = agenda.open().take(3).map { it.text },
                )
                val d = runCatching { DshLink.brain(p).compose(req) }.getOrNull()
                if (Gate.acceptsLlm(d) && !com.dshdriver.core.Interaction.formulaic(d!!.text) && Similar.maxSimilarity(d.text, recent) < 0.6) {
                    q = Gate.Question(com.dshdriver.core.Interaction.withMaterial(d, fallback.text.substringBefore('\n')).text.take(240), d.anchor ?: fallback.anchor, d.missing ?: fallback.missing, fallback.agendaId, "llm")
                } else {
                    q = Gate.pick(agenda, last?.conclusion, last?.task, profile, seed = recent.size, kind = store.nextInteractionKind())
                }
            }
            runCatching { store.pushGateQuestion(q.text) }
            main.post {
                if (isFinishing || isDestroyed) return@post
                question = q
                questionView.text = q.text
                submit.isEnabled = true
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
        q.agendaId?.let { id ->
            runCatching {
                val agenda = Engine.store.agenda()
                agenda.done(id, System.currentTimeMillis())
                Engine.store.saveAgenda(agenda)
            }
        }
        passed()
    }

    private fun log(kind: String, data: Map<String, Any?> = emptyMap()) {
        runCatching { Engine.store.appender("gate")(mapOf("at" to System.currentTimeMillis(), "kind" to kind) + data) }
    }
}

/** Curbox API v3 compatibility entry; all caller checks stay at this boundary. */
class CheckActivity : QuestionActivity() {
    private var requestId: String? = null
    override fun acceptLaunch(): Boolean {
        val rid = intent.getStringExtra("request_id")
        val caller = callingPackage
        val expected = Curbox.selected(this)
        val valid = intent.action == Curbox.ACTION_CHECK && caller != null &&
            caller in Curbox.installations(this) && (expected == null || caller == expected) &&
            intent.getStringExtra("condition_id") == Curbox.CONDITION_ID &&
            rid != null && rid.isNotEmpty() && rid.length <= 100
        if (valid) requestId = rid
        return valid
    }
    override fun passed() {
        setResult(RESULT_OK, Intent().putExtra("request_id", requestId))
        finish()
    }
}
