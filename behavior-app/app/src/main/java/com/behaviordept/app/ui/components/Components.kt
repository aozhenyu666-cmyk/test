package com.behaviordept.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.behaviordept.app.ai.Section
import com.behaviordept.app.ui.theme.LocalReduceMotion
import com.behaviordept.app.ui.theme.Paper
import kotlin.math.ceil

val CardShape = RoundedCornerShape(6.dp)
private val ButtonShape = RoundedCornerShape(6.dp)

/** 页面色卡片：练习本里的一页纸。 */
@Composable
fun PaperCard(
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(20.dp),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    content: @Composable ColumnScope.() -> Unit,
) {
    val p = Paper.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(p.page, CardShape)
            .border(1.dp, p.divider, CardShape)
            .padding(padding),
        verticalArrangement = verticalArrangement,
        content = content,
    )
}

/** 小节标签：次墨水色、加宽字距。 */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.labelMedium.copy(letterSpacing = 0.12.sp),
        color = Paper.colors.ink2,
    )
}

/** 墨水色主按钮。全 App 每屏最多一个。 */
@Composable
fun InkButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    height: Dp = 56.dp,
) {
    val p = Paper.colors
    Button(
        onClick = onClick,
        enabled = enabled && !loading,
        modifier = modifier.fillMaxWidth().height(height),
        shape = ButtonShape,
        colors = ButtonDefaults.buttonColors(
            containerColor = p.ink,
            contentColor = p.page,
            disabledContainerColor = p.grid,
            disabledContentColor = p.ink2,
        ),
        contentPadding = PaddingValues(horizontal = 20.dp),
    ) {
        if (loading) {
            CircularProgressIndicator(Modifier.size(20.dp), color = p.ink2, strokeWidth = 2.5.dp)
            Spacer(Modifier.width(12.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold))
    }
}

/** 次要按钮：描边、次墨水色。 */
@Composable
fun LineButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val p = Paper.colors
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(48.dp),
        shape = ButtonShape,
        border = androidx.compose.foundation.BorderStroke(1.dp, p.divider),
        colors = ButtonDefaults.outlinedButtonColors(contentColor = p.ink, disabledContentColor = p.ink2),
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun QuietButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    TextButton(onClick = onClick, modifier = modifier) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Paper.colors.ink2)
    }
}

/**
 * 横格书写区：背景是 32 行距的横线，文字压在线上。
 * 行距用 sp，跟随系统字号一起缩放，线和字始终对齐。
 */
@Composable
fun RuledTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minLines: Int = 4,
    singleLine: Boolean = false,
    enabled: Boolean = true,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    secret: Boolean = false,
) {
    val p = Paper.colors
    val lineHeight = 32.sp
    val style = MaterialTheme.typography.bodyLarge.copy(
        color = p.ink,
        lineHeight = lineHeight,
        lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
    )
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = singleLine,
        minLines = if (singleLine) 1 else minLines,
        textStyle = style,
        cursorBrush = SolidColor(p.red),
        keyboardOptions = keyboardOptions,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        modifier = modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(p.page, CardShape)
                    .border(1.dp, p.divider, CardShape)
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .drawBehind {
                        val lh = lineHeight.toPx()
                        val count = ceil(size.height / lh).toInt().coerceAtLeast(1)
                        for (i in 0 until count) {
                            val y = i * lh + lh * 0.9f
                            if (y > size.height) break
                            drawLine(p.grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                        }
                    },
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = style.copy(color = p.ink2.copy(alpha = 0.6f)))
                }
                inner()
            }
        },
    )
}

/**
 * 一格田字格，带浅色虚线十字。done 时红笔写一个记号（✓ / △ / ✗）；
 * 从没勾到勾上时，记号沿笔画方向描出来（系统关闭动画时直接显示）。
 */
@Composable
fun TianZiGeCell(
    done: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 40.dp,
    highlight: Boolean = false,
    mark: String = "✓",
) {
    val p = Paper.colors
    val reduce = LocalReduceMotion.current
    val progress = remember { Animatable(if (done) 1f else 0f) }
    LaunchedEffect(done) {
        if (!done) progress.snapTo(0f)
        else if (progress.value < 1f) {
            if (reduce) progress.snapTo(1f) else progress.animateTo(1f, tween(560, easing = FastOutSlowInEasing))
        }
    }
    Box(
        modifier
            .size(size)
            .background(p.page)
            .border(if (highlight) 1.5.dp else 1.dp, if (highlight) p.ink else p.divider)
            .drawBehind {
                val dash = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))
                val w = 1.dp.toPx()
                val cx = this.size.width / 2f
                val cy = this.size.height / 2f
                drawLine(p.grid, Offset(cx, 0f), Offset(cx, this.size.height), w, pathEffect = dash)
                drawLine(p.grid, Offset(0f, cy), Offset(this.size.width, cy), w, pathEffect = dash)
                if (done || progress.value > 0f) drawPenMark(mark, progress.value, p.red)
            },
    )
}

