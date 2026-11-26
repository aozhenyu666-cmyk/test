package io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.proactive

import android.content.ClipData
import android.content.ClipboardManager
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.FrameLayout
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
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
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.SurfaceReport
import io.github.ccch1mneyyy.dsh_app.dafeiyu_loop.core.VisibleSurface
import io.github.ccch1mneyyy.dsh_app.pet.OverlayPalette
import io.github.ccch1mneyyy.dsh_app.pet.dp
import io.github.ccch1mneyyy.dsh_app.phone.a11y.PhoneA11yService

/** 超级大肥鱼战术浮窗规格（可附带 A/B 反向阻力双按钮）。 */
data class SuperSparkCardSpec(
    val id: String,
    val mode: String = "contrast_push",
    val badge: String = "",
    val title: String = "",
    val ask: String = "",
    val voiceLine: String = "",
    val optionALabel: String = "A 省力切刷题舱 20m",
    val optionAMode: String = "quiz_only",
    val optionAMinutes: Int = 20,
    val optionBLabel: String = "B 反锁进 Gemini Live",
    val optionBMode: String = "gemini_only",
    val optionBMinutes: Int = 15,
    val geminiTopic: String = "",
    val geminiBrief: String = "",
    val autoClickLive: Boolean = true,
    val anchorSource: String = "",
)

/** 强制计划模态中的单个草案时间块。 */
data class PlanDraftBlock(
    val id: String = "",
    val slot: String = "",
    val startMin: Int = 0,
    val endMin: Int = 0,
    val title: String = "",
    val deliverable: String = "",
    val lockMode: String = "verify",
    val allowedApps: List<String> = emptyList(),
)

/** 强制计划模态弹窗规格。 */
data class PlanModalSpec(
    val day: String = "",
    val headline: String = "今日必须确认计划并激活验收锁",
    val prompt: String = "",
    val draftBlocks: List<PlanDraftBlock> = emptyList(),
    val snoozeLeft: Int = 0,
    val mustConfirm: Boolean = false,
    val energyLevel: String = "mid",
)

/** #1 亮屏 1 秒左右盲点微题规格（无关闭键，左对右错）。 */
data class WakeQuizSpec(
    val itemId: String,
    val claim: String,
    val sourceLabel: String = "知识海",
    val activePoolCount: Int = 0,
    val antiMashMs: Int = 320,
)

/** 纯 JVM 可测：超级大肥鱼战术浮窗解析、去重互斥与 A/B 反向阻力按钮动作策略。 */
object SuperSparkOverlayPolicy {
    const val MUTE_MINUTES_MS = 15 * 60_000L

    data class TacticalAction(
        val slot: String,
        val label: String,
        val capsuleMode: String,
        val capsuleMinutes: Int,
        val launchGeminiLive: Boolean,
        val muteAndClose: Boolean,
    )

