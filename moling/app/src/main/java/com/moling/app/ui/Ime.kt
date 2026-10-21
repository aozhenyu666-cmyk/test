package com.moling.app.ui

import android.view.ViewTreeObserver
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalView
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** 键盘开着没（直接问窗口，不依赖 Compose 的边到边设置）。 */
@Composable
fun rememberImeVisible(): Boolean {
    val view = LocalView.current
    var visible by remember { mutableStateOf(false) }
    DisposableEffect(view) {
        val l = ViewTreeObserver.OnGlobalLayoutListener {
            visible = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true
        }
        view.viewTreeObserver.addOnGlobalLayoutListener(l)
        onDispose { view.viewTreeObserver.removeOnGlobalLayoutListener(l) }
    }
    return visible
}
