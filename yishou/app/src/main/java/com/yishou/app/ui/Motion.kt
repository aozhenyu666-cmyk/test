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
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.sin

/*
 * 动效只有几种，全都克制：
 * - 点阵：等待时，一片小点按对角线依次亮起，像在排一盘棋；
 * - 枝条：一步做完，枝条从这一节长到下一节；
 * - 潮汐：首页底部几道水纹，各走各的，永不重复；点一下水面荡开涟漪；
 * - 时之轮：一天 24 小时一圈，做过的事落在发生的时刻上（见 Wheel.kt）；
 * - 螺旋：最近每一次练习按黄金角排成一朵向日葵，慢慢转（见 Wheel.kt）；
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

/** 连续的秒数（每帧更新）。关了动画就一直是 0。 */
@Composable
fun rememberSeconds(still: Boolean): State<Float> = produceState(0f, still) {
    if (still) return@produceState
    val start = withFrameNanos { it }
    while (true) withFrameNanos { value = (it - start) / 1e9f }
}

/** 点一下水面：在点的位置荡开一圈涟漪 */
class TideState {
    internal data class Drop(val x: Float, val y: Float, val at: Float)
    internal val drops = mutableStateListOf<Drop>()
    internal var now = 0f

    fun poke(x: Float, y: Float) {
        drops.removeAll { now - it.at > RIPPLE_SECONDS }
        if (drops.size < 4) drops += Drop(x, y, now)
    }

    companion object {
        const val RIPPLE_SECONDS = 3.2f
    }
}

/**
 * 潮汐：五道水纹各走各的速度和波长，振幅自己慢慢涨落，整片水面还随“潮”缓缓升降，
 * 所以永远不会重复同一个样子。最上面一道是金色，底下铺一层很淡的水色；几粒浮沫顺着水面漂。
 * 点一下水面，就从那里荡开一圈涟漪，附近的水纹也跟着晃一晃。
 * 画在 [height] 高的底部区域里，[state] 接收点击（由外层把点击位置传进来）。
 */
@Composable
fun TideBackdrop(modifier: Modifier = Modifier, state: TideState = remember { TideState() }, height: Dp = 240.dp) {
    val still = reducedMotion()
    val time = rememberSeconds(still)
    val ink = MaterialTheme.colorScheme.onBackground
    val gold = MaterialTheme.colorScheme.tertiary
    val sea = MaterialTheme.colorScheme.secondary
    Canvas(modifier) {
        // 只在画的时候读时间：每帧只重画，不重组
        val t = time.value
        state.now = t
        val w = size.width
        val band = height.toPx().coerceAtMost(size.height)
        val top = size.height - band
        // 潮：整片水面 40 秒一涨一落
        val tide = band * 0.05f * sin(t * 2f * PI.toFloat() / 40f)
        val ripples = state.drops.filter { t - it.at in 0f..TideState.RIPPLE_SECONDS }

        fun waveY(k: Int, x: Float): Float {
            val base = top + band * (0.34f + 0.15f * k) + tide * (1f - 0.15f * k)
            val f = (1.1f + 0.37f * k) * 2f * PI.toFloat() / w
            val speed = 0.35f + 0.11f * k * (if (k % 2 == 0) 1f else -1f)
            val amp = band * (0.035f + 0.012f * k) * (0.55f + 0.45f * sin(t * (0.07f + 0.023f * k) + k * 1.9f))
            var y = base + amp * (0.75f * sin(f * x + speed * t + k * 1.3f) + 0.25f * sin(2.3f * f * x - 0.6f * speed * t + k * 0.7f))
            // 涟漪附近的水纹晃一下，越远越弱，越久越弱
            for (d in ripples) {
                val age = t - d.at
                val decay = (1f - age / TideState.RIPPLE_SECONDS).coerceIn(0f, 1f)
                val dx = (x - d.x) / (w * 0.12f + age * w * 0.08f)
                y += band * 0.05f * decay * decay * exp(-dx * dx) * sin(age * 9f - abs(x - d.x) / (w * 0.03f))
            }
            return y
        }

        val steps = 64
        fun path(k: Int, closed: Boolean): Path {
            val p = Path()
            for (i in 0..steps) {
                val x = w * i / steps
                val y = waveY(k, x)
                if (i == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            if (closed) {
                p.lineTo(w, size.height)
                p.lineTo(0f, size.height)
                p.close()
            }
            return p
        }

        // 水色：金线下面一层很淡的渐变
        drawPath(
            path(0, closed = true),
            Brush.verticalGradient(listOf(gold.copy(alpha = 0.07f), sea.copy(alpha = 0.04f), Color.Transparent), startY = top, endY = size.height),
        )
        for (k in 4 downTo 0) {
            val color = if (k == 0) gold.copy(alpha = 0.45f) else ink.copy(alpha = 0.05f + 0.025f * (4 - k))
            drawPath(path(k, closed = false), color, style = Stroke(if (k == 0) 1.6.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round))
        }
        // 浮沫：顺着金线漂，各自忽明忽暗
        if (!still) {
            for (i in 0 until 12) {
                val speed = 0.012f + 0.006f * (i % 5)
                val x = ((i * 0.137f + t * speed) % 1f) * w
                val y = waveY(0, x) - 3.dp.toPx() - (i % 3) * 2.dp.toPx()
                val a = 0.25f * (0.5f + 0.5f * sin(t * (0.8f + 0.13f * i) + i))
                drawCircle(gold.copy(alpha = a), (1.2f + (i % 3) * 0.5f).dp.toPx(), Offset(x, y))
            }
        }
        // 涟漪：压扁的同心圈（水面是斜着看的）
        for (d in ripples) {
            val age = t - d.at
            for (r in 0 until 3) {
                val a = age - r * 0.35f
                if (a <= 0f) continue
                val radius = a * w * 0.16f
                val alpha = (0.35f * (1f - age / TideState.RIPPLE_SECONDS)).coerceIn(0f, 1f) * (1f - r * 0.25f)
                drawOval(
                    gold.copy(alpha = alpha),
                    topLeft = Offset(d.x - radius, d.y - radius * 0.32f),
                    size = Size(radius * 2, radius * 0.64f),
                    style = Stroke(1.2.dp.toPx()),
                )
            }
        }
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
