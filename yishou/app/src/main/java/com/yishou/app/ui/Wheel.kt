package com.yishou.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.yishou.app.settings.AppZone
import com.yishou.app.ui.theme.reducedMotion
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** 时之轮上的一颗子：什么时候、哪一类、做得好不好 */
data class WheelMark(val at: Long, val kind: MarkKind, val good: Boolean)

enum class MarkKind { CARD, DRILL, DEED }

fun minuteOfDay(at: Long): Int {
    val z = java.time.Instant.ofEpochMilli(at).atZone(AppZone.zone)
    return z.hour * 60 + z.minute
}

/**
 * 时之轮：一天 24 小时一圈，零点在正上方，顺时针走。
 * - 外圈刻度每 15 分钟一格；一圈虚线极慢地转着；
 * - 陪练窗口是一段淡蓝的弧；
 * - 指针指着现在，身后拖一段两小时长的金色余晖；
 * - 做过的事落在发生的时刻上：题卡在中圈，练思维在外圈，实操在内圈；先后相连，连成一张星图。
 * 中间留给 [center]。
 */
@OptIn(androidx.compose.ui.text.ExperimentalTextApi::class)
@Composable
fun TimeWheel(
    marks: List<WheelMark>,
    windowStart: Int?,
    windowMinutes: Int,
    modifier: Modifier = Modifier,
    center: @Composable () -> Unit = {},
) {
    val still = reducedMotion()
    val time = rememberSeconds(still)
    var now by remember { mutableIntStateOf(minuteOfDay(System.currentTimeMillis())) }
    LaunchedEffect(Unit) {
        while (true) {
            now = minuteOfDay(System.currentTimeMillis())
            delay(30_000)
        }
    }
    val appear = remember { Animatable(0f) }
    LaunchedEffect(marks.size) {
        val to = marks.size.toFloat()
        if (still) appear.snapTo(to) else appear.animateTo(to, tween(900, easing = FastOutSlowInEasing))
    }
    val ink = MaterialTheme.colorScheme.onBackground
    val faint = MaterialTheme.colorScheme.outlineVariant
    val gold = MaterialTheme.colorScheme.tertiary
    val sea = MaterialTheme.colorScheme.secondary
    val miss = MaterialTheme.colorScheme.error
    val measurer = rememberTextMeasurer()
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val sorted = remember(marks) { marks.sortedBy { it.at } }

    Box(modifier.aspectRatio(1f), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val t = time.value
            val c = this.center
            val radius = size.minDimension / 2f
            fun deg(min: Float) = min / 1440f * 360f - 90f
            fun point(min: Float, r: Float): Offset {
                val a = deg(min) / 180f * PI.toFloat()
                return Offset(c.x + r * cos(a), c.y + r * sin(a))
            }

            // 一圈慢慢转的虚线
            rotate(t * 3f, c) {
                drawCircle(faint, radius * 0.975f, c, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(2.dp.toPx(), 7.dp.toPx()))))
            }
            // 刻度
            val tickOuter = radius * 0.9f
            for (i in 0 until 96) {
                val len = when {
                    i % 24 == 0 -> 10.dp.toPx()
                    i % 4 == 0 -> 6.dp.toPx()
                    else -> 2.5.dp.toPx()
                }
                val passed = i * 15 <= now
                drawLine(
                    if (passed) ink.copy(alpha = if (i % 4 == 0) 0.55f else 0.3f) else faint.copy(alpha = 0.8f),
                    point(i * 15f, tickOuter - len),
                    point(i * 15f, tickOuter),
                    strokeWidth = if (i % 4 == 0) 1.4.dp.toPx() else 1.dp.toPx(),
                    cap = StrokeCap.Round,
                )
            }
            // 0、6、12、18 点
            listOf(0, 6, 12, 18).forEach { h ->
                val layout = measurer.measure("$h", labelStyle)
                val p = point(h * 60f, radius * 0.74f)
                drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
            }
            // 陪练窗口
            if (windowStart != null && windowMinutes > 0) {
                val r = radius * 0.835f
                drawArc(
                    sea.copy(alpha = 0.22f),
                    startAngle = deg(windowStart.toFloat()),
                    sweepAngle = windowMinutes / 1440f * 360f,
                    useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(7.dp.toPx(), cap = StrokeCap.Round),
                )
            }
            // 指针身后两小时的余晖
            val trail = 30f
            rotate(deg(now.toFloat()) - trail, c) {
                drawArc(
                    Brush.sweepGradient(
                        0f to Color.Transparent,
                        trail / 360f to gold.copy(alpha = 0.28f),
                        trail / 360f + 0.001f to Color.Transparent,
                        1f to Color.Transparent,
                        center = c,
                    ),
                    startAngle = 0f,
                    sweepAngle = trail,
                    useCenter = true,
                    topLeft = Offset(c.x - tickOuter, c.y - tickOuter),
                    size = Size(tickOuter * 2, tickOuter * 2),
                )
            }
            // 星图：做过的事先后相连
            val shown = appear.value
            val pts = sorted.mapIndexed { i, m ->
                val orbit = when (m.kind) {
                    MarkKind.DEED -> 0.48f
                    MarkKind.CARD -> 0.57f
                    MarkKind.DRILL -> 0.66f
                }
                point(minuteOfDay(m.at).toFloat(), radius * orbit + (i % 3) * 2.dp.toPx())
            }
            for (i in 1 until pts.size) {
                if (i >= shown) break
                drawLine(ink.copy(alpha = 0.1f), pts[i - 1], pts[i], strokeWidth = 1.dp.toPx())
            }
            sorted.forEachIndexed { i, m ->
                val grow = (shown - i).coerceIn(0f, 1f)
                if (grow <= 0f) return@forEachIndexed
                val color = when (m.kind) {
                    MarkKind.CARD -> ink
                    MarkKind.DRILL -> gold
                    MarkKind.DEED -> sea
                }
                // 流星留痕：刚落下的那颗从轮外飞进来，身后拖一道光
                if (i == sorted.lastIndex && grow < 1f) {
                    val from = point(minuteOfDay(m.at).toFloat() - 40f, radius * 1.02f)
                    val cur = Offset(from.x + (pts[i].x - from.x) * grow, from.y + (pts[i].y - from.y) * grow)
                    drawLine(
                        Brush.linearGradient(listOf(Color.Transparent, color.copy(alpha = 0.8f)), start = from, end = cur),
                        from, cur, strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                    )
                }
                val r = 4.2.dp.toPx() * grow
                if (m.good) drawCircle(color, r, pts[i]) else drawCircle(miss.copy(alpha = 0.85f), r, pts[i], style = Stroke(1.5.dp.toPx()))
            }
            // 指针
            val pulse = if (still) 0.5f else 0.5f + 0.5f * sin(t * 2.2f)
            val tip = point(now.toFloat(), tickOuter - 1.dp.toPx())
            drawLine(gold.copy(alpha = 0.85f), point(now.toFloat(), radius * 0.42f), tip, strokeWidth = 1.6.dp.toPx(), cap = StrokeCap.Round)
            drawCircle(gold.copy(alpha = 0.18f + 0.18f * pulse), (6f + 3f * pulse).dp.toPx(), tip)
            drawCircle(gold, 3.dp.toPx(), tip)
        }
        center()
    }
}

