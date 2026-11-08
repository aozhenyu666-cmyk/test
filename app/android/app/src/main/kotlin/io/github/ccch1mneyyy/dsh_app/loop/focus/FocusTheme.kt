package io.github.ccch1mneyyy.dsh_app.loop.focus

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color

/**
 * 原生专注界面的调色板：跟 DSH Go 的浅色玻璃 / 深色玻璃保持一致，
 * 而不是另起一套深蓝。跟随系统深浅色，避免在浅色 App 里突然出现一块黑屏。
 */
data class FocusPalette(
    val bgTop: Int,
    val bgBottom: Int,
    val card: Int,
    val stroke: Int,
    val text: Int,
    val sub: Int,
    val faint: Int,
    val accent: Int,
    val accentSoft: Int,
    val input: Int,
    val onAccent: Int,
    val night: Boolean,
)

object FocusTheme {
    fun of(context: Context): FocusPalette {
        val night = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return if (night) {
            FocusPalette(
                bgTop = Color.rgb(11, 18, 32),
                bgBottom = Color.rgb(9, 14, 24),
                card = Color.rgb(20, 28, 43),
                stroke = Color.rgb(36, 49, 73),
                text = Color.rgb(248, 250, 252),
                sub = Color.rgb(203, 213, 225),
                faint = Color.rgb(148, 163, 184),
                accent = Color.rgb(105, 143, 223),
                accentSoft = Color.rgb(30, 41, 59),
                input = Color.rgb(16, 24, 39),
                onAccent = Color.rgb(11, 18, 32),
                night = true,
            )
        } else {
            FocusPalette(
                bgTop = Color.rgb(245, 248, 255),
                bgBottom = Color.rgb(232, 239, 250),
                card = Color.WHITE,
                stroke = Color.rgb(226, 234, 246),
                text = Color.rgb(15, 23, 42),
                sub = Color.rgb(71, 85, 105),
                faint = Color.rgb(148, 163, 184),
                accent = Color.rgb(61, 111, 214),
                accentSoft = Color.rgb(232, 240, 255),
                input = Color.rgb(248, 250, 255),
                onAccent = Color.WHITE,
                night = false,
            )
        }
    }
}
