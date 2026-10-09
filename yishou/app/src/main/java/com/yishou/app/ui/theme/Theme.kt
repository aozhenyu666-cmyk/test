package com.yishou.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 朱砂：印章、强调、提醒 */
val Cinnabar = Color(0xFFB0382A)

/** 水墨配色：宣纸底、墨色字、朱砂点睛。不跟随壁纸取色，保持一致的气质。 */
private val Paper = lightColorScheme(
    primary = Color(0xFF23211E),
    onPrimary = Color(0xFFF7F2E8),
    primaryContainer = Color(0xFFE4DCCB),
    onPrimaryContainer = Color(0xFF23211E),
    secondary = Color(0xFF5E574C),
    onSecondary = Color(0xFFF7F2E8),
    secondaryContainer = Color(0xFFE9E1D0),
    onSecondaryContainer = Color(0xFF2A2621),
    tertiary = Cinnabar,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFF3DED5),
    onTertiaryContainer = Color(0xFF5A1A12),
    background = Color(0xFFF5F0E6),
    onBackground = Color(0xFF1F1D1A),
    surface = Color(0xFFF7F3EA),
    onSurface = Color(0xFF1F1D1A),
    surfaceVariant = Color(0xFFECE4D4),
    onSurfaceVariant = Color(0xFF5E574C),
    surfaceContainer = Color(0xFFEFE8DA),
    surfaceContainerHigh = Color(0xFFEAE2D2),
    error = Color(0xFF9E2A20),
    errorContainer = Color(0xFFF4D9D3),
    onErrorContainer = Color(0xFF4A120C),
    outline = Color(0xFFB0A692),
    outlineVariant = Color(0xFFD5CBB8),
)

private val Ink = darkColorScheme(
    primary = Color(0xFFEDE5D4),
    onPrimary = Color(0xFF1B1A17),
    primaryContainer = Color(0xFF34312B),
    onPrimaryContainer = Color(0xFFEDE5D4),
    secondary = Color(0xFFB9B0A0),
    secondaryContainer = Color(0xFF2B2824),
    onSecondaryContainer = Color(0xFFE8E0D0),
    tertiary = Color(0xFFE07A62),
    onTertiary = Color(0xFF2A0D08),
    tertiaryContainer = Color(0xFF4A2219),
    onTertiaryContainer = Color(0xFFF6D8CF),
    background = Color(0xFF171614),
    onBackground = Color(0xFFEAE3D5),
    surface = Color(0xFF1C1B18),
    onSurface = Color(0xFFEAE3D5),
    surfaceVariant = Color(0xFF2A2824),
    onSurfaceVariant = Color(0xFFB9B0A0),
    surfaceContainer = Color(0xFF221F1C),
    surfaceContainerHigh = Color(0xFF2A2724),
    error = Color(0xFFF0907E),
    errorContainer = Color(0xFF5A1F18),
    onErrorContainer = Color(0xFFF8DAD3),
    outline = Color(0xFF6F675A),
    outlineVariant = Color(0xFF3E3A33),
)

/** 标题用宋体（系统衬线字体），正文保持默认黑体，读起来清楚。 */
private fun serifTypography(): Typography {
    val base = Typography()
    fun TextStyle.serif() = copy(fontFamily = FontFamily.Serif)
    return base.copy(
        displayLarge = base.displayLarge.serif(),
        displayMedium = base.displayMedium.serif(),
        displaySmall = base.displaySmall.serif(),
        headlineLarge = base.headlineLarge.serif(),
        headlineMedium = base.headlineMedium.serif(),
        headlineSmall = base.headlineSmall.serif(),
        titleLarge = base.titleLarge.serif(),
        titleMedium = base.titleMedium.serif(),
        titleSmall = base.titleSmall.serif(),
    )
}

@Composable
fun YishouTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) Ink else Paper,
        typography = serifTypography(),
        content = content,
    )
}

/** 朱印“一手”。 */
@Composable
fun SealMark(size: Dp = 30.dp) {
    Box(
        Modifier
            .size(size)
            .background(Cinnabar, RoundedCornerShape(6.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "一手",
            color = Color(0xFFFBF3E6),
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            fontSize = (size.value * 0.36f).sp,
            lineHeight = (size.value * 0.4f).sp,
        )
    }
}

/** 页面背景上的淡墨晕染：右上一团、左下一团，很淡，不影响阅读。 */
fun Modifier.inkWash(color: Color): Modifier = drawBehind {
    drawCircle(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = 0.10f), Color.Transparent),
            center = Offset(size.width * 0.92f, size.height * 0.06f),
            radius = size.width * 0.75f,
        ),
        radius = size.width * 0.75f,
        center = Offset(size.width * 0.92f, size.height * 0.06f),
    )
    drawCircle(
        brush = Brush.radialGradient(
            listOf(color.copy(alpha = 0.06f), Color.Transparent),
            center = Offset(size.width * 0.05f, size.height * 0.85f),
            radius = size.width * 0.6f,
        ),
        radius = size.width * 0.6f,
        center = Offset(size.width * 0.05f, size.height * 0.85f),
    )
}
