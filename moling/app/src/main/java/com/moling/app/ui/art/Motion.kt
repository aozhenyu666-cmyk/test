package com.moling.app.ui.art

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.abs

/*
 * 墨灵的动效小零件（第八批后的界面打磨）。
 *
 * 原则：像墨在纸上慢慢显出来——入场是“淡 + 轻轻上浮”，按压是“缩一点”，
 * 数字和进度是“慢慢走到位”；不用弹跳、不用闪烁，省电也不吵。
 */

/** 第一次组合时淡入 + 上浮；[order] 用来做同一屏里的错峰。 */
@Composable
fun Modifier.inkEnter(order: Int = 0, distance: Dp = 14.dp, duration: Int = 520): Modifier {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (order > 0) delay((order * 45).toLong())
        progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
    }
    return this.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * distance.toPx()
    }
}

/** 按下去缩一点，松开弹回；配合 clickable 的 interactionSource 用。 */
@Composable
fun Modifier.pressScale(interaction: InteractionSource, down: Float = 0.96f): Modifier {
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) down else 1f,
        animationSpec = spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow),
        label = "pressScale",
    )
    return this.graphicsLayer {
        scaleX = scale
        scaleY = scale
    }
}

/** 0 → 1 的缓慢进度，用来做 Canvas 的“画出来”效果；[order] 同样错峰。 */
@Composable
fun rememberInkProgress(order: Int = 0, duration: Int = 700): State<Float> {
    val progress = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        if (order > 0) delay((order * 45).toLong())
        progress.animateTo(1f, tween(duration, easing = FastOutSlowInEasing))
    }
    return progress.asState()
}

/** 数字慢慢走到目标值（分钟 / 次数 / 分数）。 */
@Composable
fun AnimatedCount(
    value: Int,
    style: TextStyle = MaterialTheme.typography.titleMedium,
    color: androidx.compose.ui.graphics.Color = MaterialTheme.colorScheme.onSurface,
) {
    val shown by animateIntAsState(value, tween(650, easing = FastOutSlowInEasing), label = "count")
    Text("$shown", style = style, color = color)
}

/** “她在想…”：三个墨点依次呼吸，比一行静态小字更像在动脑。 */
@Composable
fun InkDots(label: String = "她在想", modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        // 用一个无限循环的相位让三个点错开呼吸。
        val t = remember { androidx.compose.animation.core.Animatable(0f) }
        LaunchedEffect(Unit) {
            while (true) {
                t.snapTo(0f)
                t.animateTo(1f, tween(1_100, easing = androidx.compose.animation.core.LinearEasing))
            }
        }
        repeat(3) { i ->
            val k = (t.value - i * 0.18f + 1f) % 1f
            val a = 0.25f + 0.75f * (1f - abs(k - 0.5f) * 2f)
            androidx.compose.foundation.layout.Box(
                Modifier
                    .size(5.dp)
                    .graphicsLayer { alpha = a }
                    .background(com.moling.app.ui.Ink, CircleShape),
            )
        }
    }
}
