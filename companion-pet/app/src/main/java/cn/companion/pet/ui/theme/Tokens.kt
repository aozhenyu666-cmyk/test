package cn.companion.pet.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 语义颜色。页面不直接写十六进制。
 *
 * 方向：纸感同桌。强调色只用于一处主操作或当前选中，
 * 不承担徽标、标签、按钮三种角色，避免焦点分散。
 */
data class CompanionColors(
    val paper: Color,
    val panel: Color,
    val ink: Color,
    val ink2: Color,
    val rule: Color,
    val chip: Color,
    /** 强调色（珊瑚）：单一主操作、当前选中。 */
    val accent: Color,
    val onAccent: Color,
    /** 柔和高亮（替代原高饱和柠檬）：徽标、次级标记。 */
    val signal: Color,
    val onSignal: Color,
    val success: Color,
    val warning: Color,
    val info: Color,
    val danger: Color,
)

val LightCompanionColors = CompanionColors(
    paper = Color(0xFFF7F3EA),
    panel = Color(0xFFFFFDF8),
    ink = Color(0xFF1C1A16),
    ink2 = Color(0xFF5F594E),
    rule = Color(0xFFE0D8C9),
    chip = Color(0xFFEFE9DC),
    accent = Color(0xFFC9512E),
    onAccent = Color(0xFFFFFDF8),
    signal = Color(0xFFEFE3C8),
    onSignal = Color(0xFF1C1A16),
    success = Color(0xFF2F6B4F),
    warning = Color(0xFF9A6A16),
    info = Color(0xFF3C5A78),
    danger = Color(0xFFA5342A),
)

val DarkCompanionColors = CompanionColors(
    paper = Color(0xFF16140F),
    panel = Color(0xFF201D17),
    ink = Color(0xFFF2EADC),
    ink2 = Color(0xFFB3A899),
    rule = Color(0xFF3A342B),
    chip = Color(0xFF2A261F),
    accent = Color(0xFFE8794F),
    onAccent = Color(0xFF16140F),
    signal = Color(0xFF3A3226),
    onSignal = Color(0xFFF2EADC),
    success = Color(0xFF6FBF95),
    warning = Color(0xFFD9A441),
    info = Color(0xFF8FB0CE),
    danger = Color(0xFFF08A7C),
)

val LocalCompanionColors = staticCompositionLocalOf { LightCompanionColors }

object CompanionSpacing {
    val page = 20.dp
    val group = 22.dp
    val row = 14.dp
    val card = 16.dp
    val minTouch = 48.dp
}

object CompanionShapes {
    val small = RoundedCornerShape(12.dp)
    val button = RoundedCornerShape(16.dp)
    val hero = RoundedCornerShape(24.dp)
    /** 标签/徽标：胶囊，与整体圆角体系并列而非随机的 999。 */
    val pill = RoundedCornerShape(percent = 50)
}

object CompanionMotion {
    const val press = 100
    const val state = 240
    const val dialogIn = 280
    const val dialogOut = 180
}

val CompanionTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 41.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 27.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 21.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 17.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.6.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 0.8.sp),
)
