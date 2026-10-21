package com.moling.app.ui.art

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.moling.app.ui.Ink
import com.moling.app.ui.Paper
import com.moling.app.ui.Seal
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * 墨线插画（v7）：细墨线 + 一点淡彩，纸底。全部用代码画、会轻轻动，不放图片。
 * 每一个对应墨灵的一件事：
 *   光环 = 在想 / 在加载      月相 = 一天走到哪      波纹 = 今天的水道
 *   山 = 主线                火 = 精力 / 专注       星涡 = 体系图
 *   叶 = 一天天长出来的信任    云 = 休息             沙漏 = 拦人倒计时
 *   环 = 进度                涟漪 = 她出声          阶梯 = 偏离的升级
 *   对话 = 和 GPT 贯通        纸页 = 收件箱
 */

val WashBlue = Color(0xFF6F8FB8)
val WashGold = Color(0xFFD7A24A)
val WashGreen = Color(0xFF7E9B7C)
val WashViolet = Color(0xFF8C82C8)
val WashOrange = Color(0xFFE0814F)

private val Line = Ink.copy(alpha = 0.72f)
private val Faint = Ink.copy(alpha = 0.28f)

private fun DrawScope.hair(w: Float = 0.8f) = Stroke(w.dp.toPx(), cap = StrokeCap.Round)

/** 无限循环的 0..1（[ms] 一圈）；[on] = false 时停在 0，省电。 */
@Composable
private fun loop(ms: Int, on: Boolean = true): State<Float> {
    if (!on) return remember { mutableFloatStateOf(0f) }
    val t = rememberInfiniteTransition(label = "ink")
    return t.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "ink")
}

/** 参考图里常见的“轴线 + 小圆点”。 */
private fun DrawScope.axis(c: Offset, r: Float) {
    drawLine(Faint, Offset(c.x, c.y - r), Offset(c.x, c.y + r), 0.6.dp.toPx())
    drawCircle(Line, 1.6.dp.toPx(), Offset(c.x, c.y - r))
    drawCircle(Line, 1.2.dp.toPx(), Offset(c.x, c.y + r))
}

// ------------------------------------------------------------------ 光环（在想）

@Composable
fun InkHalo(modifier: Modifier = Modifier.size(48.dp), color: Color = WashGold, animated: Boolean = true) {
    val t by loop(2600, animated)
    Canvas(modifier) {
        val c = center
        val r = size.minDimension * 0.22f
        axis(c, size.minDimension * 0.48f)
        for (i in 0 until 28) {
            val a = (i / 28f) * 2 * PI.toFloat()
            val l = if (i % 2 == 0) 1.55f else 1.38f
            drawLine(Faint, c + Offset(cos(a), sin(a)) * (r * 1.22f), c + Offset(cos(a), sin(a)) * (r * l), 0.6.dp.toPx())
        }
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0.05f)), c, r), r, c)
        drawCircle(Line, r, c, style = hair())
        val orbit = r * 1.85f
        drawCircle(Faint, orbit, c, style = Stroke(0.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 4.dp.toPx()))))
        val a = t * 2 * PI.toFloat()
        drawCircle(color, 2.6.dp.toPx(), c + Offset(cos(a), sin(a)) * orbit)
        drawCircle(Line, 1.4.dp.toPx(), c + Offset(cos(a + PI.toFloat()), sin(a + PI.toFloat())) * orbit)
    }
}

/** 一行“在想…”：小光环 + 字。 */
@Composable
fun InkThinking(text: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        InkHalo(Modifier.size(28.dp))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ------------------------------------------------------------------ 月相（一天走到哪）

/** [phase] 0..1：0 = 新月（午夜），0.5 = 满月（正午）。 */
@Composable
fun InkMoon(phase: Float, modifier: Modifier = Modifier.size(56.dp)) {
    Canvas(modifier) {
        val c = center
        val r = size.minDimension * 0.3f
        axis(c, size.minDimension * 0.48f)
        drawCircle(Faint, r * 1.45f, c, style = Stroke(0.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 3.dp.toPx()))))
        val light = 1f - abs(2f * phase - 1f)
        val dir = if (phase < 0.5f) 1f else -1f
        val disk = Path().apply { addOval(Rect(c, r)) }
        clipPath(disk) {
            drawCircle(Brush.radialGradient(listOf(Color(0xFF55565C), Ink), c + Offset(-r * 0.3f, -r * 0.3f), r * 1.4f), r, c)
            drawCircle(Paper, r, c + Offset(dir * 2 * r * (1f - light), 0f))
        }
        drawCircle(Line, r, c, style = hair())
        // 侧边的小月亮
        drawCircle(Line, r * 0.22f, Offset(c.x, c.y - r * 1.45f - r * 0.3f), style = hair(0.6f))
    }
}

