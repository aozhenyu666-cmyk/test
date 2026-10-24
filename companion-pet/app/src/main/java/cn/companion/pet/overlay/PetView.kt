package cn.companion.pet.overlay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import cn.companion.pet.core.life.LifeState
import cn.companion.pet.core.probe.Mood
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.min
import kotlin.math.sin

/**
 * 占位角色：一只圆滚滚的小团子，用 Canvas 画。
 *
 * 只用于开发联调，正式版换成原创 Lottie 动画；状态接口（[mood]、[life]、[talking]、[attention]）保持不变。
 * 待机 24 帧，做动作时 60 帧；不可见时停止绘制以省电。
 */
class PetView(context: Context) : View(context) {
    init {
        // 读屏和验收脚本靠这个找到桌宠（悬浮窗位置会变，不能用固定坐标）。
        contentDescription = "桌宠"
    }

    var mood: Mood = Mood.IDLE
        set(value) { field = value; kick() }
    var life: LifeState = LifeState.AWAKE
        set(value) { field = value; kick() }
    var talking = false
        set(value) { field = value; kick() }
    var holdingLetter = false
        set(value) { field = value; kick() }
    var listening = false
        set(value) { field = value; kick() }
    /** Gemini 会话进行中：头顶左边一个小小的蓝色“G”徽章。 */
    var inSession = false
        set(value) { field = value; kick() }
    /** 角色在屏幕右边时朝左看，反之朝右。 */
    var facingLeft = true
        set(value) { field = value; invalidate() }

    private var attentionUntil = 0L
    private var running = false
    private val start = SystemClock.uptimeMillis()

