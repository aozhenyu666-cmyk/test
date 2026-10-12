package com.yishou.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.yishou.app.ui.theme.reducedMotion
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import androidx.compose.ui.graphics.drawscope.rotate

/*
 * 动效只有几种，全都克制：
 * - 点阵：等待时，一片小点按对角线依次亮起，像在排一盘棋；
 * - 枝条：一步做完，枝条从这一节长到下一节；
 * - 潮汐：首页底部几道很淡的水纹，慢慢起伏；
 * - 今天这一枝：做完一张就在枝上落一颗子。
 * 系统关了动画就全部静止。
 */

/** 等待（出题、判定）时的点阵：7×4 个小点，一道光沿对角线扫过去。 */
@Composable
fun MatrixLoader(label: String, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val t = if (still) 0f else rememberInfiniteTransition(label = "matrix").animateFloat(
        0f, 1f, infiniteRepeatable(tween(1_600, easing = LinearEasing)), label = "t",
    ).value
    val ink = MaterialTheme.colorScheme.onBackground
    val gold = MaterialTheme.colorScheme.tertiary
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Canvas(Modifier.size(width = 76.dp, height = 40.dp)) {
            val cols = 7
            val rows = 4
            val gx = size.width / cols
            val gy = size.height / rows
            for (c in 0 until cols) for (r in 0 until rows) {
                // 对角线上的位置 0–1，和扫过的光的距离决定亮度
                val d = (c + r).toFloat() / (cols + rows - 2)
                val dist = abs(((d - t + 1f) % 1f) - 0.5f) * 2f
                val glow = (1f - dist).coerceIn(0f, 1f)
                val a = if (still) 0.35f else 0.12f + 0.75f * glow * glow
                drawCircle(
                    if (glow > 0.85f && !still) gold.copy(alpha = a) else ink.copy(alpha = a),
                    radius = gx * (0.12f + 0.08f * glow),
                    center = Offset(gx * (c + 0.5f), gy * (r + 0.5f)),
                )
            }
        }
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/**
 * 枝条步骤：一根细枝连着几个节点，做完的节点实心，当前节点外面一圈呼吸。
 * 往前走一步时，枝条从上一个节点长过去（带一点缓出）。
 */
@Composable
fun BranchSteps(steps: List<String>, current: Int, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val grow = remember { Animatable(current.toFloat()) }
    LaunchedEffect(current) {
        if (still) grow.snapTo(current.toFloat()) else grow.animateTo(current.toFloat(), tween(520, easing = FastOutSlowInEasing))
    }
    val pulse = if (still) 0.6f else rememberInfiniteTransition(label = "pulse").animateFloat(
        0.25f, 0.9f, infiniteRepeatable(tween(1_200), RepeatMode.Reverse), label = "p",
    ).value
    val ink = MaterialTheme.colorScheme.onBackground
    val faint = MaterialTheme.colorScheme.outlineVariant
    val gold = MaterialTheme.colorScheme.tertiary
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            val n = steps.size
            if (n < 2) return@Canvas
            val y = size.height / 2
            val pad = 10.dp.toPx()
            val gap = (size.width - pad * 2) / (n - 1)
            fun x(i: Float) = pad + gap * i
            drawLine(faint, Offset(x(0f), y), Offset(x((n - 1).toFloat()), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            drawLine(ink, Offset(x(0f), y), Offset(x(grow.value.coerceIn(0f, (n - 1).toFloat())), y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
            for (i in 0 until n) {
                val c = Offset(x(i.toFloat()), y)
                when {
                    i < current -> drawCircle(ink, 5.dp.toPx(), c)
                    i == current -> {
                        drawCircle(gold.copy(alpha = pulse * 0.35f), 9.dp.toPx(), c)
                        drawCircle(gold, 5.dp.toPx(), c)
                    }
                    else -> drawCircle(faint, 4.dp.toPx(), c, style = Stroke(2.dp.toPx()))
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            steps.forEachIndexed { i, s ->
                Text(
                    s,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == current) gold else if (i < current) ink else MaterialTheme.colorScheme.outline,
                )
            }
        }
    }
}

/** 多个不同周期叠加，曲线的振幅、节奏、方向各异，长时间看不会整齐地一起移动。 */
@Composable
fun TideBackdrop(modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val t = if (still) 0f else rememberInfiniteTransition(label = "tide").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(60_000, easing = LinearEasing)), label = "t",
    ).value
    val ink = MaterialTheme.colorScheme.onBackground
    val gold = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        for (k in 0 until 5) {
            val base = h * (0.38f + 0.12f * k)
            val amp = h * (0.05f + 0.012f * k) * (1f + 0.28f * sin(t * (k + 2) + k))
            val path = Path()
            val steps = 80
            for (i in 0..steps) {
                val x = w * i / steps
                val direction = if (k % 2 == 0) 1f else -1f
                val wave = x / w * 2f * PI.toFloat()
                val y = base + amp * sin(wave * (1.1f + 0.22f * k) + direction * t * (7 + 2 * k) + k * 1.7f) +
                    h * 0.025f * sin(wave * 2.3f - t * (3 + k) + k)
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, (if (k == 1) gold else ink).copy(alpha = if (k == 1) 0.3f else 0.09f), style = Stroke(1.3.dp.toPx()))
        }
    }
}

/** 螺旋时之轮。中心呼吸、外沿落子；有倒计时进度时外圈表示真实进度。 */
@Composable
fun TimeWheel(modifier: Modifier = Modifier, progress: Float? = null) {
    val still = reducedMotion()
    val t = if (still) 0f else rememberInfiniteTransition(label = "time-wheel").animateFloat(
        0f, (2 * PI).toFloat(), infiniteRepeatable(tween(12_000, easing = LinearEasing)), label = "turn",
    ).value
    val ink = MaterialTheme.colorScheme.onBackground
    val gold = MaterialTheme.colorScheme.tertiary
    Canvas(modifier) {
        val radius = size.minDimension * 0.42f
        val center = Offset(size.width / 2, size.height / 2)
        drawCircle(ink.copy(alpha = 0.12f), radius, center, style = Stroke(1.dp.toPx()))
        rotate(t * 180f / PI.toFloat(), center) {
            for (arm in 0..1) {
                val path = Path()
                for (i in 0..120) {
                    val f = i / 120f
                    val angle = f * 2.6f * PI.toFloat() + arm * PI.toFloat()
                    val r = radius * (0.13f + 0.72f * f) * (1f + 0.035f * sin(t * 2f + angle))
                    val p = center + Offset(cos(angle) * r, sin(angle) * r)
                    if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
                }
                drawPath(path, (if (arm == 0) gold else ink).copy(alpha = if (arm == 0) 0.75f else 0.24f), style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        if (progress != null) drawArc(gold, -90f, progress.coerceIn(0f, 1f) * 360f, false,
            topLeft = center - Offset(radius, radius), size = androidx.compose.ui.geometry.Size(radius * 2, radius * 2), style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        val angle = t - PI.toFloat() / 2
        drawCircle(gold, 2.8.dp.toPx(), center + Offset(cos(angle) * radius, sin(angle) * radius))
        drawCircle(ink, 3.dp.toPx() * (1f + 0.15f * sin(t * 2)), center)
    }
}

/**
 * 今天这一枝：一根从左往右长的枝，做完一张落一颗子。
 * 实心是自己做对，空心是做错或靠帮助；枝条会长到最新的那颗子。
 */
@Composable
fun TodayBranch(results: List<Boolean>, target: Int, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val grow = remember { Animatable(0f) }
    LaunchedEffect(results.size) {
        val to = results.size.toFloat()
        if (still) grow.snapTo(to) else grow.animateTo(to, tween(700, easing = FastOutSlowInEasing))
    }
    val ink = MaterialTheme.colorScheme.onBackground
    val faint = MaterialTheme.colorScheme.outlineVariant
    val miss = MaterialTheme.colorScheme.error
    Canvas(modifier.fillMaxWidth().height(36.dp)) {
        val slots = maxOf(target, results.size, 1)
        val pad = 12.dp.toPx()
        val gap = (size.width - pad * 2) / slots
        val y = size.height / 2
        drawLine(faint, Offset(pad, y), Offset(size.width - pad, y), strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        drawLine(ink, Offset(pad, y), Offset(pad + gap * grow.value, y), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round)
        for (i in 0 until slots) {
            val c = Offset(pad + gap * (i + 0.5f), y)
            when {
                i >= results.size -> drawCircle(faint, 3.dp.toPx(), c)
                i.toFloat() + 0.5f > grow.value -> {}
                results[i] -> {
                    drawCircle(ink, 7.dp.toPx(), c)
                    drawCircle(androidx.compose.ui.graphics.Color.White.copy(alpha = 0.22f), 2.2.dp.toPx(), Offset(c.x - 2.dp.toPx(), c.y - 2.dp.toPx()))
                }
                else -> drawCircle(miss, 6.dp.toPx(), c, style = Stroke(2.dp.toPx()))
            }
        }
    }
}
