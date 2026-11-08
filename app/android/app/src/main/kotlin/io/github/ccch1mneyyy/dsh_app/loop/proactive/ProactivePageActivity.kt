package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.TypedValue
import android.view.Gravity
import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.loop.core.SurfaceReport
import io.github.ccch1mneyyy.dsh_app.loop.core.VisibleSurface

/**
 * The proactive page. It knows only its token: every report carries it, and the coordinator refuses
 * any token that is not the current presentation, so a page left over from a withdrawn, restarted
 * or replaced presentation can never answer a newer event.
 *
 * Reports: page really visible → Shown; 回应 → Answered("reply"); 我看到了 → Acked;
 * 稍后 / back / leaving → Dismissed.
 * Screen off is not an answer: the page stays and the coordinator's deadline decides.
 *
 * 「我看到了」 is an *acknowledgement*, not an answer: the person confirms they read the message, and
 * the kernel's reply window (20 min, 60 s in a debug build) is what turns that into "answered" or
 * "ignored". The page never holds that policy and never sends a timestamp of its own — only the
 * token. That is why the third button reports a bare [SurfaceReport.Acked].
 *
 * **`onResume` alone is not "Shown".** The coordinator treats Shown as "the person had this on
 * screen" and feeds it into the scheduler's ignored-message backoff, so claiming it for a page
 * nobody saw would corrupt the schedule. An activity can be resumed without being drawn — started
 * behind the lock screen, covered at once by another activity, or on a ROM that lets it be created
 * but never brought forward (the same restriction that makes background activity starts so
 * confusing). Shown is therefore only reported once the decor view is attached, shown, laid out
 * *and* focused; until then the page has merely been posted, and leaving it reports Dismissed.
 */
class ProactivePageActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var token: String? = null
    private var reported = false

    /** Shown was reported: the page is proven on screen, not merely resumed. */
    private var shown = false

    /** The ack was sent; the page stays open so an answer is still possible. */
    private var acked = false

    /** He typed back at least once: this page is a conversation now, not a notice. */
    private var chatted = false
    private var waiting = false
    private var transcript: TextView? = null
    private var scroller: ScrollView? = null
    private var input: EditText? = null
    private var sendButton: Button? = null
    private var attempts = 0
    private val proof = Runnable { proveVisible() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        if (savedInstanceState != null) { finish(); return } // restored by the system: the event is gone
        bind(intent)
    }

    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        // singleInstance: a newer presentation replaces this one; the old token simply goes stale.
        unregister()
        reported = false
        shown = false
        acked = false
        chatted = false
        waiting = false
        setIntent(next)
        bind(next)
    }

    private fun bind(start: Intent) {
        val t = start.getStringExtra(EXTRA_TOKEN)
        val text = start.getStringExtra(EXTRA_TEXT)
        if (t == null || text == null || start.getLongExtra(EXTRA_DEADLINE, 0) <= System.currentTimeMillis()) {
            finish(); return
        }
        token = t
        live[t] = this
        // 双人格的〔名字〕标记：兜底页面上显示成"名字：正文"。
        val (who, body) = ProactiveOverlay.splitSpeaker(text)
        setContentView(content(if (who != null) "$who：$body" else text))
        awaitVisible()
    }

    override fun onResume() {
        super.onResume()
        if (!reported && !shown) awaitVisible()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !reported && !shown) awaitVisible()
    }

    /**
     * Report Shown only when the page is provably on screen. Retried briefly, because the first
     * layout pass has not finished when [onResume] returns.
     */
    private fun awaitVisible() {
        attempts = 0
        main.removeCallbacks(proof)
        main.post(proof)
    }

    private fun proveVisible() {
        val t = token ?: return
        if (reported || shown) return
        val decor = runCatching { window?.decorView }.getOrNull()
        val visible = decor != null && decor.isAttachedToWindow && decor.isShown &&
            decor.width > 0 && decor.height > 0 && decor.hasWindowFocus()
        if (visible) {
            shown = true
            main.removeCallbacks(proof)
            ProactiveRuntime.onSurface(t, SurfaceReport.Shown)
            return
        }
        if (++attempts <= MAX_PROOF_ATTEMPTS) main.postDelayed(proof, PROOF_STEP_MS)
    }

    @Deprecated("Activity back handling; targetSdk 28")
    override fun onBackPressed() = report(SurfaceReport.Dismissed)

    override fun onStop() {
        super.onStop()
        val screenOn = getSystemService(PowerManager::class.java)?.isInteractive != false
        if (!reported && !isChangingConfigurations && screenOn && !isFinishing) report(SurfaceReport.Dismissed)
        // A finished conversation must not linger in the background holding the visible-surface slot.
        else if (chatted && !isChangingConfigurations && screenOn && !isFinishing) finish()
    }

    override fun onDestroy() {
        main.removeCallbacks(proof)
        // Free the shared visible-surface slot for this token. Withdraw already does it; this covers
        // the page dying on its own (deadline, system kill, back press) with no command behind it.
        VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, token)
        unregister()
        super.onDestroy()
    }

    private fun report(r: SurfaceReport) {
        val t = token ?: return
        if (reported) return
        reported = true
        main.removeCallbacks(proof)
        ProactiveRuntime.onSurface(t, r)
        if (!isFinishing) finish()
    }

    /**
     * 「我看到了」 is the one report that does **not** close the page: the person confirmed they read
     * it, and may still answer within the kernel's reply window. The page stays up, the ack is sent
     * once, and a later 回应 / 稍后 / back still closes the event normally.
     */
    private fun acknowledge() {
        val t = token ?: return
        if (reported || acked) return
        acked = true
        ProactiveRuntime.onSurface(t, SurfaceReport.Acked)
    }

    /**
     * 直接跟大肥鱼聊: the line goes into his session as the person's own words (companion /say),
     * the reply comes back here. The first line already counts as answering the event, so the kernel
     * closes it as RESPONDED; the page itself stays open as a conversation until he leaves it.
     */
    private fun send() {
        val box = input ?: return
        val text = box.text?.toString()?.trim().orEmpty()
        if (text.isEmpty() || waiting) return
        box.setText("")
        append("你：$text")
        if (!chatted) {
            chatted = true
            val t = token
            if (t != null && !reported) {
                reported = true
                main.removeCallbacks(proof)
                ProactiveRuntime.onSurface(t, SurfaceReport.Answered("chat"))
            }
        }
        setWaiting(true)
        ProactiveRuntime.say(text) { reply, error ->
            if (isFinishing || isDestroyed) return@say
            setWaiting(false)
            append(
                when {
                    !reply.isNullOrBlank() -> ProactiveOverlay.splitSpeaker(reply).let { (w, b) -> "${w ?: "大肥鱼"}：$b" }
                    error == "agent-busy" || error == "desk-busy" -> "（大肥鱼还在说上一句，等一下再发）"
                    else -> "（没发出去：${error ?: "unknown"}）"
                },
            )
        }
    }

    private fun setWaiting(on: Boolean) {
        waiting = on
        sendButton?.isEnabled = !on
        sendButton?.text = if (on) "…" else "发送"
    }

    private fun append(line: String) {
        val view = transcript ?: return
        view.append(if (view.text.isNullOrEmpty()) line else "\n\n$line")
        scroller?.post { scroller?.fullScroll(ScrollView.FOCUS_DOWN) }
    }

    private fun unregister() { token?.let { if (live[it] === this) live.remove(it) } }

    private fun content(text: String): LinearLayout {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        val ctx = this
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
            // The ball and everything said after it, in one scrolling column.
            val log = TextView(ctx).apply { this.text = text; setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f) }
            transcript = log
            addView(ScrollView(ctx).apply {
                scroller = this
                addView(log)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(12)
            })
            // 直接回他：打字就是在跟大肥鱼聊，不用切回 App。
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                val box = EditText(ctx).apply {
                    hint = "跟大肥鱼说…"
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    maxLines = 4
                    imeOptions = EditorInfo.IME_ACTION_SEND
                    setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEND) { send(); true } else false }
                }
                input = box
                addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(Button(ctx).apply {
                    sendButton = this
                    this.text = "发送"
                    setOnClickListener { send() }
                })
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = dp(8)
            })
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                addView(Button(ctx).apply {
                    this.text = AppStrings.get(ctx, R.string.proactive_later)
                    // After a conversation this just closes the window: the event is already answered.
                    setOnClickListener { if (chatted) finish() else report(SurfaceReport.Dismissed) }
                })
                // 「我看到了」: confirms attention without answering. The kernel's reply window then
                // decides whether silence becomes "ignored"; this page knows nothing about that.
                addView(Button(ctx).apply {
                    this.text = AppStrings.get(ctx, R.string.proactive_ack)
                    setOnClickListener { acknowledge() }
                })
                addView(Button(ctx).apply {
                    this.text = AppStrings.get(ctx, R.string.proactive_reply)
                    setOnClickListener { report(SurfaceReport.Answered("reply")) }
                })
            })
        }
    }

    companion object {
        private const val EXTRA_TOKEN = "proactive_token"
        private const val EXTRA_TEXT = "proactive_text"
        private const val EXTRA_DEADLINE = "proactive_deadline"

        /** ~2 s of retries for the visibility proof. */
        private const val PROOF_STEP_MS = 100L
        private const val MAX_PROOF_ATTEMPTS = 20

        /** Pages on screen by token, for Withdraw. Main thread only. */
        private val live = HashMap<String, ProactivePageActivity>()

        /** Started from the accessibility service's process; false if the system refused at once. */
        fun start(context: Context, token: String, text: String, deadline: Long): Boolean = runCatching {
            context.startActivity(
                Intent(context, ProactivePageActivity::class.java)
                    .putExtra(EXTRA_TOKEN, token).putExtra(EXTRA_TEXT, text.take(2_000)).putExtra(EXTRA_DEADLINE, deadline)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            )
        }.isSuccess

        fun withdraw(token: String) { live.remove(token)?.let { it.reported = true; it.finish() } }

        fun withdrawAll() { live.values.toList().forEach { it.reported = true; it.finish() }; live.clear() }
    }
}
