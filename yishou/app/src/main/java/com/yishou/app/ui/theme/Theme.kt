package com.yishou.app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.yishou.app.YishouApp
import kotlinx.coroutines.delay
import kotlin.math.sin
import kotlin.random.Random

/*
 * 视觉概念：“一手”来自围棋。棋盘上的九个星位，连着夜空里的星。
 * 白局（浅色）：青白纸面、墨色字、黑子；夜局（深色）：靛青夜空、白子、金色星位。
 * 唯一的强调色是星位的金；按钮用石青。陪练说话用宋体，用户说话用黑体，两种声音。
 * 动效只在两处：掷骰和落子；夜局的星位缓慢明灭。系统关了动画就全部静止。
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
    surfaceContainer = Color(0xFFE6E9E2),
    surfaceContainerHigh = Color(0xFFDFE2DA),
    error = Color(0xFFA3362B),
    errorContainer = Color(0xFFF3DAD5),
    onErrorContainer = Color(0xFF45100A),
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
    surfaceContainer = Color(0xFF161F31),
    surfaceContainerHigh = Color(0xFF1D283C),
    error = Color(0xFFF0938A),
    errorContainer = Color(0xFF4A1E1C),
    onErrorContainer = Color(0xFFF8DAD3),
    outline = Color(0xFF4A5568),
    outlineVariant = Color(0xFF283246),
)

/** 主题模式：0 跟随系统，1 白局，2 夜局。 */
object ThemeMode {
    const val SYSTEM = 0
    const val WHITE = 1
    const val NIGHT = 2
}

/** 标题和陪练的话用宋体（系统衬线字体），正文用默认黑体。 */
private fun typography(): Typography {
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
    val app = LocalContext.current.applicationContext as? YishouApp
    val mode = app?.settings?.app?.collectAsState()?.value?.themeMode ?: ThemeMode.SYSTEM
    val night = when (mode) {
        ThemeMode.WHITE -> false
        ThemeMode.NIGHT -> true
        else -> isSystemInDarkTheme()
    }
    MaterialTheme(colorScheme = if (night) Night else White, typography = typography(), content = content)
}

val ColorScheme.isNight: Boolean get() = background.luminance() < 0.2f

/** 系统设置里关掉了动画时，所有非必要的动效都停下。 */
@Composable
fun reducedMotion(): Boolean {
    val context = LocalContext.current
    return remember {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    }
}

/**
 * 页面底下的棋盘：很淡的 19 路格线和九个星位。夜局里星位是金色，再撒一些星，缓慢明灭。
 * 每秒只重绘十几次，不跑满帧，省电。
 */
@Composable
fun BoardBackdrop(modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val night = scheme.isNight
    val still = reducedMotion()
    var t by remember { mutableFloatStateOf(0f) }
    if (night && !still) {
        LaunchedEffect(Unit) {
            while (true) {
                delay(80)
                t += 0.08f
            }
        }
    }
    val stars = remember { List(36) { Triple(Random(it * 31).nextFloat(), Random(it * 17 + 5).nextFloat(), Random(it * 7 + 3).nextFloat()) } }
    val lineColor = scheme.onBackground.copy(alpha = if (night) 0.06f else 0.05f)
    val starColor = scheme.tertiary
    Canvas(modifier.fillMaxSize()) {
        val cell = size.width / 18.5f
        val left = (size.width - cell * 18) / 2
        val rows = (size.height / cell).toInt() + 1
        for (i in 0..18) {
            val x = left + i * cell
            drawLine(lineColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1f)
        }
        for (j in 0..rows) {
            val y = j * cell
            drawLine(lineColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1f)
        }
        // 九个星位：第 4、10、16 路的交点（从顶上数起，竖向重复整个页面高度）
        val hoshi = listOf(3, 9, 15)
        var k = 0
        for (r in hoshi) for (c in hoshi) {
            val phase = k++ * 0.9f
            val a = if (night) 0.45f + 0.35f * sin(t + phase) else 0.18f
            drawCircle(
                if (night) starColor.copy(alpha = a.coerceIn(0.1f, 0.9f)) else scheme.onBackground.copy(alpha = a),
                radius = if (night) cell * 0.09f else cell * 0.08f,
                center = Offset(left + c * cell, r * cell),
            )
        }
        if (night) {
            stars.forEachIndexed { i, (fx, fy, p) ->
                val a = 0.12f + 0.22f * (0.5f + 0.5f * sin(t * (0.6f + p) + i))
                drawCircle(
                    Color.White.copy(alpha = a),
                    radius = 1.2f + p * 1.6f,
                    center = Offset(fx * size.width, fy * size.height),
                )
            }
        }
    }
}

/** 标识：一颗棋子，带一点金色星光。白局是黑子，夜局是白子。 */
@Composable
fun StoneMark(size: Dp = 28.dp) {
    val scheme = MaterialTheme.colorScheme
    Canvas(Modifier.size(size)) {
        val r = this.size.minDimension / 2
        drawCircle(scheme.primary, r)
        drawCircle(scheme.onPrimary.copy(alpha = 0.18f), r * 0.32f, center = Offset(r * 0.68f, r * 0.68f))
        drawCircle(scheme.tertiary, r * 0.16f, center = Offset(r * 1.38f, r * 0.62f))
    }
}