// ------------------------------------------------------------------ 波纹（水道）

@Composable
fun InkWaves(modifier: Modifier = Modifier.fillMaxWidth().height(64.dp), color: Color = WashBlue, lines: Int = 16, animated: Boolean = true) {
    val t by loop(9000, animated)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val ph = t * 2 * PI.toFloat()
        for (i in 0 until lines) {
            val k = i / (lines - 1f)
            val amp = h * (0.12f + 0.22f * sin(k * PI.toFloat()))
            val base = h * (0.2f + 0.6f * k)
            val p = Path()
            var x = 0f
            while (x <= w) {
                val y = base + amp * sin(x / w * 2.4f * PI.toFloat() + ph + k * 1.6f) * (0.6f + 0.4f * sin(x / w * PI.toFloat()))
                if (x == 0f) p.moveTo(x, y) else p.lineTo(x, y)
                x += 6f
            }
            val a = 0.18f + 0.5f * sin(k * PI.toFloat())
            drawPath(p, (if (i % 3 == 0) Ink else color).copy(alpha = a * if (i % 3 == 0) 0.6f else 1f), style = hair(0.7f))
        }
    }
}

// ------------------------------------------------------------------ 山（主线）

@Composable
fun InkMountain(modifier: Modifier = Modifier.size(96.dp), sun: Color = WashGold) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val base = h * 0.66f
        drawCircle(sun.copy(alpha = 0.18f), w * 0.11f, Offset(w * 0.5f, h * 0.16f))
        drawCircle(Line, w * 0.11f, Offset(w * 0.5f, h * 0.16f), style = hair(0.6f))
        drawArc(Faint, 200f, 140f, false, Offset(w * 0.12f, h * 0.02f), Size(w * 0.76f, h * 0.9f), style = hair(0.6f))
        val peaks = listOf(
            Triple(0.08f, 0.5f, 0.42f), Triple(0.28f, 0.52f, 0.62f), Triple(0.52f, 0.22f, 0.92f), Triple(0.7f, 0.45f, 0.98f),
        )
        peaks.forEach { (l, top, r) ->
            val px = w * (l + r) / 2
            val path = Path().apply { moveTo(w * l, base); lineTo(px, h * top); lineTo(w * r, base) }
            drawPath(path, Paper)
            drawPath(path, Line, style = hair())
            // 阴面的排线
            for (i in 1..6) {
                val f = i / 7f
                drawLine(Faint, Offset(px, h * top) + Offset(0f, (base - h * top) * f * 0.15f), Offset(px + (w * r - px) * f, base), 0.5.dp.toPx())
            }
        }
        drawLine(Line, Offset(0f, base), Offset(w, base), 0.6.dp.toPx())
        for (i in 1..4) {
            val y = base + h * 0.06f * i
            val half = w * (0.42f - i * 0.08f)
            drawLine(Faint, Offset(w / 2 - half, y), Offset(w / 2 + half, y), 0.6.dp.toPx())
        }
    }
}

// ------------------------------------------------------------------ 火（精力 / 专注）

/** [level] 0..1：火有多旺。 */
@Composable
fun InkFlame(level: Float, modifier: Modifier = Modifier.size(56.dp), color: Color = WashOrange, animated: Boolean = true) {
    val t by loop(1800, animated)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val lv = level.coerceIn(0.15f, 1f)
        val sway = sin(t * 2 * PI.toFloat()) * w * 0.04f
        for (i in 0 until 5) {
            val k = i / 4f
            val top = h * (0.95f - lv * (0.8f - k * 0.35f))
            val half = w * (0.32f - k * 0.05f)
            val bx = w / 2
            val by = h * 0.95f
            val p = Path().apply {
                moveTo(bx, by)
                cubicTo(bx - half * 1.2f, by - h * 0.15f, bx - half * 0.6f + sway, top + (by - top) * 0.45f, bx + sway * (1.5f + k), top)
                cubicTo(bx + half * 0.5f + sway, top + (by - top) * 0.4f, bx + half * 1.2f, by - h * 0.2f, bx, by)
            }
            drawPath(p, color.copy(alpha = 0.07f + 0.05f * (1 - k)))
            drawPath(p, color.copy(alpha = 0.45f + 0.4f * k), style = hair(0.6f + 0.2f * k))
        }
        drawCircle(color, 1.6.dp.toPx(), Offset(w * 0.82f, h * (0.35f - 0.1f * t)))
        drawCircle(color.copy(alpha = 0.6f), 1.2.dp.toPx(), Offset(w * 0.16f, h * 0.5f))
    }
}

