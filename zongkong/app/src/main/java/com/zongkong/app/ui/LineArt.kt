package com.zongkong.app.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/*
 * 线描小图（0.12）：米色纸面上的细线条——日 / 月、沙漏、水纹。
 * 只用来告诉你“现在是什么时候、这一段还剩多少”，不做装饰性的满屏插画。全部尊重系统的“移除动画”。
 */

/**
 * 日月：白天是一轮带光芒的太阳，夜里是一弯月亮；一条竖线穿过，上下两个点，外面一圈虚线轨道（参考“光环”“月相”）。
 * 光芒极慢地转。
 */
@Composable
fun SunMoonGlyph(hour: Int, color: Color, size: Dp = 56.dp, modifier: Modifier = Modifier) {
    val night = hour < 6 || hour >= 19
    val still = LocalReduceMotion.current
    val turn by rememberInfiniteTransition(label = "sun").animateFloat(0f, 360f, infiniteRepeatable(tween(90_000, easing = LinearEasing), RepeatMode.Restart), label = "sunTurn")
    Canvas(modifier.size(size)) {
        val c = Offset(this.size.width / 2, this.size.height / 2)
        val r = this.size.minDimension * 0.2f
        val hair = 1.dp.toPx()
        // 竖线 + 上下两点
        drawLine(color.copy(alpha = 0.55f), Offset(c.x, 0f), Offset(c.x, this.size.height), hair * 0.8f)
        drawCircle(color, hair * 1.8f, Offset(c.x, this.size.height * 0.06f))
        drawCircle(color, hair * 1.8f, Offset(c.x, this.size.height * 0.94f))
        // 虚线轨道
        drawCircle(color.copy(alpha = 0.4f), this.size.minDimension * 0.44f, c, style = Stroke(hair * 0.7f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(2f, 6f))))
        if (!night) {
            rotate(if (still) 0f else turn, c) {
                for (i in 0 until 28) {
                    val a = i * (2 * PI / 28)
                    val long = i % 2 == 0
                    val r1 = r * 1.35f
                    val r2 = r * (if (long) 2.0f else 1.7f)
                    drawLine(color.copy(alpha = if (long) 0.7f else 0.4f), Offset(c.x + r1 * cos(a).toFloat(), c.y + r1 * sin(a).toFloat()), Offset(c.x + r2 * cos(a).toFloat(), c.y + r2 * sin(a).toFloat()), hair * 0.7f, StrokeCap.Round)
                }
            }
            drawCircle(color, r, c, style = Stroke(hair * 1.2f))
            // 一半细密的排线，像铅笔的阴影
            for (k in 1..6) drawArc(color.copy(alpha = 0.35f), 90f, 180f, false, Offset(c.x - r + k * r * 0.12f, c.y - r + k * r * 0.12f), Size((r - k * r * 0.12f) * 2, (r - k * r * 0.12f) * 2), style = Stroke(hair * 0.6f))
        } else {
            // 月牙：大圆减去偏右的小圆
            val moon = Path().apply {
                addOval(androidx.compose.ui.geometry.Rect(c, r * 1.1f))
            }
            val bite = Path().apply { addOval(androidx.compose.ui.geometry.Rect(Offset(c.x + r * 0.55f, c.y - r * 0.2f), r * 1.0f)) }
            val crescent = Path().apply { op(moon, bite, androidx.compose.ui.graphics.PathOperation.Difference) }
            drawPath(crescent, color.copy(alpha = 0.18f))
            drawPath(crescent, color, style = Stroke(hair * 1.2f))
            // 几颗小星
            listOf(Offset(0.22f, 0.25f), Offset(0.78f, 0.3f), Offset(0.7f, 0.78f)).forEach { p ->
                val s = Offset(this.size.width * p.x, this.size.height * p.y)
                drawLine(color.copy(alpha = 0.6f), Offset(s.x - hair * 3, s.y), Offset(s.x + hair * 3, s.y), hair * 0.7f)
                drawLine(color.copy(alpha = 0.6f), Offset(s.x, s.y - hair * 3), Offset(s.x, s.y + hair * 3), hair * 0.7f)
            }
        }
    }
}

/** 沙漏：上面剩多少就是这一段还剩多少（[left] 0..1）。 */
@Composable
fun HourglassGlyph(left: Float, color: Color, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val hair = 1.dp.toPx()
        val l = left.coerceIn(0f, 1f)
        val top = Path().apply { moveTo(w * 0.2f, h * 0.08f); lineTo(w * 0.8f, h * 0.08f); lineTo(w * 0.52f, h * 0.5f); lineTo(w * 0.48f, h * 0.5f); close() }
        val bottom = Path().apply { moveTo(w * 0.48f, h * 0.5f); lineTo(w * 0.52f, h * 0.5f); lineTo(w * 0.8f, h * 0.92f); lineTo(w * 0.2f, h * 0.92f); close() }
        // 上半剩的沙
        val sandTop = Path().apply {
            val y = h * 0.5f - (h * 0.42f) * l
            val half = (w * 0.3f) * l
            moveTo(w * 0.5f - half, y); lineTo(w * 0.5f + half, y); lineTo(w * 0.5f, h * 0.5f); close()
        }
        val sandBottom = Path().apply {
            val y = h * 0.92f - (h * 0.42f) * (1 - l)
            val half = (w * 0.3f) * (1 - l)
            moveTo(w * 0.5f, y); lineTo(w * 0.5f + half, h * 0.92f); lineTo(w * 0.5f - half, h * 0.92f); close()
        }
        drawPath(sandTop, color.copy(alpha = 0.35f))
        drawPath(sandBottom, color.copy(alpha = 0.35f))
        drawPath(top, color, style = Stroke(hair))
        drawPath(bottom, color, style = Stroke(hair))
        drawLine(color, Offset(w * 0.12f, h * 0.08f), Offset(w * 0.88f, h * 0.08f), hair * 1.4f)
        drawLine(color, Offset(w * 0.12f, h * 0.92f), Offset(w * 0.88f, h * 0.92f), hair * 1.4f)
    }
}

/** 水纹：几道平行的细波浪（“流动波纹”），空状态用。 */
@Composable
fun WaveLines(color: Color, modifier: Modifier = Modifier, lines: Int = 7) {
    val still = LocalReduceMotion.current
    val phase by rememberInfiniteTransition(label = "wave").animateFloat(0f, (2 * PI).toFloat(), infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "wavePhase")
    Canvas(modifier) {
        val w = this.size.width
        val h = this.size.height
        val hair = 1.dp.toPx() * 0.8f
        for (i in 0 until lines) {
            val p = Path()
            val base = h * (0.25f + 0.5f * i / (lines - 1).coerceAtLeast(1))
            val amp = h * (0.10f + 0.05f * (i % 3))
            var x = 0f
            while (x <= w) {
                val y = base + amp * sin((x / w) * 2 * PI + (if (still) 0f else phase) + i * 0.5).toFloat()
                if (x == 0f) p.moveTo(x, y) else p.lineTo(x, y)
                x += w / 60
            }
            drawPath(p, color.copy(alpha = 0.25f + 0.5f * (i.toFloat() / lines)), style = Stroke(hair))
        }
    }
}
