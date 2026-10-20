package com.moling.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * 体系：聊出来的判断织成网（下一批做）。这一批只放空页。
 */
@Composable
fun AtlasScreen() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text("体系：聊出来的判断会织成网", style = MaterialTheme.typography.bodyMedium)
    }
}
