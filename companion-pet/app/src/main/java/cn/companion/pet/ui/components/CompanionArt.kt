package cn.companion.pet.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import cn.companion.pet.ui.theme.LocalCompanionColors
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 细线天体：轨道环 + 核心 + 两颗卫星；缓慢自转，作为页面主视觉。 */
@Composable
fun OrbitArt(
    modifier: Modifier = Modifier,
    size: Dp = 168.dp,
    accent: Color? = null,
    core: Color? = null,
) {
    val colors = LocalCompanionColors.current
    val ring = accent ?: colors.accent
    val center = core ?: colors.signal
    val transition = rememberInfiniteTransition(label = "orbit")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(18000, easing = LinearEasing), RepeatMode.Restart),
        label = "orbit-angle",
    )
    Box(modifier.size(size)) {
        Canvas(Modifier.size(size)) {
            val c = Offset(this.size.width / 2f, this.size.height / 2f)
            val r = this.size.minDimension * 0.36f
            drawCircle(ring.copy(alpha = 0.22f), radius = r * 1.22f, center = c, style = Stroke(1f))
            drawCircle(ring.copy(alpha = 0.35f), radius = r * 0.92f, center = c, style = Stroke(1f))
            drawCircle(ring, radius = r * 0.58f, center = c, style = Stroke(1.2f))
            drawCircle(center, radius = r * 0.24f, center = c)
            drawCircle(center.copy(alpha = 0.14f), radius = r * 0.42f, center = c)
            val rad = angle * PI / 180.0
            val p1 = Offset(c.x + (r * 1.22f * cos(rad)).toFloat(), c.y + (r * 1.22f * sin(rad)).toFloat())
            val p2 = Offset(c.x + (r * 0.92f * cos(rad + 2.4)).toFloat(), c.y + (r * 0.92f * sin(rad + 2.4)).toFloat())
            drawCircle(ring, radius = 4.2f, center = p1)
            drawCircle(ring.copy(alpha = 0.6f), radius = 3f, center = p2)
            // 小刻点，模拟参考图里的星点。
            repeat(18) { i ->
                val a = (i * 20.0 + angle * 0.15) * PI / 180.0
                val rr = r * (1.34f + (i % 3) * 0.035f)
                drawCircle(colors.ink2.copy(alpha = 0.28f), radius = 1f,
                    center = Offset(c.x + (rr * cos(a)).toFloat(), c.y + (rr * sin(a)).toFloat()))
            }
        }
    }
}

/** 流线波纹：两层相位相反的细线，轻微呼吸，作为分组头图。 */
@Composable
fun WaveArt(modifier: Modifier = Modifier, height: Dp = 72.dp) {
    val colors = LocalCompanionColors.current
    val transition = rememberInfiniteTransition(label = "wave")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = (2 * PI).toFloat(),
        animationSpec = infiniteRepeatable(tween(9000, easing = LinearEasing), RepeatMode.Restart),
        label = "wave-phase",
    )
    Canvas(modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        for (layer in 0..2) {
            val path = Path()
            val amp = h * (0.10f + layer * 0.035f)
            val mid = h * (0.5f + layer * 0.05f)
            var x = 0f
            while (x <= w) {
                val y = mid + amp * sin((x / w * 2.2 * PI + phase + layer * 0.9).toFloat())
                if (x == 0f) path.moveTo(x, y) else path.lineTo(x, y)
                x += 8f
            }
            drawPath(path, color = colors.accent.copy(alpha = 0.18f + layer * 0.12f), style = Stroke(1.1f))
        }
    }
}
