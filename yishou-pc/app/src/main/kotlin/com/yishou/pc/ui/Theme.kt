package com.yishou.pc.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/*
 * 和手机版同一套视觉：白局是青白纸面配黑子，夜局是靛青夜空配白子和金色星位。跟着系统的深浅色走。
 */

private val White = lightColorScheme(
    primary = Color(0xFF14171C),
    onPrimary = Color(0xFFF5F6F2),
    primaryContainer = Color(0xFFD9DED6),
    onPrimaryContainer = Color(0xFF14171C),
    secondary = Color(0xFF2E5E86),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE2E8EB),
    onSecondaryContainer = Color(0xFF14171C),
    tertiary = Color(0xFF9C7A14),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEFE5C4),
    onTertiaryContainer = Color(0xFF3B2E05),
    background = Color(0xFFECEEE9),
    onBackground = Color(0xFF14171C),
    surface = Color(0xFFF4F5F1),
    onSurface = Color(0xFF14171C),
    surfaceVariant = Color(0xFFE1E4DD),
    onSurfaceVariant = Color(0xFF545B65),
    error = Color(0xFFA3362B),
    outline = Color(0xFF98A0A7),
    outlineVariant = Color(0xFFCBD0CA),
)

private val Night = darkColorScheme(
    primary = Color(0xFFE6E4DC),
    onPrimary = Color(0xFF0E1524),
    primaryContainer = Color(0xFF26324A),
    onPrimaryContainer = Color(0xFFE6E4DC),
    secondary = Color(0xFF8DB3D8),
    onSecondary = Color(0xFF0B1A2A),
    secondaryContainer = Color(0xFF1D2A41),
    onSecondaryContainer = Color(0xFFDDE5EF),
    tertiary = Color(0xFFD9B44A),
    onTertiary = Color(0xFF241A00),
    tertiaryContainer = Color(0xFF3A3016),
    onTertiaryContainer = Color(0xFFF3E3B0),
    background = Color(0xFF0E1524),
    onBackground = Color(0xFFE6E4DC),
    surface = Color(0xFF131B2C),
    onSurface = Color(0xFFE6E4DC),
    surfaceVariant = Color(0xFF1B2538),
    onSurfaceVariant = Color(0xFF9AA3B5),
    error = Color(0xFFF0938A),
    outline = Color(0xFF4A5568),
    outlineVariant = Color(0xFF283246),
)

@Composable
fun YishouTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Night else White, content = content)
}

private val isNight @Composable get() = MaterialTheme.colorScheme.background.luminance() < 0.2f

/** 很淡的 19 路棋盘和九个星位，铺在页面底下。 */
@Composable
fun BoardBackdrop(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val night = isNight
    val line = scheme.onBackground.copy(alpha = if (night) 0.06f else 0.05f)
    val star = if (night) scheme.tertiary.copy(alpha = 0.7f) else scheme.onBackground.copy(alpha = 0.18f)
    Canvas(modifier.fillMaxSize()) {
        val cell = (size.width / 30f).coerceAtLeast(36f)
        val cols = (size.width / cell).toInt() + 1
        val rows = (size.height / cell).toInt() + 1
        for (i in 0..cols) drawLine(line, Offset(i * cell, 0f), Offset(i * cell, size.height), 1f)
        for (j in 0..rows) drawLine(line, Offset(0f, j * cell), Offset(size.width, j * cell), 1f)
        val cx = cols / 2
        val cy = rows / 2
        for (dx in listOf(-6, 0, 6)) for (dy in listOf(-6, 0, 6)) {
            drawCircle(star, cell * 0.09f, Offset((cx + dx) * cell, (cy + dy) * cell))
        }
    }
}

/** 标识：一颗棋子，带一点金色星光。 */
@Composable
fun StoneMark(size: Dp = 28.dp) {
    val scheme = MaterialTheme.colorScheme
    Canvas(Modifier.size(size)) { drawStone(scheme.primary, scheme.onPrimary, scheme.tertiary) }
}

private fun DrawScope.drawStone(stone: Color, shine: Color, gold: Color) {
    val r = size.minDimension / 2
    drawCircle(stone, r * 0.86f, Offset(r, r))
    drawCircle(shine.copy(alpha = 0.18f), r * 0.28f, Offset(r * 0.7f, r * 0.7f))
    drawCircle(gold, r * 0.15f, Offset(r * 1.45f, r * 0.55f))
}

/** 托盘和窗口图标：黑子金星 */
object StoneIcon : Painter() {
    override val intrinsicSize = Size(64f, 64f)
    override fun DrawScope.onDraw() {
        drawStone(Color(0xFF14171C), Color.White, Color(0xFFD9B44A))
    }
}