    fun parseSpec(raw: Any?): SuperSparkCardSpec? {
        val root = raw as? Map<*, *> ?: return null
        val m = (root["spark"] as? Map<*, *>) ?: root
        if (m["triggered"] == false) return null
        val id = (m["id"] as? String)?.trim().orEmpty()
        if (id.isEmpty()) return null
        return SuperSparkCardSpec(
            id = id,
            mode = (m["mode"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "contrast_push",
            badge = (m["badge"] as? String)?.trim().orEmpty(),
            title = (m["title"] as? String)?.trim().orEmpty(),
            ask = (m["ask"] as? String)?.trim().orEmpty(),
            voiceLine = (m["voiceLine"] as? String)?.trim().orEmpty(),
            optionALabel = (m["optionALabel"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "A 省力切刷题舱 20m",
            optionAMode = (m["optionAMode"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "quiz_only",
            optionAMinutes = ((m["optionAMinutes"] as? Number)?.toInt() ?: 20).coerceIn(5, 180),
            optionBLabel = (m["optionBLabel"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "B 反锁进 Gemini Live",
            optionBMode = (m["optionBMode"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "gemini_only",
            optionBMinutes = ((m["optionBMinutes"] as? Number)?.toInt() ?: 15).coerceIn(5, 180),
            geminiTopic = (m["geminiTopic"] as? String)?.trim().orEmpty(),
            geminiBrief = (m["geminiBrief"] as? String)?.trim().orEmpty(),
            autoClickLive = m["autoClickLive"] != false,
            anchorSource = (m["anchorSource"] as? String)?.trim().orEmpty(),
        )
    }

    fun shouldShow(
        spec: SuperSparkCardSpec?,
        shownIds: Set<String>,
        interceptHeld: Boolean,
        planModalShowing: Boolean,
        chattingActive: Boolean = false,
    ): Boolean {
        if (spec == null) return false
        if (spec.id in shownIds) return false
        if (interceptHeld || planModalShowing || chattingActive) return false
        return true
    }

    fun resolveTacticalActions(spec: SuperSparkCardSpec): Pair<TacticalAction, TacticalAction> {
        val a = TacticalAction(
            slot = "A",
            label = spec.optionALabel.ifBlank { "A 省力切刷题舱 20m" },
            capsuleMode = spec.optionAMode.ifBlank { "quiz_only" },
            capsuleMinutes = spec.optionAMinutes.coerceIn(5, 180),
            launchGeminiLive = false,
            muteAndClose = true,
        )
        val b = TacticalAction(
            slot = "B",
            label = spec.optionBLabel.ifBlank { "B 反锁进 Gemini Live" },
            capsuleMode = spec.optionBMode.ifBlank { "gemini_only" },
            capsuleMinutes = spec.optionBMinutes.coerceIn(5, 180),
            launchGeminiLive = true,
            muteAndClose = false,
        )
        return a to b
    }

    fun formatCardBody(spec: SuperSparkCardSpec): String = buildString {
        if (spec.title.isNotBlank()) append("〔鲸澜〕${spec.title}")
        if (spec.ask.isNotBlank()) {
            if (isNotEmpty()) append("\n\n")
            append(spec.ask)
        }
        if (spec.anchorSource.isNotBlank()) {
            if (isNotEmpty()) append("\n")
            append("锚点：${spec.anchorSource}")
        }
        if (isEmpty()) append("〔鲸澜〕现在挑一条最小动作：先刷题还是进 Gemini Live？")
    }
}

/** 纯 JVM 可测：强制计划模态弹窗的解析、90 秒去重节流、延后条件与草案块格式化策略。 */
object PlanModalPolicy {
    const val MIN_SHOW_INTERVAL_MS = 90_000L

    fun parseSpec(raw: Any?): PlanModalSpec? {
        val m = raw as? Map<*, *> ?: return null
        if (m["active"] != true) return null
        val blocks = (m["draftBlocks"] as? List<*>).orEmpty().mapNotNull { item ->
            val b = item as? Map<*, *> ?: return@mapNotNull null
            val title = (b["title"] as? String)?.trim().orEmpty()
            if (title.isEmpty()) return@mapNotNull null
            PlanDraftBlock(
                id = (b["id"] as? String)?.trim().orEmpty(),
                slot = (b["slot"] as? String)?.trim().orEmpty(),
                startMin = (b["startMin"] as? Number)?.toInt() ?: 0,
                endMin = (b["endMin"] as? Number)?.toInt() ?: 0,
                title = title,
                deliverable = (b["deliverable"] as? String)?.trim().orEmpty(),
                lockMode = (b["lockMode"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "verify",
                allowedApps = (b["allowedApps"] as? List<*>).orEmpty().mapNotNull { (it as? String)?.trim()?.takeIf { s -> s.isNotEmpty() } },
            )
        }
        return PlanModalSpec(
            day = (m["day"] as? String)?.trim().orEmpty(),
            headline = (m["headline"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "今日必须确认计划并激活验收锁",
            prompt = (m["prompt"] as? String)?.trim().orEmpty(),
            draftBlocks = blocks,
            snoozeLeft = ((m["snoozeLeft"] as? Number)?.toInt() ?: 0).coerceAtLeast(0),
            mustConfirm = m["mustConfirm"] == true,
            energyLevel = (m["energyLevel"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "mid",
        )
    }

    fun shouldShow(
        spec: PlanModalSpec?,
        lastShownAtMs: Long,
        nowMs: Long,
        interceptHeld: Boolean,
        alreadyShowing: Boolean,
        minIntervalMs: Long = MIN_SHOW_INTERVAL_MS,
    ): Boolean {
        if (spec == null) return false
        if (interceptHeld || alreadyShowing) return false
        if (lastShownAtMs > 0L && nowMs - lastShownAtMs < minIntervalMs) return false
        return true
    }

    fun canSnooze(spec: PlanModalSpec): Boolean = !spec.mustConfirm && spec.snoozeLeft > 0

    fun formatMinutesHm(minOfDay: Int): String {
        val m = minOfDay.coerceIn(0, 24 * 60)
        return String.format(java.util.Locale.US, "%02d:%02d", m / 60, m % 60)
    }

    fun formatBlockLine(block: PlanDraftBlock): String {
        val timeRange = if (block.endMin > block.startMin) {
            "${formatMinutesHm(block.startMin)}–${formatMinutesHm(block.endMin)} "
        } else {
            ""
        }
        val modeTag = if (block.lockMode == "verify") "[验收锁]" else "[${block.lockMode}]"
        val deliv = if (block.deliverable.isNotBlank()) "\n  交付：${block.deliverable}" else ""
        return "$timeRange${block.title} $modeTag$deliv"
    }

    fun validateNegotiationText(raw: String?): String? {
        val t = raw?.trim().orEmpty()
        return if (t.length >= 2) t.take(500) else null
    }
}

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
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var app: Context? = null
    private val live = LinkedHashMap<String, Card>()
    private var liveDice: DiceCard? = null
    private var livePlanModal: PlanModalCard? = null
    private var livePostRelay: PostLiveRelayCard? = null
    private var liveWakeQuiz: WakeQuizCard? = null

    fun install(context: Context) {
        app = context.applicationContext
    }

    fun hasActiveChat(): Boolean = live.values.any { it.chatting() }

    fun isPlanModalShowing(): Boolean = livePlanModal != null

    /** Adds the window; false means "no host" and the caller should fall back to the Activity. */
    fun show(
        token: String,
        text: String,
        deadline: Long,
        why: String? = null,
        spark: SuperSparkCardSpec? = null,
    ): Boolean {
        val context = app ?: return false
        // He is chatting in the window that is up: the new ball joins that conversation instead of
        // wiping it (it used to replace the window, so "回一下就没了").
        val chatting = live.values.lastOrNull()?.takeIf { it.chatting() }
        if (chatting != null) {
            live.values.filter { it !== chatting }.forEach { it.destroy() }
            live.clear()
            chatting.adopt(token, text, spark)
            live[token] = chatting
            return true
        }
        // One visible surface: a new ball replaces whatever was left of the previous one.
        live.values.toList().forEach { it.destroy() }
        live.clear()
        val host = host(context) ?: return false
        val card = Card(context, host.first, host.second, token, deadline, spark)
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

    /** 弹出超级大肥鱼战术浮窗（附带 A 切刷题舱 / B 反锁 Gemini Live 双按钮）。 */
    fun showSuperSpark(spec: SuperSparkCardSpec): Boolean {
        val body = SuperSparkOverlayPolicy.formatCardBody(spec)
        val why = spec.badge.ifBlank { null }
        val deadline = System.currentTimeMillis() + COLLAPSE_AFTER_MS
        return show(token = "spark:${spec.id}", text = body, deadline = deadline, why = why, spark = spec)
    }

    /** 弹出独立强制计划模态弹窗（不可点外部轻易划走，弹出时震动）。 */
    fun showPlanModal(spec: PlanModalSpec): Boolean {
        val context = app ?: return false
        dismissPlanModal()
        val host = host(context) ?: return false
        val modal = PlanModalCard(context, host.first, host.second, spec)
        return try {
            modal.attach()
            livePlanModal = modal
            true
        } catch (e: Exception) {
            android.util.Log.i(TAG, "plan modal overlay add failed: ${e.javaClass.simpleName}")
            modal.destroy()
            false
        }
    }

    fun dismissPlanModal() {
        livePlanModal?.destroy()
        livePlanModal = null
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
        dismissDice()
        dismissPlanModal()
        dismissPostLiveRelay()
    }

    fun showPostLiveRelay(
        durationSec: Long,
        onCheckpoint25m: () -> Unit,
        onQuizCapsule20m: () -> Unit,
        onRecapOnly: () -> Unit,
        earlyBailout: Boolean = false,
        onReturnToLive: (() -> Unit)? = null,
        onSwitchToText: (() -> Unit)? = null,
        onPauseKeeper15m: (() -> Unit)? = null,
    ): Boolean {
        val context = app ?: return false
        dismissPostLiveRelay()
        val host = host(context) ?: return false
        val card = PostLiveRelayCard(
            context = host.first,
            type = host.second,
            durationSec = durationSec,
            onCheckpoint25m = onCheckpoint25m,
            onQuizCapsule20m = onQuizCapsule20m,
            onRecapOnly = onRecapOnly,
            earlyBailout = earlyBailout,
            onReturnToLive = onReturnToLive,
            onSwitchToText = onSwitchToText,
            onPauseKeeper15m = onPauseKeeper15m,
        )
        return try {
            card.attach()
            livePostRelay = card
            true
        } catch (e: Exception) {
            android.util.Log.i(TAG, "post-live relay overlay add failed: ${e.javaClass.simpleName}")
            card.destroy()
            false
        }
    }

    fun dismissPostLiveRelay() {
        livePostRelay?.destroy()
        livePostRelay = null
    }

    fun isShowing(token: String) = live.containsKey(token)

    /** 摇一摇六骰子轻干预悬浮窗：独立于主动发球事件，随时可摇、可记一句思考、可复制话术切去 Gemini。 */
    fun showDice(
        dice: Int,
        name: String,
        ask: String,
        anchor: String = "",
        appLabel: String = "",
        geminiPrompt: String = "",
        onSubmitThought: (String, (Boolean, String?) -> Unit) -> Unit,
        onReroll: () -> Unit,
        onEnterLive: (() -> Unit)? = null,
        onClose: () -> Unit = {},
    ): Boolean {
        val context = app ?: return false
        dismissDice()
        val host = host(context) ?: return false
        val card = DiceCard(
            app = context,
            context = host.first,
            type = host.second,
            dice = dice,
            name = name,
            ask = ask,
            anchor = anchor,
            appLabel = appLabel,
            geminiPrompt = geminiPrompt,
            onSubmitThought = onSubmitThought,
            onReroll = onReroll,
            onEnterLive = onEnterLive,
            onClose = onClose,
        )
        return try {
            card.attach()
            liveDice = card
            true
        } catch (e: Exception) {
            android.util.Log.i(TAG, "dice overlay add failed: ${e.javaClass.simpleName}")
            card.destroy()
            false
        }
    }

    fun dismissDice() {
        liveDice?.destroy()
        liveDice = null
    }

    /**
     * B4 连发的后续一段：追加到同一个窗口，不重置事件状态（[Card.adopt] 是「下一球」用的，
     * 会把上报标记清掉）。窗口已经不在了返回 false，这一段就丢掉——话已经晚了，不再另开一个窗口。
     */
    fun say(token: String, text: String): Boolean {
        val card = live[token] ?: return false
        card.say(text)
        return true
    }

    /**
     * 执法优先（C1）: a lock page is about to take the screen. Every open card folds into its bubble,
     * which never takes focus, so the lock page can prove it is on screen. Nothing is lost: the
     * conversation stays and the bubble reopens it. Also used when the card had not been answered
     * yet - folding is not closing, the event is still waiting for his reply.
     */
    fun yieldForLock() {
        live.values.toList().forEach { it.foldForLock() }
        dismissDice()
        dismissPlanModal()
        dismissPostLiveRelay()
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
        private var currentSpark: SuperSparkCardSpec? = null,
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
            ProactiveHaptics.vibrateSuperSpark(app)
            main.postDelayed({ proveShown() }, 120L)
            // 回应窗口：20 分钟没动静就收成气泡（不删掉）。coordinator 自己的 60 秒时限只关事件，
            // 不关这个窗口——关闭它是人点「收起」或回复之后的事。
            main.postDelayed(shrink, COLLAPSE_AFTER_MS)
        }

        /** He has talked in this window and it is still there (open or as a bubble). */
        fun chatting(): Boolean = talked && (root != null || bubble != null)

        /** A new ball while he is chatting here: same window, next line, new event. */
        fun adopt(newToken: String, text: String, spark: SuperSparkCardSpec? = null) {
            token = newToken
            shownReported = false
            closedReported = false
            // The next line he sends answers this new event.
            chatted = false
            if (spark != null) currentSpark = spark
            val (who, body) = splitSpeaker(text)
            append(if (who != null) "$who：$body" else body)
            ProactiveHaptics.vibrateSuperSpark(app)
            main.removeCallbacks(shrink)
            if (collapsed) expand() else main.postDelayed(shrink, COLLAPSE_AFTER_MS)
            main.postDelayed({ proveShown() }, 120L)
        }

        /** B4 连发的后续一段：同一窗口追加一行，事件的上报状态不动。 */
        fun say(text: String) {
            val (who, body) = splitSpeaker(text)
            append(if (who != null) "$who：$body" else body)
            main.removeCallbacks(shrink)
            if (collapsed) expand() else main.postDelayed(shrink, COLLAPSE_AFTER_MS)
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

            currentSpark?.let { spark ->
                val (actA, actB) = SuperSparkOverlayPolicy.resolveTacticalActions(spark)
                val tacticalRow = LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setPadding(0, 0, 0, context.dp(8f))
                }
                fun sparkBtn(label: String, primary: Boolean, onTap: () -> Unit) = TextView(context).apply {
                    this.text = label
                    gravity = Gravity.CENTER
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(if (primary) palette.onAccent else palette.onTonal)
                    setPadding(context.dp(10f), context.dp(9f), context.dp(10f), context.dp(9f))
                    background = GradientDrawable().apply {
                        cornerRadius = context.dp(12f).toFloat()
                        setColor(if (primary) palette.accent else palette.tonal)
                        if (!primary) setStroke(context.dp(1f).coerceAtLeast(1), palette.stroke)
                    }
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { onTap() }
                }
                tacticalRow.addView(
                    sparkBtn(actA.label, primary = false) {
                        if (waiting) return@sparkBtn
                        ProactiveRuntime.activateReverseCapsule(actA.capsuleMode, actA.capsuleMinutes)
                        ProactiveRuntime.muteFor(SuperSparkOverlayPolicy.MUTE_MINUTES_MS)
                        report(SurfaceReport.Answered("super_spark_a"))
                        destroy()
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = context.dp(6f)
                    },
                )
                tacticalRow.addView(
                    sparkBtn(actB.label, primary = true) {
                        if (waiting) return@sparkBtn
                        ProactiveRuntime.activateReverseCapsule(actB.capsuleMode, actB.capsuleMinutes)
                        GeminiAutoLauncher.launchAndAutomateLive(
                            app = app,
                            topic = spark.geminiTopic.ifBlank { spark.title },
                            brief = spark.geminiBrief.ifBlank { spark.ask },
                            voiceLine = spark.voiceLine,
                            mode = "instant",
                            autoClickLive = spark.autoClickLive,
                        )
                        report(SurfaceReport.Answered("super_spark_b"))
                        destroy()
                    },
                    LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
                )
                card.addView(tacticalRow)
            }

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

            val actionsTop = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, context.dp(4f), 0, 0)
            }
            actionsTop.addView(textAction("放空闭嘴 15m") {
                if (waiting) return@textAction
                ProactiveRuntime.muteFor(SuperSparkOverlayPolicy.MUTE_MINUTES_MS)
                if (currentSpark != null) ProactiveRuntime.notifySuperSparkMuted15m()
                close(SurfaceReport.Dismissed, muted = true)
            })
            // 在开会 / 上课：一键「在忙，晚点回」（C4）。窗口收起，但**不算回过话、不开安静期**：
            // 插件只记一笔 busy；关窗后照常复查，5 分钟里在娱乐 App 累计 15 秒就算违抗。
            actionsTop.addView(textAction("在忙，晚点回") { // l10n:keep companion overlay quick reply
                if (waiting) return@textAction
                close(SurfaceReport.Dismissed, busy = true)
            })
            card.addView(actionsTop)

            val actions = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
            }
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
    private const val DICE_IDLE_TIMEOUT_MS = 3 * 60_000L

    private class DiceCard(
        private val app: Context,
        private val context: Context,
        private val type: Int,
        private val dice: Int,
        private val name: String,
        private val ask: String,
        private val anchor: String,
        private val appLabel: String,
        private val geminiPrompt: String,
        private val onSubmitThought: (String, (Boolean, String?) -> Unit) -> Unit,
        private val onReroll: () -> Unit,
        private val onEnterLive: (() -> Unit)?,
        private val onClose: () -> Unit,
    ) {
        private val palette = OverlayPalette.of(context)
        private val wm = context.getSystemService(WindowManager::class.java)
        private var root: View? = null
        private var params: WindowManager.LayoutParams? = null
        private var input: EditText? = null
        private var statusView: TextView? = null
        private var submitBtn: TextView? = null
        private var escapeBtn: TextView? = null
        private var escapeStage = 0
        private var typing = false
        private var busy = false
        private val idleTimeout = Runnable { close() }

        fun attach() {
            val view = buildView()
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = context.dp(46f)
                title = "大肥鱼六骰微干预"
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                val maxW = (context.resources.displayMetrics.widthPixels * 0.92f).toInt()
                width = minOf(maxW, context.dp(420f))
            }
            root = view
            params = p
            wm!!.addView(view, p)
            main.postDelayed(idleTimeout, DICE_IDLE_TIMEOUT_MS)
        }

        private fun buildView(): View {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = context.dp(14f)
                setPadding(pad, context.dp(12f), pad, context.dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(18f).toFloat()
                    setColor(palette.fill)
                    setStroke(context.dp(1.5f).coerceAtLeast(1), palette.accent)
                }
            }

            // 左侧 60dp 立体大骰子 + 右侧标题/锚点/收起：高分屏一秒看清点数且不撑高悬浮窗
            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val dieView = DiceFaceView(context, palette, dice).also { v ->
                val sz = context.dp(60f)
                topRow.addView(v, LinearLayout.LayoutParams(sz, sz).apply {
                    marginEnd = context.dp(12f)
                })
                v.playRoll(dice)
            }
            val metaCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            val headerLine = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headerLine.addView(TextView(context).apply {
                text = "骰$dice · $name" + if (appLabel.isNotBlank()) "（$appLabel）" else ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(palette.accent)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            val dismissLabel = TextView(context).apply {
                text = "稍后"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(palette.muted)
                setPadding(context.dp(8f), context.dp(4f), context.dp(2f), context.dp(4f))
                isClickable = true
                setOnClickListener {
                    val typedLen = input?.text?.toString()?.trim()?.length ?: 0
                    if (typedLen >= 6 || escapeStage >= 1) {
                        close()
                    } else {
                        escapeStage = 1
                        text = "确认休整"
                        setTextColor(palette.accent)
                        ProactiveHaptics.vibrateGentleNudge(context)
                        setStatus("不能顺手收起绕过：点「灌料进 Live」或写 ≥6 字卡点；真没电再点一次确认休整")
                    }
                }
            }
            escapeBtn = dismissLabel
            headerLine.addView(dismissLabel)
            metaCol.addView(headerLine)

            if (anchor.isNotBlank()) {
                metaCol.addView(TextView(context).apply {
                    text = "锚点：$anchor"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    setTextColor(palette.muted)
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setPadding(0, context.dp(3f), 0, 0)
                })
            }
            topRow.addView(metaCol, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            dieView.setOnClickListener {
                if (!busy) onReroll()
            }
            card.addView(topRow)

            card.addView(TextView(context).apply {
                text = ask
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTextColor(palette.ink)
                setLineSpacing(0f, 1.25f)
                setPadding(0, context.dp(6f), 0, context.dp(8f))
            })

            val status = TextView(context).apply {
                text = ""
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTextColor(palette.accent)
                visibility = View.GONE
                setPadding(0, 0, 0, context.dp(6f))
            }
            statusView = status
            card.addView(status)

            val box = EditText(context).apply {
                hint = "写一句当下思考（记进记忆 + Obsidian 碎记）…"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(palette.ink)
                setHintTextColor(palette.muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                maxLines = 3
                setPadding(context.dp(10f), context.dp(8f), context.dp(10f), context.dp(8f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(12f).toFloat()
                    setColor(palette.pressed)
                }
                setOnTouchListener { _, ev ->
                    if (ev.action == MotionEvent.ACTION_DOWN && !typing) setTyping(true)
                    main.removeCallbacks(idleTimeout)
                    main.postDelayed(idleTimeout, DICE_IDLE_TIMEOUT_MS)
                    false
                }
                setOnEditorActionListener { _, action, _ ->
                    if (action == EditorInfo.IME_ACTION_DONE) { submitThought(); true } else false
                }
            }
            input = box
            card.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

            val btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.END
                setPadding(0, context.dp(8f), 0, 0)
            }
            fun chip(label: String, accent: Boolean = false, onTap: () -> Unit) = TextView(context).apply {
                text = label
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setTextColor(if (accent) palette.onAccent else palette.ink)
                setPadding(context.dp(10f), context.dp(6f), context.dp(10f), context.dp(6f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(12f).toFloat()
                    setColor(if (accent) palette.accent else palette.pressed)
                }
                isClickable = true
                setOnClickListener {
                    main.removeCallbacks(idleTimeout)
                    main.postDelayed(idleTimeout, DICE_IDLE_TIMEOUT_MS)
                    onTap()
                }
            }
            btnRow.addView(chip("再摇一骰") {
                if (busy) return@chip
                onReroll()
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(6f)
            })
            btnRow.addView(chip("🎲 灌料进 Live", accent = true) {
                val customEnter = onEnterLive
                if (customEnter != null) {
                    setStatus("正在用本骰灌料并进入官方 Gemini Live…")
                    customEnter()
                } else {
                    val clipText = geminiPrompt.ifBlank { "【骰$dice · $name】$ask" }
                    runCatching {
                        val cm = app.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("大肥鱼六骰提问", clipText))
                        setStatus("简报已复制：正在切入 Live 并尝试共享屏幕")
                    }
                    runCatching {
                        GeminiAutoLauncher.launchAndAutomateLive(
                            app = app,
                            topic = "骰$dice · $name",
                            brief = clipText,
                            voiceLine = "骰$dice$name，直接开答",
                            mode = "instant",
                            autoClickLive = true,
                            requestScreenShare = true,
                        )
                    }
                }
            }, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                marginEnd = context.dp(6f)
            })
            val saveBtn = chip("记一句思考") { submitThought() }
            submitBtn = saveBtn
            btnRow.addView(saveBtn)
            card.addView(btnRow)

            return object : FrameLayout(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK && typing) {
                        if (event.action == KeyEvent.ACTION_UP) setTyping(false)
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply { addView(card) }
        }

        private fun submitThought() {
            if (busy) return
            val text = input?.text?.toString()?.trim().orEmpty()
            if (text.isEmpty()) {
                setStatus("先写几个字，哪怕一句大白话也算思考过")
                if (!typing) setTyping(true)
                return
            }
            busy = true
            submitBtn?.alpha = 0.5f
            setStatus("正在记入记忆库与 Obsidian 碎记…")
            onSubmitThought(text) { ok, err ->
                main.post {
                    if (root == null) return@post
                    busy = false
                    submitBtn?.alpha = 1f
                    if (ok) {
                        setTyping(false)
                        setStatus("✓ 已记录思考并同步给两姐妹")
                        main.postDelayed({ close() }, 1_100L)
                    } else {
                        setStatus("写入失败：${err ?: "未知错误"}")
                    }
                }
            }
        }

        private fun setStatus(msg: String?) {
            statusView?.text = msg ?: ""
            statusView?.visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
        }

        private fun setTyping(on: Boolean) {
            val v = root ?: return
            val p = params ?: return
            if (typing == on) return
            typing = on
            p.flags = if (on) p.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            else p.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
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

        private fun close() {
            destroy()
            onClose()
        }

        fun destroy() {
            main.removeCallbacks(idleTimeout)
            root?.let { runCatching { wm?.removeView(it) } }
            root = null
            if (liveDice === this) liveDice = null
        }
    }

    /**
     * 自绘 60dp 立体骰子 + 380ms 弹性翻滚定格动画：
     * 象牙白骰面、暖灰细边、软阴影；1、4 点朱红，其余墨黑，点有凹陷的明暗。
     */
    private class DiceFaceView(
        context: Context,
        @Suppress("UNUSED_PARAMETER") palette: OverlayPalette,
        initialFace: Int,
    ) : View(context) {
        private var currentFace = initialFace.coerceIn(1, 6)
        private var rollAnim: android.animation.ValueAnimator? = null
        private val facePaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val borderPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
        private val pipPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()

        fun playRoll(targetFace: Int) {
            val target = targetFace.coerceIn(1, 6)
            rollAnim?.cancel()
            val animEnabled = if (Build.VERSION.SDK_INT >= 26) android.animation.ValueAnimator.areAnimatorsEnabled() else true
            if (!animEnabled) {
                currentFace = target
                rotation = 0f
                scaleX = 1f
                scaleY = 1f
                invalidate()
                return
            }
            var lastStep = -1
            rollAnim = android.animation.ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 380L
                interpolator = android.view.animation.OvershootInterpolator(1.35f)
                addUpdateListener { va ->
                    val t = va.animatedFraction.coerceIn(0f, 1f)
                    val step = (t * 5f).toInt()
                    if (t >= 0.92f) {
                        currentFace = target
                        rotation = 0f
                    } else if (step != lastStep) {
                        lastStep = step
                        currentFace = ((target + step * 2) % 6) + 1
                    }
                    rotation = ((1f - t) * 18f * kotlin.math.sin(t * kotlin.math.PI * 3.0)).toFloat()
                    val s = if (t < 0.45f) 0.80f + (t / 0.45f) * 0.28f else 1.08f - ((t - 0.45f) / 0.55f) * 0.08f
                    scaleX = s
                    scaleY = s
                    invalidate()
                }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            rollAnim?.cancel()
            super.onDetachedFromWindow()
        }

        // 2026-11-19：不再用主题色（蓝）描边——改成真骰子的样子：象牙白渐变骰面、暖灰细边、底部软阴影、
        // 有立体感的点（1、4 点朱红，其余墨黑）。深浅色主题下都是同一颗骰子。
        private val shadowPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val highlightPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
        }
        private var faceShader: android.graphics.Shader? = null
        private var shaderFor = 0f

        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val pad = w * 0.08f
            val radius = w * 0.2f

            // 软阴影：往下错一点、半透明，让骰子「立」在卡片上。
            shadowPaint.color = android.graphics.Color.argb(46, 0, 0, 0)
            rect.set(pad + w * 0.02f, pad + w * 0.06f, w - pad + w * 0.01f, h - pad + w * 0.06f)
            canvas.drawRoundRect(rect, radius, radius, shadowPaint)

            rect.set(pad, pad, w - pad, h - pad)
            if (faceShader == null || shaderFor != h) {
                faceShader = android.graphics.LinearGradient(
                    0f, pad, 0f, h - pad,
                    android.graphics.Color.parseColor("#FFFFFF"),
                    android.graphics.Color.parseColor("#E9E3D8"),
                    android.graphics.Shader.TileMode.CLAMP,
                )
                shaderFor = h
            }
            facePaint.shader = faceShader
            canvas.drawRoundRect(rect, radius, radius, facePaint)
            facePaint.shader = null
            // 暖灰细边（不是主题色）+ 顶部一道高光。
            borderPaint.color = android.graphics.Color.parseColor("#CFC6B8")
            borderPaint.strokeWidth = (w * 0.022f).coerceAtLeast(1.2f)
            canvas.drawRoundRect(rect, radius, radius, borderPaint)
            highlightPaint.color = android.graphics.Color.argb(170, 255, 255, 255)
            highlightPaint.strokeWidth = (w * 0.02f).coerceAtLeast(1f)
            val inset = w * 0.035f
            rect.set(pad + inset, pad + inset, w - pad - inset, h - pad - inset)
            canvas.drawArc(rect, 200f, 140f, false, highlightPaint)

            val cx = w / 2f
            val cy = h / 2f
            val off = w * 0.215f
            val positions: List<Pair<Float, Float>> = when (currentFace) {
                1 -> listOf(cx to cy)
                2 -> listOf(cx - off to cy - off, cx + off to cy + off)
                3 -> listOf(cx - off to cy - off, cx to cy, cx + off to cy + off)
                4 -> listOf(cx - off to cy - off, cx + off to cy - off, cx - off to cy + off, cx + off to cy + off)
                5 -> listOf(cx - off to cy - off, cx + off to cy - off, cx to cy, cx - off to cy + off, cx + off to cy + off)
                else -> listOf(
                    cx - off to cy - off, cx + off to cy - off,
                    cx - off to cy, cx + off to cy,
                    cx - off to cy + off, cx + off to cy + off,
                )
            }
            val red = currentFace == 1 || currentFace == 4
            val light = android.graphics.Color.parseColor(if (red) "#F0605A" else "#4A505C")
            val dark = android.graphics.Color.parseColor(if (red) "#B3261E" else "#15181E")
            val r = if (currentFace == 1) w * 0.13f else w * 0.075f
            for (pt in positions) {
                // 凹进去的点：左上亮、右下暗。
                pipPaint.shader = android.graphics.RadialGradient(
                    pt.first - r * 0.35f, pt.second - r * 0.35f, r * 1.4f, light, dark,
                    android.graphics.Shader.TileMode.CLAMP,
                )
                canvas.drawCircle(pt.first, pt.second, r, pipPaint)
            }
            pipPaint.shader = null
        }
    }

    /**
     * 强制计划模态弹窗：弹出时调用 [ProactiveHaptics.vibratePlanModal]，展示草案块与今日验收锁提示，
     * 拦截外部点击与返回键防止误划走；提供「确认并激活验收锁」「发给鲸澜重构计划」「延后 15 分钟」。
     */
    private class PlanModalCard(
        private val app: Context,
        private val context: Context,
        private val type: Int,
        private val spec: PlanModalSpec,
    ) {
        private val palette = OverlayPalette.of(context)
        private val wm = context.getSystemService(WindowManager::class.java)
        private var root: View? = null
        private var input: EditText? = null
        private var statusView: TextView? = null
        private var confirmBtn: Button? = null
        private var negotiateBtn: Button? = null
        private var snoozeBtn: Button? = null
        private var busy = false

        fun attach() {
            val view = buildView()
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                type,
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.CENTER
                title = "大肥鱼强制计划模态"
                @Suppress("DEPRECATION")
                softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
            }
            root = view
            wm!!.addView(view, p)
            ProactiveHaptics.vibratePlanModal(app)
        }

        private fun buildView(): View {
            val scrim = object : FrameLayout(context) {
                override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                    if (event.keyCode == KeyEvent.KEYCODE_BACK) {
                        if (event.action == KeyEvent.ACTION_UP) {
                            setStatus("请先确认今日计划或发给鲸澜调整（不可直接返回关闭）", isError = true)
                        }
                        return true
                    }
                    return super.dispatchKeyEvent(event)
                }
            }.apply {
                setBackgroundColor(Color.argb(168, 12, 16, 24))
                isClickable = true
                isFocusable = true
                isFocusableInTouchMode = true
                setOnClickListener {
                    setStatus("计划未武装验收锁，不能点外部关闭；请确认、重构或延后", isError = true)
                }
            }

            val maxW = minOf(
                (context.resources.displayMetrics.widthPixels * 0.94f).toInt(),
                context.dp(430f),
            ).coerceAtLeast(context.dp(280f))

            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = context.dp(18f)
                setPadding(pad, context.dp(16f), pad, context.dp(16f))
                isClickable = true
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(22f).toFloat()
                    setColor(palette.fill)
                    setStroke(context.dp(1.5f).coerceAtLeast(1), palette.accent)
                }
                elevation = context.dp(10f).toFloat()
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(context).apply {
                text = "⚡ 今日计划确认 · 激活验收锁"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.onTonal)
                setPadding(context.dp(10f), context.dp(4f), context.dp(10f), context.dp(4f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(999f).toFloat()
                    setColor(palette.tonal)
                }
            })
            val energyText = when (spec.energyLevel) {
                "low" -> "低电量保底"
                "high" -> "高精力冲刺"
                else -> "标准推进"
            }
            topRow.addView(TextView(context).apply {
                text = energyText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(palette.muted)
                setPadding(context.dp(8f), context.dp(4f), 0, context.dp(4f))
            })
            card.addView(topRow, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(8f)
            })

            card.addView(TextView(context).apply {
                text = spec.headline.ifBlank { "今日必须确认计划并激活验收锁" }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 18f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.ink)
                setLineSpacing(0f, 1.2f)
            })

            val promptText = buildString {
                if (spec.prompt.isNotBlank()) append(spec.prompt)
                if (isNotEmpty()) append("\n")
                append("不确认今日计划，P3 验收锁无法正常武装；确认后到点未交付将自动触发验收锁。")
            }
            card.addView(TextView(context).apply {
                text = promptText
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                setTextColor(palette.muted)
                setLineSpacing(0f, 1.3f)
                setPadding(0, context.dp(6f), 0, context.dp(10f))
            })

            // 草案时间块清单
            val blocksContainer = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(context.dp(10f), context.dp(8f), context.dp(10f), context.dp(8f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(14f).toFloat()
                    setColor(palette.pressed)
                    setStroke(context.dp(1f).coerceAtLeast(1), palette.stroke)
                }
            }
            blocksContainer.addView(TextView(context).apply {
                text = if (spec.draftBlocks.isNotEmpty()) {
                    "今日草案时间块（共 ${spec.draftBlocks.size} 块 · 激活后带验收锁）"
                } else {
                    "今日尚未生成草案块：可直接点确认套用保底块，或在下方发给鲸澜生成"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(palette.ink)
                setPadding(0, 0, 0, context.dp(6f))
            })
            spec.draftBlocks.take(6).forEachIndexed { idx, blk ->
                blocksContainer.addView(TextView(context).apply {
                    text = "${idx + 1}. ${PlanModalPolicy.formatBlockLine(blk)}"
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
                    setTextColor(palette.ink)
                    setLineSpacing(0f, 1.25f)
                    setPadding(0, context.dp(3f), 0, context.dp(3f))
                })
            }
            card.addView(blocksContainer, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(10f)
            })

            val status = TextView(context).apply {
                visibility = View.GONE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setLineSpacing(0f, 1.25f)
                setPadding(0, 0, 0, context.dp(8f))
            }
            statusView = status
            card.addView(status)

            val box = EditText(context).apply {
                hint = "告诉鲸澜你怎么排今天（如：下午判断推理 40m，晚上申论复盘 45m）"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setTextColor(palette.ink)
                setHintTextColor(palette.muted)
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                imeOptions = EditorInfo.IME_ACTION_DONE or EditorInfo.IME_FLAG_NO_EXTRACT_UI
                minLines = 2
                maxLines = 4
                setPadding(context.dp(12f), context.dp(10f), context.dp(12f), context.dp(10f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(14f).toFloat()
                    setColor(palette.pressed)
                    setStroke(context.dp(1f).coerceAtLeast(1), palette.stroke)
                }
            }
            input = box
            card.addView(box, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                bottomMargin = context.dp(10f)
            })

            fun modalBtn(label: String, style: Int, onTap: () -> Unit): Button = Button(context).apply {
                // style: 0=primary, 1=tonal, 2=secondary
                text = label
                textSize = 14f
                isAllCaps = false
                setTextColor(
                    when (style) {
                        0 -> palette.onAccent
                        1 -> palette.onTonal
                        else -> palette.ink
                    },
                )
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(14f).toFloat()
                    setColor(
                        when (style) {
                            0 -> palette.accent
                            1 -> palette.tonal
                            else -> palette.pressed
                        },
                    )
                    if (style == 2) setStroke(context.dp(1f).coerceAtLeast(1), palette.stroke)
                }
                stateListAnimator = null
                backgroundTintList = null
                elevation = 0f
                setOnClickListener { onTap() }
            }

            val cBtn = modalBtn("确认并激活验收锁", style = 0) {
                if (busy) return@modalBtn
                setBusy(true, "正在激活今日验收锁…")
                ProactiveRuntime.confirmPlanModal(spec.draftBlocks) { ok, msg ->
                    main.post {
                        if (root == null) return@post
                        if (ok) {
                            runCatching { android.widget.Toast.makeText(app, "✓ 今日计划已确认并激活验收锁", android.widget.Toast.LENGTH_LONG).show() }
                            dismissPlanModal()
                        } else {
                            setBusy(false)
                            setStatus(msg ?: "激活失败，请重试或发给鲸澜重构", isError = true)
                        }
                    }
                }
            }
            confirmBtn = cBtn
            card.addView(cBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(46f)).apply {
                bottomMargin = context.dp(8f)
            })

            val nBtn = modalBtn("发给鲸澜重构计划", style = 1) {
                if (busy) return@modalBtn
                val cleanText = PlanModalPolicy.validateNegotiationText(input?.text?.toString())
                if (cleanText == null) {
                    setStatus("请先在上方输入框写下今天安排（至少 2 个字）", isError = true)
                    return@modalBtn
                }
                setBusy(true, "鲸澜正在重构计划并武装验收锁…")
                ProactiveRuntime.negotiatePlanModal(cleanText) { ok, reply, armedCount, err ->
                    main.post {
                        if (root == null) return@post
                        if (ok) {
                            val note = reply?.takeIf { it.isNotBlank() } ?: "计划已重构"
                            setStatus("✓ $note（已武装 $armedCount 块验收锁）", isError = false)
                            main.postDelayed({ dismissPlanModal() }, 1_000L)
                        } else {
                            setBusy(false)
                            setStatus(err ?: "重构失败，请重试", isError = true)
                        }
                    }
                }
            }
            negotiateBtn = nBtn
            card.addView(nBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(44f)).apply {
                bottomMargin = context.dp(8f)
            })

            val snoozeAllowed = PlanModalPolicy.canSnooze(spec)
            val snoozeLabel = if (snoozeAllowed) {
                "延后 15 分钟（今日还剩 ${spec.snoozeLeft} 次）"
            } else {
                "今日必须确认计划（不可再延后）"
            }
            val sBtn = modalBtn(snoozeLabel, style = 2) {
                if (busy || !snoozeAllowed) return@modalBtn
                setBusy(true, "正在延后 15 分钟…")
                ProactiveRuntime.snoozePlanModal(15) { ok, autoArmed, err ->
                    main.post {
                        if (root == null) return@post
                        if (ok) {
                            val toastMsg = if (autoArmed) {
                                "延后已达上限，系统已自动钉入 25m 起手验收锁"
                            } else {
                                "已延后 15 分钟，到点请确认今日计划"
                            }
                            runCatching { android.widget.Toast.makeText(app, toastMsg, android.widget.Toast.LENGTH_LONG).show() }
                            dismissPlanModal()
                        } else {
                            setBusy(false)
                            setStatus(err ?: "延后失败，请直接确认或重构计划", isError = true)
                        }
                    }
                }
            }.apply {
                isEnabled = snoozeAllowed
                alpha = if (snoozeAllowed) 1f else 0.45f
            }
            snoozeBtn = sBtn
            card.addView(sBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, context.dp(42f)))

            val scroll = ScrollView(context).apply {
                isFillViewport = false
                addView(card)
            }
            scrim.addView(
                scroll,
                FrameLayout.LayoutParams(maxW, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER).apply {
                    topMargin = context.dp(24f)
                    bottomMargin = context.dp(24f)
                },
            )
            return scrim
        }

        private fun setBusy(on: Boolean, statusMsg: String? = null) {
            busy = on
            confirmBtn?.isEnabled = !on
            confirmBtn?.alpha = if (on) 0.5f else 1f
            negotiateBtn?.isEnabled = !on
            negotiateBtn?.alpha = if (on) 0.5f else 1f
            val snoozeAllowed = PlanModalPolicy.canSnooze(spec)
            snoozeBtn?.isEnabled = !on && snoozeAllowed
            snoozeBtn?.alpha = if (!on && snoozeAllowed) 1f else 0.45f
            if (statusMsg != null) setStatus(statusMsg, isError = false)
        }

        private fun setStatus(msg: String?, isError: Boolean) {
            statusView?.apply {
                text = msg ?: ""
                visibility = if (msg.isNullOrBlank()) View.GONE else View.VISIBLE
                setTextColor(if (isError) palette.danger else palette.accent)
            }
        }

        fun destroy() {
            val v = root ?: return
            root = null
            runCatching { wm?.removeViewImmediate(v) }
        }
    }

    /**
     * 本轮 Gemini Live 结束后的零打字 3 键接力卡（或秒退防逃离护航卡）。
     */
    private class PostLiveRelayCard(
        private val context: Context,
        private val type: Int,
        private val durationSec: Long,
        private val onCheckpoint25m: () -> Unit,
        private val onQuizCapsule20m: () -> Unit,
        private val onRecapOnly: () -> Unit,
        private val earlyBailout: Boolean = false,
        private val onReturnToLive: (() -> Unit)? = null,
        private val onSwitchToText: (() -> Unit)? = null,
        private val onPauseKeeper15m: (() -> Unit)? = null,
    ) {
        private val palette = OverlayPalette.of(context)
        private val wm = context.getSystemService(WindowManager::class.java)
        private var root: View? = null
        private val autoTimeout = Runnable { destroy() }

        fun attach() {
            val view = buildView()
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = context.dp(56f)
                title = "大肥鱼 Live 接力卡"
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                val maxW = (context.resources.displayMetrics.widthPixels * 0.90f).toInt()
                width = minOf(maxW, context.dp(400f))
            }
            root = view
            wm!!.addView(view, p)
            if (!earlyBailout) {
                main.postDelayed(autoTimeout, 45_000L)
            }
        }

        private fun buildView(): View {
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = context.dp(14f)
                setPadding(pad, context.dp(12f), pad, context.dp(12f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(18f).toFloat()
                    setColor(palette.fill)
                    setStroke(context.dp(1.5f).coerceAtLeast(1), palette.accent)
                }
            }

            val head = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            head.addView(TextView(context).apply {
                text = if (earlyBailout) {
                    "⚡ 刚进 Live 仅 ${PostLiveRelayPolicy.formatDuration(durationSec)} 就退出了"
                } else {
                    "🎙️ 本轮 Live 结束（${PostLiveRelayPolicy.formatDuration(durationSec)}）"
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(palette.accent)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            card.addView(head)

            card.addView(TextView(context).apply {
                text = if (earlyBailout) {
                    "常驻 Live 护航开启中：选定下一步继续训练，别静默溜走："
                } else {
                    "趁脑子还热，立刻接下一棒或切文本调 MCP 沉淀："
                }
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTextColor(palette.ink)
                setPadding(0, context.dp(4f), 0, context.dp(10f))
            })

            fun actionBtn(label: String, accent: Boolean = false, onTap: () -> Unit) = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f)
                setTypeface(typeface, if (accent) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                setTextColor(if (accent) palette.onAccent else palette.ink)
                setPadding(context.dp(12f), context.dp(9f), context.dp(12f), context.dp(9f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(12f).toFloat()
                    setColor(if (accent) palette.accent else palette.pressed)
                }
                isClickable = true
                setOnClickListener {
                    destroy()
                    onTap()
                }
            }

            if (earlyBailout) {
                card.addView(
                    actionBtn("🎙️ 回到 Live（语音 + 看屏）", accent = true) { onReturnToLive?.invoke() },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = context.dp(6f)
                    },
                )
                card.addView(
                    actionBtn("💬 切 Gemini 文本（MCP 沉淀）", accent = false) { onSwitchToText?.invoke() },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = context.dp(6f)
                    },
                )
                card.addView(
                    actionBtn("⏸ 暂停护航 15 分钟", accent = false) { onPauseKeeper15m?.invoke() },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                )
            } else {
                card.addView(
                    actionBtn("签 25m 验收点", accent = true, onTap = onCheckpoint25m),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = context.dp(6f)
                    },
                )
                if (onSwitchToText != null) {
                    card.addView(
                        actionBtn("💬 切 Gemini 文本（MCP 沉淀）", accent = false, onTap = onSwitchToText),
                        LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                            bottomMargin = context.dp(6f)
                        },
                    )
                }
                card.addView(
                    actionBtn("进 20m 纯刷题舱", accent = false, onTap = onQuizCapsule20m),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                        bottomMargin = context.dp(6f)
                    },
                )
                card.addView(
                    actionBtn("仅记复盘收起", accent = false, onTap = onRecapOnly),
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT),
                )
            }
            return card
        }

        fun destroy() {
            main.removeCallbacks(autoTimeout)
            val v = root ?: return
            root = null
            runCatching { wm?.removeView(v) }
            if (livePostRelay === this) livePostRelay = null
        }
    }

