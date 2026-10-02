package com.behaviordept.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.behaviordept.app.training.Share
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.ui.theme.SerifSC
import kotlin.math.roundToInt

/** 选择块：选中是墨水实底，未选是描边。 */
@Composable
fun ChoiceChip(
    text: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    height: Dp = 44.dp,
    red: Boolean = false,
    onClick: () -> Unit,
) {
    val p = Paper.colors
    val fill = if (red) p.red else p.ink
    Box(
        modifier
            .height(height)
            .let { if (selected) it.background(fill, CardShape) else it.border(1.dp, p.divider, CardShape).background(p.page, CardShape) }
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            // 大按钮（登记对局这种要一下点准的）用更大的字。
            style = if (height >= 56.dp) MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, fontFamily = androidx.compose.ui.text.font.FontFamily.Default)
            else MaterialTheme.typography.labelLarge,
            color = if (selected) p.page else p.ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 占比横条图：每类一条，当前重点那一条用红色（PRD 视觉规范：死因占比横条图）。
 */
@Composable
fun ShareBars(shares: List<Share>, highlight: String?, modifier: Modifier = Modifier, unit: String = "次") {
    val p = Paper.colors
    val max = (shares.maxOfOrNull { it.share } ?: 0.0).coerceAtLeast(0.01)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        shares.forEach { s ->
            val hot = s.name == highlight
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    s.name,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (hot) p.red else p.ink,
                    modifier = Modifier.width(92.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Canvas(Modifier.weight(1f).height(14.dp)) {
                    drawRoundRect(p.grid, size = size, cornerRadius = CornerRadius(3.dp.toPx()))
                    val w = (size.width * (s.share / max)).toFloat().coerceAtLeast(if (s.count > 0) 3.dp.toPx() else 0f)
                    drawRoundRect(if (hot) p.red else p.ink2, size = Size(w, size.height), cornerRadius = CornerRadius(3.dp.toPx()))
                }
                Text(
                    "${(s.share * 100).roundToInt()}% · ${s.count}$unit",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (hot) p.red else p.ink2,
                    textAlign = TextAlign.End,
                    modifier = Modifier.width(84.dp),
                )
            }
        }
    }
}

/** 小折线：最近几次的数字，最后一个点用红笔。lowerIsBetter 时上下翻转，进步总是往上走。 */
@Composable
fun Sparkline(values: List<Double>, modifier: Modifier = Modifier, lowerIsBetter: Boolean = false) {
    val p = Paper.colors
    Canvas(modifier.fillMaxWidth().height(56.dp)) {
        if (values.isEmpty()) return@Canvas
        val min = values.min()
        val max = values.max()
        val span = (max - min).takeIf { it > 0 } ?: 1.0
        val pad = 6.dp.toPx()
        fun pt(i: Int, v: Double): Offset {
            val x = if (values.size == 1) size.width / 2 else pad + (size.width - 2 * pad) * i / (values.size - 1)
            val t = ((v - min) / span).toFloat().let { if (lowerIsBetter) 1f - it else it }
            val y = size.height - pad - t * (size.height - 2 * pad)
            return Offset(x, y)
        }
        drawLine(p.grid, Offset(0f, size.height - 1), Offset(size.width, size.height - 1), 1.dp.toPx())
        if (values.size > 1) {
            val path = Path()
            values.forEachIndexed { i, v -> val o = pt(i, v); if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y) }
            drawPath(path, p.ink2, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
        values.forEachIndexed { i, v ->
            val last = i == values.lastIndex
            drawCircle(if (last) p.red else p.ink2, radius = (if (last) 4.5 else 3.0).dp.toPx(), center = pt(i, v))
        }
    }
}

/** 进度条：超过上限的部分用红色。 */
@Composable
fun LimitBar(value: Int, limit: Int, modifier: Modifier = Modifier) {
    val p = Paper.colors
    Canvas(modifier.fillMaxWidth().height(12.dp)) {
        val r = CornerRadius(3.dp.toPx())
        drawRoundRect(p.grid, size = size, cornerRadius = r)
        if (limit <= 0) return@Canvas
        val scale = maxOf(value, limit).toFloat()
        val limitX = size.width * limit / scale
        val w = size.width * value / scale
        drawRoundRect(p.ink2, size = Size(minOf(w, limitX), size.height), cornerRadius = r)
        if (value > limit) {
            drawRoundRect(p.red, topLeft = Offset(limitX, 0f), size = Size(w - limitX, size.height), cornerRadius = r)
        }
        drawLine(p.ink, Offset(limitX, -2.dp.toPx()), Offset(limitX, size.height + 2.dp.toPx()), 1.5.dp.toPx())
    }
}

/** 填数字的小格子：页面底色、描边、宋体数字。 */
@Composable
fun NumberField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    width: Dp = 64.dp,
    decimal: Boolean = false,
) {
    val p = Paper.colors
    BasicTextField(
        value = value,
        onValueChange = { v -> onValueChange(v.filter { it.isDigit() || (decimal && it == '.') }.take(6)) },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge.copy(color = p.ink, textAlign = TextAlign.Center, fontFamily = SerifSC, fontWeight = FontWeight.Black),
        cursorBrush = SolidColor(p.red),
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.width(width),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .background(p.page, CardShape)
                    .border(1.dp, p.divider, CardShape),
                contentAlignment = Alignment.Center,
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(placeholder, style = MaterialTheme.typography.labelMedium, color = p.ink2.copy(alpha = 0.6f))
                }
                inner()
            }
        },
    )
}

/** 一排 7 格田字格：当前重点 7 天里哪几天练过。 */
@Composable
fun SevenDays(checked: List<Boolean>, todayIndex: Int, modifier: Modifier = Modifier, cell: Dp = 34.dp) {
    val p = Paper.colors
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        checked.forEachIndexed { i, done ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                TianZiGeCell(done = done, highlight = i == todayIndex, size = cell)
                Spacer(Modifier.height(2.dp))
                Text("第${i + 1}天", style = MaterialTheme.typography.labelSmall, color = if (i == todayIndex) p.ink else p.ink2)
            }
        }
    }
}

/** 30 天娱乐用时柱状图 + 上限横线，超出部分红色（PRD 视觉规范）。 */
@Composable
fun UsageBars(values: List<Int>, limit: Int, modifier: Modifier = Modifier) {
    val p = Paper.colors
    Canvas(modifier.fillMaxWidth().height(120.dp)) {
        val max = maxOf(values.maxOrNull() ?: 0, limit, 1).toFloat() * 1.1f
        val n = values.size.coerceAtLeast(1)
        val slot = size.width / n
        val barW = slot * 0.62f
        fun y(v: Float) = size.height - size.height * (v / max)
        values.forEachIndexed { i, v ->
            val x = i * slot + (slot - barW) / 2
            val under = minOf(v, limit).toFloat()
            if (under > 0) drawRect(p.ink2, Offset(x, y(under)), Size(barW, size.height - y(under)))
            if (v > limit) drawRect(p.red, Offset(x, y(v.toFloat())), Size(barW, y(limit.toFloat()) - y(v.toFloat())))
        }
        val ly = y(limit.toFloat())
        drawLine(p.ink, Offset(0f, ly), Offset(size.width, ly), 1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 4.dp.toPx())))
        drawLine(p.divider, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
    }
}
