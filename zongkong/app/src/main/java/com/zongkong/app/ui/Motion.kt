package com.zongkong.app.ui

import android.provider.Settings
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/*
 * 动效只用在“告诉你发生了什么”的地方：卡片依次浮现、按下微缩、进度环走动、盖章、呼吸圈、打字点。
 * 系统里关了动画（开发者选项的动画缩放 = 0，或无障碍里“移除动画”），这里全部静止。
 */

/** 系统是否关了动画。 */
val LocalReduceMotion = staticCompositionLocalOf { false }

@Composable
fun rememberReduceMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        try {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        } catch (e: Exception) {
            false
        }
    }
}

/** 卡片依次浮现：第 index 个晚 index × 45 毫秒，从下面 18dp 处淡入上移。 */
fun Modifier.enter(index: Int = 0): Modifier = composed {
    if (LocalReduceMotion.current) return@composed this
    val anim = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        delay((index.coerceIn(0, 10) * 45).toLong())
        anim.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    graphicsLayer {
        alpha = anim.value
        translationY = (1f - anim.value) * 18.dp.toPx()
    }
}

/** 按下时微微缩小（配合 clickable 用同一个 interactionSource）。 */
fun Modifier.pressScale(source: MutableInteractionSource): Modifier = composed {
    val pressed by source.collectIsPressedAsState()
    val s by animateFloatAsState(if (pressed && !LocalReduceMotion.current) 0.97f else 1f, spring(stiffness = Spring.StiffnessMediumLow), label = "press")
    scale(s)
}

/** 进度环：从 0 走到 [progress]。 */
@Composable
fun ProgressRing(progress: Float, color: Color, size: Dp = 44.dp, stroke: Dp = 4.dp, track: Color = color.copy(alpha = 0.15f), content: @Composable () -> Unit = {}) {
    val target = progress.coerceIn(0f, 1f)
    val reduce = LocalReduceMotion.current
    val anim = remember { Animatable(if (reduce) target else 0f) }
    LaunchedEffect(target) { anim.animateTo(target, tween(900, easing = FastOutSlowInEasing)) }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val w = stroke.toPx()
            val d = this.size.minDimension - w
            val tl = Offset(w / 2, w / 2)
            drawArc(track, 0f, 360f, false, tl, Size(d, d), style = Stroke(w))
            drawArc(color, -90f, 360f * anim.value, false, tl, Size(d, d), style = Stroke(w, cap = StrokeCap.Round))
        }
        content()
    }
}

/** 一闪一闪的小圆点：严管中、正在做、参谋在想。 */
@Composable
fun PulseDot(color: Color, size: Dp = 8.dp) {
    val alpha = if (LocalReduceMotion.current) {
        1f
    } else {
        val t = rememberInfiniteTransition(label = "pulse")
        t.animateFloat(0.35f, 1f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Reverse), label = "a").value
    }
    Box(Modifier.size(size).alpha(alpha).background(color, CircleShape))
}

/** 参谋在想：三个点依次起伏。 */
@Composable
fun TypingDots(color: Color) {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "typing")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val a = if (reduce) {
                0.7f
            } else {
                t.animateFloat(
                    0.25f, 1f,
                    infiniteRepeatable(tween(520, delayMillis = i * 160, easing = LinearEasing), RepeatMode.Reverse),
                    label = "d$i",
                ).value
            }
            Box(Modifier.size(7.dp).alpha(a).background(color, CircleShape))
        }
    }
}

/**
 * 呼吸圈（参考 Endel）：几圈同心圆缓慢涨落，专注计时用。
 * [breathMillis] 一吸一呼的时长。
 */
@Composable
fun BreathingRings(color: Color, modifier: Modifier = Modifier, rings: Int = 4, breathMillis: Int = 5000) {
    val reduce = LocalReduceMotion.current
    val t = rememberInfiniteTransition(label = "breath")
    val phase = if (reduce) 0.5f else t.animateFloat(0f, 1f, infiniteRepeatable(tween(breathMillis, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "p").value
    Canvas(modifier) {
        val c = center
        val maxR = size.minDimension / 2
        for (i in 0 until rings) {
            val k = (i + 1f) / rings
            val r = maxR * (0.55f + 0.45f * k) * (0.92f + 0.08f * phase)
            drawCircle(color.copy(alpha = 0.10f + 0.22f * (1 - k) * (0.6f + 0.4f * phase)), r, c, style = Stroke(1.2.dp.toPx() + (rings - i) * 0.4f))
        }
        drawCircle(color.copy(alpha = 0.06f + 0.06f * phase), maxR * 0.5f, c)
    }
}

/**
 * 盖章：印章从大到小落下，带一点旋转和回弹。验收通过、部署完成、复盘完成用。
 */
@Composable
fun Stamp(text: String, color: Color, size: Dp = 96.dp, play: Boolean = true) {
    val reduce = LocalReduceMotion.current
    val scale = remember { Animatable(if (reduce || !play) 1f else 1.8f) }
    val alpha = remember { Animatable(if (reduce || !play) 1f else 0f) }
    LaunchedEffect(play) {
        if (!play || reduce) return@LaunchedEffect
        alpha.animateTo(1f, tween(120))
        scale.animateTo(1f, spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        Modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale.value
                scaleY = scale.value
                rotationZ = -8f
                this.alpha = alpha.value
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val w = 4.dp.toPx()
            drawRoundRect(color, cornerRadius = androidx.compose.ui.geometry.CornerRadius(10.dp.toPx()), style = Stroke(w))
            val inset = 7.dp.toPx()
            drawRoundRect(
                color.copy(alpha = 0.55f),
                topLeft = Offset(inset, inset),
                size = Size(this.size.width - inset * 2, this.size.height - inset * 2),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
                style = Stroke(1.2.dp.toPx()),
            )
        }
        Text(
            text,
            color = color,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * if (text.length <= 2) 0.3f else 0.22f).sp,
            style = MaterialTheme.typography.titleLarge.merge(SerifTitle),
        )
    }
}
