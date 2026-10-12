package com.zongkong.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * 线条动效（Claude 风格：细墨线、留白、慢）。每个部门、每种状态一个母题：
 *
 *  潮汐 TideLines      信息收集部：水位 = 收集箱积压；收得越多潮越高
 *  叶脉 LeafVein       谋划思考部 / 脉络：主脉分出侧脉、细脉，结构长出来
 *  矩阵 Lattice        统筹规划部：一格一根指针，排得越满越整齐
 *  雷霆 Lightning      行为管理部、严格态度、连续娱乐：一道闪电劈下来
 *  漩涡 Vortex         卡住、混乱、总控在想：风暴往里旋
 *  涟漪 Ripple         听你说话（语音）：一圈圈往外荡
 *  年轮 TreeRings      档案：一天一圈，信用越高线越稳
 *  流线 FlowLines      首页底纹：几条慢慢漂的长线
 *  （已有）星空、树枝生长、呼吸圈
 *
 * 系统关了动画就全部静止在一个好看的定格上。
 */

@Composable
private fun loop(periodMs: Int, label: String): Float {
    if (LocalReduceMotion.current) return 0.3f
    return rememberInfiniteTransition(label = label).animateFloat(0f, 1f, infiniteRepeatable(tween(periodMs, easing = LinearEasing)), label = label).value
}

private fun DrawScope.ink(path: Path, color: Color, width: Float, alpha: Float = 1f) =
    drawPath(path, color.copy(alpha = color.alpha * alpha), style = Stroke(width, cap = StrokeCap.Round, join = StrokeJoin.Round))

// ---------- 潮汐 ----------

