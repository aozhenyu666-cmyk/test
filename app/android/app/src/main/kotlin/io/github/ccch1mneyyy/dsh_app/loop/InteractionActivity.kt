package io.github.ccch1mneyyy.dsh_app.loop

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings

/**
 * The interaction page of the first loop: says which app was opened, offers one 返回 button.
 * Its own task (manifest: taskAffinity, excludeFromRecents), so finishing it puts the person back
 * in the test app, and it never touches DSH's main Flutter task.
 *
 * SHOWN means the page is *really on screen*: [onCreate] only builds the view, and the event moves
 * to [EventState.SHOWN] only after the decor view is attached and actually visible. A ROM that
 * lets the activity be created but never draws it (background-activity-start refused, task
 * replaced) therefore stays TRIGGERED, and [InteractionHub]'s watchdog closes it honestly as
 * UNAVAILABLE(not_shown) — instead of leaving a receipt that claims the person saw a page that was
 * never drawn.
 *
 * Every way out closes the event once: button → RETURNED, back → BACK, leaving the screen →
 * DISMISSED, deadline → TIMEOUT. Leaving before the page became visible → UNAVAILABLE(not_shown),
 * because the person was never shown anything. A page whose event is already closed (stale
 * intent) finishes at once.
 */
class InteractionActivity : Activity() {
    private val main = Handler(Looper.getMainLooper())
    private var eventId: String? = null
    private var closed = false

    /** The page is attached, visible, and the engine already has it as SHOWN. */
    private var shownConfirmed = false
    private var timeoutMs = LoopConfig.DEFAULT_TIMEOUT_MS
    private val timeout = Runnable { close(EventResult.TIMEOUT) }

    /** Retries until the page is proven visible; see [checkVisible]. */
    private val proof = Runnable { checkVisible() }
    private var attempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(false)
        // Recreated after process death: recovery already closed that event.
        if (savedInstanceState != null) { closed = true; finish(); return }
        bind(intent)
    }

    /** singleInstance: a start while an older page is still finishing lands here. */
    override fun onNewIntent(next: Intent) {
        super.onNewIntent(next)
        if (!closed) close(EventResult.DISMISSED, finish = false, reason = "replaced")
        setIntent(next)
        bind(next)
    }

    private fun bind(start: Intent) {
        val id = start.getStringExtra(InteractionHub.EXTRA_EVENT)
        // Only a live event (TRIGGERED, or SHOWN by an earlier bind of this same page) may be shown,
        // and asking for its state must not mark it shown — that is the bug this class fixes.
        val state = id?.let { LoopRuntime.engine(this).stateOf(it) }
        if (id == null || (state != EventState.TRIGGERED && state != EventState.SHOWN)) {
            closed = true; finish(); return
        }
        eventId = id
        closed = false
        shownConfirmed = state == EventState.SHOWN
        val pkg = start.getStringExtra(InteractionHub.EXTRA_PACKAGE)
        val label = runCatching { LoopRuntime.appLabel(this, pkg) }.getOrDefault(pkg.orEmpty())
        // The AgentBridge's sentence when there is one (v0's local template, later a real turn);
        // otherwise the page's own sentence. Never blank: EXTRA_TEXT is null on fallback.
        val sentence = start.getStringExtra(InteractionHub.EXTRA_TEXT)?.takeIf { it.isNotBlank() }
        setContentView(content(label, sentence))
        timeoutMs = start.getLongExtra(InteractionHub.EXTRA_TIMEOUT, LoopConfig.DEFAULT_TIMEOUT_MS)
            .coerceIn(5_000, 10 * 60_000L)
        if (shownConfirmed) scheduleTimeout() else awaitVisible()
    }

    /**
     * Prove the page is really visible before calling it SHOWN. "Visible" = the decor view is
     * attached to a window and shown, i.e. the system actually drew it. Retried briefly because
     * the first layout pass has not finished when [onCreate] returns; if it never happens, the
     * watchdog in [InteractionHub] closes the event as UNAVAILABLE(not_shown).
     */
    private fun awaitVisible() {
        attempts = 0
        main.removeCallbacks(proof)
        main.post(proof)
    }

    private fun checkVisible() {
        if (closed || shownConfirmed) return
        val id = eventId ?: return
        val decor = runCatching { window?.decorView }.getOrNull()
        val visible = decor != null && decor.isAttachedToWindow && decor.isShown && decor.width > 0 && decor.height > 0
        if (visible) {
            // The engine decides: false means the event was closed meanwhile (watchdog, config
            // change) and this page is stale.
            if (!LoopRuntime.engine(this).shown(id)) { closed = true; if (!isFinishing) finish(); return }
            shownConfirmed = true
            Log.i(InteractionHub.TAG, "page visible: $id")
            main.removeCallbacks(proof)
            scheduleTimeout()
            return
        }
        if (++attempts <= MAX_PROOF_ATTEMPTS) main.postDelayed(proof, PROOF_STEP_MS)
    }

    private fun scheduleTimeout() {
        main.removeCallbacks(timeout)
        main.postDelayed(timeout, timeoutMs)
    }

    override fun onResume() {
        super.onResume()
        if (!closed && !shownConfirmed) awaitVisible()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus && !closed && !shownConfirmed) awaitVisible()
    }

    private fun content(label: String, sentence: String?): LinearLayout {
        fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), resources.displayMetrics).toInt()
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(20), dp(24), dp(12))
            addView(TextView(this@InteractionActivity).apply {
                text = AppStrings.get(this@InteractionActivity, R.string.loop_page_title)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
            })
            addView(TextView(this@InteractionActivity).apply {
                text = sentence ?: AppStrings.get(this@InteractionActivity, R.string.loop_page_text, label)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setPadding(0, dp(12), 0, dp(16))
            })
            addView(Button(this@InteractionActivity).apply {
                text = AppStrings.get(this@InteractionActivity, R.string.loop_page_return)
                setOnClickListener { close(EventResult.RETURNED) }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.END
            })
        }
    }

    @Deprecated("Activity back handling; targetSdk 28")
    override fun onBackPressed() = close(EventResult.BACK)

    override fun onStop() {
        super.onStop()
        // Home, recents, another app or screen off took the page away without an answer.
        if (closed || isChangingConfigurations) return
        if (shownConfirmed) close(EventResult.DISMISSED) else close(EventResult.UNAVAILABLE, reason = "not_shown")
    }

    override fun onDestroy() {
        main.removeCallbacks(timeout)
        main.removeCallbacks(proof)
        if (!closed && !isChangingConfigurations) {
            if (shownConfirmed) close(EventResult.DISMISSED, finish = false) else close(EventResult.UNAVAILABLE, finish = false, reason = "not_shown")
        }
        super.onDestroy()
    }

    private fun close(result: EventResult, finish: Boolean = true, reason: String? = null) {
        if (closed) return
        closed = true
        main.removeCallbacks(timeout)
        main.removeCallbacks(proof)
        // A page that never became visible was never shown: say so instead of fabricating an answer.
        val notShown = !shownConfirmed && result != EventResult.UNAVAILABLE
        eventId?.let { LoopRuntime.engine(this).answer(it, if (notShown) EventResult.UNAVAILABLE else result, reason ?: if (notShown) "not_shown" else null) }
        if (finish && !isFinishing) finish()
    }

    companion object {
        /** ~3 s of retries: the watchdog in [InteractionHub] fires at the same point. */
        private const val PROOF_STEP_MS = 100L
        private const val MAX_PROOF_ATTEMPTS = 30
    }
}
