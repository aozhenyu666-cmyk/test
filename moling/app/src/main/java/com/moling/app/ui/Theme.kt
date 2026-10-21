package com.moling.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 墨灵的配色：纸、墨、印泥。界面尽量安静，把注意力留给内容。 */
val Paper = Color(0xFFF7F4EE)
val Ink = Color(0xFF1B1D22)
val Seal = Color(0xFFC23B22)
val Mist = Color(0xFFE8E2D6)

/** 纸的冷暖两面：大背景用一点渐变，界面不至于像纯色贴图。 */
val PaperWarm = Color(0xFFF9F6F0)
val PaperCool = Color(0xFFF2EDE3)

private val scheme = lightColorScheme(
    primary = Seal,
    onPrimary = Color.White,
    secondary = Ink,
    onSecondary = Paper,
    background = Paper,
    onBackground = Ink,
    surface = Color.White,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Ink,
    outline = Ink.copy(alpha = 0.32f),
    outlineVariant = Ink.copy(alpha = 0.14f),
)

/** 比默认更圆一点：卡片像一张被压过的纸。 */
private val molingShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** 标题略收一点字距、粗一点，像手写标题；正文不动，保证长文好读。 */
private val molingTypography = Typography().let { t ->
    t.copy(
        headlineSmall = t.headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleLarge = t.titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.2).sp),
        titleMedium = t.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = t.titleSmall.copy(fontWeight = FontWeight.SemiBold),
        labelSmall = t.labelSmall.copy(letterSpacing = 0.3.sp),
    )
}

@Composable
fun MolingTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = scheme,
        shapes = molingShapes,
        typography = molingTypography,
        content = content,
    )
}
