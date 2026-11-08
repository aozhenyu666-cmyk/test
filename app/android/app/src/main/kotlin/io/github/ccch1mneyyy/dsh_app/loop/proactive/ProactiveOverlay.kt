package io.github.ccch1mneyyy.dsh_app.loop.proactive

import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import io.github.ccch1mneyyy.dsh_app.R
import io.github.ccch1mneyyy.dsh_app.i18n.AppStrings
import io.github.ccch1mneyyy.dsh_app.loop.core.SurfaceReport
import io.github.ccch1mneyyy.dsh_app.loop.core.VisibleSurface
import io.github.ccch1mneyyy.dsh_app.pet.OverlayPalette
import io.github.ccch1mneyyy.dsh_app.pet.dp
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService

/**
 * 主动发球的悬浮窗 (P1).
 *
 * The old page was a Dialog-themed Activity: switching apps put it in onStop, which the runtime read
 * as "he swiped it away" and taught the scheduler the wrong lesson. A window added by the
 * accessibility service (or, without it, by the overlay permission) is not tied to any activity, so
 * switching apps leaves it alone.
 *
 * Visual language: [OverlayPalette] is the app's 雾蓝 surfaces/ink on the native side (the pet
 * bubble and 问一句 use the same one), and the metrics below mirror `pet/AskBar.kt` — 18 dp card,
 * 14 dp fields, a 40 dp circular send key — so this window looks like part of DSH Go, not a
 * separate dialog.
 *
 * Rules from the task book:
 *  - only 收起 / a reply close it; switching apps reports nothing;
 *  - the first line sent counts as `Answered("chat")`, 收起 counts as `Dismissed`;
 *  - after the 20-minute reply window it shrinks to a bubble, not disappears;
 *  - the Activity version stays as the fallback when no window can be added.
 *
 * Main thread only, like [ProactiveRuntime].
 */
object ProactiveOverlay {
    private const val TAG = "DshProactive"
    private const val MAX_TEXT = 4_000
    private val main = Handler(Looper.getMainLooper())
    private var app: Context? = null
    private val live = LinkedHashMap<String, Card>()

    fun install(context: Context) {
        app = context.applicationContext
    }

    /** Adds the window; false means "no host" and the caller should fall back to the Activity. */
    fun show(token: String, text: String, deadline: Long, why: String? = null): Boolean {
        val context = app ?: return false
        // He is chatting in the window that is up: the new ball joins that conversation instead of
        // wiping it (it used to replace the window, so "回一下就没了").
        val chatting = live.values.lastOrNull()?.takeIf { it.chatting() }
        if (chatting != null) {
            live.values.filter { it !== chatting }.forEach { it.destroy() }
            live.clear()
            chatting.adopt(token, text)
            live[token] = chatting
            return true
        }
        // One visible surface: a new ball replaces whatever was left of the previous one.
        live.values.toList().forEach { it.destroy() }
        live.clear()
        val host = host(context) ?: return false
        val card = Card(context, host.first, host.second, token, deadline)
        return try {
            card.attach(text, why)
            live[token] = card
            true
        } catch (e: Exception) {
            android.util.Log.i(TAG, "proactive overlay add failed: ${e.javaClass.simpleName}")
            // Not shown: the runtime still owns the visible-surface slot and decides the fallback.
            card.abandon()
            false
        }
    }

    fun withdraw(token: String) {
        // The coordinator closed the *event* (answered / its own timer); the ball itself is the
        // person's to close. Free the shared surface slot, keep the window (it shrinks to a bubble
        // later) — a new presentation replaces it in show().
        live[token]?.releaseForNow()
    }

    fun withdrawAll() {
        live.values.toList().forEach { it.destroy() }
        live.clear()
    }

    fun isShowing(token: String) = live.containsKey(token)

    /**
     * 执法优先（C1）: a lock page is about to take the screen. Every open card folds into its bubble,
     * which never takes focus, so the lock page can prove it is on screen. Nothing is lost: the
     * conversation stays and the bubble reopens it. Also used when the card had not been answered
     * yet - folding is not closing, the event is still waiting for his reply.
     */
    fun yieldForLock() {
        live.values.toList().forEach { it.foldForLock() }
    }

