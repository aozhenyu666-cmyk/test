package com.moling.app.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.dp
import com.moling.core.live.Mood

/**
 * 墨灵的样子：一滴墨。会呼吸、会眨眼，表情跟着 [Mood] 变。
 * 气鼓鼓的时候腮帮子鼓起来、脸红、眉毛往下压、头上冒一个小怒气符号。
 * 全用代码画，不放别人的形象图。
 */
@Composable
fun MolingFace(mood: Mood, modifier: Modifier = Modifier.size(120.dp)) {
    val t = rememberInfiniteTransition(label = "face")
    val breathe by t.animateFloat(
        initialValue = 0.97f,
        targetValue = 1.03f,
        animationSpec = infiniteRepeatable(tween(if (mood == Mood.SLEEPY) 2600 else 1500, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "breathe",
    )
    val blink by t.animateFloat(
        initialValue = 0f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            keyframes {
                durationMillis = 3800
                0f at 0
                0f at 3500
                1f at 3600
                0f at 3720
            },
        ),
        label = "blink",
    )
    val shake by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(160), RepeatMode.Reverse), label = "shake")
    val puff by animateFloatAsState(if (mood == Mood.POUT) 1.13f else 1f, spring(dampingRatio = 0.35f), label = "puff")

    Canvas(modifier) {
        val r = minOf(size.width, size.height) * 0.34f
        val cx = size.width / 2 + if (mood == Mood.POUT) shake * r * 0.015f else 0f
        val cy = size.height * 0.6f
        val top = cy - r * 1.55f
        val tilt = when (mood) { Mood.CURIOUS -> -9f; Mood.SMUG -> 5f; else -> 0f }

        rotate(tilt, Offset(cx, cy + r)) {
            scale(breathe * puff, breathe * (1f - (puff - 1f) * 0.35f), Offset(cx, cy + r)) {
                body(cx, cy, r, top)
                face(mood, cx, cy, r, if (mood.eyesOpen()) blink else 0f)
            }
        }
        extras(mood, cx, cy, r, top)
    }
}

private fun Mood.eyesOpen() = this != Mood.HAPPY && this != Mood.SLEEPY

private val Cheek = Color(0xFFE8826E)
private val Tear = Color(0xFF8EC5E8)
private val Mouth = Color(0xFFF2B8A8)

private fun DrawScope.body(cx: Float, cy: Float, r: Float, top: Float) {
    val drop = Path().apply {
        moveTo(cx, top)
        cubicTo(cx + r * 0.25f, top + r * 0.55f, cx + r, cy - r * 0.65f, cx + r, cy)
        arcTo(Rect(Offset(cx, cy), r), 0f, 180f, false)
        cubicTo(cx - r, cy - r * 0.65f, cx - r * 0.25f, top + r * 0.55f, cx, top)
        close()
    }
    drawPath(drop, Brush.radialGradient(listOf(Color(0xFF454A57), Ink), Offset(cx - r * 0.35f, cy - r * 0.45f), r * 1.7f))
    // 纸上的反光
    drawOval(Color.White.copy(alpha = 0.16f), Offset(cx - r * 0.66f, cy - r * 0.7f), Size(r * 0.28f, r * 0.46f))
}

