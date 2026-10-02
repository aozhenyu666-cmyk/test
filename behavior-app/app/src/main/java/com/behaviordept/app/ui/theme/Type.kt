package com.behaviordept.app.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.behaviordept.app.R

/** 思源宋体（Noto Serif SC）600 / 900，打包在 res/font（按 GB2312 常用字裁剪，缺字自动回落到系统字体）。 */
val SerifSC = FontFamily(
    Font(R.font.noto_serif_sc_semibold, FontWeight.SemiBold),
    Font(R.font.noto_serif_sc_black, FontWeight.Black),
)

/** 正文用系统无衬线，整体比默认粗一档：正文 Medium，标签和按钮 SemiBold。 */
private val Sans = FontFamily.Default

val AppTypography = Typography(
    displayLarge = TextStyle(fontFamily = SerifSC, fontWeight = FontWeight.Black, fontSize = 64.sp, lineHeight = 72.sp),
    displayMedium = TextStyle(fontFamily = SerifSC, fontWeight = FontWeight.Black, fontSize = 46.sp, lineHeight = 54.sp),
    displaySmall = TextStyle(fontFamily = SerifSC, fontWeight = FontWeight.Black, fontSize = 34.sp, lineHeight = 42.sp),
    headlineLarge = TextStyle(fontFamily = SerifSC, fontWeight = FontWeight.Black, fontSize = 30.sp, lineHeight = 40.sp),
    headlineMedium = TextStyle(fontFamily = SerifSC, fontWeight = FontWeight.Black, fontSize = 26.sp, lineHeight = 36.sp),
    headlineSmall = TextStyle(fontFamily = SerifSC, fontWeight = FontWeight.Black, fontSize = 22.sp, lineHeight = 30.sp),
    titleLarge = TextStyle(fontFamily = SerifSC, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 28.sp),
    titleMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp),
    titleSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 22.sp),
    bodyLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 26.sp, letterSpacing = 0.01.em),
    bodyMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 23.sp, letterSpacing = 0.01.em),
    bodySmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 15.sp, lineHeight = 20.sp),
    labelMedium = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 13.sp, lineHeight = 18.sp),
    labelSmall = TextStyle(fontFamily = Sans, fontWeight = FontWeight.SemiBold, fontSize = 11.sp, lineHeight = 16.sp),
)
