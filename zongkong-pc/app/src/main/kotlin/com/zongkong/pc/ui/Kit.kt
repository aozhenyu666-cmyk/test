package com.zongkong.pc.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zongkong.pc.Cat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** 和手机版同一套颜色：纸、墨、赭石。 */
data class Sig(
    val paper: Color = Color(0xFFF4F1EA),
    val card: Color = Color(0xFFFDFCF9),
    val wash: Color = Color(0xFFEAE5DA),
    val ink: Color = Color(0xFF1B1D22),
    val muted: Color = Color(0xFF6B6A66),
    val line: Color = Color(0xFFDDD8CC),
    val ai: Color = Color(0xFFC0643F),
    val strict: Color = Color(0xFFC23B22),
    val free: Color = Color(0xFF2F6B4F),
    val warn: Color = Color(0xFFB7791F),
    val plan: Color = Color(0xFF1F3A5F),
    val think: Color = Color(0xFF6B46C1),
    val info: Color = Color(0xFF2B6CB0),
    val night: Color = Color(0xFF14171D),
) {
    fun cat(c: Cat): Color = when (c) {
        Cat.GAME -> strict
        Cat.FUN -> ai
        Cat.SOCIAL -> warn
        Cat.WORK -> free
        Cat.TOOL -> info
        Cat.OTHER -> muted
    }
}

val LocalSig = staticCompositionLocalOf { Sig() }

val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)

@Composable
fun ZkTheme(content: @Composable () -> Unit) {
    val s = Sig()
    val base = Typography()
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = s.plan, onPrimary = Color.White, secondary = s.ai, background = s.paper, onBackground = s.ink,
            surface = s.card, onSurface = s.ink, surfaceVariant = s.wash, outline = Color(0xFFBDB7A9), outlineVariant = s.line, error = s.strict,
        ),
        typography = Typography(
            displayMedium = base.displayMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
            headlineMedium = base.headlineMedium.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
            headlineSmall = base.headlineSmall.copy(fontFamily = FontFamily.Serif, fontWeight = FontWeight.SemiBold),
            titleLarge = base.titleLarge.copy(fontWeight = FontWeight.Bold),
            titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Bold),
            titleSmall = base.titleSmall.copy(fontWeight = FontWeight.Bold),
            bodyLarge = base.bodyLarge.copy(lineHeight = 26.sp),
            bodyMedium = base.bodyMedium.copy(lineHeight = 22.sp),
        ),
    ) { CompositionLocalProvider(LocalSig provides s) { content() } }
}

@Composable
fun Panel(modifier: Modifier = Modifier, accent: Color? = null, onClick: (() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit) {
    val sig = LocalSig.current
    Surface(
        modifier = modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = sig.card,
        shape = RoundedCornerShape(18.dp),
        border = accent?.let { BorderStroke(1.dp, it.copy(alpha = 0.6f)) },
    ) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content) }
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier, color: Color = LocalSig.current.muted) =
    Text(text, style = MaterialTheme.typography.bodySmall, color = color, modifier = modifier)

@Composable
fun SectionLabel(text: String) = Text(text, style = MaterialTheme.typography.labelLarge, color = LocalSig.current.muted, modifier = Modifier.padding(top = 6.dp))

@Composable
fun Tag(text: String, color: Color) {
    Box(Modifier.background(color.copy(alpha = 0.12f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = color)
    }
}

/** 一个可点的小块（选时长、选项）。 */
@Composable
fun Chip(text: String, color: Color, selected: Boolean = false, onClick: () -> Unit) {
    Box(
        Modifier.background(if (selected) color else color.copy(alpha = 0.1f), RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) { Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) Color.White else color) }
}

/** 印章（托盘图标、标题旁）：一个方块里一个字。 */
@Composable
fun Seal(ch: String, color: Color, size: Dp = 30.dp) {
    Box(Modifier.size(size).background(color, RoundedCornerShape(size / 5)), contentAlignment = Alignment.Center) {
        Text(ch, color = Color.White, fontSize = (size.value * 0.55f).sp, fontWeight = FontWeight.Black)
    }
}

/** 托盘和窗口图标：赭石色方块里一个“控”。 */
object SealIcon : Painter() {
    override val intrinsicSize = androidx.compose.ui.geometry.Size(64f, 64f)
    override fun androidx.compose.ui.graphics.drawscope.DrawScope.onDraw() {
        drawRoundRect(Color(0xFFC0643F), cornerRadius = androidx.compose.ui.geometry.CornerRadius(size.minDimension / 5))
        val w = size.width
        val s = Stroke(w * 0.09f, cap = StrokeCap.Round)
        // 一个简化的“控”：左边提手，右边一个框
        drawLine(Color.White, Offset(w * 0.28f, w * 0.22f), Offset(w * 0.28f, w * 0.8f), s.width, StrokeCap.Round)
        drawLine(Color.White, Offset(w * 0.16f, w * 0.38f), Offset(w * 0.4f, w * 0.34f), s.width, StrokeCap.Round)
        drawRect(Color.White, Offset(w * 0.5f, w * 0.3f), androidx.compose.ui.geometry.Size(w * 0.32f, w * 0.42f), style = s)
    }
}

