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
 * 桌面宠物：一只 Q 版的小家伙（夜枭 / 团子 / 小葵，可选），浮在所有应用上面（借无障碍服务的悬浮层，不用另开“悬浮窗”权限）。
 *  - 点一下：弹出它最近说的话，和“说话 / 打开 / 收起”三个按钮
 *  - 按住：对它说话（配了听写接口时直接录音；没配就打开对讲页），松手发出去，它的回答显示在气泡里并念出来
 *  - 拖动：挪位置，松手贴边
 *  - 腮红、项圈、花盆跟着态度变色：信任绿、正常赭、审慎琥珀、严格朱红；严格时皱眉（夜枭戴眼镜），主动找你时会跳一下
 */
class PetView(context: Context) : View(context) {
    enum class Mood { IDLE, TALK, LISTEN, THINK, ALERT, SLEEP }

    /** 三个形象。 */
    enum class Kind(val label: String, val what: String) {
        OWL("夜枭", "小猫头鹰：夜里也醒着，严格的时候戴上眼镜"),
        CAT("团子", "橘色圆猫：尾巴会甩，项圈上挂个铃铛"),
        FLOWER("小葵", "盆里的向日葵：想事情时花瓣转圈，花盆是态度色"),
    }

    var kind = Kind.OWL
        set(v) { field = v; invalidate() }

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
        paint(canvas, width.toFloat(), height.toFloat(), t, mood, stance, ((SystemClock.uptimeMillis() - bounceAt) / 600f).coerceIn(0f, 1f), kind)
        if (isAttachedToWindow) postInvalidateDelayed(if (mood == Mood.IDLE || mood == Mood.SLEEP) 66 else 33)
    }

    companion object {
        fun color(s: Stance): Int = when (s) {
            Stance.TRUST -> Color.rgb(0x4F, 0x8A, 0x5B)
            Stance.NORMAL -> Color.rgb(0xC1, 0x66, 0x3E)
            Stance.WARY -> Color.rgb(0xC9, 0x8A, 0x2B)
            Stance.STRICT -> Color.rgb(0xB8, 0x3A, 0x2E)
        }

        fun kindOf(name: String): Kind = Kind.entries.firstOrNull { it.name == name } ?: Kind.OWL

        private val INK = Color.rgb(0x2A, 0x24, 0x1F)
        private val PAPER = Color.rgb(0xFB, 0xF8, 0xF2)
        private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

        private fun oval(x: Float, y: Float, rx: Float, ry: Float) = RectF(x - rx, y - ry, x + rx, y + ry)
        private fun fillC(color: Int, alpha: Int = 255) { fill.color = color; fill.alpha = alpha }
        private fun inkC(color: Int, width: Float, alpha: Int = 255) { ink.color = color; ink.alpha = alpha; ink.strokeWidth = width }

        /**
         * 画一只。[bounce] 0 → 1 是刚跳完的进度（1 = 不跳）。
         * 三个形象共用表情：眨眼、睡着闭眼、听时眼睛睁大、想时眼睛往上瞟、说话张嘴、警觉皱眉加一道小闪电。
         */
        fun paint(c: Canvas, w: Float, h: Float, t: Float, mood: Mood, stance: Stance, bounce: Float, kind: Kind = Kind.OWL) {
            val accent = color(stance)
            val hop = if (bounce < 1f) -sin(bounce * PI.toFloat()) * h * 0.12f else 0f
            val breathe = if (mood == Mood.SLEEP) sin(t * 1.2f) * 0.015f else sin(t * 2.2f) * 0.025f
            // 听的时候：身后一圈圈涟漪
            if (mood == Mood.LISTEN) for (i in 0 until 3) {
                val f = ((t * 0.8f) + i / 3f) % 1f
                inkC(accent, 2f, ((1 - f) * 200).toInt())
                c.drawCircle(w / 2, h * 0.55f, minOf(w, h) * 0.3f * (1.05f + 0.6f * f), ink)
            }
            c.save()
            c.translate(0f, hop)
            val head = when (kind) {
                Kind.OWL -> owl(c, w, h, t, mood, stance, accent, breathe)
                Kind.CAT -> cat(c, w, h, t, mood, stance, accent, breathe)
                Kind.FLOWER -> flower(c, w, h, t, mood, stance, accent, breathe)
            }
            overlays(c, head[0], head[1], head[2], t, mood, stance, accent)
            c.restore()
        }

        /** 二次元的眼睛：黑色椭圆 + 两个高光；闭眼是一道弯。 */
        private fun eye(c: Canvas, x: Float, y: Float, rw: Float, rh: Float, t: Float, mood: Mood) {
            val blink = (t % 4.3f) < 0.13f
            if (mood == Mood.SLEEP || blink) {
                inkC(INK, rw * 0.5f)
                c.drawArc(oval(x, y - rh * 0.2f, rw * 1.1f, rh * 0.6f), 20f, 140f, false, ink)
                return
            }
            val look = when (mood) {
                Mood.THINK -> floatArrayOf(rw * 0.35f, -rh * 0.3f)
                Mood.IDLE -> floatArrayOf(sin(t * 0.5f) * rw * 0.25f, 0f)
                else -> floatArrayOf(0f, 0f)
            }
            val k = when (mood) { Mood.LISTEN -> 1.15f; Mood.ALERT -> 0.85f; else -> 1f }
            val ex = x + look[0]
            val ey = y + look[1]
            fillC(INK)
            c.drawOval(oval(ex, ey, rw * k, rh * k), fill)
            fillC(Color.WHITE)
            c.drawCircle(ex - rw * 0.32f, ey - rh * 0.38f, rw * 0.38f * k, fill)
            c.drawCircle(ex + rw * 0.35f, ey + rh * 0.35f, rw * 0.16f * k, fill)
        }

        /** 嘴：说话一开一合，警觉是 o，想是一条斜线，平时微笑。 */
        private fun mouth(c: Canvas, x: Float, y: Float, s: Float, t: Float, mood: Mood) {
            inkC(INK, s * 0.32f)
            when (mood) {
                Mood.TALK -> {
                    val open = (sin(t * 14f) * 0.5f + 0.5f) * s * 0.9f + s * 0.2f
                    fillC(Color.rgb(0x8E, 0x3B, 0x2E))
                    c.drawOval(oval(x, y, s * 0.6f, open / 2), fill)
                    c.drawOval(oval(x, y, s * 0.6f, open / 2), ink)
                }
                Mood.ALERT -> c.drawCircle(x, y, s * 0.35f, ink)
                Mood.THINK -> c.drawLine(x - s * 0.5f, y + s * 0.1f, x + s * 0.5f, y - s * 0.1f, ink)
                Mood.SLEEP -> c.drawCircle(x, y, s * 0.18f, ink)
                else -> c.drawArc(oval(x, y - s * 0.3f, s * 0.7f, s * 0.6f), 25f, 130f, false, ink)
            }
        }

        private fun blush(c: Canvas, x1: Float, x2: Float, y: Float, rr: Float, accent: Int) {
            fillC(accent, 70)
            c.drawOval(oval(x1, y, rr * 1.3f, rr * 0.8f), fill)
            c.drawOval(oval(x2, y, rr * 1.3f, rr * 0.8f), fill)
        }

        /** 严格 / 警觉时皱眉。 */
        private fun brows(c: Canvas, x1: Float, x2: Float, y: Float, s: Float) {
            inkC(INK, s * 0.22f)
            c.drawLine(x1 - s, y - s * 0.35f, x1 + s * 0.7f, y + s * 0.15f, ink)
            c.drawLine(x2 + s, y - s * 0.35f, x2 - s * 0.7f, y + s * 0.15f, ink)
        }

        // ---------- 夜枭：小猫头鹰，严格时戴上眼镜 ----------

        private fun owl(c: Canvas, w: Float, h: Float, t: Float, mood: Mood, stance: Stance, accent: Int, breathe: Float): FloatArray {
            val body = Color.rgb(0xA8, 0x86, 0x66)
            val wing = Color.rgb(0x86, 0x66, 0x4C)
            val belly = Color.rgb(0xF3, 0xE6, 0xD0)
            val beak = Color.rgb(0xE3, 0xA2, 0x3B)
            val cx = w / 2
            val r = minOf(w, h) * 0.31f
            val cy = h * 0.57f
            val sy = 1 + breathe
            // 耳羽
            val wig = sin(t * 1.3f) * r * 0.05f
            for (s in listOf(-1f, 1f)) {
                val p = Path().apply {
                    moveTo(cx + s * r * 0.4f, cy - r * 0.8f * sy)
                    lineTo(cx + s * r * 0.8f + s * wig, cy - r * 1.25f * sy)
                    lineTo(cx + s * r * 0.86f, cy - r * 0.5f * sy)
                    close()
                }
                fillC(body); c.drawPath(p, fill)
                inkC(INK, r * 0.07f); c.drawPath(p, ink)
            }
            // 翅膀（说话时扑）
            val flap = if (mood == Mood.TALK) sin(t * 10f) * 10f else sin(t * 1.5f) * 2f
            for (s in listOf(-1f, 1f)) {
                c.save()
                c.rotate(s * (10f + flap), cx + s * r * 0.8f, cy - r * 0.2f)
                val wp = oval(cx + s * r * 0.9f, cy + r * 0.2f, r * 0.24f, r * 0.5f)
                fillC(wing); c.drawOval(wp, fill)
                inkC(INK, r * 0.06f); c.drawOval(wp, ink)
                c.restore()
            }
            // 身子
            val b = oval(cx, cy, r * 0.98f, r * sy)
            fillC(body); c.drawOval(b, fill)
            fillC(belly); c.drawOval(oval(cx, cy + r * 0.4f * sy, r * 0.6f, r * 0.52f * sy), fill)
            inkC(INK, r * 0.035f, 110)
            for ((dx, dy) in listOf(-0.22f to 0.28f, 0.22f to 0.28f, 0f to 0.5f, -0.24f to 0.7f, 0.24f to 0.7f)) {
                val x = cx + dx * r
                val y = cy + dy * r * sy
                c.drawLine(x - r * 0.07f, y - r * 0.05f, x, y + r * 0.02f, ink)
                c.drawLine(x, y + r * 0.02f, x + r * 0.07f, y - r * 0.05f, ink)
            }
            inkC(INK, r * 0.08f); c.drawOval(b, ink)
            // 脚
            fillC(beak)
            for (s in listOf(-1f, 1f)) for (k in -1..1) c.drawCircle(cx + s * r * 0.32f + k * r * 0.08f, cy + r * sy, r * 0.06f, fill)
            // 眼盘
            val ey = cy - r * 0.3f * sy
            val ex = r * 0.4f
            val er = r * 0.34f
            for (s in listOf(-1f, 1f)) {
                fillC(PAPER); c.drawCircle(cx + s * ex, ey, er, fill)
                inkC(INK, r * 0.05f); c.drawCircle(cx + s * ex, ey, er, ink)
                eye(c, cx + s * ex, ey, er * 0.5f, er * 0.6f, t, mood)
            }
            blush(c, cx - r * 0.66f, cx + r * 0.66f, ey + er * 1.05f, r * 0.1f, accent)
            // 喙：说话时上下分开
            val by = ey + er * 0.62f
            val open = if (mood == Mood.TALK) (sin(t * 14f) * 0.5f + 0.5f) * r * 0.1f else 0f
            fillC(beak)
            if (open > 0f) c.drawPath(Path().apply { moveTo(cx - r * 0.08f, by + r * 0.08f + open); lineTo(cx + r * 0.08f, by + r * 0.08f + open); lineTo(cx, by + r * 0.18f + open); close() }, fill)
            c.drawPath(Path().apply { moveTo(cx - r * 0.12f, by); lineTo(cx + r * 0.12f, by); lineTo(cx, by + r * 0.17f); close() }, fill)
            // 严格：戴上圆眼镜
            if (stance == Stance.STRICT) {
                inkC(INK, r * 0.075f)
                for (s in listOf(-1f, 1f)) c.drawCircle(cx + s * ex, ey, er * 1.08f, ink)
                c.drawLine(cx - (ex - er * 1.08f), ey - er * 0.1f, cx + (ex - er * 1.08f), ey - er * 0.1f, ink)
            } else if (mood == Mood.ALERT) brows(c, cx - ex, cx + ex, ey - er * 1.15f, r * 0.2f)
            return floatArrayOf(cx, cy - r * 0.25f, r)
        }

        // ---------- 团子：橘色圆猫，尾巴会甩，挂个铃铛 ----------

        private fun cat(c: Canvas, w: Float, h: Float, t: Float, mood: Mood, stance: Stance, accent: Int, breathe: Float): FloatArray {
            val orange = Color.rgb(0xF2, 0xA6, 0x5A)
            val dark = Color.rgb(0xD9, 0x82, 0x36)
            val cream = Color.rgb(0xFB, 0xEB, 0xD5)
            val pink = Color.rgb(0xF3, 0xA6, 0xA6)
            val cx = w / 2
            val r = minOf(w, h) * 0.31f
            val cy = h * 0.6f
            val rx = r * 1.08f * (1 - breathe * 0.5f)
            val ry = r * 0.92f * (1 + breathe)
            // 尾巴（在身子后面）：警觉时甩得快
            val sw = sin(t * if (mood == Mood.ALERT) 7f else 2.4f) * r * 0.3f
            val tail = Path().apply {
                moveTo(cx + rx * 0.6f, cy + ry * 0.6f)
                cubicTo(cx + rx * 1.45f, cy + ry * 0.7f, cx + rx * 1.3f + sw * 0.3f, cy - ry * 0.1f, cx + rx * 1.12f + sw, cy - ry * 0.55f)
            }
            inkC(INK, r * 0.3f); c.drawPath(tail, ink)
            inkC(orange, r * 0.19f); c.drawPath(tail, ink)
            // 耳朵（偶尔抖一下）
            val twitch = if ((t % 5f) < 0.25f) r * 0.08f else 0f
            for (s in listOf(-1f, 1f)) {
                val tip = floatArrayOf(cx + s * r * 0.72f + (if (s > 0) twitch else 0f), cy - ry * 1.32f)
                val ear = Path().apply { moveTo(cx + s * r * 0.2f, cy - ry * 0.82f); lineTo(tip[0], tip[1]); lineTo(cx + s * r * 0.92f, cy - ry * 0.4f); close() }
                fillC(orange); c.drawPath(ear, fill)
                inkC(INK, r * 0.07f); c.drawPath(ear, ink)
                val inner = Path().apply { moveTo(cx + s * r * 0.38f, cy - ry * 0.8f); lineTo(tip[0] - s * r * 0.06f, tip[1] + r * 0.18f); lineTo(cx + s * r * 0.78f, cy - ry * 0.5f); close() }
                fillC(pink); c.drawPath(inner, fill)
            }
            // 身子：一颗团子
            val b = oval(cx, cy, rx, ry)
            fillC(orange); c.drawOval(b, fill)
            fillC(cream); c.drawOval(oval(cx, cy + ry * 0.38f, rx * 0.56f, ry * 0.46f), fill)
            // 额头三道纹
            inkC(dark, r * 0.07f)
            for (k in -1..1) c.drawLine(cx + k * r * 0.16f, cy - ry * 0.88f, cx + k * r * 0.13f, cy - ry * (if (k == 0) 0.58f else 0.68f), ink)
            inkC(INK, r * 0.08f); c.drawOval(b, ink)
            // 项圈 + 铃铛（态度色）
            inkC(accent, r * 0.1f)
            c.drawArc(RectF(cx - rx * 0.72f, cy + ry * 0.1f, cx + rx * 0.72f, cy + ry * 0.78f), 25f, 130f, false, ink)
            fillC(Color.rgb(0xF2, 0xC9, 0x4C)); c.drawCircle(cx, cy + ry * 0.8f, r * 0.11f, fill)
            inkC(INK, r * 0.04f); c.drawCircle(cx, cy + ry * 0.8f, r * 0.11f, ink)
            c.drawLine(cx, cy + ry * 0.83f, cx, cy + ry * 0.91f, ink)
            // 脸
            val ey = cy - ry * 0.15f
            val ex = r * 0.42f
            for (s in listOf(-1f, 1f)) eye(c, cx + s * ex, ey, r * 0.13f, r * 0.18f, t, mood)
            blush(c, cx - r * 0.66f, cx + r * 0.66f, ey + r * 0.26f, r * 0.1f, accent)
            val my = cy + ry * 0.14f
            fillC(pink); c.drawPath(Path().apply { moveTo(cx - r * 0.07f, my - r * 0.1f); lineTo(cx + r * 0.07f, my - r * 0.1f); lineTo(cx, my - r * 0.02f); close() }, fill)
            if (mood == Mood.IDLE || mood == Mood.LISTEN) {
                // ω
                inkC(INK, r * 0.045f)
                c.drawArc(oval(cx - r * 0.07f, my, r * 0.07f, r * 0.06f), 0f, 180f, false, ink)
                c.drawArc(oval(cx + r * 0.07f, my, r * 0.07f, r * 0.06f), 0f, 180f, false, ink)
            } else mouth(c, cx, my + r * 0.06f, r * 0.14f, t, mood)
            // 胡须
            inkC(INK, r * 0.03f, 150)
            for (s in listOf(-1f, 1f)) {
                c.drawLine(cx + s * r * 0.55f, my - r * 0.04f, cx + s * r * 0.98f, my - r * 0.12f, ink)
                c.drawLine(cx + s * r * 0.55f, my + r * 0.04f, cx + s * r * 0.98f, my + r * 0.08f, ink)
            }
            if (stance == Stance.STRICT || mood == Mood.ALERT) brows(c, cx - ex, cx + ex, ey - r * 0.3f, r * 0.13f)
            return floatArrayOf(cx, cy - ry * 0.2f, r)
        }

        // ---------- 小葵：盆里的向日葵，花瓣跟着心情转 ----------

        private fun flower(c: Canvas, w: Float, h: Float, t: Float, mood: Mood, stance: Stance, accent: Int, breathe: Float): FloatArray {
            val yellow = Color.rgb(0xF6, 0xC5, 0x3B)
            val seed = Color.rgb(0x8A, 0x5A, 0x2B)
            val face = Color.rgb(0xF8, 0xE3, 0xB4)
            val green = Color.rgb(0x6F, 0x9E, 0x4F)
            val cx = w / 2
            val r = minOf(w, h) * 0.17f * (1 + breathe)
            val potTop = h * 0.8f
            val sway = if (mood == Mood.SLEEP) r * 0.25f else sin(t * 1.4f) * r * 0.18f
            val hx = cx + sway
            val hy = h * 0.37f + if (mood == Mood.SLEEP) r * 0.2f else 0f
            // 茎和叶子
            inkC(green, r * 0.18f)
            c.drawPath(Path().apply { moveTo(cx, potTop); quadTo(cx - r * 0.2f, (potTop + hy) / 2, hx, hy + r) }, ink)
            for (s in listOf(-1f, 1f)) {
                c.save()
                c.rotate(s * (35f + sin(t * 1.8f + s) * 8f), cx, h * 0.68f)
                val leaf = oval(cx + s * r * 0.55f, h * 0.68f, r * 0.55f, r * 0.24f)
                fillC(green); c.drawOval(leaf, fill)
                inkC(INK, r * 0.06f); c.drawOval(leaf, ink)
                c.restore()
            }
            // 花盆（态度色）
            val pot = Path().apply { moveTo(cx - r * 1.05f, potTop); lineTo(cx + r * 1.05f, potTop); lineTo(cx + r * 0.78f, h * 0.97f); lineTo(cx - r * 0.78f, h * 0.97f); close() }
            fillC(accent); c.drawPath(pot, fill)
            inkC(INK, r * 0.08f); c.drawPath(pot, ink)
            c.drawLine(cx - r * 1.1f, potTop + r * 0.2f, cx + r * 1.1f, potTop + r * 0.2f, ink)
            // 花瓣：想的时候转圈，平时轻轻摆，睡着收拢一点
            val rot = if (mood == Mood.THINK) t * 60f else sin(t * 0.8f) * 6f
            val dist = if (mood == Mood.SLEEP) r * 1.15f else r * 1.32f
            for (k in 0 until 12) {
                c.save()
                c.rotate(rot + k * 30f, hx, hy)
                val pp = oval(hx, hy - dist, r * 0.27f, r * 0.52f)
                fillC(yellow); c.drawOval(pp, fill)
                inkC(INK, r * 0.06f); c.drawOval(pp, ink)
                c.restore()
            }
            // 花盘：外圈是籽，里面是脸
            fillC(seed); c.drawCircle(hx, hy, r * 1.05f, fill)
            fillC(Color.rgb(0x5E, 0x3C, 0x1C))
            for (k in 0 until 18) {
                val a = (2 * PI * k / 18).toFloat()
                c.drawCircle(hx + r * 0.93f * cos(a), hy + r * 0.93f * sin(a), r * 0.05f, fill)
            }
            fillC(face); c.drawCircle(hx, hy, r * 0.8f, fill)
            inkC(INK, r * 0.07f); c.drawCircle(hx, hy, r * 1.05f, ink)
            val ey = hy - r * 0.1f
            val ex = r * 0.33f
            for (s in listOf(-1f, 1f)) eye(c, hx + s * ex, ey, r * 0.13f, r * 0.18f, t, mood)
            blush(c, hx - r * 0.55f, hx + r * 0.55f, ey + r * 0.3f, r * 0.1f, accent)
            mouth(c, hx, hy + r * 0.38f, r * 0.15f, t, mood)
            if (stance == Stance.STRICT || mood == Mood.ALERT) brows(c, hx - ex, hx + ex, ey - r * 0.3f, r * 0.13f)
            return floatArrayOf(hx, hy, r * 1.6f)
        }

        /** 三个形象共用的小附件：警觉的闪电、想时的漩涡、睡觉的 z。 */
        private fun overlays(c: Canvas, hx: Float, hy: Float, hr: Float, t: Float, mood: Mood, stance: Stance, accent: Int) {
            if (mood == Mood.ALERT || stance == Stance.STRICT) {
                inkC(Color.rgb(0xB8, 0x3A, 0x2E), hr * 0.07f, (180 + 75 * sin(t * 9f)).toInt().coerceIn(0, 255))
                val bx = hx + hr * 1.05f
                val by = hy - hr * 0.95f
                c.drawPath(Path().apply { moveTo(bx, by); lineTo(bx - hr * 0.18f, by + hr * 0.32f); lineTo(bx + hr * 0.02f, by + hr * 0.32f); lineTo(bx - hr * 0.16f, by + hr * 0.7f) }, ink)
            }
            if (mood == Mood.THINK) {
                inkC(accent, hr * 0.04f, 220)
                val sp = Path()
                for (k in 0..40) {
                    val rr = hr * 0.02f + k * hr * 0.006f
                    val a = k * 0.45f + t * 4f
                    val x = hx - hr * 0.95f + rr * cos(a)
                    val y = hy - hr * 1.0f + rr * sin(a)
                    if (k == 0) sp.moveTo(x, y) else sp.lineTo(x, y)
                }
                c.drawPath(sp, ink)
            }
            if (mood == Mood.SLEEP) {
                fillC(INK, 150); fill.textSize = hr * 0.4f
                c.drawText("z", hx + hr * 0.9f, hy - hr * 0.8f - (t % 2f) * hr * 0.2f, fill)
                fill.textSize = hr * 0.28f
                c.drawText("z", hx + hr * 1.15f, hy - hr * 1.1f - (t % 2f) * hr * 0.2f, fill)
            }
        }

        /** 桌面小部件用：画成一张图。 */
        fun render(sizePx: Int, mood: Mood, stance: Stance, kind: Kind = Kind.OWL): Bitmap {
            val b = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
            paint(Canvas(b), sizePx.toFloat(), sizePx.toFloat(), 0.4f, mood, stance, 1f, kind)
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
                val size = dp(s, 72f)
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
            pet?.kind = PetView.kindOf(store.config.value.boss.petKind)
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