/** 几道细浪，水位 [level]（0 空 … 1 满）。浪越高越急。 */
@Composable
fun TideLines(level: Float, color: Color, modifier: Modifier = Modifier, waves: Int = 4) {
    val t = loop(7000, "tide")
    val lv = remember { Animatable(0f) }
    LaunchedEffect(level) { lv.animateTo(level.coerceIn(0f, 1f), tween(1400, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val base = h * (0.92f - 0.75f * lv.value)
        for (i in 0 until waves) {
            val p = Path()
            val amp = h * (0.035f + 0.05f * lv.value) * (1f - i * 0.15f)
            val freq = 1.4f + i * 0.35f
            val phase = (t * 2 * PI * (1 + i * 0.3)).toFloat() + i * 0.9f
            val y0 = base + i * h * 0.07f
            var x = 0f
            p.moveTo(0f, y0 + amp * sin(phase))
            while (x <= w) {
                x += w / 60f
                p.lineTo(x, y0 + amp * sin((x / w) * freq * 2 * PI.toFloat() + phase))
            }
            ink(p, color, 1.6f + (waves - i) * 0.35f, 0.9f - i * 0.18f)
        }
    }
}

// ---------- 叶脉 ----------

/** 一片叶子的脉：主脉 → 侧脉 → 细脉，按 [grown] 依次长出来；长满后轻轻摆。 */
@Composable
fun LeafVein(grown: Float, color: Color, modifier: Modifier = Modifier, seed: Int = 11) {
    val g = remember { Animatable(0f) }
    val reduce = LocalReduceMotion.current
    LaunchedEffect(grown) { if (reduce) g.snapTo(grown) else g.animateTo(grown.coerceIn(0f, 1f), tween(2200, easing = FastOutSlowInEasing)) }
    val sway = loop(9000, "vein")
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val start = Offset(w * 0.08f, h * 0.9f)
        val ctrl = Offset(w * 0.45f, h * (0.35f + 0.02f * sin(sway * 2 * PI.toFloat())))
        val end = Offset(w * 0.94f, h * 0.12f)
        fun at(t: Float) = Offset(
            (1 - t) * (1 - t) * start.x + 2 * (1 - t) * t * ctrl.x + t * t * end.x,
            (1 - t) * (1 - t) * start.y + 2 * (1 - t) * t * ctrl.y + t * t * end.y,
        )
        fun dir(t: Float): Offset {
            val d = Offset(2 * (1 - t) * (ctrl.x - start.x) + 2 * t * (end.x - ctrl.x), 2 * (1 - t) * (ctrl.y - start.y) + 2 * t * (end.y - ctrl.y))
            val len = sqrt(d.x * d.x + d.y * d.y).coerceAtLeast(0.001f)
            return Offset(d.x / len, d.y / len)
        }
        // 叶缘（淡）
        val outline = Path()
        val steps = 40
        for (side in listOf(1f, -1f)) {
            for (i in 0..steps) {
                val t = i / steps.toFloat()
                val p = at(t)
                val d = dir(t)
                val n = Offset(-d.y * side, d.x * side)
                val wdt = sin(t * PI.toFloat()) * h * 0.32f
                val q = Offset(p.x + n.x * wdt, p.y + n.y * wdt)
                if (i == 0 && side > 0) outline.moveTo(q.x, q.y) else outline.lineTo(q.x, q.y)
            }
        }
        ink(outline, color, 1.2f, 0.18f * g.value)
        // 主脉
        val mainEnd = (g.value / 0.35f).coerceIn(0f, 1f)
        val midrib = Path()
        for (i in 0..(steps * mainEnd).toInt()) {
            val p = at(i / steps.toFloat())
            if (i == 0) midrib.moveTo(p.x, p.y) else midrib.lineTo(p.x, p.y)
        }
        ink(midrib, color, 2.6f)
        // 侧脉、细脉
        val rnd = Random(seed)
        val count = 9
        for (k in 0 until count) {
            val t = 0.12f + k * 0.085f
            val appear = ((g.value - 0.3f - k * 0.05f) / 0.25f).coerceIn(0f, 1f)
            if (appear <= 0f || t > mainEnd) continue
            val side = if (k % 2 == 0) 1f else -1f
            val p = at(t)
            val d = dir(t)
            val n = Offset(-d.y * side, d.x * side)
            val len = sin(t * PI.toFloat()) * h * 0.3f * appear
            val v = Offset(d.x * 0.55f + n.x * 0.85f, d.y * 0.55f + n.y * 0.85f)
            val q = Offset(p.x + v.x * len, p.y + v.y * len)
            val side1 = Path().apply { moveTo(p.x, p.y); quadraticBezierTo(p.x + n.x * len * 0.5f, p.y + n.y * len * 0.5f, q.x, q.y) }
            ink(side1, color, 1.6f, 0.85f)
            // 细脉
            if (appear > 0.6f) for (j in 1..2) {
                val f = j / 3f
                val m = Offset(p.x + (q.x - p.x) * f, p.y + (q.y - p.y) * f)
                val l2 = len * 0.28f * (0.7f + rnd.nextFloat() * 0.6f)
                val tip = Offset(m.x + d.x * l2, m.y + d.y * l2)
                ink(Path().apply { moveTo(m.x, m.y); lineTo(tip.x, tip.y) }, color, 1f, 0.5f * (appear - 0.6f) / 0.4f)
            }
        }
    }
}

// ---------- 几何矩阵 ----------

/**
 * 一格一根指针的矩阵（像 ClockClock）。[order] 0 = 各转各的，1 = 全部对齐成一个图案（斜纹）。
 */
@Composable
fun Lattice(order: Float, color: Color, modifier: Modifier = Modifier, cols: Int = 12, rows: Int = 4) {
    val t = loop(12000, "lattice")
    val o = remember { Animatable(0f) }
    LaunchedEffect(order) { o.animateTo(order.coerceIn(0f, 1f), tween(1800, easing = FastOutSlowInEasing)) }
    Canvas(modifier) {
        val cw = size.width / cols
        val ch = size.height / rows
        val r = min(cw, ch) * 0.42f
        for (i in 0 until cols) for (j in 0 until rows) {
            val c = Offset(cw * (i + 0.5f), ch * (j + 0.5f))
            val free = (t * 2 * PI + (i * 0.55 + j * 0.9)).toFloat()
            val target = (PI / 4 + ((i + j) % 2) * PI / 2).toFloat()
            val a = free * (1 - o.value) + target * o.value
            val a2 = a + (PI / 2 * (1 - o.value) + PI * o.value).toFloat()
            for (ang in listOf(a, a2)) {
                drawLine(color.copy(alpha = 0.75f), c, Offset(c.x + r * cos(ang), c.y + r * sin(ang)), strokeWidth = 1.8f, cap = StrokeCap.Round)
            }
            drawCircle(color.copy(alpha = 0.25f), 1.6f, c)
        }
    }
}

// ---------- 雷霆 ----------

private fun bolt(rnd: Random, from: Offset, to: Offset, rough: Float, depth: Int, out: MutableList<Pair<Offset, Offset>>) {
    if (depth == 0) { out += from to to; return }
    val mid = Offset((from.x + to.x) / 2 + (rnd.nextFloat() - 0.5f) * rough, (from.y + to.y) / 2 + (rnd.nextFloat() - 0.5f) * rough * 0.3f)
    bolt(rnd, from, mid, rough / 2, depth - 1, out)
    bolt(rnd, mid, to, rough / 2, depth - 1, out)
}

/**
 * 一道闪电：[strike] 每变一次劈一次（先一笔画下去，再闪两下淡掉）。[ambient] 时每隔几秒自己劈。
 */
@Composable
fun Lightning(strike: Int, color: Color, modifier: Modifier = Modifier, ambient: Boolean = false) {
    val reduce = LocalReduceMotion.current
    var n by remember { mutableIntStateOf(0) }
    val draw = remember { Animatable(1f) }
    val glow = remember { Animatable(if (reduce) 0.6f else 0f) }
    LaunchedEffect(strike, ambient) {
        if (reduce) return@LaunchedEffect
        do {
            n++
            draw.snapTo(0f)
            glow.snapTo(1f)
            draw.animateTo(1f, tween(180))
            glow.animateTo(0.3f, tween(90)); glow.animateTo(1f, tween(70)); glow.animateTo(0f, tween(900))
            if (ambient) delay(2500L + Random.nextLong(3500))
        } while (ambient)
    }
    Canvas(modifier) {
        val rnd = Random(n * 7919 + strike)
        val segs = mutableListOf<Pair<Offset, Offset>>()
        val top = Offset(size.width * (0.3f + rnd.nextFloat() * 0.4f), 0f)
        val bottom = Offset(size.width * (0.2f + rnd.nextFloat() * 0.6f), size.height)
        bolt(rnd, top, bottom, size.width * 0.35f, 6, segs)
        // 分叉
        val branches = mutableListOf<Pair<Offset, Offset>>()
        segs.filterIndexed { i, _ -> i % 16 == 7 }.forEach { (a, _) ->
            val end = Offset(a.x + (rnd.nextFloat() - 0.5f) * size.width * 0.5f, a.y + size.height * (0.15f + rnd.nextFloat() * 0.2f))
            bolt(rnd, a, end, size.width * 0.12f, 4, branches)
        }
        val shown = (segs.size * draw.value).toInt()
        val a = if (reduce) 0.6f else glow.value
        segs.take(shown).forEach { (p, q) ->
            drawLine(color.copy(alpha = a * 0.25f), p, q, strokeWidth = 7f, cap = StrokeCap.Round)
            drawLine(color.copy(alpha = a), p, q, strokeWidth = 2.2f, cap = StrokeCap.Round)
        }
        if (draw.value >= 1f) branches.forEach { (p, q) -> drawLine(color.copy(alpha = a * 0.7f), p, q, strokeWidth = 1.2f, cap = StrokeCap.Round) }
    }
}

// ---------- 漩涡 ----------

/** 风暴漩涡：几条对数螺线往里旋，[intensity] 越大转得越快、线越多。 */
@Composable
fun Vortex(color: Color, modifier: Modifier = Modifier, intensity: Float = 1f) {
    val t = loop((9000 / intensity.coerceIn(0.3f, 3f)).toInt(), "vortex")
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val rMax = min(size.width, size.height) / 2
        val arms = (4 + intensity * 2).toInt().coerceIn(3, 10)
        for (k in 0 until arms) {
            val p = Path()
            val a0 = (2 * PI * k / arms + t * 2 * PI).toFloat()
            var first = true
            var r = rMax * 0.06f
            while (r < rMax) {
                val ang = a0 + 0.9f * ln(r / (rMax * 0.06f))
                val x = c.x + r * cos(ang)
                val y = c.y + r * sin(ang) * 0.82f
                if (first) { p.moveTo(x, y); first = false } else p.lineTo(x, y)
                r *= 1.06f
            }
            ink(p, color, 1.5f, 0.35f + 0.5f * (k % 2))
        }
        drawCircle(color.copy(alpha = 0.6f), rMax * 0.05f, c, style = Stroke(1.5f))
    }
}

