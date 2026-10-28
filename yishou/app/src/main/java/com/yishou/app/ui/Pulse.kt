package com.yishou.app.ui

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.delay

/**
 * 读秒催子：停着不动（没打字、没说话）一会儿，就短震两下，把问题切小一级给你。
 * 一动（输入变了）就重新计时。发呆要安静和停滞，这里不给它停滞。
 * 返回当前该显示的那句小问题；还没到点返回 null。
 */
@Composable
fun idlePulse(input: String, steps: List<String>, firstMs: Long = 20_000, gapMs: Long = 15_000): String? {
    val context = LocalContext.current
    var shown by remember(steps) { mutableStateOf<String?>(null) }
    LaunchedEffect(input, steps) {
        if (input.isNotBlank()) {
            shown = null
            return@LaunchedEffect
        }
        delay(firstMs)
        for (s in steps) {
            buzz(context)
            shown = s
            delay(gapMs)
        }
    }
    return shown
}

private fun buzz(context: Context) {
    try {
        context.getSystemService(Vibrator::class.java)?.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 60, 90, 60), -1))
    } catch (e: Exception) {
        // 没有震动马达或被系统拦下：不震就是了
    }
}