// ------------------------------------------------------------------ 星涡（体系图）

@Composable
fun InkGalaxy(modifier: Modifier = Modifier.size(56.dp), color: Color = WashViolet, animated: Boolean = true) {
    val t by loop(24000, animated)
    Canvas(modifier) {
        val c = center
        val r = size.minDimension * 0.46f
        rotate(t * 360f, c) {
            for (arm in 0 until 3) {
                for (i in 0 until 46) {
                    val k = i / 46f
                    val a = arm * 2.094f + k * 4.6f
                    val d = r * (0.08f + 0.92f * k)
                    val p = c + Offset(cos(a), sin(a)) * d
                    drawCircle((if (i % 4 == 0) color else Ink).copy(alpha = 0.25f + 0.55f * (1 - k)), (0.5f + 1.1f * (1 - k)).dp.toPx(), p)
                }
            }
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.5f), Color.Transparent), c, r * 0.3f), r * 0.3f, c)
        }
        star(c + Offset(-r * 0.55f, -r * 0.55f), r * 0.12f)
        star(c + Offset(r * 0.6f, r * 0.45f), r * 0.09f)
    }
}

private fun DrawScope.star(c: Offset, s: Float) {
    val p = Path().apply {
        moveTo(c.x, c.y - s)
        quadraticBezierTo(c.x, c.y, c.x + s, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y + s)
        quadraticBezierTo(c.x, c.y, c.x - s, c.y)
        quadraticBezierTo(c.x, c.y, c.x, c.y - s)
        close()
    }
    drawPath(p, Ink)
}

// ------------------------------------------------------------------ 叶（一天天长出来的）

/** [growth] 0..1：长到哪了（例如连续天数 / 14）。 */
@Composable
fun InkLeaf(growth: Float, modifier: Modifier = Modifier.size(72.dp), color: Color = WashGreen) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val g = growth.coerceIn(0.1f, 1f)
        val bottom = Offset(w * 0.5f, h * 0.98f)
        val tip = Offset(w * 0.55f, h * (0.98f - 0.9f * g))
        val ctrl = Offset(w * 0.38f, h * (0.98f - 0.5f * g))
        drawPath(Path().apply { moveTo(bottom.x, bottom.y); quadraticBezierTo(ctrl.x, ctrl.y, tip.x, tip.y) }, Line, style = hair())
        drawArc(Faint, 280f, 160f, false, Offset(w * 0.1f, h * 0.05f), Size(w * 0.8f, h * 0.8f), style = hair(0.6f))
        listOf(0.25f, 0.45f, 0.62f, 0.78f, 0.92f).forEachIndexed { i, f ->
            if (f > g + 0.05f) return@forEachIndexed
            // 茎（二次曲线）上 f 处的点
            val at = bottom * ((1 - f) * (1 - f)) + ctrl * (2 * (1 - f) * f) + tip * (f * f)
            val side = if (i % 2 == 0) -1f else 1f
            val len = w * (0.34f - 0.04f * i)
            val end = at + Offset(side * len, -len * 0.55f)
            val mid = (at + end) / 2f
            val n = Offset(-(end.y - at.y), end.x - at.x) * 0.28f
            val leaf = Path().apply {
                moveTo(at.x, at.y)
                quadraticBezierTo(mid.x + n.x, mid.y + n.y, end.x, end.y)
                quadraticBezierTo(mid.x - n.x, mid.y - n.y, at.x, at.y)
            }
            drawPath(leaf, color.copy(alpha = 0.22f + 0.1f * i / 4f))
            drawPath(leaf, Line, style = hair(0.7f))
            drawLine(Faint, at, end, 0.5.dp.toPx())
        }
        drawCircle(Line, 1.6.dp.toPx(), Offset(w * 0.5f, h * 0.04f))
    }
}

// ------------------------------------------------------------------ 云（休息）

