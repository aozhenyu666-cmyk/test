package com.zongkong.app.system

import android.Manifest
import android.accessibilityservice.AccessibilityService
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import com.zongkong.app.ui.MainActivity
import com.zongkong.app.zk
import com.zongkong.core.ApiResult
import com.zongkong.core.boss.Stance
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 桌面宠物：一只线条画的小墨灵，浮在所有应用上面（借无障碍服务的悬浮层，不用另开“悬浮窗”权限）。
 *  - 点一下：弹出它最近说的话，和“说话 / 打开 / 收起”三个按钮
 *  - 按住：对它说话（配了听写接口时直接录音；没配就打开对讲页），松手发出去，它的回答显示在气泡里并念出来
 *  - 拖动：挪位置，松手贴边
 *  - 颜色跟着态度变：信任绿、正常赭、审慎琥珀、严格朱红；头上一根叶脉触角，主动找你时会跳一下
 */
class PetView(context: Context) : View(context) {
    enum class Mood { IDLE, TALK, LISTEN, THINK, ALERT, SLEEP }

    var mood = Mood.IDLE
        set(v) { field = v; invalidate() }
    var stance = Stance.NORMAL
        set(v) { field = v; invalidate() }
    private val born = SystemClock.uptimeMillis()
    private var bounceAt = 0L

