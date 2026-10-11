package com.yishou.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.yishou.app.ui.theme.reducedMotion

/**
 * 翻页钟（参考 Fliqlo）：深色牌面、白色等宽数字、中间一道细缝。
 * 数字变化时旧的往上翻走、新的从下面翻上来；系统关了动画就直接换。
 */
@Composable
fun FlipClock(ms: Long, modifier: Modifier = Modifier, tile: Dp = 64.dp) {
    val total = (ms.coerceAtLeast(0) + 999) / 1000
    val text = "%02d:%02d".format(total / 60, total % 60)
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        text.forEach { ch ->
            if (ch == ':') {
                Text(":", fontSize = (tile.value * 0.6f).sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f))
            } else {
                FlipDigit(ch, tile)
            }
        }
    }
}

@Composable
private fun FlipDigit(ch: Char, tile: Dp) {
    val still = reducedMotion()
    val face = MaterialTheme.colorScheme.primary
    val ink = MaterialTheme.colorScheme.onPrimary
    Box(
        Modifier
            .width(tile * 0.72f)
            .height(tile)
            .clip(RoundedCornerShape(tile * 0.14f))
            .background(face),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedContent(
            targetState = ch,
            transitionSpec = {
                if (still) fadeIn(tween(0)) togetherWith fadeOut(tween(0))
                else (slideInVertically(tween(260)) { it / 2 } + fadeIn(tween(260))) togetherWith
                    (slideOutVertically(tween(260)) { -it / 2 } + fadeOut(tween(200)))
            },
            label = "digit",
        ) { c ->
            Text(
                c.toString(),
                color = ink,
                fontSize = (tile.value * 0.62f).sp,
                fontFamily = FontFamily.Monospace,
                fontWeight = FontWeight.Bold,
            )
        }
        // 牌面中间的细缝
        Canvas(Modifier.fillMaxWidth().height(tile)) {
            drawLine(face.copy(alpha = 0.9f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2.5f)
        }
    }
}

/** 小号的进度：剩余时间占总时长的比例，一根细线（参考 Mondaine 的克制）。 */
@Composable
fun ThinProgress(fraction: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
    val bar = MaterialTheme.colorScheme.tertiary
    Canvas(modifier.fillMaxWidth().height(3.dp).padding(horizontal = 0.dp)) {
        drawLine(track, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = size.height)
        drawLine(bar, Offset(0f, size.height / 2), Offset(size.width * fraction.coerceIn(0f, 1f), size.height / 2), strokeWidth = size.height)
    }
}