// ---------- 涟漪 ----------

/** 涟漪：[active] 时一圈圈往外荡（听你说话），否则一圈静静的。 */
@Composable
fun Ripple(active: Boolean, color: Color, modifier: Modifier = Modifier) {
    val t = loop(2400, "ripple")
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val rMax = min(size.width, size.height) / 2
        if (!active) { drawCircle(color.copy(alpha = 0.5f), rMax * 0.45f, c, style = Stroke(1.5f)); return@Canvas }
        for (i in 0 until 3) {
            val f = (t + i / 3f) % 1f
            drawCircle(color.copy(alpha = (1 - f) * 0.8f), rMax * (0.25f + 0.75f * f), c, style = Stroke(2f * (1 - f) + 0.6f))
        }
        drawCircle(color, rMax * 0.18f, c)
    }
}

// ---------- 年轮 ----------

/** 年轮：[rings] 圈，最外一圈最新。[steady] 0–1：越稳圈越圆（信用高），越乱抖得越厉害。 */
@Composable
fun TreeRings(rings: Int, steady: Float, color: Color, modifier: Modifier = Modifier) {
    val t = loop(20000, "rings")
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val rMax = min(size.width, size.height) / 2 * 0.95f
        val n = rings.coerceIn(1, 30)
        for (i in 1..n) {
            val r = rMax * i / n
            val wob = (1 - steady.coerceIn(0f, 1f)) * rMax * 0.05f + rMax * 0.008f
            val p = Path()
            for (k in 0..90) {
                val a = (2 * PI * k / 90).toFloat()
                val rr = r + wob * sin(a * (3 + i % 4) + i * 1.7f + t * 2 * PI.toFloat() * (if (i % 2 == 0) 1 else -1))
                val x = c.x + rr * cos(a)
                val y = c.y + rr * sin(a)
                if (k == 0) p.moveTo(x, y) else p.lineTo(x, y)
            }
            ink(p, color, if (i == n) 2.2f else 1.1f, if (i == n) 1f else 0.25f + 0.5f * i / n)
        }
    }
}

