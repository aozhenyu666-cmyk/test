package com.zongkong.app.window

import android.accessibilityservice.AccessibilityService
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.zongkong.app.director.DirectorHost
import com.zongkong.app.system.Voice
import com.zongkong.app.zk
import com.zongkong.core.ApiResult
import com.zongkong.core.director.Card
import com.zongkong.core.director.Opening
import com.zongkong.core.director.Reply
import com.zongkong.core.director.WindowPort
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 墨灵的小窗（施工队 A 的 A2）。
 *
 * 收起时是一颗小圆点（可拖、松手贴边），展开是一张卡：一次只突出一个问题，下面是可以直接念给演员的话。
 * 借无障碍服务的悬浮层（TYPE_ACCESSIBILITY_OVERLAY），和桌面宠物同一套办法，不需要额外的悬浮窗权限，
 * 所以在 Gemini Live 全屏时也浮得上去（P1 待真机确认）。
 *
 * 规矩：窗口里不放输入框（悬浮窗不抢焦点，键盘弹不出来）。要写字就去总控里写；这里只做“点一下”和“说一句”。
 */
object MailuoWindow : WindowPort {
    private const val TAG = "MailuoWindow"

    /** 窗口把用户的动作交回导演宿主。 */
    interface Host {
        fun reply(reply: Reply)
        fun onSession(log: com.zongkong.core.director.SessionLog)
        fun cardClosed()
    }

    private var host: Host? = null
    private var service: AccessibilityService? = null
    private var wm: WindowManager? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private var dot: View? = null
    private var dotLp: WindowManager.LayoutParams? = null
    private var box: LinearLayout? = null
    private var boxLp: WindowManager.LayoutParams? = null

    private var card: Card? = null
    private var countdown: TextView? = null
    private var ticker: Runnable? = null
    private var recording = false

    private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    private val ink = Color.rgb(0x2A, 0x24, 0x1F)
    private val paper = Color.rgb(0xFB, 0xF8, 0xF2)
    private val accent = Color.rgb(0xC1, 0x66, 0x3E)
    private val muted = Color.rgb(0x8A, 0x7F, 0x73)

    fun attachHost(h: Host) {
        host = h
    }

    /** 无障碍服务连上时调用。 */
    fun attach(s: AccessibilityService) {
        service = s
        wm = s.getSystemService(WindowManager::class.java)
        if (dot == null) ensureDot(s)
        // 服务重连后把还没处理的卡补上
        card?.let { show(it) }
    }

    fun detach() {
        stopTicker()
        box?.let { runCatching { wm?.removeView(it) } }
        box = null
        dot?.let { runCatching { wm?.removeView(it) } }
        dot = null
        service = null
        recording = false
    }

    private fun prefs(c: Context) = c.getSharedPreferences("pet", Context.MODE_PRIVATE)

    private fun params(c: Context, w: Int, h: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    // ---------- 小圆点 ----------

    private fun ensureDot(s: AccessibilityService) {
        if (dot != null) return
        val size = dp(s, 44f)
        val p = prefs(s)
        val x = p.getInt("mailuo_x", s.resources.displayMetrics.widthPixels - size - dp(s, 6f))
        val y = p.getInt("mailuo_y", (s.resources.displayMetrics.heightPixels * 0.55f).toInt())
        val lp = params(s, size, size, x, y)
        val v = TextView(s).apply {
            text = "墨"
            setTextColor(Color.WHITE)
            textSize = 15f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(accent)
                setStroke(dp(s, 1.5f), Color.argb(60, 0, 0, 0))
            }
            elevation = dp(s, 4f).toFloat()
            setOnTouchListener(dragTouch(s))
        }
        try {
            wm?.addView(v, lp)
            dot = v
            dotLp = lp
        } catch (e: Exception) {
            Log.w(TAG, "加小圆点失败", e)
        }
    }

    private fun removeDot() {
        dot?.let { runCatching { wm?.removeView(it) } }
        dot = null
    }

    // ---------- 卡 ----------

