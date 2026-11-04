package com.dshdriver.app

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.Editable
import android.text.TextWatcher
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
        private const val STATE_DRAFT = "draft"
        private const val STATE_Q_TEXT = "q_text"
        private const val STATE_Q_ANCHOR = "q_anchor"
        private const val STATE_Q_MISSING = "q_missing"
        private const val STATE_Q_AGENDA = "q_agenda"
        private const val STATE_Q_SOURCE = "q_source"
        private const val STATE_DONE = "done"

        /** 草稿超过这个时间就不再接回来：半小时前没答完的门，重新开一道。 */
        private const val DRAFT_TTL_MS = 30 * 60_000L

        /** 拦截页在前台时无障碍不要再拦（否则自己弹自己）。 */
        @Volatile var visible = false

        /** 最近一次真正拉起拦截页的时间，给 UsageStats 兜底和重复事件去重用。 */
        @Volatile var lastShownAt = 0L

        /**
         * 不要加 FLAG_ACTIVITY_CLEAR_TASK：它会清掉已有任务，把正在答的题和输入一起丢掉。
         * singleTask + 独立 taskAffinity 已经能防止叠出好几层；同包再次被拦只会走 onNewIntent。
         */
        fun intent(context: Context, pkg: String): Intent =
            Intent(context, GateActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
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
    private var draftSaver: Runnable? = null
    /** 关掉/换题时自增，挡住已经排进 worker 的旧草稿写入，避免"答完又被写回来"。 */
    @Volatile private var draftToken = 0
    /** 换包时自增，挡住上一个包迟到的出题结果。 */
    @Volatile private var loadToken = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        target = intent.getStringExtra(EXTRA_PKG).orEmpty()
        if (target.isBlank()) { finish(); return }
        // 已经答完/放弃的门被系统重建时，不要再把旧题显示一遍
        if (savedInstanceState?.getBoolean(STATE_DONE) == true) { finish(); return }
        UsageMonitor.clearBlocked(this)
        visible = true
        lastShownAt = System.currentTimeMillis()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vibrate()
        render()
        val restored = restore(savedInstanceState)
        if (restored != null) showQuestion(restored.first, restored.second) else loadQuestion()
    }

    /**
     * 同一个包再次被拦时只把页面提到前台，保留原题和已输入的内容；
     * 换了一个包才重新出题。
     */
    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent == null) return
        setIntent(intent)
        val incoming = intent.getStringExtra(EXTRA_PKG).orEmpty()
        if (incoming.isBlank() || incoming == target) return
        target = incoming
        draftToken++
        loadToken++
        question = null
        done = false
        lastShownAt = System.currentTimeMillis()
        UsageMonitor.clearBlocked(this)
        render()
        loadQuestion()
    }

    override fun onResume() {
        super.onResume()
        visible = true
    }

    override fun onPause() {
        visible = false
        persistDraft()
        super.onPause()
    }

    override fun onDestroy() {
        visible = false
        draftSaver?.let(main::removeCallbacks)
        if (!done) persistDraft()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(EXTRA_PKG, target)
        outState.putBoolean(STATE_DONE, done)
        outState.putString(STATE_DRAFT, input.text?.toString().orEmpty())
        question?.let { q ->
            outState.putString(STATE_Q_TEXT, q.text)
            outState.putString(STATE_Q_ANCHOR, q.anchor)
            outState.putString(STATE_Q_MISSING, q.missing)
            outState.putString(STATE_Q_AGENDA, q.agendaId)
            outState.putString(STATE_Q_SOURCE, q.source)
        }
    }

    private fun restore(state: Bundle?): Pair<Gate.Question, String>? {
        if (state != null) {
            val text = state.getString(STATE_Q_TEXT)?.takeIf { it.isNotBlank() }
            if (text != null && state.getString(EXTRA_PKG) == target) {
                return Gate.Question(
                    text,
                    state.getString(STATE_Q_ANCHOR),
                    state.getString(STATE_Q_MISSING),
                    state.getString(STATE_Q_AGENDA),
                    state.getString(STATE_Q_SOURCE, "agenda"),
                ) to state.getString(STATE_DRAFT).orEmpty()
            }
        }
        val d = Engine.store.gateDraft() ?: return null
        val now = System.currentTimeMillis()
        if (d.target != target || now - d.at > DRAFT_TTL_MS) {
            runCatching { Engine.store.clearGateDraft() }
            return null
        }
        return Gate.Question(d.text, d.anchor, d.missing, d.agendaId, d.source) to d.draft
    }

    private fun showQuestion(q: Gate.Question, draft: String) {
        question = q
        questionView.text = q.text
        input.setText(draft)
        input.setSelection(input.text?.length ?: 0)
        submit.isEnabled = true
        submit.alpha = 1f
        persistDraft()
    }

    /** 把当前题和草稿落盘，进程被系统杀掉后还能接回来。写盘放到 worker，别卡主线程。 */
    private fun persistDraft() {
        val q = question ?: return
        if (done) return
        val row = Store.GateDraft(
            target = target,
            text = q.text,
            anchor = q.anchor,
            missing = q.missing,
            agendaId = q.agendaId,
            source = q.source,
            draft = input.text?.toString().orEmpty(),
            at = System.currentTimeMillis(),
        )
        val token = draftToken
        Engine.background { if (token == draftToken) runCatching { Engine.store.saveGateDraft(row) } }
    }

    /** 关闭这道门：先让旧草稿写入失效，再删盘上的草稿。 */
    private fun closeDraft() {
        draftToken++
        runCatching { Engine.store.clearGateDraft() }
    }

    private fun scheduleDraftSave() {
        val q = question ?: return
        if (done) return
        draftSaver?.let(main::removeCallbacks)
        val r = Runnable { persistDraft() }
        draftSaver = r
        main.postDelayed(r, 600)
    }

    private fun abandon() {
        done = true
        closeDraft()
        finish()
    }

    @Deprecated("Activity API")
    override fun onBackPressed() {
        // 返回键等同于"不打开了"：清掉草稿，别让下一次开门接着旧题
        abandon()
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
        input = ui.field("写下你的依据", multiline = true, lines = 3, noPaste = true)
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = scheduleDraftSave()
        })
        feedback = ui.text("", 13f, p.warn, top = 6f, bottom = 0f)
        submit = ui.primary("提交") { check() }.also {
            it.isEnabled = false
            it.alpha = 0.45f
        }
        ui.quiet("不打开了") { abandon() }

        setContentView(ScrollView(this).apply { isFillViewport = true; addView(page) })
    }

    private fun loadQuestion() {
        val token = ++loadToken
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
                // 已经换包/恢复到一道题/已经答完时，别让迟到的模型结果把题换掉
                if (token != loadToken || isFinishing || question != null || done) return@post
                showQuestion(q, "")
                gateLog("question", mapOf("source" to q.source, "text" to q.text, "pkg" to target))
            }
        }.start()
    }

    private fun check() {
        val q = question ?: return
        if (done) return
        val answer = input.text?.toString().orEmpty()
        val v = Gate.judge(answer, q, Engine.store.blocking().minChars)
        gateLog("answer", mapOf("judged" to v.pass, "why" to v.why, "chars" to v.features.chars, "pkg" to target))
        if (!v.pass) {
            feedback.text = v.why
            persistDraft()
            return
        }
        val minutes = Intercept.minutes(this)
        val refusal = Intercept.allow(this, target, minutes)
        if (refusal != null) {
            feedback.text = refusal
            persistDraft()
            gateLog("refused", mapOf("why" to refusal, "pkg" to target))
            return
        }
        done = true
        draftSaver?.let(main::removeCallbacks)
        closeDraft()
        submit.isEnabled = false
        submit.alpha = 0.45f
        q.agendaId?.let { id ->
            runCatching {
                val agenda = Engine.store.agenda()
                agenda.done(id, System.currentTimeMillis())
                Engine.store.saveAgenda(agenda)
            }
        }
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
