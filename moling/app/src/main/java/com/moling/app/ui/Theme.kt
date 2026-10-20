package com.moling.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** 墨灵的配色：纸、墨、印泥。界面尽量安静，把注意力留给内容。 */
val Paper = Color(0xFFF7F4EE)
val Ink = Color(0xFF1B1D22)
val Seal = Color(0xFFC23B22)
val Mist = Color(0xFFE8E2D6)

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
)

@Composable
fun MolingTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
