package com.moling.app

import android.content.Intent
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.moling.app.data.SettingsStore
import com.moling.app.ui.AtlasScreen
import com.moling.app.ui.ChatScreen
import com.moling.app.ui.MolingTheme
import com.moling.app.ui.SettingsScreen
import com.moling.app.ui.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 墨灵的外壳：底部三个标签（对话 / 今天 / 体系），右上角设置。
 * 通知点进来会带上要打开的标签（[EXTRA_TAB]）。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 通知点开时带的目标标签：0 对话 / 1 今天 / 2 体系。 */
        const val EXTRA_TAB = "tab"
    }

    /** 外面（通知 / onNewIntent）要求切到哪个标签；界面观察它。 */
    private val requestedTab = mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedTab.intValue = intent?.getIntExtra(EXTRA_TAB, 0) ?: 0

        // 后台心跳：设置里开着就起常驻服务；起不来（没通知权限等）不影响前台用。
        runCatching {
            if (SettingsStore(this).backgroundHeart() && !HeartService.isRunning()) HeartService.start(this)
        }

        // 前台心跳：打开 App 立刻跳一次；之后若后台服务在跑就不重复跳。
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                BrainHost.tickNow()
                while (true) {
                    delay(60_000L)
                    if (!HeartService.isRunning()) BrainHost.tickNow()
                }
            }
        }

        setContent {
            MolingTheme {
                MolingShell(requestedTab)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedTab.intValue = intent.getIntExtra(EXTRA_TAB, requestedTab.intValue)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MolingShell(requestedTab: MutableIntState) {
    var tab by remember { mutableIntStateOf(requestedTab.intValue) }
    // 从通知点进来时切到指定标签（包括 Activity 已经在前台的情况）。
    LaunchedEffect(requestedTab.intValue) { tab = requestedTab.intValue }
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