/** 进度圈：今天用了多久 / 上限。 */
@Composable
fun Ring(fraction: Float, color: Color, modifier: Modifier = Modifier, stroke: Dp = 8.dp, content: @Composable () -> Unit = {}) {
    val sig = LocalSig.current
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = stroke.toPx()
            drawArc(sig.line, 0f, 360f, false, style = Stroke(w))
            drawArc(color, -90f, 360f * fraction.coerceIn(0f, 1f), false, style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** 一条细进度：已用 / 上限，超了变红。 */
@Composable
fun Meter(used: Int, limit: Int?, color: Color, modifier: Modifier = Modifier) {
    val sig = LocalSig.current
    Canvas(modifier.fillMaxWidth().size(height = 6.dp, width = 10.dp)) {
        drawRoundRect(sig.line, cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
        val f = if (limit == null || limit <= 0) (if (used > 0) 1f else 0f) else (used.toFloat() / limit).coerceIn(0f, 1f)
        val c = if (limit != null && used >= limit) sig.strict else color
        drawRoundRect(c, size = size.copy(width = size.width * f), cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
    }
}

@Composable
private fun loop(ms: Int): Float = rememberInfiniteTransition(label = "l").animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing)), label = "l").value

/** 几条慢慢漂的长线（底纹）。 */
@Composable
fun FlowLines(color: Color, modifier: Modifier = Modifier, lines: Int = 4) {
    val t = loop(16000)
    Canvas(modifier) {
        for (i in 0 until lines) {
            val p = Path()
            val y0 = size.height * (i + 0.5f) / lines
            val ph = (t * 2 * PI).toFloat() + i * 1.3f
            var x = 0f
            p.moveTo(0f, y0)
            while (x <= size.width) {
                x += size.width / 60
                p.lineTo(x, y0 + size.height * 0.08f * sin(x / size.width * 3f + ph) + size.height * 0.04f * sin(x / size.width * 7f - ph * 1.5f))
            }
            drawPath(p, color.copy(alpha = 0.25f + 0.1f * (i % 3)), style = Stroke(1.2f, cap = StrokeCap.Round))
        }
    }
}

/** 锁屏上的呼吸圈：慢慢涨落，一圈圈细线。 */
@Composable
fun Breath(color: Color, modifier: Modifier = Modifier) {
    val t = loop(9000)
    Canvas(modifier) {
        val c = Offset(size.width / 2, size.height / 2)
        val r = min(size.width, size.height) / 2
        val k = 0.75f + 0.25f * sin(t * 2 * PI.toFloat())
        for (i in 1..7) drawCircle(color.copy(alpha = 0.08f + 0.05f * i), r * k * i / 7, c, style = Stroke(1.2f))
        for (j in 0 until 24) {
            val a = (2 * PI * j / 24 + t * 2 * PI / 6).toFloat()
            drawCircle(color.copy(alpha = 0.5f), 1.6f, Offset(c.x + r * k * cos(a), c.y + r * k * sin(a)))
        }
    }
}

/** 今天的时间轴：一天 24 小时一条，用过的段按类上色。 */
@Composable
fun DayStrip(segs: List<com.zongkong.pc.Seg>, dayStart: Long, modifier: Modifier = Modifier) {
    val sig = LocalSig.current
    Canvas(modifier) {
        drawRoundRect(sig.wash, cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()))
        val span = 24 * 3600_000f
        segs.forEach { s ->
            val x0 = ((s.from - dayStart) / span * size.width).coerceIn(0f, size.width)
            val x1 = ((s.to - dayStart) / span * size.width).coerceIn(0f, size.width)
            drawRect(sig.cat(Cat.of(s.cat)), Offset(x0, 0f), androidx.compose.ui.geometry.Size((x1 - x0).coerceAtLeast(1f), size.height))
        }
        for (h in 0..24 step 6) {
            val x = h / 24f * size.width
            drawLine(sig.muted.copy(alpha = 0.4f), Offset(x, size.height - 3.dp.toPx()), Offset(x, size.height), 1f)
        }
    }
}

@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).background(color, CircleShape))

@Composable
fun KeyValue(k: String, v: String, color: Color = LocalSig.current.ink) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(k, style = MaterialTheme.typography.bodyMedium, color = LocalSig.current.muted, modifier = Modifier.weight(1f))
        Text(v, style = MaterialTheme.typography.bodyMedium.merge(Mono), color = color)
    }
}