/** 单独的红笔记号（不带格子），用在按钮和列表里。 */
@Composable
fun PenMark(mark: String, size: Dp, color: Color, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) { drawPenMark(mark, 1f, color) }
}

/** 红笔记号的笔画，坐标是格子边长的比例。 */
private fun penStrokes(mark: String, s: Float): List<Path> = when (mark) {
    "△" -> listOf(
        Path().apply {
            moveTo(0.50f * s, 0.22f * s)
            lineTo(0.79f * s, 0.74f * s)
            lineTo(0.21f * s, 0.74f * s)
            lineTo(0.50f * s, 0.22f * s)
        },
    )
    "✗" -> listOf(
        Path().apply {
            moveTo(0.27f * s, 0.26f * s)
            quadraticBezierTo(0.50f * s, 0.47f * s, 0.74f * s, 0.75f * s)
        },
        Path().apply {
            moveTo(0.74f * s, 0.25f * s)
            quadraticBezierTo(0.48f * s, 0.50f * s, 0.25f * s, 0.75f * s)
        },
    )
    else -> listOf(
        Path().apply {
            moveTo(0.20f * s, 0.50f * s)
            quadraticBezierTo(0.31f * s, 0.58f * s, 0.41f * s, 0.73f * s)
            quadraticBezierTo(0.55f * s, 0.45f * s, 0.82f * s, 0.22f * s)
        },
    )
}

/** 按 progress 画出笔画的前一段，多笔时按长度依次画。 */
private fun DrawScope.drawPenMark(mark: String, progress: Float, color: Color) {
    val s = size.minDimension
    val strokes = penStrokes(mark, s)
    val measures = strokes.map { PathMeasure().apply { setPath(it, false) } }
    val total = measures.sumOf { it.length.toDouble() }.toFloat()
    var remaining = total * progress.coerceIn(0f, 1f)
    val style = Stroke(width = s * 0.085f, cap = StrokeCap.Round, join = StrokeJoin.Round)
    measures.forEach { m ->
        if (remaining <= 0f) return
        val seg = Path()
        m.getSegment(0f, minOf(remaining, m.length), seg, true)
        drawPath(seg, color, style = style)
        remaining -= m.length
    }
}

/** 红笔批改块：左侧 2dp 红线，红色文字，【小标题】加粗。 */
@Composable
fun RedPenBlock(sections: List<Section>, modifier: Modifier = Modifier, heading: String? = null) {
    val p = Paper.colors
    Column(
        modifier
            .fillMaxWidth()
            .drawBehind {
                drawLine(p.red, Offset(1.dp.toPx(), 0f), Offset(1.dp.toPx(), size.height), 2.dp.toPx())
            }
            .padding(start = 16.dp, top = 2.dp, bottom = 2.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (heading != null) {
            Text(heading, style = MaterialTheme.typography.labelMedium, color = p.red)
        }
        sections.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(
                    "【${s.title}】",
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                    color = p.red,
                )
                s.lines.forEach { line ->
                    Text(line, style = MaterialTheme.typography.bodyMedium, color = p.red)
                }
            }
        }
    }
}

/** 大号宋体数字 + 小字单位，例如“已练 5 天”。 */
@Composable
fun BigNumber(prefix: String, number: String, suffix: String, modifier: Modifier = Modifier, red: Boolean = false) {
    val p = Paper.colors
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        Text(prefix, style = MaterialTheme.typography.titleMedium, color = p.ink2, modifier = Modifier.padding(bottom = 4.dp))
        Spacer(Modifier.width(6.dp))
        Text(
            number,
            style = MaterialTheme.typography.displayMedium.copy(
                lineHeight = MaterialTheme.typography.displayMedium.fontSize,
                lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.Both),
            ),
            color = if (red) p.red else p.ink,
        )
        Spacer(Modifier.width(6.dp))
        Text(suffix, style = MaterialTheme.typography.titleMedium, color = p.ink2, modifier = Modifier.padding(bottom = 4.dp))
    }
}

/** AI 失败时的提示：说清楚错在哪，给“重试”，能离线时再给“对照资料自查”。 */
@Composable
fun ErrorPanel(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    onSelfCheck: (() -> Unit)? = null,
) {
    val p = Paper.colors
    Column(
        modifier
            .fillMaxWidth()
            .background(p.redWash, CardShape)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = p.red)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            LineButton("重试", onRetry)
            if (onSelfCheck != null) LineButton("改为对照资料自查", onSelfCheck)
        }
    }
}

/** 灰色说明文字。 */
@Composable
fun Hint(text: String, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, modifier = modifier, style = MaterialTheme.typography.bodySmall, color = Paper.colors.ink2, textAlign = align)
}

/** 带标题的一页：顶部宋体大标题 + 一句说明。 */
@Composable
fun PageHeader(title: String, subtitle: String? = null, modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, color = Paper.colors.ink)
        if (subtitle != null) {
            Spacer(Modifier.height(4.dp))
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = Paper.colors.ink2)
        }
    }
}

@Composable
fun Divider(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(1.dp).background(Paper.colors.divider))
}