    /** 弹出 #1 亮屏 1 秒左右盲点微题（无收起/关闭键，玄墨金箔风格，左半对 / 右半错）。 */
    fun showWakeMicroQuiz(
        spec: WakeQuizSpec,
        onAnswered: (userTrue: Boolean, elapsedMs: Long, feedback: (Boolean, String) -> Unit) -> Unit,
    ): Boolean {
        val context = app ?: return false
        dismissWakeMicroQuiz()
        val host = host(context) ?: return false
        val card = WakeQuizCard(host.first, host.second, spec, onAnswered)
        return try {
            card.attach()
            liveWakeQuiz = card
            true
        } catch (e: Exception) {
            android.util.Log.i(TAG, "wake micro quiz add failed: ${e.javaClass.simpleName}")
            card.destroy()
            false
        }
    }

    fun dismissWakeMicroQuiz() {
        liveWakeQuiz?.destroy()
        liveWakeQuiz = null
    }

    private class WakeQuizCard(
        private val context: Context,
        private val windowType: Int,
        private val spec: WakeQuizSpec,
        private val onAnswered: (userTrue: Boolean, elapsedMs: Long, feedback: (Boolean, String) -> Unit) -> Unit,
    ) {
        private val wm = context.getSystemService(WindowManager::class.java)
        private var root: View? = null
        private var shownAtMs: Long = 0L
        private var answered: Boolean = false
        private lateinit var claimView: TextView
        private lateinit var feedbackView: TextView
        private lateinit var btnRow: LinearLayout

        fun attach() {
            shownAtMs = System.currentTimeMillis()
            val v = buildView()
            val p = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                windowType,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = context.dp(34f)
                title = "大肥鱼亮屏微题"
                if (Build.VERSION.SDK_INT >= 28) {
                    layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                if (Build.VERSION.SDK_INT >= 30) setFitInsetsTypes(0)
                val maxW = (context.resources.displayMetrics.widthPixels * 0.94f).toInt()
                width = minOf(maxW, context.dp(420f))
            }
            root = v
            wm?.addView(v, p)
        }

        private fun buildView(): View {
            val obsidianBg = Color.parseColor("#EE0B0B0F")
            val gold = Color.parseColor("#C5A059")
            val goldBright = Color.parseColor("#E8CF82")
            val ink = Color.parseColor("#ECE7DC")
            val sealRed = Color.parseColor("#A63429")

            val container = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                val pad = context.dp(16f)
                setPadding(pad, context.dp(14f), pad, context.dp(14f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(18f).toFloat()
                    setColor(obsidianBg)
                    setStroke(context.dp(1.2f).coerceAtLeast(1), gold)
                }
            }

            val topRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            topRow.addView(TextView(context).apply {
                text = "万世铭 · ${spec.sourceLabel} · 弹药 ${spec.activePoolCount}"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f)
                setTextColor(gold)
            }, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            topRow.addView(TextView(context).apply {
                text = "点判即过"
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
                setTextColor(Color.parseColor("#FAF5EB"))
                setPadding(context.dp(7f), context.dp(2f), context.dp(7f), context.dp(2f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(4f).toFloat()
                    setColor(sealRed)
                }
            })
            container.addView(topRow)

            claimView = TextView(context).apply {
                text = spec.claim
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 15.5f)
                setLineSpacing(context.dp(3f).toFloat(), 1f)
                setTextColor(ink)
                setPadding(0, context.dp(10f), 0, context.dp(12f))
            }
            container.addView(claimView)

            feedbackView = TextView(context).apply {
                visibility = View.GONE
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f)
                setPadding(0, 0, 0, context.dp(10f))
            }
            container.addView(feedbackView)

            fun halfBtn(label: String, strokeColor: Int, textColor: Int, userTrue: Boolean): TextView = TextView(context).apply {
                text = label
                gravity = Gravity.CENTER
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14.5f)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(textColor)
                setPadding(context.dp(10f), context.dp(13f), context.dp(10f), context.dp(13f))
                background = GradientDrawable().apply {
                    cornerRadius = context.dp(12f).toFloat()
                    setColor(Color.parseColor("#181820"))
                    setStroke(context.dp(1f).coerceAtLeast(1), strokeColor)
                }
                isClickable = true
                setOnClickListener {
                    if (answered) return@setOnClickListener
                    answered = true
                    val elapsed = (System.currentTimeMillis() - shownAtMs).coerceAtLeast(1L)
                    onAnswered(userTrue, elapsed) { ok, correction ->
                        main.post {
                            feedbackView.visibility = View.VISIBLE
                            feedbackView.setTextColor(if (ok) goldBright else Color.parseColor("#F08A7E"))
                            feedbackView.text = if (ok) {
                                "✓ 判得准 · ${correction.ifBlank { "已记账消解" }}"
                            } else {
                                "✕ 踩坑了 · ${correction.ifBlank { "注意纠偏" }}"
                            }
                            btnRow.alpha = 0.45f
                            main.postDelayed({ destroy() }, if (ok) 520L else 1250L)
                        }
                    }
                }
            }

            btnRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
            }
            btnRow.addView(
                halfBtn("✓ 对 / 成立", gold, goldBright, true),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = context.dp(8f)
                },
            )
            btnRow.addView(
                halfBtn("✕ 错 / 有坑", sealRed, Color.parseColor("#F2D8D4"), false),
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
            container.addView(btnRow)
            return container
        }

        fun destroy() {
            val v = root ?: return
            root = null
            runCatching { wm?.removeView(v) }
            if (liveWakeQuiz === this) liveWakeQuiz = null
        }
    }
}
