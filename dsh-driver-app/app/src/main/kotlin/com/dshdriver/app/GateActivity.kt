package com.dshdriver.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.WindowManager
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
 * 拦截门：无障碍服务拦下娱乐 App 后拉起这里。
 *
 * 流程：答对一道有依据的题 → 给这个包写一段放行时间 → 打开那个 App；放弃 → 回桌面。
 * 出题优先用议程 / 上次结论 / 主线目标兜底，配对了大肥鱼就请它出。
 * 本页 `excludeFromRecents`，所以在最近任务里划不掉——这是和"用外部拦截器"最大的区别。
 */
class GateActivity : Activity() {
    companion object {
        private const val EXTRA_PKG = "pkg"

        /** 拦截页在前台时无障碍不要再拦（否则自己弹自己）。 */
        @Volatile var visible = false

        fun intent(context: Context, pkg: String): Intent =
            Intent(context, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(EXTRA_PKG, pkg)
    }

    private val main = Handler(Looper.getMainLooper())
    private lateinit var ui: Ui
    private lateinit var questionView: TextView
    private lateinit var input: EditText
    private lateinit var feedback: TextView
    private lateinit var submit: Button

    private var question: Gate.Question? = null
    private var target = ""
    private var done = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_PKG).orEmpty()
        if (target.isBlank()) { finish(); return }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vibrate()
        render()
        loadQuestion()
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        super.onPause()
    }

    private fun render() {
        val p = Palette.dark
        ui = Ui(this, p)
        window.statusBarColor = p.bg

        val page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(ui.px(22), ui.px(88), ui.px(22), ui.px(28))
            setBackgroundColor(p.bg)
        }
        ui.reset(page)

        ui.kicker("拦下了")
        ui.headline("要打开${label(target)}，先答一道题", 24f)
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
        ui.quiet("不打开了") { finish() }

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
            store.pairing()?.let { pairing ->
                val req = ComposeRequest(
                    intent = "gate", cog = CogState(Cog.ACTIVE_FLOW, "gate"), die = null,
                    topic = last?.topic.orEmpty(), task = last?.task.orEmpty(), minute = 1, minutes = 1,
                    breakpoint = fallback.anchor, lastQuestion = fallback.text, missing = fallback.missing,
                    recentReplies = emptyList(), asked = recent.takeLast(3),
                    goals = profile.goals.take(3), habits = profile.habits.take(4), patterns = store.history().patterns(),
                    unresolved = agenda.open().take(3).map { it.text },
                )
                val d = runCatching { DshLink.brain(pairing).compose(req) }.getOrNull()
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
                gateLog("question", mapOf("source" to q.source, "text" to q.text, "pkg" to target))
            }
        }.start()
    }

    private fun check() {
        val q = question ?: return
        if (done) return
        val answer = input.text?.toString().orEmpty()
        val v = Gate.judge(answer, q)
        gateLog("answer", mapOf("pass" to v.pass, "why" to v.why, "chars" to v.features.chars, "pkg" to target))
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
        val minutes = Intercept.minutes(this)
        Intercept.allow(this, target, minutes)
        gateLog("pass", mapOf("minutes" to minutes, "pkg" to target))
        launchTarget()
        finish()
    }

    private fun launchTarget() {
        val i = packageManager.getLaunchIntentForPackage(target) ?: return
        runCatching { startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }

    private fun label(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("它")

    private fun vibrate() {
        runCatching {
            getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createOneShot(90, VibrationEffect.DEFAULT_AMPLITUDE))
        }
    }

    private fun gateLog(kind: String, data: Map<String, Any?>) {
        runCatching { Engine.store.appender("gate")(mapOf("at" to System.currentTimeMillis(), "kind" to kind) + data) }
    }
}
