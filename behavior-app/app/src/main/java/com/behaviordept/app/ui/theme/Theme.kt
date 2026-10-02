package com.behaviordept.app.ui.theme

import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** “练习本”色板：纸、页面、格线、分隔线、墨水、次墨水、红笔。红色只留给反馈和完成标记。 */
@Immutable
data class PaperColors(
    val paper: Color,
    val page: Color,
    val grid: Color,
    val divider: Color,
    val ink: Color,
    val ink2: Color,
    val red: Color,
    /** 红笔的浅底，用于高亮块的背景。 */
    val redWash: Color,
    val isDark: Boolean,
)

val LightPaper = PaperColors(
    paper = Color(0xFFF5F7F9),
    page = Color(0xFFFFFFFF),
    grid = Color(0xFFE3E9F0),
    divider = Color(0xFFD3DCE6),
    ink = Color(0xFF1E2940),
    ink2 = Color(0xFF4B5874),
    red = Color(0xFFC2302A),
    redWash = Color(0x14C2302A),
    isDark = false,
)

val DarkPaper = PaperColors(
    paper = Color(0xFF131821),
    page = Color(0xFF192030),
    grid = Color(0xFF232C3A),
    divider = Color(0xFF2C3646),
    ink = Color(0xFFDDE4EE),
    ink2 = Color(0xFFA9B5C7),
    red = Color(0xFFEF6A61),
    redWash = Color(0x1FEF6A61),
    isDark = true,
)

val LocalPaper = staticCompositionLocalOf { LightPaper }

/** 系统“移除动画”打开时为 true，动效全部跳过。 */
val LocalReduceMotion = staticCompositionLocalOf { false }

object Paper {
    val colors: PaperColors
        @Composable get() = LocalPaper.current
}

@Composable
fun BehaviorTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    val p = if (dark) DarkPaper else LightPaper
    val scheme = if (dark) {
        darkColorScheme(
            primary = p.ink, onPrimary = p.page,
            primaryContainer = p.grid, onPrimaryContainer = p.ink,
            secondary = p.ink2, onSecondary = p.page,
            secondaryContainer = p.grid, onSecondaryContainer = p.ink,
            tertiary = p.red, onTertiary = p.page,
            background = p.paper, onBackground = p.ink,
            surface = p.page, onSurface = p.ink,
            surfaceVariant = p.grid, onSurfaceVariant = p.ink2,
            surfaceContainer = p.page, surfaceContainerHigh = p.page,
            surfaceContainerHighest = p.grid, surfaceContainerLow = p.paper,
            surfaceContainerLowest = p.paper,
            outline = p.divider, outlineVariant = p.grid,
            error = p.red, onError = p.page,
        )
    } else {
        lightColorScheme(
            primary = p.ink, onPrimary = p.page,
            primaryContainer = p.grid, onPrimaryContainer = p.ink,
            secondary = p.ink2, onSecondary = p.page,
            secondaryContainer = p.grid, onSecondaryContainer = p.ink,
            tertiary = p.red, onTertiary = p.page,
            background = p.paper, onBackground = p.ink,
            surface = p.page, onSurface = p.ink,
            surfaceVariant = p.grid, onSurfaceVariant = p.ink2,
            surfaceContainer = p.page, surfaceContainerHigh = p.page,
            surfaceContainerHighest = p.grid, surfaceContainerLow = p.paper,
            surfaceContainerLowest = p.paper,
            outline = p.divider, outlineVariant = p.grid,
            error = p.red, onError = p.page,
        )
    }
    val context = LocalContext.current
    val reduceMotion = remember(context) {
        runCatching {
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        }.getOrDefault(false)
    }
    CompositionLocalProvider(LocalPaper provides p, LocalReduceMotion provides reduceMotion) {
        MaterialTheme(colorScheme = scheme, typography = AppTypography, content = content)
    }
}
