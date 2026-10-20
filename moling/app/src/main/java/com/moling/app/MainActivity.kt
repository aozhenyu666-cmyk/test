package com.moling.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.moling.app.ui.AtlasScreen
import com.moling.app.ui.ChatScreen
import com.moling.app.ui.MolingTheme
import com.moling.app.ui.SettingsScreen
import com.moling.app.ui.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 墨灵的外壳：底部三个标签（对话 / 今天 / 体系），右上角设置。
 * 这一批把「今天」页和总控接上；「体系」页还是空页（下一批）。
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 大脑的心跳：读总控、对账到点的预言、过期提议、看总控掉没掉。
        // 这一批只做“App 在前台时每 60 秒一次”，不做后台定时、不读前台应用。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                while (true) {
                    runCatching { BrainHost.tickNow() }
                    delay(60_000L)
                }
            }
        }

        setContent {
            MolingTheme {
                MolingShell()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MolingShell() {
    var tab by remember { mutableIntStateOf(0) }
    var settingsOpen by remember { mutableStateOf(false) }

    // 设置页占满全屏，不套 Scaffold：它自己带返回。
    if (settingsOpen) {
        SettingsScreen(onDone = { settingsOpen = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("墨灵") },
                actions = {
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Text("谈") },
                    label = { Text("对话") },
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Text("今") },
                    label = { Text("今天") },
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Text("系") },
                    label = { Text("体系") },
                )
            }
        },
    ) { inner ->
        Box(Modifier.fillMaxSize().padding(inner)) {
            when (tab) {
                0 -> ChatScreen()
                1 -> TodayScreen()
                else -> AtlasScreen()
            }
        }
    }
}
