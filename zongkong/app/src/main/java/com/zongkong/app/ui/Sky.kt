package com.zongkong.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** 夜空的颜色：总控时间里不管系统是深色浅色，都是夜里。 */
object Night {
    val top = Color(0xFF070A1A)
    val mid = Color(0xFF121A3A)
    val low = Color(0xFF241E3E)
    val ink = Color(0xFFF2EEE6)
    val dim = Color(0xFFA7A9C2)
    val glow = Color(0xFFE9B98F)
    val card = Color(0x1FFFFFFF)
    val line = Color(0x33FFFFFF)
}

private data class Star(val x: Float, val y: Float, val r: Float, val phase: Float, val speed: Float)

/**
 * 星空（参考 Star Walk / 光遇）：几百颗星缓慢转动、各自明灭，偶尔几条星座连线。
 * 系统关了动画就静止。
 */
@Composable
fun NightSky(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    val stars = remember {
        val r = Random(42)
        List(220) { Star(r.nextFloat(), r.nextFloat(), 0.4f + r.nextFloat() * r.nextFloat() * 1.8f, r.nextFloat() * 6.28f, 0.6f + r.nextFloat() * 1.6f) }
    }
    val links = remember { listOf(3 to 17, 17 to 42, 42 to 58, 58 to 91, 120 to 133, 133 to 150, 150 to 171) }
    val reduce = LocalReduceMotion.current
    val t = if (reduce) 0f else rememberInfiniteTransition(label = "sky").animateFloat(0f, 1f, infiniteRepeatable(tween(120_000, easing = LinearEasing)), label = "t").value
    Box(modifier.background(Brush.verticalGradient(listOf(Night.top, Night.mid, Night.low)))) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width * 0.5f, size.height * 1.15f)
            val rot = t * 2f * PI.toFloat() * 0.25f
            val radius = size.maxDimension * 1.25f
            fun pos(s: Star): Offset {
                // 绕下方一个点慢慢转（像星空绕北极星）
                val a = s.x * 2f * PI.toFloat() + rot
                val d = 0.18f + s.y * 0.82f
                return Offset(c.x + cos(a) * radius * d, c.y + sin(a) * radius * d)
            }
            links.forEach { (i, j) ->
                drawLine(Night.line.copy(alpha = 0.12f), pos(stars[i]), pos(stars[j]), strokeWidth = 0.8.dp.toPx())
            }
            stars.forEach { s ->
                val p = pos(s)
                if (p.x < -10 || p.x > size.width + 10 || p.y < -10 || p.y > size.height + 10) return@forEach
                val tw = if (reduce) 0.8f else 0.35f + 0.65f * abs(sin(t * 2f * PI.toFloat() * 40f * s.speed + s.phase))
                drawCircle(Color.White.copy(alpha = tw * 0.9f), s.r.dp.toPx(), p)
                if (s.r > 1.6f) drawCircle(Night.glow.copy(alpha = tw * 0.12f), s.r.dp.toPx() * 4f, p)
            }
        }
        content()
    }
}

private data class Seg(val x0: Float, val y0: Float, val angle: Float, val len: Float, val depth: Int, val birth: Float, val dur: Float) {
    val x1: Float get() = x0 + cos(angle) * len
    val y1: Float get() = y0 + sin(angle) * len
}

/** 长一棵树：主干往上，分出几根枝，枝上再分小枝。时间都归一到 0..1。 */
private fun grow(seed: Int, levels: Int): List<Seg> {
    val r = Random(seed)
    val out = mutableListOf<Seg>()
    fun branch(x: Float, y: Float, angle: Float, len: Float, depth: Int, birth: Float) {
        val dur = 0.16f + len * 0.35f
        val s = Seg(x, y, angle, len, depth, birth, dur)
        out += s
        if (depth >= levels) return
        val kids = if (depth == 0) 3 else 2 + r.nextInt(2)
        repeat(kids) { k ->
            val t = 0.45f + (k + 1f) / (kids + 1f) * 0.5f + (r.nextFloat() - 0.5f) * 0.08f
            val side = if (k % 2 == 0) -1f else 1f
            val spread = (0.32f + r.nextFloat() * 0.38f) * side
            branch(
                x + cos(angle) * len * t, y + sin(angle) * len * t,
                angle + spread, len * (0.55f + r.nextFloat() * 0.18f), depth + 1,
                birth + dur * t,
            )
        }
    }
    branch(0.5f, 1.0f, -PI.toFloat() / 2f + (r.nextFloat() - 0.5f) * 0.12f, 0.42f, 0, 0f)
    val end = out.maxOf { it.birth + it.dur }
    return out.map { it.copy(birth = it.birth / end, dur = it.dur / end) }
}

/**
 * 树枝生长（Claude 式）：一根线往上长，长到一半分出几根小枝，小枝再分。
 * [grown] 0..1：长到哪儿（总控时间里每修完一个部门长一截）。从上一次的位置平滑长到新的位置。
 */
@Composable
fun BranchGrowth(grown: Float, color: Color, modifier: Modifier = Modifier, seed: Int = 7, levels: Int = 4, durationMillis: Int = 2400) {
    val segs = remember(seed, levels) { grow(seed, levels) }
    val reduce = LocalReduceMotion.current
    val anim = remember { Animatable(if (reduce) grown else 0f) }
    LaunchedEffect(grown) { anim.animateTo(grown.coerceIn(0f, 1f), tween(if (reduce) 0 else durationMillis, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val unit = h
        val ox = (w - unit) / 2f
        val p = anim.value
        segs.forEach { s ->
            val local = ((p - s.birth) / s.dur).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            val start = Offset(ox + s.x0 * unit, s.y0 * unit)
            val end = Offset(ox + (s.x0 + cos(s.angle) * s.len * local) * unit, (s.y0 + sin(s.angle) * s.len * local) * unit)
            val width = (3.2f * fpow(0.62f, s.depth)).coerceAtLeast(0.7f).dp.toPx()
            drawLine(color.copy(alpha = 0.95f - s.depth * 0.12f), start, end, strokeWidth = width, cap = StrokeCap.Round)
            if (local >= 1f && s.depth == levels) drawCircle(color.copy(alpha = 0.85f), 2.2.dp.toPx(), Offset(ox + s.x1 * unit, s.y1 * unit))
        }
    }
}

private fun fpow(b: Float, e: Int): Float {
    var r = 1f
    repeat(e) { r *= b }
    return r
}

/** 循环生长的小树（参谋在想的时候）。 */
@Composable
fun BranchThinking(color: Color, modifier: Modifier = Modifier) {
    val reduce = LocalReduceMotion.current
    val t = if (reduce) 1f else rememberInfiniteTransition(label = "bt").animateFloat(0.05f, 1f, infiniteRepeatable(tween(2600, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "g").value
    val segs = remember { grow(11, 3) }
    Canvas(modifier) {
        val unit = size.height
        val ox = (size.width - unit) / 2f
        segs.forEach { s ->
            val local = ((t - s.birth) / s.dur).coerceIn(0f, 1f)
            if (local <= 0f) return@forEach
            drawLine(
                color.copy(alpha = 0.9f - s.depth * 0.15f),
                Offset(ox + s.x0 * unit, s.y0 * unit),
                Offset(ox + (s.x0 + cos(s.angle) * s.len * local) * unit, (s.y0 + sin(s.angle) * s.len * local) * unit),
                strokeWidth = (2.2f - s.depth * 0.45f).coerceAtLeast(0.6f).dp.toPx(), cap = StrokeCap.Round,
            )
        }
    }
}