    private val body = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 214, 165) }
    private val bodyShade = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(246, 186, 128) }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(70, 48, 38); style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 48, 38) }
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
    private val cheek = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 128, 128) }
    private val paper = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 252, 240) }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(108, 142, 230) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(70, 48, 38); textAlign = Paint.Align.CENTER }
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val path = Path()
    private val rect = RectF()

    /** 吸引注意的动作：跳两下、晃一晃。 */
    fun attention(durationMillis: Long = 1600) {
        attentionUntil = SystemClock.uptimeMillis() + durationMillis
        kick()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        kick()
    }

    override fun onDetachedFromWindow() {
        running = false
        super.onDetachedFromWindow()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        running = visibility == VISIBLE && isAttachedToWindow
        if (running) kick()
    }

    private fun kick() = invalidate()

    override fun onDraw(canvas: Canvas) {
        val now = SystemClock.uptimeMillis()
        val t = (now - start) / 1000f
        val w = width.toFloat()
        val h = height.toFloat()
        val s = min(w, h)
        outline.strokeWidth = s * 0.035f

        val attentive = now < attentionUntil
        val breathe = sin(t * 2f * PI.toFloat() / 3.2f)
        var jump = 0f
        var tilt = 0f
        if (attentive) {
            val p = (attentionUntil - now) / 1600f
            jump = abs(sin(p * 4 * PI.toFloat())) * s * 0.12f
            tilt = sin(p * 10 * PI.toFloat()) * 10f
        } else if (mood == Mood.HAPPY) {
            jump = abs(sin(t * 3f)) * s * 0.04f
        } else if (mood == Mood.CURIOUS) {
            tilt = if (facingLeft) -8f else 8f
        }

        val cx = w / 2
        val bodyW = s * (0.78f + breathe * 0.015f)
        val bodyH = s * (0.64f - breathe * 0.015f)
        val bottom = h * 0.94f - jump
        val top = bottom - bodyH
        val cy = (top + bottom) / 2

        canvas.save()
        canvas.rotate(tilt, cx, bottom)

        // 耳朵
        val earR = s * 0.11f
        drawBlob(canvas, cx - bodyW * 0.30f, top + earR * 0.4f, earR, body)
        drawBlob(canvas, cx + bodyW * 0.30f, top + earR * 0.4f, earR, body)

        // 身体
        rect.set(cx - bodyW / 2, top, cx + bodyW / 2, bottom)
        canvas.drawOval(rect, body)
        rect.set(cx - bodyW * 0.36f, cy + bodyH * 0.05f, cx + bodyW * 0.36f, bottom - bodyH * 0.04f)
        canvas.drawOval(rect, bodyShade)
        rect.set(cx - bodyW / 2, top, cx + bodyW / 2, bottom)
        canvas.drawOval(rect, outline)

        // 脸
        val look = lookOffset(t) * s
        val eyeY = cy - bodyH * 0.06f
        val eyeDx = bodyW * 0.18f
        val eyeR = s * 0.055f
        // 道具画在脸之前，避免书本/信纸盖住嘴；位置也放低到身体下沿。
        drawProps(canvas, cx, top, bottom, bodyW, s, t)
        drawEyes(canvas, cx + look, eyeY, eyeDx, eyeR, t)
        canvas.drawCircle(cx - eyeDx * 1.6f, eyeY + eyeR * 1.8f, eyeR * 0.9f, cheek)
        canvas.drawCircle(cx + eyeDx * 1.6f, eyeY + eyeR * 1.8f, eyeR * 0.9f, cheek)
        drawMouth(canvas, cx + look, eyeY + eyeR * 2.2f, s, t)

        canvas.restore()

        drawOverhead(canvas, cx, top, s, t)
        if (inSession) {
            val bx = s * 0.16f
            val by = s * 0.16f
            canvas.drawCircle(bx, by, s * 0.12f, accent)
            canvas.drawCircle(bx, by, s * 0.12f, outline)
            badgeText.textSize = s * 0.15f
            canvas.drawText("G", bx, by + s * 0.055f, badgeText)
        }

        if (running) {
            val busy = attentive || talking || listening || mood == Mood.HAPPY
            postInvalidateDelayed(if (busy) 16 else 42)
        }
    }

    private fun lookOffset(t: Float): Float {
        if (listening || talking) return if (facingLeft) -0.03f else 0.03f
        if (life == LifeState.READING || life == LifeState.WRITING) return 0f
        // 每 7 秒左右看一眼
        val phase = t % 7f
        return when {
            phase < 0.4f -> -0.05f * (phase / 0.4f)
            phase < 1.6f -> -0.05f
            phase < 2.0f -> -0.05f + 0.1f * ((phase - 1.6f) / 0.4f)
            phase < 3.2f -> 0.05f
            phase < 3.6f -> 0.05f * (1 - (phase - 3.2f) / 0.4f)
            else -> 0f
        }
    }

    private fun drawEyes(canvas: Canvas, cx: Float, y: Float, dx: Float, r: Float, t: Float) {
        val blink = (t % 4.3f) < 0.13f
        val closedStyle = mood == Mood.HAPPY || blink
        val sleepy = mood == Mood.SLEEPY || life == LifeState.SLEEPY
        val down = life == LifeState.READING || life == LifeState.WRITING
        for (side in listOf(-1, 1)) {
            val ex = cx + side * dx
            when {
                mood == Mood.TEASING && side == 1 -> arc(canvas, ex, y, r, up = false)
                closedStyle -> arc(canvas, ex, y, r, up = mood == Mood.HAPPY)
                sleepy -> canvas.drawLine(ex - r, y + r * 0.3f, ex + r, y + r * 0.3f, outline)
                else -> {
                    val big = if (mood == Mood.CURIOUS || listening) 1.25f else 1f
                    val oy = when {
                        down -> r * 0.5f
                        mood == Mood.THINKING || life == LifeState.DAZING -> -r * 0.5f
                        else -> 0f
                    }
                    canvas.drawCircle(ex, y + oy, r * big, ink)
                    canvas.drawCircle(ex + r * 0.35f, y + oy - r * 0.35f, r * 0.32f * big, white)
                }
            }
        }
    }

    private fun arc(canvas: Canvas, x: Float, y: Float, r: Float, up: Boolean) {
        rect.set(x - r, y - r * 0.8f, x + r, y + r * 0.8f)
        canvas.drawArc(rect, if (up) 200f else 20f, 140f, false, outline)
    }

    private fun drawMouth(canvas: Canvas, x: Float, y: Float, s: Float, t: Float) {
        val m = s * 0.06f
        when {
            talking -> {
                val open = (abs(sin(t * 14f)) * 0.8f + 0.2f) * m
                rect.set(x - m * 0.7f, y - open * 0.3f, x + m * 0.7f, y + open)
                canvas.drawOval(rect, ink)
            }
            listening -> canvas.drawCircle(x, y + m * 0.2f, m * 0.4f, ink)
            mood == Mood.TEASING -> {
                path.reset(); path.moveTo(x - m, y); path.quadTo(x, y + m * 0.6f, x + m, y - m * 0.5f)
                canvas.drawPath(path, outline)
            }
            mood == Mood.HAPPY -> {
                rect.set(x - m, y - m * 0.6f, x + m, y + m)
                canvas.drawArc(rect, 0f, 180f, true, ink)
            }
            mood == Mood.THINKING || life == LifeState.DAZING -> canvas.drawLine(x - m * 0.5f, y + m * 0.2f, x + m * 0.5f, y + m * 0.2f, outline)
            else -> {
                // 小小的 w 嘴
                path.reset()
                path.moveTo(x - m, y)
                path.quadTo(x - m * 0.5f, y + m * 0.7f, x, y)
                path.quadTo(x + m * 0.5f, y + m * 0.7f, x + m, y)
                canvas.drawPath(path, outline)
            }
        }
    }

    private fun drawProps(canvas: Canvas, cx: Float, top: Float, bottom: Float, bodyW: Float, s: Float, t: Float) {
        val propY = bottom - s * 0.10f
        when {
            holdingLetter -> {
                val wob = sin(t * 4f) * s * 0.015f
                rect.set(cx - s * 0.2f, propY - s * 0.12f + wob, cx + s * 0.2f, propY + s * 0.14f + wob)
                canvas.drawRect(rect, paper)
                canvas.drawRect(rect, outline)
                path.reset(); path.moveTo(rect.left, rect.top); path.lineTo(cx, rect.centerY()); path.lineTo(rect.right, rect.top)
                canvas.drawPath(path, outline)
                canvas.drawCircle(cx, rect.centerY() + s * 0.02f, s * 0.025f, cheek)
            }
            life == LifeState.READING -> {
                rect.set(cx - s * 0.24f, propY - s * 0.06f, cx + s * 0.24f, propY + s * 0.14f)
                canvas.drawRect(rect, accent)
                canvas.drawRect(rect, outline)
                canvas.drawLine(cx, rect.top, cx, rect.bottom, outline)
            }
            life == LifeState.WRITING -> {
                rect.set(cx - s * 0.2f, propY - s * 0.02f, cx + s * 0.14f, propY + s * 0.14f)
                canvas.drawRect(rect, paper)
                canvas.drawRect(rect, outline)
                val px = cx + s * 0.1f + sin(t * 6f) * s * 0.05f
                canvas.drawLine(px, propY + s * 0.06f, px + s * 0.1f, propY - s * 0.1f, outline)
            }
        }
    }

    private fun drawOverhead(canvas: Canvas, cx: Float, top: Float, s: Float, t: Float) {
        text.textSize = s * 0.16f
        val x = cx + s * 0.32f
        val y = top - s * 0.02f
        when {
            listening -> {
                for (i in 0..2) {
                    val a = ((t * 2 + i * 0.33f) % 1f)
                    ink.alpha = (255 * (1 - a)).toInt()
                    canvas.drawCircle(x - s * 0.1f + i * s * 0.09f, y, s * 0.025f, ink)
                }
                ink.alpha = 255
            }
            mood == Mood.SLEEPY || life == LifeState.SLEEPY -> {
                val a = (t * 0.7f) % 1f
                text.alpha = (255 * (1 - a)).toInt()
                canvas.drawText("z", x, y - a * s * 0.15f, text)
                text.alpha = 255
            }
            mood == Mood.THINKING -> {
                val n = ((t * 2).toInt() % 3) + 1
                canvas.drawText(".".repeat(n), x, y, text)
            }
            mood == Mood.CURIOUS -> canvas.drawText("?", x, y, text)
        }
    }

    private fun drawBlob(canvas: Canvas, x: Float, y: Float, r: Float, paint: Paint) {
        canvas.drawCircle(x, y, r, paint)
        canvas.drawCircle(x, y, r, outline)
    }
}