    /** The accessibility service's own window context when it is on; else the overlay permission. */
    private fun host(context: Context): Pair<Context, Int>? {
        val service = PhoneA11yService.instance
        if (service != null) {
            runCatching { return service.overlayWindowContext() to WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY }
                .onFailure { android.util.Log.i(TAG, "a11y overlay context: ${it.javaClass.simpleName}") }
        }
        if (!Settings.canDrawOverlays(context)) return null
        val ctx = if (Build.VERSION.SDK_INT >= 30) {
            context.createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
        } else {
            context
        }
        return ctx to WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
    }

    private class Card(
        private val app: Context,
        private val context: Context,
        private val type: Int,
        /** The coordinator's token; a new ball that joins this window's conversation takes it over. */
        private var token: String,
        /** The coordinator's presentation deadline; kept for reference, the window lives 20 min. */
        private val deadline: Long,
    ) {
        private val palette = OverlayPalette.of(context)
        private val wm = context.getSystemService(WindowManager::class.java)
        private var root: View? = null
        private var params: WindowManager.LayoutParams? = null
        private var transcript: TextView? = null
        private var scroller: ScrollView? = null
        private var input: EditText? = null
        private var send: ImageView? = null
        private var statusView: TextView? = null
        private var waiting = false
        /** He answered the current event (reported once per event). */
        private var chatted = false
        /** He has typed in this window at all; it stays a conversation across new balls. */
        private var talked = false
        /** Shown is reported once; it must not consume the later Answered/Dismissed report. */
        private var shownReported = false
        private var closedReported = false
        private var collapsed = false
        private var bubble: View? = null
        private var lastText = ""
        /** Who is speaking (鱼丸 / 鲸澜): the plugin tags each line with 〔名字〕. Null = single persona. */
        private var speaker: String? = null
        private var lastWhy: String? = null
        private val slow = Runnable { setStatus(AppStrings.get(app, R.string.proactive_overlay_slow_model)) }
        private val shrink = Runnable { collapse() }

        fun attach(text: String, why: String?) {
            val (who, body) = splitSpeaker(text)
            speaker = who
            lastText = body.take(MAX_TEXT)
            lastWhy = why
            val view = backAware(expanded(lastText, why))
            val p = windowParams()
            root = view
            params = p
            wm!!.addView(view, p)
            main.postDelayed({ proveShown() }, 120L)
            // 回应窗口：20 分钟没动静就收成气泡（不删掉）。coordinator 自己的 60 秒时限只关事件，
            // 不关这个窗口——关闭它是人点「收起」或回复之后的事。
            main.postDelayed(shrink, COLLAPSE_AFTER_MS)
        }

        /** He has talked in this window and it is still there (open or as a bubble). */
        fun chatting(): Boolean = talked && (root != null || bubble != null)

        /** A new ball while he is chatting here: same window, next line, new event. */
        fun adopt(newToken: String, text: String) {
            token = newToken
            shownReported = false
            closedReported = false
            // The next line he sends answers this new event.
            chatted = false
            val (who, body) = splitSpeaker(text)
            append(if (who != null) "$who：$body" else body)
            main.removeCallbacks(shrink)
            if (collapsed) expand() else main.postDelayed(shrink, COLLAPSE_AFTER_MS)
            main.postDelayed({ proveShown() }, 120L)
        }

        private fun proveShown() {
            val v = root ?: return
            if (!v.isAttachedToWindow || !v.isShown || collapsed) return
            report(SurfaceReport.Shown)
        }

        // ---- views -----------------------------------------------------------------------------

        private fun expanded(text: String, why: String?): View {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = context.dp(12f)
                setPadding(pad, context.dp(10f), pad, pad)
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(18f).toFloat()
                    setColor(palette.fill)
                    setStroke(context.dp(1f).coerceAtLeast(1), palette.stroke)
                }
            }
            card.addView(TextView(context).apply {
                this.text = speaker ?: AppStrings.get(app, R.string.proactive_overlay_from)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(palette.muted)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            if (!why.isNullOrBlank()) {
                card.addView(TextView(context).apply {
                    this.text = why
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.muted)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, context.dp(2f), 0, 0)
                })
            }