// ---------- 流线 ----------

/** 几条慢慢漂的长线（底纹）。 */
@Composable
fun FlowLines(color: Color, modifier: Modifier = Modifier, lines: Int = 5) {
    val t = loop(16000, "flow")
    Canvas(modifier) {
        for (i in 0 until lines) {
            val p = Path()
            val y0 = size.height * (i + 0.5f) / lines
            val ph = (t * 2 * PI).toFloat() + i * 1.3f
            var x = 0f
            p.moveTo(0f, y0)
            while (x <= size.width) {
                x += size.width / 50
                val y = y0 + size.height * 0.08f * sin(x / size.width * 3f + ph) + size.height * 0.04f * sin(x / size.width * 7f - ph * 1.5f)
                p.lineTo(x, y)
            }
            ink(p, color, 1.2f, 0.25f + 0.1f * (i % 3))
        }
    }
}

/** 动效画廊用：名字、母题、用在哪。 */
data class Motif(val name: String, val what: String, val where: String)

val MOTIFS = listOf(
    Motif("潮汐", "几道细浪，水位跟着收集箱积压涨落", "信息收集部"),
    Motif("叶脉", "主脉分出侧脉、细脉，结构一点点长出来", "谋划思考部、脉络"),
    Motif("几何矩阵", "一格一根指针，排得越满越整齐", "统筹规划部、精力地图"),
    Motif("雷霆", "一笔劈下、闪两下、淡掉", "行为管理部、严格态度、连续娱乐"),
    Motif("风暴漩涡", "对数螺线往里旋，越乱转得越快", "卡住了、总控在想"),
    Motif("涟漪", "一圈圈往外荡", "语音：听你说话"),
    Motif("年轮", "一天一圈，信用越高圈越圆", "档案"),
    Motif("流线", "几条慢慢漂的长线", "首页底纹"),
    Motif("星空 · 树枝 · 呼吸圈", "（之前就有）", "总控时间"),
)

/** 让雷霆在点击时劈一下的小工具。 */
@Composable
fun rememberStrike(): Pair<Int, () -> Unit> {
    var k by remember { mutableIntStateOf(0) }
    return k to { k++ }
}