    fun bounce() { bounceAt = SystemClock.uptimeMillis(); invalidate() }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val t = (SystemClock.uptimeMillis() - born) / 1000f
        paint(canvas, width.toFloat(), height.toFloat(), t, mood, stance, ((SystemClock.uptimeMillis() - bounceAt) / 600f).coerceIn(0f, 1f))
        if (isAttachedToWindow) postInvalidateDelayed(if (mood == Mood.IDLE || mood == Mood.SLEEP) 66 else 33)
    }

    companion object {
        fun color(s: Stance): Int = when (s) {
            Stance.TRUST -> Color.rgb(0x4F, 0x8A, 0x5B)
            Stance.NORMAL -> Color.rgb(0xC1, 0x66, 0x3E)
            Stance.WARY -> Color.rgb(0xC9, 0x8A, 0x2B)
            Stance.STRICT -> Color.rgb(0xB8, 0x3A, 0x2E)
        }

        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        /** 画一只墨灵。[bounce] 0 → 1 是刚跳完的进度（1 = 不跳）。 */
        fun paint(c: Canvas, w: Float, h: Float, t: Float, mood: Mood, stance: Stance, bounce: Float) {
            val accent = color(stance)
            val inkColor = Color.rgb(0x2A, 0x24, 0x1F)
            val cx = w / 2
            val hop = if (bounce < 1f) -sin(bounce * PI.toFloat()) * h * 0.12f else 0f
            val breathe = if (mood == Mood.SLEEP) sin(t * 1.2f) * 0.015f else sin(t * 2.2f) * 0.03f
            val r = minOf(w, h) * 0.30f * (1 + breathe)
            val cy = h * 0.58f + hop
            // 听的时候：涟漪
            if (mood == Mood.LISTEN) for (i in 0 until 3) {
                val f = ((t * 0.8f) + i / 3f) % 1f
                ink.color = accent; ink.alpha = ((1 - f) * 200).toInt(); ink.strokeWidth = 2f
                c.drawCircle(cx, cy, r * (1.05f + 0.6f * f), ink)
            }
            // 身体：一团微微起伏的墨，纸色填充
            val body = Path()
            for (k in 0..48) {
                val a = (2 * PI * k / 48).toFloat()
                val wob = 1 + 0.045f * sin(a * 3 + t * 1.6f) + 0.03f * sin(a * 5 - t * 2.1f)
                val x = cx + r * wob * cos(a)
                val y = cy + r * wob * sin(a) * 0.92f
                if (k == 0) body.moveTo(x, y) else body.lineTo(x, y)
            }
            body.close()
            fill.color = Color.rgb(0xFB, 0xF8, 0xF2); fill.alpha = 245
            c.drawPath(body, fill)
            ink.color = inkColor; ink.alpha = 255; ink.strokeWidth = r * 0.09f
            c.drawPath(body, ink)
            // 头上的叶脉触角，摆
            val sway = sin(t * 1.7f) * r * 0.25f
            val tip = floatArrayOf(cx + sway, cy - r * 1.75f)
            val stem = Path().apply { moveTo(cx, cy - r * 0.95f); quadTo(cx - r * 0.1f, cy - r * 1.35f, tip[0], tip[1]) }
            ink.color = accent; ink.strokeWidth = r * 0.08f
            c.drawPath(stem, ink)
            ink.strokeWidth = r * 0.05f
            for (side in listOf(-1f, 1f)) {
                val m = floatArrayOf(cx + sway * 0.6f, cy - r * 1.45f)
                c.drawLine(m[0], m[1], m[0] + side * r * 0.28f, m[1] - r * 0.18f, ink)
            }
            fill.color = accent; fill.alpha = 255
            c.drawCircle(tip[0], tip[1], r * 0.1f, fill)
            // 眼睛：眨眼、睡着是两道弧、听的时候睁大
            val eyeY = cy - r * 0.1f
            val dx = r * 0.36f
            val blink = (t % 4.3f) < 0.13f
            fill.color = inkColor
            ink.color = inkColor; ink.strokeWidth = r * 0.07f
            for (s in listOf(-1f, 1f)) {
                val ex = cx + s * dx
                when {
                    mood == Mood.SLEEP -> c.drawArc(RectF(ex - r * 0.12f, eyeY - r * 0.08f, ex + r * 0.12f, eyeY + r * 0.1f), 0f, 180f, false, ink)
                    blink -> c.drawLine(ex - r * 0.1f, eyeY, ex + r * 0.1f, eyeY, ink)
                    else -> c.drawCircle(ex, eyeY, r * if (mood == Mood.LISTEN) 0.12f else 0.085f, fill)
                }
            }
            // 嘴：说话时一开一合，想的时候是一条线，警觉时是 o
            val my = cy + r * 0.3f
            when (mood) {
                Mood.TALK -> {
                    val open = (sin(t * 14f) * 0.5f + 0.5f) * r * 0.16f + r * 0.03f
                    c.drawOval(RectF(cx - r * 0.13f, my - open / 2, cx + r * 0.13f, my + open / 2), fill)
                }
                Mood.ALERT -> c.drawCircle(cx, my, r * 0.08f, ink)
                Mood.THINK -> c.drawLine(cx - r * 0.12f, my, cx + r * 0.12f, my, ink)
                else -> c.drawArc(RectF(cx - r * 0.16f, my - r * 0.12f, cx + r * 0.16f, my + r * 0.08f), 20f, 140f, false, ink)
            }
            // 腮红（态度色）
            fill.color = accent; fill.alpha = 60
            c.drawCircle(cx - r * 0.58f, cy + r * 0.12f, r * 0.12f, fill)
            c.drawCircle(cx + r * 0.58f, cy + r * 0.12f, r * 0.12f, fill)
            // 警觉：旁边一道小闪电
            if (mood == Mood.ALERT || stance == Stance.STRICT) {
                ink.color = Color.rgb(0xB8, 0x3A, 0x2E); ink.alpha = (180 + 75 * sin(t * 9f)).toInt().coerceIn(0, 255); ink.strokeWidth = r * 0.07f
                val bx = cx + r * 1.15f
                val by = cy - r * 0.9f
                val bolt = Path().apply { moveTo(bx, by); lineTo(bx - r * 0.18f, by + r * 0.32f); lineTo(bx + r * 0.02f, by + r * 0.32f); lineTo(bx - r * 0.16f, by + r * 0.7f) }
                c.drawPath(bolt, ink)
            }
            // 想：头顶一个小漩涡
            if (mood == Mood.THINK) {
                ink.color = accent; ink.alpha = 220; ink.strokeWidth = r * 0.04f
                val sp = Path()
                for (k in 0..40) {
                    val rr = r * 0.02f + k * r * 0.006f
                    val a = k * 0.45f + t * 4f
                    val x = cx + r * 0.95f + rr * cos(a)
                    val y = cy - r * 1.25f + rr * sin(a)
                    if (k == 0) sp.moveTo(x, y) else sp.lineTo(x, y)
                }
                c.drawPath(sp, ink)
            }
            // 睡：zz
            if (mood == Mood.SLEEP) {
                fill.color = inkColor; fill.alpha = 150; fill.textSize = r * 0.4f
                c.drawText("z", cx + r * 0.9f, cy - r * 0.9f - (t % 2f) * r * 0.2f, fill)
            }
        }

        /** 桌面小部件用：画成一张图。 */
        fun render(sizePx: Int, mood: Mood, stance: Stance): Bitmap {
            val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            paint(Canvas(b), sizePx.toFloat(), sizePx.toFloat(), 0.4f, mood, stance, 1f)
            return b
        }
    }
}

