package com.yishou.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val Light = lightColorScheme(
    primary = Color(0xFF6B4E16),
    primaryContainer = Color(0xFFF6E3BF),
    secondaryContainer = Color(0xFFEDE5D8),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFE5C27F),
    primaryContainer = Color(0xFF4F3A0E),
    secondaryContainer = Color(0xFF3B352B),
)

/** Android 12 以上跟随系统壁纸取色，更早的系统用棋盘木色。 */
@Composable
fun YishouTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val ctx = LocalContext.current
            if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        }
        dark -> Dark
        else -> Light
    }
    MaterialTheme(colorScheme = colors, content = content)
}
