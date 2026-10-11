package com.yishou.pc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** 加减按钮：- 数值 单位 + */
@Composable
fun Stepper(value: Int, range: IntRange, unit: String, step: Int = 1, onChange: (Int) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { onChange((value - step).coerceIn(range)) }, enabled = value > range.first) { Text("−") }
        Text(
            "$value $unit",
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(min = 72.dp),
        )
        OutlinedButton(onClick = { onChange((value + step).coerceIn(range)) }, enabled = value < range.last) { Text("+") }
    }
}

/** 一根细线进度：已用 / 额度。用满变红。 */
@Composable
fun ThinBar(fraction: Float, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.1f)
    val bar = if (fraction >= 1f) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.tertiary
    Canvas(modifier.fillMaxWidth().height(4.dp)) {
        val y = size.height / 2
        drawLine(track, Offset(0f, y), Offset(size.width, y), size.height)
        drawLine(bar, Offset(0f, y), Offset(size.width * fraction.coerceIn(0f, 1f), y), size.height)
    }
}

fun mmss(ms: Long): String {
    val s = (ms.coerceAtLeast(0) + 999) / 1000
    return "%02d:%02d".format(s / 60, s % 60)
}