            val log = TextView(context).apply {
                this.text = text
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(palette.ink)
                setLineSpacing(0f, 1.2f)
            }
            transcript = log
            card.addView(ScrollView(context).apply {
                scroller = this
                addView(log)
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(190f)).apply {
                topMargin = context.dp(6f)
                bottomMargin = context.dp(8f)
            })

            val status = TextView(context).apply {
                this.text = ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(palette.muted)
                visibility = View.GONE
            }
            statusView = status
            card.addView(status, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(6f)
            })

            val row = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.BOTTOM
            }
            val box = EditText(context).apply {
                hint = AppStrings.get(app, R.string.proactive_overlay_hint)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(palette.ink)
                setHintTextColor(palette.muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                imeOptions = EditorInfo.IME_ACTION_SEND or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                maxLines = 4
                setPadding(context.dp(12f), context.dp(9f), context.dp(12f), context.dp(9f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(14f).toFloat()
                    setColor(palette.pressed)
                }
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_SEND) { sendNow(); true } else false
                }
                // 窗口平时不可聚焦，点进输入框这一下才切成可聚焦并弹键盘。
                setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_DOWN && !typing) setTyping(true)
                    false
                }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                    override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                    override fun afterTextChanged(s: Editable?) = refreshSendKey()
                })
            }
            input = box
            row.addView(box, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val key = ImageView(context).apply {
                setImageResource(R.drawable.ic_pet_send)
                scaleType = ImageView.ScaleType.CENTER
                contentDescription = AppStrings.get(app, R.string.pet_ask_send)
                isClickable = true
                isFocusable = true
                setOnClickListener { sendNow() }
            }
            send = key
            row.addView(key, LinearLayout.LayoutParams(context.dp(40f), context.dp(40f)).apply {
                marginStart = context.dp(8f)
            })
            card.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                topMargin = context.dp(6f)
            })
            refreshSendKey()

            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, context.dp(4f), 0, 0)
            }
            // 在开会 / 上课：一键「在忙，晚点回」（C4）。窗口收起，但**不算回过话、不开安静期**：
            // 插件只记一笔 busy；关窗后照常复查，5 分钟里在娱乐 App 累计 15 秒就算违抗。
            actions.addView(textAction("在忙，晚点回") { // l10n:keep companion overlay quick reply
                if (waiting) return@textAction
                close(SurfaceReport.Dismissed, busy = true)
            })
            // 不许装看不见：没回过一句，「别烦我」「收起」都不放他走（他要的：总得说几句）。
            actions.addView(textAction(AppStrings.get(app, R.string.proactive_overlay_mute)) {
                if (!mayLeave()) return@textAction
                ProactiveRuntime.muteFor(60 * 60_000L)
                close(SurfaceReport.Dismissed, muted = true)
            })
            actions.addView(textAction(AppStrings.get(app, R.string.proactive_overlay_collapse)) {
                if (!mayLeave()) return@textAction
                close(SurfaceReport.Dismissed)
            })
            card.addView(actions)
            return card
        }

        private fun textAction(label: String, onTap: () -> Unit) = TextView(context).apply {
            text = label
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            setTextColor(palette.muted)
            setPadding(context.dp(10f), context.dp(8f), context.dp(10f), context.dp(8f))
            isClickable = true
            setOnClickListener { onTap() }
        }

        private fun refreshSendKey() {
            val key = send ?: return
            val can = !waiting && !input?.text.isNullOrBlank()
            key.isEnabled = can
            key.alpha = if (can) 1f else 0.4f
            key.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(if (can) palette.accent else palette.pressed)
            }
            key.imageTintList = ColorStateList.valueOf(if (can) palette.onAccent else palette.muted)
        }

        /** A window of our own does not resize with the keyboard on every ROM; lift it ourselves. */
        private fun trackIme(view: View) {
            if (Build.VERSION.SDK_INT < 30) return
            view.setOnApplyWindowInsetsListener { _, insets ->
                val ime = insets.getInsets(WindowInsets.Type.ime()).bottom
                val p = params
                val w = wm
                val v = root
                if (p != null && w != null && v != null) {
                    val y = context.dp(16f) + ime
                    if (p.y != y) {
                        p.y = y
                        runCatching { w.updateViewLayout(v, p) }
                    }
                }
                insets
            }
        }

        private fun windowParams() = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            // 默认不抢焦点：返回键、游戏里的按键照常给下面的 App。点输入框才拿焦点（setTyping）。
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            title = AppStrings.get(app, R.string.proactive_overlay_title)
            // 不让系统按键盘挪窗口，也不自己跟着键盘高度挪：两边一起动会来回抖（真机：点输入框疯狂闪）。
            // 打字时整张卡片移到屏幕上方（setTyping），键盘在下方，谁也不挡谁。
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            y = context.dp(16f)
            if (Build.VERSION.SDK_INT >= 28) layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
            if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
            val maxW = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
            width = minOf(maxW, context.dp(420f))
        }

        // ---- focus ------------------------------------------------------------------------------

        /** 正在打字：窗口可聚焦、键盘在。否则窗口不可聚焦，按键全归下面的 App。 */
        private var typing = false

        private fun setTyping(on: Boolean) {
            val v = root ?: return
            val p = params ?: return
            if (typing == on) return
            typing = on
            p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            // 打字：贴到屏幕上方，避开键盘；不打字：回到底部。只在切换时动一次，不跟着键盘高度来回挪。
            p.gravity = (if (on) Gravity.TOP else Gravity.BOTTOM) or Gravity.CENTER_HORIZONTAL
            p.y = context.dp(if (on) 40f else 16f)
            runCatching { wm?.updateViewLayout(v, p) }
            val imm = context.getSystemService(InputMethodManager::class.java)
            val box = input ?: return
            if (on) {
                box.requestFocus()
                box.post { imm?.showSoftInput(box, InputMethodManager.SHOW_IMPLICIT) }
            } else {
                imm?.hideSoftInputFromWindow(box.windowToken, 0)
                box.clearFocus()
            }
        }

        /** 打字时按返回：收起键盘、把焦点还给下面的 App，卡片留着。 */
        private fun backAware(content: View): View = object : FrameLayout(context) {
            override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                    if (event.action == KeyEvent.ACTION_UP) setTyping(false)
                    return true
                }
                return super.dispatchKeyEvent(event)
            }
        }.apply { addView(content) }

        // ---- conversation ----------------------------------------------------------------------

        private fun sendNow() {
            val box = input ?: return
            val text = box.text?.toString()?.trim().orEmpty()
            if (text.isEmpty() || waiting) return
            box.setText("")
            append(AppStrings.get(app, R.string.proactive_overlay_you, text))
            talked = true
            if (!chatted) {
                chatted = true
                report(SurfaceReport.Answered("chat"))
            }
            waiting = true
            refreshSendKey()
            setStatus(AppStrings.get(app, R.string.proactive_overlay_thinking))
            main.postDelayed(slow, 10_000L)
            ProactiveRuntime.say(text) { reply, error ->
                main.removeCallbacks(slow)
                if (root == null) return@say
                waiting = false
                refreshSendKey()
                when {
                    !reply.isNullOrBlank() -> {
                        setStatus(null)
                        val (who, body) = splitSpeaker(reply)
                        append(if (who != null) "$who：$body" else AppStrings.get(app, R.string.proactive_overlay_reply, reply))
                    }
                    error == "agent-busy" || error == "desk-busy" -> {
                        setStatus(null)
                        append(AppStrings.get(app, R.string.proactive_overlay_busy))
                    }
                    error == "in-conversation" -> {
                        setStatus(null)
                        append(AppStrings.get(app, R.string.proactive_overlay_queued))
                    }
                    else -> {
                        setStatus(null)
                        append(AppStrings.get(app, R.string.proactive_overlay_failed, error ?: "unknown"))
                    }
                }
            }
        }

        private fun setStatus(text: String?) {
            statusView?.text = text ?: ""
            statusView?.visibility = if (text.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        private fun append(line: String) {
            // The whole conversation, so reopening from the bubble shows it all, not just the first ball.
            lastText = (if (lastText.isEmpty()) line else "$lastText\n\n$line").takeLast(MAX_TEXT)
            if (root == null) return
            val view = transcript ?: return
            view.append(if (view.text.isNullOrEmpty()) line else "\n\n$line")
            scroller?.post { scroller?.fullScroll(ScrollView.FOCUS_DOWN) }
        }

        // ---- collapse / close ------------------------------------------------------------------

        /** A lock page is coming: fold to the bubble now, answered or not. */
        fun foldForLock() {
            main.removeCallbacks(shrink)
            collapse(force = true)
        }

        /** Reply window ran out: keep the ball, as a bubble he can reopen. */
        private fun collapse(force: Boolean = false) {
            if (collapsed || root == null) return
            // Not answered yet: it stays open (no quiet fold-away into a bubble).
            if (!talked && !force) {
                main.postDelayed(shrink, COLLAPSE_AFTER_MS)
                return
            }
            collapsed = true
            removeRoot()
            val pill = TextView(context).apply {
                text = AppStrings.get(app, R.string.proactive_overlay_bubble)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.onAccent)
                setPadding(context.dp(14f), context.dp(10f), context.dp(14f), context.dp(10f))
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(22f).toFloat()
                    setColor(palette.accent)
                }
                setOnClickListener { expand() }
            }
            bubble = pill
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                // 气泡可能挂很久：永远不抢焦点，否则这段时间里返回键都给了它。
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = context.dp(16f); y = context.dp(96f)
                title = AppStrings.get(app, R.string.proactive_overlay_title)
            }
            runCatching { wm?.addView(pill, p) }.onFailure { destroy() }
        }

        private fun expand() {
            val pill = bubble ?: return
            runCatching { wm?.removeView(pill) }
            bubble = null
            collapsed = false
            val view = backAware(expanded(lastText, lastWhy))
            val p = windowParams()
            root = view
            params = p
            typing = false
            runCatching { wm?.addView(view, p) }.onFailure { destroy() }
            // Reopened from the bubble: give it the same answer window again.
            main.postDelayed(shrink, COLLAPSE_AFTER_MS)
        }

        /** He may close only after he has said something in this window. */
        private fun mayLeave(): Boolean {
            if (talked) return true
            setStatus("先回一句再走——随便说点什么都行。") // l10n:keep companion overlay rule
            setTyping(true)
            return false
        }

        /** 收起 / 别烦我: the person closed it on purpose. */
        private fun close(what: SurfaceReport, muted: Boolean = false, busy: Boolean = false) {
            report(what)
            destroy()
            // Tell the companion he closed it (and whether he had talked), then watch the next
            // 5 minutes: back in a game for 15 s in total is defiance, not "didn't see it".
            ProactiveRuntime.onOverlayClosed(chatted = talked, muted = muted, busy = busy)
        }

        private fun report(what: SurfaceReport) {
            if (what == SurfaceReport.Shown) {
                if (shownReported) return
                shownReported = true
            } else {
                if (closedReported) return
                closedReported = true
            }
            ProactiveRuntime.onSurface(token, what)
        }

        private fun removeRoot() {
            root?.let { runCatching { wm?.removeView(it) } }
            root = null
        }

        /** The event closed; the shared screen is free again, but the window stays for the person. */
        fun releaseForNow() {
            VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, token)
        }

        fun destroy() {
            main.removeCallbacks(slow)
            main.removeCallbacks(shrink)
            removeRoot()
            bubble?.let { runCatching { wm?.removeView(it) } }
            bubble = null
            VisibleSurface.release(VisibleSurface.OWNER_PROACTIVE, token)
            if (live[token] === this) live.remove(token)
        }

        /** The add failed: clean up without touching the visible-surface slot the caller still owns. */
        fun abandon() {
            main.removeCallbacks(slow)
            main.removeCallbacks(shrink)
            removeRoot()
            bubble?.let { runCatching { wm?.removeView(it) } }
            bubble = null
            if (live[token] === this) live.remove(token)
        }
    }

    /** 双人格：插件在每句话前标〔名字〕（〔鱼丸〕/〔鲸澜〕）。拆出说话人和正文；没标就是单人格。 */
    private val SPEAKER = Regex("^〔([^〕]{1,20})〕")
    fun splitSpeaker(text: String): Pair<String?, String> {
        val m = SPEAKER.find(text) ?: return null to text
        return m.groupValues[1] to text.substring(m.range.last + 1).trimStart()
    }

    /** 回应窗口 20 分钟：到点收成气泡，不删掉。 */
    private const val COLLAPSE_AFTER_MS = 20 * 60_000L
}