    override fun show(card: Card) {
        this.card = card
        main.post { render(card) }
    }

    override fun hide() {
        main.post {
            stopTicker()
            box?.let { runCatching { wm?.removeView(it) } }
            box = null
            card = null
        }
    }

    override fun speak(text: String) {
        val s = service ?: return
        val cfg = runCatching { s.zk.store.config.value }.getOrNull() ?: return
        // 睡觉时段不吵；说话开关在导演设置里
        if (cfg.mailuo.speak) Voice.speak(s, text)
    }

    private fun render(card: Card) {
        val s = service ?: return
        ensureDot(s)
        box?.let { runCatching { wm?.removeView(it) } }
        box = null
        stopTicker()

        val pad = dp(s, 16f)
        val root = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(s, 14f), pad, dp(s, 12f))
            background = GradientDrawable().apply {
                setColor(paper)
                cornerRadius = dp(s, 20f).toFloat()
                setStroke(dp(s, 1.2f), Color.argb(70, 0x2A, 0x24, 0x1F))
            }
            elevation = dp(s, 8f).toFloat()
        }

        // 标题 + 关闭
        val head = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(s, card.title, 13f, muted, bold = true, maxLines = 1).also { it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        head.addView(text(s, "×", 18f, muted).apply {
            setPadding(dp(s, 10f), 0, 0, dp(s, 2f))
            setOnClickListener { dismiss(false) }
        })
        root.addView(head, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 现在这一问
        root.addView(text(s, card.ask, 17f, ink, bold = true).apply {
            setPadding(0, dp(s, 6f), 0, dp(s, 4f))
            setLineSpacing(0f, 1.2f)
        })

        if (card.confidence.isNotBlank()) {
            root.addView(text(s, "我猜（把握：${card.confidence}）", 12f, muted).apply { setPadding(0, 0, 0, dp(s, 2f)) })
        }

        // 可以直接念给演员的话
        for (line in card.sayLines) {
            root.addView(text(s, "▸ $line", 14f, ink).apply {
                setPadding(0, dp(s, 6f), 0, dp(s, 2f))
                setLineSpacing(0f, 1.15f)
                setOnClickListener { copy(s, line, "已复制，念给 TA 听") }
            })
        }

        if (card.queued > 0) root.addView(text(s, "还有 ${card.queued} 个", 12f, muted).apply { setPadding(0, dp(s, 4f), 0, 0) })

        if (card.minutes > 0) {
            val tv = text(s, "", 12f, accent)
            tv.setPadding(0, dp(s, 4f), 0, 0)
            root.addView(tv)
            countdown = tv
            startTicker(s, card)
        }

        // 选项
        if (card.choices.isNotEmpty()) {
            for (c in card.choices) {
                root.addView(button(s, c.label) {
                    if (c.key == "go") Relay.begin(s, card) else reply(Reply.Pick(card.id, System.currentTimeMillis(), c.key))
                }, rowParams(s))
            }
        } else {
            val row1 = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL }
            row1.addView(button(s, "继续这一问") { reply(Reply.Continue(card.id, System.currentTimeMillis())) }, halfParams(s, true))
            row1.addView(button(s, "卡住了") { reply(Reply.Stuck(card.id, System.currentTimeMillis())) }, halfParams(s, false))
            root.addView(row1, rowParams(s))
            val row2 = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL }
            val mic = button(s, "有结论 🎤") { conclusion(s, card) }
            row2.addView(mic, halfParams(s, true))
            row2.addView(button(s, "休息") { reply(Reply.Rest(card.id, System.currentTimeMillis(), 0)) }, halfParams(s, false))
            root.addView(row2, rowParams(s))
        }

        // 去演员那里聊
        if (card.actor != com.zongkong.core.director.Actor.NONE) {
            root.addView(button(s, "去聊：${card.actor.label}", primary = true) { Relay.begin(s, card) }, rowParams(s))
        }

        // 记错了
        root.addView(text(s, "你猜错了", 12f, muted).apply {
            setPadding(0, dp(s, 8f), 0, 0)
            setOnClickListener { reply(Reply.Wrong(card.id, System.currentTimeMillis(), "")) }
        })

        val w = minOf((s.resources.displayMetrics.widthPixels * 0.86f).toInt(), dp(s, 330f))
        val left = dotLp?.let { it.x + it.width / 2 < s.resources.displayMetrics.widthPixels / 2 } ?: true
        val x = if (left) dp(s, 8f) else s.resources.displayMetrics.widthPixels - w - dp(s, 8f)
        val y = ((dotLp?.y ?: dp(s, 240f)) - dp(s, 40f)).coerceAtLeast(dp(s, 40f))
        val lp = params(s, w, WindowManager.LayoutParams.WRAP_CONTENT, x, y)
        try {
            wm?.addView(root, lp)
            box = root
            boxLp = lp
        } catch (e: Exception) {
            Log.w(TAG, "加卡片失败", e)
        }
    }

    /** 开场白：给演员的那段话，可以复制、展开、照着念。 */
    fun showOpening(opening: Opening, onBegin: () -> Unit) {
        val s = service ?: return
        box?.let { runCatching { wm?.removeView(it) } }
        box = null
        stopTicker()

        val pad = dp(s, 16f)
        val root = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, dp(s, 14f), pad, dp(s, 12f))
            background = GradientDrawable().apply {
                setColor(paper); cornerRadius = dp(s, 20f).toFloat(); setStroke(dp(s, 1.2f), Color.argb(70, 0x2A, 0x24, 0x1F))
            }
            elevation = dp(s, 8f).toFloat()
        }
        val head = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(text(s, "开场白 · ${opening.actor.label}", 13f, muted, bold = true).also { it.layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f) })
        head.addView(text(s, "×", 18f, muted).apply { setPadding(dp(s, 10f), 0, 0, 0); setOnClickListener { dismiss(false) } })
        root.addView(head)

        val scroll = ScrollView(s).apply { isFillViewport = false }
        val body = text(s, opening.text, 14f, ink)
        body.setLineSpacing(0f, 1.2f)
        scroll.addView(body)
        root.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(s, 200f)).apply { topMargin = dp(s, 8f) })

        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(button(s, "复制") { copy(s, opening.text, "已复制，粘贴进 ${opening.actor.label}") }, halfParams(s, true))
        row.addView(button(s, "照念也行", primary = false) { copy(s, opening.text, "照着念：不用一字不差") }, halfParams(s, false))
        root.addView(row, rowParams(s))
        root.addView(button(s, "进 ${opening.actor.label} 了，开始", primary = true) { onBegin() }, rowParams(s))

        val w = minOf((s.resources.displayMetrics.widthPixels * 0.9f).toInt(), dp(s, 340f))
        val lp = params(s, w, WindowManager.LayoutParams.WRAP_CONTENT, dp(s, 8f), dp(s, 120f))
        try {
            wm?.addView(root, lp)
            box = root
            boxLp = lp
        } catch (e: Exception) {
            Log.w(TAG, "加开场白失败", e)
        }
    }

    // ---------- 交互 ----------

    private fun reply(r: Reply) {
        host?.reply(r)
    }

    private fun dismiss(timeout: Boolean) {
        val c = card
        stopTicker()
        if (recording) {
            Voice.cancelRecording()
            recording = false
        }
        box?.let { runCatching { wm?.removeView(it) } }
        box = null
        card = null
        if (c != null) {
            host?.reply(Reply.Dismissed(c.id, System.currentTimeMillis(), timeout))
            host?.cardClosed()
        }
    }

    /** 有结论：第一次按开始听写，第二次按结束并交给导演。听写不可用时如实说，不装作听见了。 */
    private fun conclusion(s: Context, c: Card) {
        if (!recording) {
            if (Voice.startRecording(s)) {
                recording = true
                note(s, "在听——说完再点一次")
            } else {
                note(s, "这里听不了（没麦克风权限或没配听写接口），去总控里说")
            }
            return
        }
        recording = false
        note(s, "听完了，正在转文字…")
        scope.launch {
            when (val r = Voice.stopAndTranscribe(s)) {
                is ApiResult.Ok -> {
                    Relay.noteConclusion(r.value)
                    reply(Reply.Conclusion(c.id, System.currentTimeMillis(), r.value))
                }
                is ApiResult.Err -> note(s, r.message)
            }
        }
    }

    private fun note(s: Context, text: String) {
        // 用一颗短提示：不打断，也不占地方
        main.post {
            val b = box ?: return@post
            val tv = TextView(s).apply {
                this.text = text
                textSize = 12f
                setTextColor(muted)
                setPadding(0, dp(s, 6f), 0, 0)
            }
            b.addView(tv)
            main.postDelayed({ runCatching { b.removeView(tv) } }, 4000)
        }
    }

    private fun copy(s: Context, text: String, hint: String) {
        try {
            val cm = s.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("墨灵", text))
            note(s, hint)
        } catch (e: Exception) {
            Log.w(TAG, "复制失败", e)
        }
    }

    private fun startTicker(s: Context, c: Card) {
        val tv = countdown ?: return
        val endAt = (if (c.createdAt > 0) c.createdAt else System.currentTimeMillis()) + c.minutes * 60_000L
        ticker = object : Runnable {
            override fun run() {
                val left = endAt - System.currentTimeMillis()
                if (left <= 0) {
                    tv.text = "到时间了"
                    dismiss(true)
                    return
                }
                val min = left / 60_000
                val sec = (left % 60_000) / 1000
                tv.text = "还剩 %d:%02d".format(min, sec)
                main.postDelayed(this, 1000)
            }
        }.also { main.post(it) }
    }

    private fun stopTicker() {
        ticker?.let { main.removeCallbacks(it) }
        ticker = null
        countdown = null
    }

    // ---------- 小零件 ----------

    private fun text(c: Context, s: String, size: Float, color: Int, bold: Boolean = false, maxLines: Int = 20): TextView =
        TextView(c).apply {
            text = s
            textSize = size
            setTextColor(color)
            this.maxLines = maxLines
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun button(c: Context, label: String, primary: Boolean = false, onClick: () -> Unit): TextView =
        TextView(c).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else ink)
            setPadding(dp(c, 12f), dp(c, 9f), dp(c, 12f), dp(c, 9f))
            background = GradientDrawable().apply {
                cornerRadius = dp(c, 12f).toFloat()
                if (primary) setColor(accent) else { setColor(Color.argb(16, 0x2A, 0x24, 0x1F)); setStroke(dp(c, 1f), Color.argb(40, 0x2A, 0x24, 0x1F)) }
            }
            setOnClickListener { onClick() }
        }

    private fun rowParams(c: Context) = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(c, 8f)
    }

    private fun halfParams(c: Context, left: Boolean) = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        marginEnd = if (left) dp(c, 4f) else 0
        marginStart = if (left) 0 else dp(c, 4f)
    }

    /** 拖动小圆点；点一下展开最近的卡，没有卡就什么都不做。 */
    private fun dragTouch(c: Context): View.OnTouchListener {
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        return object : View.OnTouchListener {
            override fun onTouch(v: View, e: MotionEvent): Boolean {
                val lp = dotLp ?: return false
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dx = e.rawX - downX
                        val dy = e.rawY - downY
                        if (dragging || dx * dx + dy * dy > 400) {
                            dragging = true
                            lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                            runCatching { wm?.updateViewLayout(v, lp) }
                        }
                    }
                    MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                        if (dragging) {
                            val w = v.resources.displayMetrics.widthPixels
                            lp.x = if (lp.x + lp.width / 2 < w / 2) 0 else w - lp.width
                            runCatching { wm?.updateViewLayout(v, lp) }
                            prefs(c).edit().putInt("mailuo_x", lp.x).putInt("mailuo_y", lp.y).apply()
                        } else {
                            val cur = card ?: DirectorHost.currentCard()
                            if (cur != null) show(cur)
                        }
                    }
                }
                return true
            }
        }
    }
}