@Composable
fun InkClouds(modifier: Modifier = Modifier.size(72.dp), animated: Boolean = true) {
    val t by loop(12000, animated)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val drift = sin(t * 2 * PI.toFloat()) * w * 0.03f
        drawCircle(Faint, w * 0.24f, Offset(w * 0.62f, h * 0.36f), style = hair(0.6f))
        listOf(Triple(0.28f, 0.62f, 0.22f), Triple(0.62f, 0.72f, 0.26f), Triple(0.42f, 0.84f, 0.2f)).forEachIndexed { i, (x, y, s) ->
            val cx = w * x + drift * (if (i % 2 == 0) 1 else -1)
            val cy = h * y
            val r = w * s
            val p = Path().apply {
                moveTo(cx - r * 1.4f, cy)
                cubicTo(cx - r * 1.5f, cy - r * 0.7f, cx - r * 0.7f, cy - r * 0.9f, cx - r * 0.4f, cy - r * 0.6f)
                cubicTo(cx - r * 0.2f, cy - r * 1.3f, cx + r * 0.8f, cy - r * 1.2f, cx + r * 0.8f, cy - r * 0.5f)
                cubicTo(cx + r * 1.4f, cy - r * 0.6f, cx + r * 1.6f, cy, cx + r * 1.3f, cy)
                close()
            }
            drawPath(p, Paper)
            drawPath(p, Line, style = hair(0.7f))
            for (k in 1..3) drawArc(Faint, 200f, 110f, false, Offset(cx - r * (0.9f - k * 0.15f), cy - r * (1.0f - k * 0.18f)), Size(r * (1.6f - k * 0.3f), r * 1.2f), style = hair(0.5f))
        }
    }
}

// ------------------------------------------------------------------ 沙漏（倒计时）

/** [left] 0..1：还剩多少。 */
@Composable
fun InkHourglass(left: Float, modifier: Modifier = Modifier.size(56.dp), color: Color = WashGold) {
    val t by loop(900)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val l = left.coerceIn(0f, 1f)
        val cx = w / 2
        val top = h * 0.1f
        val bot = h * 0.9f
        val mid = h / 2
        val half = w * 0.3f
        drawLine(Line, Offset(cx - half * 1.2f, top), Offset(cx + half * 1.2f, top), 1.dp.toPx())
        drawLine(Line, Offset(cx - half * 1.2f, bot), Offset(cx + half * 1.2f, bot), 1.dp.toPx())
        // 上面的沙：剩多少就多高（三角形下部）
        val ty = mid - (mid - top) * l
        val tw = half * (mid - ty) / (mid - top)
        if (l > 0) drawPath(Path().apply { moveTo(cx - tw, ty); lineTo(cx + tw, ty); lineTo(cx, mid); close() }, color.copy(alpha = 0.55f))
        // 下面堆起来的
        val by = bot - (bot - mid) * (1 - l) * 0.85f
        drawPath(Path().apply { moveTo(cx - half, bot); lineTo(cx + half, bot); lineTo(cx, by); close() }, color.copy(alpha = 0.45f))
        if (l in 0.01f..0.99f) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 3.dp.toPx()), -t * 5.dp.toPx())
            drawLine(color, Offset(cx, mid), Offset(cx, by), 1.dp.toPx(), pathEffect = dash)
        }
        val glass = Path().apply {
            moveTo(cx - half, top); lineTo(cx + half, top); lineTo(cx + w * 0.03f, mid); lineTo(cx + half, bot)
            lineTo(cx - half, bot); lineTo(cx - w * 0.03f, mid); close()
        }
        drawPath(glass, Line, style = hair())
    }
}

// ------------------------------------------------------------------ 环（进度）

@Composable
fun InkRing(progress: Float, modifier: Modifier = Modifier.size(56.dp), color: Color = Seal) {
    Canvas(modifier) {
        val stroke = 3.dp.toPx()
        val d = size.minDimension - stroke * 2
        val tl = Offset((size.width - d) / 2, (size.height - d) / 2)
        drawCircle(Faint, d / 2, center, style = hair(0.7f))
        val p = progress.coerceIn(0f, 1f)
        drawArc(color.copy(alpha = 0.85f), -90f, 360f * p, false, tl, Size(d, d), style = Stroke(stroke, cap = StrokeCap.Round))
        val a = (-90f + 360f * p) / 180f * PI.toFloat()
        drawCircle(Ink, 2.dp.toPx(), center + Offset(cos(a), sin(a)) * (d / 2))
        for (i in 0 until 12) {
            val b = i / 12f * 2 * PI.toFloat()
            drawLine(Faint, center + Offset(cos(b), sin(b)) * (d / 2 + stroke), center + Offset(cos(b), sin(b)) * (d / 2 + stroke * 1.8f), 0.5.dp.toPx())
        }
    }
}