/** 螺旋里的一颗种子：good 为 null 表示还在等现实（押后果、等对照） */
data class Seed(val kind: MarkKind, val good: Boolean?)

/**
 * 螺旋：最近的每一次练习按黄金角（137.5°）排开，最早的在中心，最新的在最外圈，像一朵向日葵。
 * 整朵极慢地转；最新的一颗在呼吸；还没长出来的位置留着很淡的空位。
 */
@Composable
fun SeedSpiral(seeds: List<Seed>, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val time = rememberSeconds(still)
    val appear = remember { Animatable(0f) }
    LaunchedEffect(seeds.size) {
        val to = seeds.size.toFloat()
        if (still) appear.snapTo(to) else appear.animateTo(to, tween(1_200, easing = FastOutSlowInEasing))
    }
    val ink = MaterialTheme.colorScheme.onBackground
    val faint = MaterialTheme.colorScheme.outlineVariant
    val gold = MaterialTheme.colorScheme.tertiary
    val sea = MaterialTheme.colorScheme.secondary
    val miss = MaterialTheme.colorScheme.error
    Canvas(modifier.aspectRatio(1f)) {
        val t = time.value
        val n = seeds.size
        val slots = maxOf(n + 8, 48)
        val maxR = size.minDimension / 2f * 0.94f
        val step = maxR / sqrt(slots.toFloat())
        val spin = t * 2f
        val golden = 137.508f
        fun pos(i: Int): Offset {
            val a = (i * golden + spin) / 180f * PI.toFloat()
            val r = step * sqrt(i + 0.5f)
            return Offset(center.x + r * cos(a), center.y + r * sin(a))
        }
        for (i in n until slots) drawCircle(faint.copy(alpha = 0.55f), 1.4.dp.toPx(), pos(i))
        val shown = appear.value
        seeds.forEachIndexed { i, s ->
            val grow = (shown - i).coerceIn(0f, 1f)
            if (grow <= 0f) return@forEachIndexed
            val p = pos(i)
            val base = (2.4f + 2.2f * (i + 1) / maxOf(n, 1)).dp.toPx() * grow
            val color = when (s.kind) {
                MarkKind.CARD -> ink
                MarkKind.DRILL -> gold
                MarkKind.DEED -> sea
            }
            if (i == n - 1 && !still) drawCircle(color.copy(alpha = 0.18f), base * (1.8f + 0.5f * sin(t * 2.5f)), p)
            seed(s.good, color, base, p, miss)
        }
    }
}

private fun DrawScope.seed(good: Boolean?, color: Color, r: Float, p: Offset, miss: Color) {
    when (good) {
        true -> drawCircle(color, r, p)
        false -> drawCircle(miss.copy(alpha = 0.8f), r, p, style = Stroke(1.3.dp.toPx()))
        null -> {
            drawCircle(color.copy(alpha = 0.35f), r, p)
            drawCircle(color, r, p, style = Stroke(1.dp.toPx()))
        }
    }
}
