package com.moling.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 今天：早上排好的“水道”放在这里（下一批做）。
 * 这一批只放空页，照着 DEEPSEEK.md 的 M0 要求。
 */
@Composable
fun TodayScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("今天：水道排好后放这里", style = MaterialTheme.typography.bodyMedium)
    }
}
