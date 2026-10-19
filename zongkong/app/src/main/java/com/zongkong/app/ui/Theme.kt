package com.zongkong.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.zongkong.core.Dept

/**
 * 视觉：调度台。纸色底、墨色字、朱红印章表示“严管”，松绿表示“放行”，琥珀表示“快到点”。
 * 四个部门各有一个颜色，用在印章上。
 */
@Immutable
data class Signals(
    val strict: Color,
    val free: Color,
    val warn: Color,
    val muted: Color,
    val line: Color,
    val info: Color,
    val think: Color,
    val plan: Color,
    val act: Color,
    val hq: Color,
    /** 参谋的颜色（赭石，参考 Claude 的发送键）。 */
    val ai: Color,
    /** 卡片下面的底：比纸色再深一点，用来分区。 */
    val wash: Color,
) {
    fun dept(d: Dept) = when (d) {
        Dept.INFO -> info
        Dept.THINK -> think
        Dept.PLAN -> plan
        Dept.ACT -> act
        Dept.HQ -> hq
    }
}

private val LightSignals = Signals(
    strict = Color(0xFFC23B22), free = Color(0xFF2F6B4F), warn = Color(0xFFB7791F),
    muted = Color(0xFF6B6A66), line = Color(0xFFDDD8CC),
    info = Color(0xFF2B6CB0), think = Color(0xFF6B46C1), plan = Color(0xFF1F3A5F), act = Color(0xFF2F6B4F), hq = Color(0xFFC23B22),
    ai = Color(0xFFC0643F), wash = Color(0xFFEAE5DA),
)

private val DarkSignals = Signals(
    strict = Color(0xFFFF6B57), free = Color(0xFF6CC59A), warn = Color(0xFFE3B04B),
    muted = Color(0xFF9A9892), line = Color(0xFF2A2E35),
    info = Color(0xFF7FB2F0), think = Color(0xFFB79CF2), plan = Color(0xFF8FB3E0), act = Color(0xFF6CC59A), hq = Color(0xFFFF6B57),
    ai = Color(0xFFE08A64), wash = Color(0xFF0E1013),
)

val LocalSignals = staticCompositionLocalOf { LightSignals }

private val LightColors = lightColorScheme(
    primary = Color(0xFF1F3A5F),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5F0),
    onPrimaryContainer = Color(0xFF0E1D30),
    secondary = Color(0xFFC23B22),
    onSecondary = Color.White,
    background = Color(0xFFF4F1EA),
    onBackground = Color(0xFF1B1D22),
    surface = Color(0xFFFDFCF9),
    onSurface = Color(0xFF1B1D22),
    surfaceVariant = Color(0xFFEAE6DC),
    onSurfaceVariant = Color(0xFF4A4944),
    surfaceContainer = Color(0xFFF7F5EF),
    surfaceContainerHigh = Color(0xFFEFECE4),
    outline = Color(0xFFBDB7A9),
    outlineVariant = Color(0xFFDDD8CC),
    error = Color(0xFFC23B22),
    errorContainer = Color(0xFFF8DED8),
    onErrorContainer = Color(0xFF5C1408),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF8FB3E0),
    onPrimary = Color(0xFF0E1D30),
    primaryContainer = Color(0xFF223652),
    onPrimaryContainer = Color(0xFFDCE5F0),
    secondary = Color(0xFFFF6B57),
    onSecondary = Color(0xFF2B0A04),
    background = Color(0xFF121418),
    onBackground = Color(0xFFE8E6E1),
    surface = Color(0xFF1A1D23),
    onSurface = Color(0xFFE8E6E1),
    surfaceVariant = Color(0xFF242830),
    onSurfaceVariant = Color(0xFFB9B6AE),
    surfaceContainer = Color(0xFF1A1D23),
    surfaceContainerHigh = Color(0xFF22262D),
    outline = Color(0xFF4A4F58),
    outlineVariant = Color(0xFF2A2E35),
    error = Color(0xFFFF6B57),
    errorContainer = Color(0xFF4A1A12),
    onErrorContainer = Color(0xFFFFDAD3),
)

private val Base = Typography()
private val Serif = FontFamily.Serif
private val Zk = Typography(
    displayLarge = Base.displayLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    displayMedium = Base.displayMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    displaySmall = Base.displaySmall.copy(fontWeight = FontWeight.Black, letterSpacing = 4.sp),
    headlineLarge = Base.headlineLarge.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineMedium = Base.headlineMedium.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    headlineSmall = Base.headlineSmall.copy(fontFamily = Serif, fontWeight = FontWeight.SemiBold),
    titleLarge = Base.titleLarge.copy(fontWeight = FontWeight.Bold),
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.Bold),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.Bold),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 26.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 22.sp),
    labelSmall = Base.labelSmall.copy(letterSpacing = 1.sp),
)

/** 等宽数字：倒计时、分钟数。 */
val Mono = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)

@Composable
fun ZkTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val reduce = rememberReduceMotion()
    androidx.compose.runtime.CompositionLocalProvider(LocalSignals provides if (dark) DarkSignals else LightSignals, LocalReduceMotion provides reduce) {
        MaterialTheme(colorScheme = if (dark) DarkColors else LightColors, typography = Zk, content = content)
    }
}

/** 夜空上的纸面：不管系统是不是深色，纸上都用浅色配色（深色字），免得浅底浅字。 */
@Composable
fun PaperTheme(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalSignals provides LightSignals) {
        MaterialTheme(colorScheme = LightColors, typography = Zk, content = content)
    }
}
