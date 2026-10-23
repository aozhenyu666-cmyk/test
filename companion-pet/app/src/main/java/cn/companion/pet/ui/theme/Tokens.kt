package cn.companion.pet.ui.theme

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.Typography
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 语义颜色。页面不直接写十六进制；需要强调时只用 [signal]。 */
data class CompanionColors(
    val paper: Color,
    val panel: Color,
    val ink: Color,
    val ink2: Color,
    val rule: Color,
    val chip: Color,
    val signal: Color,
    val onSignal: Color,
    val danger: Color,
)

val LightCompanionColors = CompanionColors(
    paper = Color(0xFFF4F4F2),
    panel = Color(0xFFFFFFFF),
    ink = Color(0xFF161616),
    ink2 = Color(0xFF5C5C5C),
    rule = Color(0xFFD9D9D6),
    chip = Color(0xFFE4E4E0),
    signal = Color(0xFFFFFA00),
    onSignal = Color(0xFF161616),
    danger = Color(0xFFB3261E),
)

val DarkCompanionColors = CompanionColors(
    paper = Color(0xFF191919),
    panel = Color(0xFF222222),
    ink = Color(0xFFEDEDED),
    ink2 = Color(0xFFA3A3A3),
    rule = Color(0xFF353535),
    chip = Color(0xFF2E2E2E),
    signal = Color(0xFFFFFA00),
    onSignal = Color(0xFF161616),
    danger = Color(0xFFFF8A7A),
)

val LocalCompanionColors = staticCompositionLocalOf { LightCompanionColors }

object CompanionSpacing {
    val page = 16.dp
    val group = 20.dp
    val row = 12.dp
    val minTouch = 48.dp
}

object CompanionShapes {
    /** tight 切角：作者标准乘 0.6。只在 token 里算一次。 */
    val small = CutCornerShape(topEnd = 4.2.dp)
    val button = CutCornerShape(topEnd = 4.8.dp)
}

object CompanionMotion {
    const val press = 100
    const val state = 240
    const val dialogIn = 280
    const val dialogOut = 180
}

val CompanionTypography = Typography(
    displayLarge = TextStyle(fontSize = 30.sp, lineHeight = 38.sp, fontWeight = FontWeight.SemiBold),
    headlineMedium = TextStyle(fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
    titleLarge = TextStyle(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    titleMedium = TextStyle(fontSize = 19.sp, lineHeight = 27.sp, fontWeight = FontWeight.Medium),
    bodyLarge = TextStyle(fontSize = 17.sp, lineHeight = 26.sp),
    bodyMedium = TextStyle(fontSize = 15.sp, lineHeight = 23.sp),
    bodySmall = TextStyle(fontSize = 13.sp, lineHeight = 20.sp),
    labelLarge = TextStyle(fontSize = 14.sp, lineHeight = 20.sp, fontWeight = FontWeight.Medium),
    labelMedium = TextStyle(fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 1.2.sp),
    labelSmall = TextStyle(fontSize = 11.sp, lineHeight = 15.sp, letterSpacing = 1.4.sp),
)