/** 悬浮层：墨灵和它的气泡。 */
@SuppressLint("StaticFieldLeak")
object Pet {
    private const val TAG = "Pet"
    private var service: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var pet: PetView? = null
    private var petLp: WindowManager.LayoutParams? = null
    private var bubble: LinearLayout? = null
    private var bubbleText: TextView? = null
    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var hideBubble: Runnable? = null
    private var lastSaid = "有事按住我说。"

    private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics).toInt()

    private fun params(c: Context, w: Int, h: Int, x: Int, y: Int) = WindowManager.LayoutParams(
        w, h, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT,
    ).apply { gravity = Gravity.TOP or Gravity.START; this.x = x; this.y = y }

    /** 无障碍服务连上时调用。 */
    fun attach(s: AccessibilityService) {
        service = s
        wm = s.getSystemService(WindowManager::class.java)
        refresh(s)
    }

    fun detach() {
        removeBubble()
        pet?.let { runCatching { wm?.removeView(it) } }
        pet = null
        service = null
    }

    private fun prefs(c: Context) = c.getSharedPreferences("pet", Context.MODE_PRIVATE)

    /** 收起一段时间。 */
    fun snooze(c: Context, minutes: Int) {
        prefs(c).edit().putLong("hidden_until", System.currentTimeMillis() + minutes * 60_000L).apply()
        refresh(c)
    }

    private fun wanted(c: Context): Boolean {
        val cfg = c.zk.store.config.value.boss
        return cfg.pet && System.currentTimeMillis() > prefs(c).getLong("hidden_until", 0) && service != null
    }

    /** 该显示就显示、该收就收，心情跟着态度。守护服务每 20 秒调一次。 */
    fun refresh(c: Context) {
        main.post {
            val s = service ?: return@post
            if (!wanted(c)) {
                removeBubble()
                pet?.let { runCatching { wm?.removeView(it) } }
                pet = null
                return@post
            }
            if (pet == null) {
                val size = dp(s, 64f)
                val p = prefs(s)
                val lp = params(s, size, size, p.getInt("x", dp(s, 8f)), p.getInt("y", dp(s, 220f)))
                val v = PetView(s)
                v.setOnTouchListener(Touch(s))
                try {
                    wm?.addView(v, lp)
                    pet = v
                    petLp = lp
                } catch (e: Exception) {
                    Log.w(TAG, "加悬浮层失败", e)
                    return@post
                }
            }
            val store = c.zk.store
            pet?.stance = store.stance()
            val quiet = com.zongkong.core.hq.Hq.quiet(store.config.value.hq, System.currentTimeMillis(), store.zone)
            if (pet?.mood == PetView.Mood.IDLE || pet?.mood == PetView.Mood.SLEEP) pet?.mood = if (quiet) PetView.Mood.SLEEP else PetView.Mood.IDLE
        }
    }

    fun mood(m: PetView.Mood) = main.post { pet?.mood = m }

    /** 墨灵说一句：跳一下，气泡里显示，过一会儿收起。 */
    fun say(c: Context, text: String, seconds: Int = 20, alert: Boolean = false) {
        lastSaid = text.replace(Regex("【选项】.*$", RegexOption.MULTILINE), "").trim().ifBlank { lastSaid }
        main.post {
            pet ?: return@post
            pet?.bounce()
            pet?.mood = if (alert) PetView.Mood.ALERT else PetView.Mood.TALK
            showBubble(c, lastSaid)
            hideBubble?.let { main.removeCallbacks(it) }
            hideBubble = Runnable { removeBubble(); pet?.mood = PetView.Mood.IDLE }.also { main.postDelayed(it, seconds * 1000L) }
            main.postDelayed({ if (pet?.mood == PetView.Mood.TALK) pet?.mood = PetView.Mood.IDLE }, (lastSaid.length * 180L).coerceIn(1500, 9000))
        }
    }

    private fun showBubble(c: Context, text: String) {
        val s = service ?: return
        val lp0 = petLp ?: return
        val screenW = s.resources.displayMetrics.widthPixels
        val onLeft = lp0.x < screenW / 2
        bubbleText?.let { it.text = text; return }
        val pad = dp(s, 14f)
        val box = LinearLayout(s).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad, pad, pad / 2)
            background = GradientDrawable().apply {
                setColor(Color.rgb(0xFB, 0xF8, 0xF2)); cornerRadius = dp(s, 18f).toFloat(); setStroke(dp(s, 1f), Color.argb(60, 0x2A, 0x24, 0x1F))
            }
            elevation = dp(s, 6f).toFloat()
        }
        val tv = TextView(s).apply {
            setTextColor(Color.rgb(0x2A, 0x24, 0x1F)); textSize = 15f; maxLines = 7; this.text = text
            setLineSpacing(0f, 1.15f)
        }
        box.addView(tv)
        val row = LinearLayout(s).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.END }
        fun btn(label: String, onClick: () -> Unit) = TextView(s).apply {
            this.text = label; textSize = 14f; setTextColor(Color.rgb(0xC1, 0x66, 0x3E)); setPadding(dp(s, 10f), dp(s, 10f), dp(s, 10f), dp(s, 6f))
            setOnClickListener { onClick() }
        }
        row.addView(btn("说话") { open(s, "voice") })
        row.addView(btn("打开") { open(s, "chat") })
        row.addView(btn("收起 1 小时") { snooze(s, 60) })
        box.addView(row)
        val w = (screenW * 0.72f).toInt().coerceAtMost(dp(s, 300f))
        val x = if (onLeft) lp0.x + lp0.width + dp(s, 4f) else (lp0.x - w - dp(s, 4f)).coerceAtLeast(0)
        val lp = params(s, w, WindowManager.LayoutParams.WRAP_CONTENT, x, lp0.y)
        try {
            wm?.addView(box, lp)
            bubble = box
            bubbleText = tv
        } catch (e: Exception) {
            Log.w(TAG, "加气泡失败", e)
        }
    }

    private fun removeBubble() {
        bubble?.let { runCatching { wm?.removeView(it) } }
        bubble = null
        bubbleText = null
    }

    private fun open(c: Context, route: String) {
        removeBubble()
        c.startActivity(Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra(MainActivity.EXTRA_ROUTE, route))
    }

    /** 点、按住说话、拖动。 */
    private class Touch(val c: Context) : View.OnTouchListener {
        private var downX = 0f
        private var downY = 0f
        private var startX = 0
        private var startY = 0
        private var dragging = false
        private var talking = false
        private val longPress = Runnable { startTalk() }

        private fun startTalk() {
            if (dragging) return
            val canRecord = Voice.canTranscribe(c) && c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (!canRecord) { open(c, "voice"); return }
            if (Voice.startRecording(c)) {
                talking = true
                pet?.mood = PetView.Mood.LISTEN
                removeBubble()
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            val lp = petLp ?: return false
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y; dragging = false; talking = false
                    main.postDelayed(longPress, 450)
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!talking && (dragging || dx * dx + dy * dy > 400)) {
                        dragging = true
                        main.removeCallbacks(longPress)
                        lp.x = (startX + dx).toInt(); lp.y = (startY + dy).toInt()
                        runCatching { wm?.updateViewLayout(v, lp) }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    main.removeCallbacks(longPress)
                    when {
                        talking -> finishTalk()
                        dragging -> {
                            // 贴边
                            val w = v.resources.displayMetrics.widthPixels
                            lp.x = if (lp.x + lp.width / 2 < w / 2) 0 else w - lp.width
                            runCatching { wm?.updateViewLayout(v, lp) }
                            prefs(c).edit().putInt("x", lp.x).putInt("y", lp.y).apply()
                            removeBubble()
                        }
                        else -> if (bubble != null) removeBubble() else showBubble(c, lastSaid)
                    }
                }
            }
            return true
        }

        private fun finishTalk() {
            talking = false
            pet?.mood = PetView.Mood.THINK
            scope.launch {
                when (val r = Voice.stopAndTranscribe(c)) {
                    is ApiResult.Err -> { say(c, r.message, 6); pet?.mood = PetView.Mood.IDLE }
                    is ApiResult.Ok -> {
                        say(c, "「${r.value}」", 30)
                        pet?.mood = PetView.Mood.THINK
                        c.zk.chat.send(r.value, echo = true)
                    }
                }
            }
        }
    }
}