// ------------------------------------------------------------------ 涟漪（她出声）

@Composable
fun InkRipple(modifier: Modifier = Modifier.size(56.dp), color: Color = WashBlue, animated: Boolean = true) {
    val t by loop(2400, animated)
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height * 0.68f)
        val w = size.width * 0.46f
        for (i in 0 until 3) {
            val k = (t + i / 3f) % 1f
            drawOval(color.copy(alpha = 0.6f * (1 - k)), Offset(c.x - w * k, c.y - w * k * 0.3f), Size(2 * w * k, 2 * w * k * 0.3f), style = hair(0.8f))
        }
        val dy = size.height * 0.4f * (1 - ((t * 2) % 1f).let { 1 - exp(-4 * it) })
        val drop = Offset(c.x, c.y - dy - size.height * 0.08f)
        val s = size.minDimension * 0.06f
        drawPath(
            Path().apply {
                moveTo(drop.x, drop.y - s * 1.8f)
                cubicTo(drop.x + s, drop.y - s * 0.4f, drop.x + s, drop.y + s, drop.x, drop.y + s)
                cubicTo(drop.x - s, drop.y + s, drop.x - s, drop.y - s * 0.4f, drop.x, drop.y - s * 1.8f)
            },
            color.copy(alpha = 0.75f),
        )
    }
}

// ------------------------------------------------------------------ 阶梯（升级）

/** [level] 走到第几级（0 = 还没开始），共 [of] 级。 */
@Composable
fun InkSteps(level: Int, of: Int, modifier: Modifier = Modifier.size(56.dp), color: Color = Seal) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val n = of.coerceAtLeast(1)
        val sw = w * 0.8f / n
        val sh = h * 0.7f / n
        val x0 = w * 0.1f
        val y0 = h * 0.92f
        for (i in 0 until n) {
            val tl = Offset(x0 + sw * i, y0 - sh * (i + 1))
            if (i < level) drawRect(color.copy(alpha = 0.18f + 0.12f * i), tl, Size(sw, sh * (i + 1)))
            drawPath(Path().apply { moveTo(tl.x, y0 - sh * i); lineTo(tl.x, tl.y); lineTo(tl.x + sw, tl.y) }, Line, style = hair())
        }
        drawLine(Line, Offset(x0, y0), Offset(x0 + sw * n, y0), 0.7.dp.toPx())
        val flagX = x0 + sw * (level.coerceIn(1, n) - 0.5f)
        val flagY = y0 - sh * level.coerceIn(1, n)
        drawLine(Line, Offset(flagX, flagY), Offset(flagX, flagY - h * 0.16f), 0.8.dp.toPx())
        drawPath(Path().apply { moveTo(flagX, flagY - h * 0.16f); lineTo(flagX + w * 0.1f, flagY - h * 0.12f); lineTo(flagX, flagY - h * 0.08f); close() }, color)
    }
}

// ------------------------------------------------------------------ 对话（和 GPT 贯通）

@Composable
fun InkBubbles(modifier: Modifier = Modifier.size(64.dp), color: Color = WashViolet, animated: Boolean = true) {
    val t by loop(1500, animated)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        fun bubble(tl: Offset, s: Size, tailLeft: Boolean, fill: Color) {
            val p = Path().apply {
                addRoundRect(androidx.compose.ui.geometry.RoundRect(Rect(tl, s), androidx.compose.ui.geometry.CornerRadius(s.height * 0.45f)))
            }
            drawPath(p, fill)
            drawPath(p, Line, style = hair(0.7f))
            val tx = if (tailLeft) tl.x + s.width * 0.22f else tl.x + s.width * 0.78f
            val tail = Path().apply {
                moveTo(tx - s.width * 0.06f, tl.y + s.height - 1f)
                lineTo(tx + (if (tailLeft) -1 else 1) * s.width * 0.1f, tl.y + s.height * 1.3f)
                lineTo(tx + s.width * 0.06f, tl.y + s.height - 1f)
            }
            drawPath(tail, fill)
            drawPath(tail, Line, style = hair(0.7f))
        }
        bubble(Offset(w * 0.06f, h * 0.12f), Size(w * 0.58f, h * 0.38f), true, color.copy(alpha = 0.16f))
        bubble(Offset(w * 0.36f, h * 0.46f), Size(w * 0.58f, h * 0.36f), false, Paper)
        for (i in 0 until 3) {
            val k = ((t * 3 - i) % 3f).let { if (it in 0f..1f) sin(it * PI.toFloat()) else 0f }
            drawCircle(Ink.copy(alpha = 0.35f + 0.5f * k), (1.4f + 0.8f * k).dp.toPx(), Offset(w * (0.53f + 0.11f * i), h * 0.64f))
        }
        drawCircle(Faint, w * 0.47f, center, style = Stroke(0.6.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.dp.toPx(), 4.dp.toPx()))))
    }
}