private fun DrawScope.face(mood: Mood, cx: Float, cy: Float, r: Float, blink: Float) {
    val ey = cy - r * 0.08f
    val ew = r * 0.15f
    val eh = r * 0.2f
    val line = Stroke(r * 0.07f, cap = StrokeCap.Round)
    val thin = Stroke(r * 0.055f, cap = StrokeCap.Round)

    // 腮红（气鼓鼓最红）
    val cheek = when (mood) { Mood.POUT -> 0.75f; Mood.HAPPY, Mood.SMUG -> 0.35f; else -> 0f }
    if (cheek > 0) listOf(-1f, 1f).forEach { s ->
        drawOval(Cheek.copy(alpha = cheek), Offset(cx + s * r * 0.62f - r * 0.17f, cy + r * 0.14f), Size(r * 0.34f, r * 0.2f))
    }

    listOf(-1f, 1f).forEach { s ->
        val x = cx + s * r * 0.36f
        val big = if (mood == Mood.CURIOUS && s > 0) 1.2f else 1f
        when (mood) {
            Mood.HAPPY -> drawArc(Color.White, 200f, 140f, false, Offset(x - ew, ey - eh * 0.6f), Size(ew * 2, eh * 1.4f), style = line)
            Mood.SLEEPY -> drawArc(Color.White, 20f, 140f, false, Offset(x - ew, ey - eh), Size(ew * 2, eh * 1.2f), style = line)
            else -> {
                val open = (1f - blink).coerceIn(0f, 1f)
                if (open < 0.2f) {
                    drawLine(Color.White, Offset(x - ew, ey), Offset(x + ew, ey), r * 0.06f, StrokeCap.Round)
                } else {
                    val w = ew * big
                    val h = eh * big * open
                    drawOval(Color.White, Offset(x - w, ey - h), Size(w * 2, h * 2))
                    val look = when (mood) {
                        Mood.SAD, Mood.WORRIED -> Offset(0f, h * 0.35f)
                        Mood.SMUG -> Offset(s * w * 0.2f, h * 0.35f)
                        Mood.CURIOUS -> Offset(w * 0.25f, -h * 0.15f)
                        else -> Offset(0f, h * 0.1f)
                    }
                    drawCircle(Ink, r * 0.085f * big, Offset(x, ey) + look)
                    drawCircle(Color.White, r * 0.025f, Offset(x, ey) + look + Offset(-r * 0.03f, -r * 0.035f))
                    // 斜眼：上半眼皮压下来
                    if (mood == Mood.SMUG) drawRect(Ink, Offset(x - w * 1.1f, ey - h * 1.1f), Size(w * 2.2f, h * 0.95f))
                }
            }
        }
        // 眉毛：生气内低外高，担心 / 难过内高外低
        val inner = x - s * ew * 1.1f
        val outer = x + s * ew * 1.1f
        val by = ey - eh * 1.75f
        when (mood) {
            Mood.POUT -> drawLine(Color.White, Offset(outer, by - r * 0.08f), Offset(inner, by + r * 0.06f), r * 0.06f, StrokeCap.Round)
            Mood.WORRIED, Mood.SAD -> drawLine(Color.White, Offset(outer, by + r * 0.04f), Offset(inner, by - r * 0.08f), r * 0.05f, StrokeCap.Round)
            Mood.CURIOUS -> if (s > 0) drawArc(Color.White, 200f, 140f, false, Offset(x - ew, by - r * 0.12f), Size(ew * 2, r * 0.16f), style = thin)
            else -> {}
        }
    }

    val my = cy + r * 0.36f
    when (mood) {
        Mood.CALM -> drawArc(Color.White, 20f, 140f, false, Offset(cx - r * 0.15f, my - r * 0.1f), Size(r * 0.3f, r * 0.16f), style = thin)
        Mood.HAPPY -> drawArc(Mouth, 0f, 180f, true, Offset(cx - r * 0.18f, my - r * 0.12f), Size(r * 0.36f, r * 0.3f))
        Mood.CURIOUS -> drawCircle(Color.White, r * 0.06f, Offset(cx + r * 0.04f, my), style = thin)
        Mood.WORRIED -> drawPath(
            Path().apply {
                moveTo(cx - r * 0.18f, my)
                quadraticBezierTo(cx - r * 0.09f, my - r * 0.07f, cx, my)
                quadraticBezierTo(cx + r * 0.09f, my + r * 0.07f, cx + r * 0.18f, my)
            },
            Color.White, style = thin,
        )
        // 嘟嘴：小小的倒弧
        Mood.POUT -> drawArc(Color.White, 200f, 140f, false, Offset(cx - r * 0.09f, my - r * 0.02f), Size(r * 0.18f, r * 0.14f), style = line)
        Mood.SLEEPY -> drawOval(Mouth, Offset(cx - r * 0.05f, my - r * 0.04f), Size(r * 0.1f, r * 0.12f))
        Mood.SMUG -> drawPath(
            Path().apply {
                moveTo(cx - r * 0.14f, my + r * 0.02f)
                quadraticBezierTo(cx + r * 0.04f, my + r * 0.06f, cx + r * 0.18f, my - r * 0.07f)
            },
            Color.White, style = thin,
        )
        Mood.SAD -> drawArc(Color.White, 200f, 140f, false, Offset(cx - r * 0.16f, my), Size(r * 0.32f, r * 0.2f), style = thin)
    }
    if (mood == Mood.SAD) {
        val x = cx - r * 0.36f
        drawOval(Tear, Offset(x - r * 0.05f, ey + eh * 1.2f), Size(r * 0.1f, r * 0.15f))
    }
}

/** 身体外面的小符号：怒气、睡觉的 z、开心的闪光。 */
private fun DrawScope.extras(mood: Mood, cx: Float, cy: Float, r: Float, top: Float) {
    val w = r * 0.07f
    when (mood) {
        Mood.POUT -> {
            val c = Offset(cx + r * 0.95f, top + r * 0.55f)
            val k = r * 0.17f
            // 四个往外翘的小弧 = 怒气符号
            listOf(0f, 90f, 180f, 270f).forEach { a ->
                rotate(a, c) {
                    drawArc(Seal, 180f, 90f, false, Offset(c.x - k * 1.15f, c.y - k * 1.15f), Size(k, k), style = Stroke(w, cap = StrokeCap.Round))
                }
            }
        }
        Mood.SLEEPY -> listOf(0.12f, 0.17f, 0.22f).forEachIndexed { i, s ->
            val o = Offset(cx + r * (0.85f + i * 0.28f), top + r * (0.9f - i * 0.38f))
            val k = r * s
            drawPath(
                Path().apply {
                    moveTo(o.x, o.y); lineTo(o.x + k, o.y); lineTo(o.x, o.y + k); lineTo(o.x + k, o.y + k)
                },
                Ink.copy(alpha = 0.7f), style = Stroke(w * 0.7f, cap = StrokeCap.Round),
            )
        }
        Mood.HAPPY, Mood.SMUG -> {
            val c = Offset(cx + r * 0.95f, top + r * 0.6f)
            val k = r * 0.16f
            drawLine(Seal, c - Offset(k, 0f), c + Offset(k, 0f), w * 0.8f, StrokeCap.Round)
            drawLine(Seal, c - Offset(0f, k), c + Offset(0f, k), w * 0.8f, StrokeCap.Round)
        }
        Mood.CURIOUS -> {
            val c = Offset(cx + r * 0.95f, top + r * 0.5f)
            drawArc(Seal, 180f, 260f, false, Offset(c.x - r * 0.12f, c.y - r * 0.12f), Size(r * 0.24f, r * 0.24f), style = Stroke(w * 0.8f, cap = StrokeCap.Round))
            drawCircle(Seal, w * 0.5f, Offset(c.x, c.y + r * 0.32f))
        }
        else -> {}
    }
}
