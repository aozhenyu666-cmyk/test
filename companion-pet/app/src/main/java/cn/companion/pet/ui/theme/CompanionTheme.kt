package cn.companion.pet.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider

@Composable
fun CompanionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colors = if (darkTheme) DarkCompanionColors else LightCompanionColors
    // Material primary 用墨色：避免默认焦点/光标变成不可读的黄色。
    val scheme = if (darkTheme) {
        darkColorScheme(
            primary = colors.ink,
            onPrimary = colors.paper,
            secondary = colors.signal,
            onSecondary = colors.onSignal,
            background = colors.paper,
            onBackground = colors.ink,
            surface = colors.panel,
            onSurface = colors.ink,
            surfaceVariant = colors.chip,
            onSurfaceVariant = colors.ink2,
            outline = colors.rule,
            error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.ink,
            onPrimary = colors.paper,
            secondary = colors.signal,
            onSecondary = colors.onSignal,
            background = colors.paper,
            onBackground = colors.ink,
            surface = colors.panel,
            onSurface = colors.ink,
            surfaceVariant = colors.chip,
            onSurfaceVariant = colors.ink2,
            outline = colors.rule,
            error = colors.danger,
        )
    }
    val shapes = Shapes(
        extraSmall = CompanionShapes.small,
        small = CompanionShapes.small,
        medium = CompanionShapes.button,
        large = CompanionShapes.button,
        extraLarge = CompanionShapes.button,
    )
    CompositionLocalProvider(LocalCompanionColors provides colors) {
        MaterialTheme(colorScheme = scheme, typography = CompanionTypography, shapes = shapes, content = content)
    }
}