// ------------------------------------------------------------------ 纸页（收件箱）

@Composable
fun InkPapers(modifier: Modifier = Modifier.size(64.dp), animated: Boolean = true) {
    val t by loop(5000, animated)
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        listOf(-12f, -4f, 6f).forEachIndexed { i, deg ->
            val lift = if (i == 2) sin(t * 2 * PI.toFloat()) * h * 0.03f else 0f
            rotate(deg + if (i == 2) sin(t * 2 * PI.toFloat()) * 3f else 0f, Offset(w * 0.5f, h * 0.6f)) {
                val tl = Offset(w * 0.24f, h * 0.18f - lift)
                val s = Size(w * 0.5f, h * 0.62f)
                drawRect(Paper, tl, s)
                drawRect(Line, tl, s, style = hair(0.7f))
                if (i == 2) for (k in 1..5) {
                    val y = tl.y + s.height * k / 6.5f
                    drawLine(Faint, Offset(tl.x + s.width * 0.14f, y), Offset(tl.x + s.width * (if (k == 5) 0.55f else 0.86f), y), 0.6.dp.toPx())
                }
            }
        }
    }
}

// ------------------------------------------------------------------ 分隔：线 + 点

/** 小节标题：字 + 一条细墨线，线尾一个小圆点（参考图里的那种）。 */
@Composable
fun InkTitle(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
        Spacer(Modifier.width(10.dp))
        Canvas(Modifier.weight(1f).height(10.dp)) {
            val y = size.height / 2
            drawLine(Faint, Offset(0f, y), Offset(size.width - 8.dp.toPx(), y), 0.7.dp.toPx())
            drawCircle(Line, 2.dp.toPx(), Offset(size.width - 3.dp.toPx(), y))
            drawCircle(Faint, 3.5.dp.toPx(), Offset(size.width - 3.dp.toPx(), y), style = hair(0.5f))
        }
    }
}

/** 0:00 新月 → 12:00 满月 → 24:00 新月。 */
fun moonPhase(minuteOfDay: Int): Float = (minuteOfDay.coerceIn(0, 1439) / 1440f)

/** I9 案板：一张纸 + 一道墨线。 */
@Composable
fun InkBench(modifier: Modifier = Modifier.size(56.dp), color: Color = Ink) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val left = w * 0.22f
        val right = w * 0.78f
        val top = h * 0.18f
        val bottom = h * 0.82f
        drawRoundRect(
            color = color.copy(alpha = 0.55f),
            topLeft = Offset(left, top),
            size = Size(right - left, bottom - top),
            cornerRadius = CornerRadius(w * 0.06f, w * 0.06f),
            style = Stroke(1.dp.toPx()),
        )
        val lineY = top + (bottom - top) * 0.36f
        drawLine(color.copy(alpha = 0.5f), Offset(left + w * 0.08f, lineY), Offset(right - w * 0.08f, lineY), 1.dp.toPx())
        drawLine(color.copy(alpha = 0.35f), Offset(left + w * 0.08f, lineY + h * 0.14f), Offset(right - w * 0.2f, lineY + h * 0.14f), 1.dp.toPx())
        drawLine(
            color = color.copy(alpha = 0.85f),
            start = Offset(left + w * 0.12f, bottom - h * 0.12f),
            end = Offset(right - w * 0.1f, top + (bottom - top) * 0.52f),
            strokeWidth = 3.dp.toPx(),
            cap = StrokeCap.Round,
        )
    }
}
