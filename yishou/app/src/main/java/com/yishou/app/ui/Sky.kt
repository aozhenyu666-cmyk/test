package com.yishou.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yishou.app.data.JobStep
import com.yishou.app.data.StepResult
import com.yishou.app.ui.theme.reducedMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

/*
 * 天气和天体：短暂的状态用天气（等待时的风暴漩涡、揭晓时的云层开窗），
 * 积累下来的东西用天体（做事的轨道和星座、进度的月相）。界面平时是静的，一次操作才起一阵变化。
 */

/**
 * 风暴漩涡：几条流线绕着中心卷进去，光点顺着流线往里走，中心一颗金色的节点在呼吸。
 * 用在要等模型的时候（出题、锁答案、判卷）。
 */
@Composable
fun VortexLoader(label: String, modifier: Modifier = Modifier, size: Dp = 64.dp) {
    val still = reducedMotion()
    val time = rememberSeconds(still)
    val ink = MaterialTheme.colorScheme.onBackground
    val gold = MaterialTheme.colorScheme.tertiary
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Canvas(Modifier.size(size)) {
            val t = time.value
            val c = center
            val radius = this.size.minDimension / 2f
            val arms = 6
            val k = 0.32f
            val spin = -t * 0.9f
            fun at(arm: Int, theta: Float): Offset {
                // 对数螺线：θ 越大越靠近中心
                val r = radius * exp(-k * theta)
                val a = theta + arm * 2f * PI.toFloat() / arms + spin
                return Offset(c.x + r * cos(a), c.y + r * sin(a))
            }
            for (arm in 0 until arms) {
                val p = Path()
                val steps = 40
                for (i in 0..steps) {
                    val pt = at(arm, i / steps.toFloat() * 7f)
                    if (i == 0) p.moveTo(pt.x, pt.y) else p.lineTo(pt.x, pt.y)
                }
                drawPath(p, ink.copy(alpha = 0.12f), style = Stroke(1.dp.toPx(), cap = StrokeCap.Round))
            }
            // 光点顺着流线卷进去
            for (i in 0 until 18) {
                val arm = i % arms
                val s = ((t * 0.35f + i / 18f) % 1f)
                val pt = at(arm, s * 7f)
                val a = (if (s < 0.15f) s / 0.15f else 1f - (s - 0.15f) / 0.85f).coerceIn(0f, 1f)
                drawCircle(if (i % 4 == 0) gold.copy(alpha = 0.9f * a) else ink.copy(alpha = 0.55f * a), (1.1f + 1.4f * (1f - s)).dp.toPx(), pt)
            }
            val pulse = if (still) 0.5f else 0.5f + 0.5f * sin(t * 3f)
            drawCircle(gold.copy(alpha = 0.18f + 0.15f * pulse), (5f + 3f * pulse).dp.toPx(), c)
            drawCircle(gold, 3.dp.toPx(), c)
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 云层开窗：内容先被几团薄云盖着，一出现云就往两边散开，露出底下的东西。
 * 用在揭晓锁住的答案时。[key] 变了就再开一次窗。
 */
@Composable
fun CloudReveal(key: Any?, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val still = reducedMotion()
    val open = remember(key) { Animatable(if (still) 1f else 0f) }
    LaunchedEffect(key) {
        if (still) return@LaunchedEffect
        delay(180)
        open.animateTo(1f, tween(1_500, easing = FastOutSlowInEasing))
    }
    val cloud = MaterialTheme.colorScheme.surfaceContainerHigh
    val sky = MaterialTheme.colorScheme.secondary
    Box(modifier) {
        content()
        if (open.value < 1f) {
            Canvas(Modifier.matchParentSize()) {
                val p = open.value
                val w = size.width
                val h = size.height
                // 先透出一点天色
                drawRect(sky.copy(alpha = 0.06f * (1f - p)))
                val blobs = listOf(
                    Triple(0.15f, 0.30f, 0.45f), Triple(0.42f, 0.65f, 0.55f), Triple(0.70f, 0.25f, 0.50f),
                    Triple(0.88f, 0.70f, 0.45f), Triple(0.30f, 0.85f, 0.40f), Triple(0.60f, 0.45f, 0.60f),
                )
                blobs.forEachIndexed { i, (bx, by, br) ->
                    val dir = if (bx < 0.5f) -1f else 1f
                    val drift = dir * p * w * (0.55f + 0.1f * (i % 3))
                    val cx = bx * w + drift
                    val cy = by * h - p * h * 0.08f * (i % 2)
                    val r = br * maxOf(w, h) * 0.5f
                    drawOval(
                        Brush.radialGradient(
                            listOf(cloud.copy(alpha = (1f - p) * 0.98f), cloud.copy(alpha = (1f - p) * 0.85f), Color.Transparent),
                            center = Offset(cx, cy),
                            radius = r,
                        ),
                        topLeft = Offset(cx - r, cy - r * 0.6f),
                        size = Size(r * 2, r * 1.2f),
                    )
                }
            }
        }
    }
}

/**
 * 月相：一件事做到几成。细弧 → 半月 → 满月。
 */
@Composable
fun MoonPhase(fraction: Float, size: Dp = 28.dp, modifier: Modifier = Modifier) {
    val gold = MaterialTheme.colorScheme.tertiary
    val faint = MaterialTheme.colorScheme.outlineVariant
    Canvas(modifier.size(size)) { moon(fraction.coerceIn(0f, 1f), gold, faint) }
}

private fun DrawScope.moon(f: Float, lit: Color, dark: Color) {
    val r = size.minDimension / 2f * 0.9f
    val c = center
    drawCircle(dark.copy(alpha = 0.6f), r, c)
    if (f <= 0f) {
        drawCircle(dark, r, c, style = Stroke(1.dp.toPx()))
        return
    }
    if (f >= 1f) {
        drawCircle(lit, r, c)
        return
    }
    val disk = Rect(c.x - r, c.y - r, c.x + r, c.y + r)
    val half = Path().apply {
        moveTo(c.x, c.y - r)
        arcTo(disk, -90f, 180f, false)
        close()
    }
    val rx = abs(1f - 2f * f) * r
    val ellipse = Path().apply { addOval(Rect(c.x - rx, c.y - r, c.x + rx, c.y + r)) }
    val shape = Path().apply { op(half, ellipse, if (f < 0.5f) PathOperation.Difference else PathOperation.Union) }
    drawPath(shape, lit)
    drawCircle(lit.copy(alpha = 0.5f), r, c, style = Stroke(1.dp.toPx()))
}

/**
 * 轨道系统：中间的太阳是现在这一步（日冕在呼吸）；近轨上是下一步，远轨上是还没轮到的几步，慢慢转；
 * 做完的步落到上方连成星座（实心做成，红圈没做成或卡住，灰点跳过）。
 * 每做完一步，一颗流星从太阳飞到星座里它的位置。
 */
@Composable
fun OrbitFlow(steps: List<JobStep>, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val time = rememberSeconds(still)
    val sorted = remember(steps) { steps.sortedBy { it.idx } }
    val done = sorted.filter { it.doneAt != null }
    val pending = sorted.filter { it.doneAt == null }
    val fly = remember { Animatable(1f) }
    LaunchedEffect(done.size) {
        if (still || done.isEmpty()) return@LaunchedEffect
        fly.snapTo(0f)
        fly.animateTo(1f, tween(900, easing = FastOutSlowInEasing))
    }
    val ink = MaterialTheme.colorScheme.onBackground
    val faint = MaterialTheme.colorScheme.outlineVariant
    val gold = MaterialTheme.colorScheme.tertiary
    val miss = MaterialTheme.colorScheme.error
    Canvas(modifier.fillMaxWidth().height(230.dp)) {
        val t = time.value
        val w = size.width
        val h = size.height
        val sun = Offset(w / 2f, h * 0.66f)
        // 星座：上方一道缓弧
        val n = maxOf(done.size, 1)
        fun star(i: Int): Offset {
            val x = w * (0.08f + 0.84f * if (n == 1) 0.5f else i / (n - 1f))
            val y = h * (0.2f + 0.08f * sin(i * 1.7f) - 0.08f * sin(PI.toFloat() * (x / w)))
            return Offset(x, y)
        }
        for (i in 1 until done.size) drawLine(ink.copy(alpha = 0.16f), star(i - 1), star(i), strokeWidth = 1.dp.toPx())
        done.forEachIndexed { i, s ->
            val last = i == done.lastIndex
            val p = if (last) fly.value else 1f
            val pos = if (p < 1f) Offset(sun.x + (star(i).x - sun.x) * p, sun.y + (star(i).y - sun.y) * p) else star(i)
            if (last && p < 1f) {
                drawLine(
                    Brush.linearGradient(listOf(Color.Transparent, gold.copy(alpha = 0.7f)), start = sun, end = pos),
                    sun, pos, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                )
            }
            when (s.result) {
                StepResult.OK -> drawCircle(ink, 4.5.dp.toPx(), pos)
                StepResult.SKIP -> drawCircle(faint, 3.dp.toPx(), pos)
                else -> drawCircle(miss, 4.dp.toPx(), pos, style = Stroke(1.6.dp.toPx()))
            }
        }
        // 轨道
        val nearRx = w * 0.2f
        val nearRy = h * 0.11f
        val farRx = w * 0.4f
        val farRy = h * 0.22f
        drawOval(faint.copy(alpha = 0.7f), Offset(sun.x - nearRx, sun.y - nearRy), Size(nearRx * 2, nearRy * 2), style = Stroke(1.dp.toPx()))
        drawOval(faint.copy(alpha = 0.45f), Offset(sun.x - farRx, sun.y - farRy), Size(farRx * 2, farRy * 2), style = Stroke(1.dp.toPx()))
        fun onOrbit(rx: Float, ry: Float, a: Float) = Offset(sun.x + rx * cos(a), sun.y + ry * sin(a))
        // 下一步在近轨，其余在远轨
        pending.drop(1).forEachIndexed { i, _ ->
            if (i == 0) {
                drawCircle(ink.copy(alpha = 0.7f), 3.5.dp.toPx(), onOrbit(nearRx, nearRy, t * 0.25f + 0.6f), style = Stroke(1.4.dp.toPx()))
            } else {
                val count = (pending.size - 2).coerceAtLeast(1)
                val a = t * 0.08f + (i - 1) * 2f * PI.toFloat() / count
                drawCircle(ink.copy(alpha = 0.35f), 2.6.dp.toPx(), onOrbit(farRx, farRy, a), style = Stroke(1.2.dp.toPx()))
            }
        }
        // 太阳：现在这一步
        if (pending.isNotEmpty()) {
            val breath = if (still) 0.5f else 0.5f + 0.5f * sin(t * 1.8f)
            for (k in 0 until 12) {
                val a = k * PI.toFloat() / 6f + t * 0.1f
                val r1 = 12.dp.toPx()
                val r2 = r1 + (4f + 3f * breath * (if (k % 2 == 0) 1f else 0.6f)).dp.toPx()
                drawLine(gold.copy(alpha = 0.45f), Offset(sun.x + r1 * cos(a), sun.y + r1 * sin(a)), Offset(sun.x + r2 * cos(a), sun.y + r2 * sin(a)), strokeWidth = 1.2.dp.toPx(), cap = StrokeCap.Round)
            }
            drawCircle(gold.copy(alpha = 0.18f + 0.12f * breath), 11.dp.toPx(), sun)
            drawCircle(gold, 7.dp.toPx(), sun)
        } else {
            drawCircle(gold, 9.dp.toPx(), sun, style = Stroke(1.6.dp.toPx()))
        }
    }
}
