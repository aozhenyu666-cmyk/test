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

/** 语义颜色：暖纸、墨色、珊瑚强调；页面不直接写十六进制。 */
data class CompanionColors(
    val paper: Color,
    val panel: Color,
    val ink: Color,
    val ink2: Color,
    val rule: Color,
    val chip: Color,
    val signal: Color,
    val onSignal: Color,
    val accent: Color,
    val onAccent: Color,
    val danger: Color,
)

val LightCompanionColors = CompanionColors(
    paper = Color(0xFFF6F1E7),
    panel = Color(0xFFFFFCF5),
    ink = Color(0xFF1E1C18),
    ink2 = Color(0xFF6E675C),
    rule = Color(0xFFDED5C6),
    chip = Color(0xFFEFE7D8),
    signal = Color(0xFFE8FF6A),
    onSignal = Color(0xFF1E1C18),
    accent = Color(0xFFE4572E),
    onAccent = Color(0xFFFFFFFF),
    danger = Color(0xFFB3261E),
)

val DarkCompanionColors = CompanionColors(
    paper = Color(0xFF14130F),
    panel = Color(0xFF1E1C17),
    ink = Color(0xFFF6EFE2),
    ink2 = Color(0xFFB6AC9D),
    rule = Color(0xFF3A352D),
    chip = Color(0xFF29251F),
    signal = Color(0xFFD7FF3F),
    onSignal = Color(0xFF1E1C18),
    accent = Color(0xFFFF7A52),
    onAccent = Color(0xFF1E1C18),
    danger = Color(0xFFFF8A7A),
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
}

object CompanionMotion {
    const val press = 100
    const val state = 240
    const val dialogIn = 280
    const val dialogOut = 180
}

val CompanionTypography = Typography(
    displayLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 34.sp, lineHeight = 40.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, lineHeight = 34.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontFamily = FontFamily.Serif, fontSize = 22.sp, lineHeight = 28.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 18.sp, lineHeight = 26.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp, lineHeight = 24.sp),
    bodyMedium = TextStyle(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = TextStyle(fontSize = 12.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.1.sp),
    labelSmall = TextStyle(fontSize = 10.sp, lineHeight = 14.sp, letterSpacing = 1.4.sp),
)
