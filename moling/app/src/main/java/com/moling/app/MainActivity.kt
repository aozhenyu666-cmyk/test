package com.moling.app

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.Crossfade
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import com.moling.app.ui.PaperWarm
import com.moling.app.ui.PaperCool
import com.moling.app.ui.art.inkEnter
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.ui.platform.LocalDensity
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import com.moling.app.ui.BridgeScreen
import com.moling.app.ui.Mist
import com.moling.app.ui.MolingFace
import com.moling.app.ui.Paper
import com.moling.app.ui.ReadyScreen
import com.moling.app.ui.art.InkBubbles
import com.moling.app.ui.art.InkBench
import com.moling.app.ui.art.InkGalaxy
import com.moling.app.ui.art.InkRing
import com.moling.app.ui.art.InkWaves
import com.moling.app.ui.moodLine
import com.moling.app.ui.moodOf
import com.moling.app.ui.AtlasScreen
import com.moling.app.ui.BenchScreen
import com.moling.app.ui.ChatScreen
import com.moling.app.ui.MolingTheme
import com.moling.app.ui.SettingsScreen
import com.moling.app.ui.TodayScreen
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 墨灵的外壳：底部四个标签（对话 / 今天 / 体系 / 案板），右上角设置。
 * 通知点进来会带上要打开的标签（[EXTRA_TAB]）。
 */
class MainActivity : ComponentActivity() {

    companion object {
        /** 通知点开时带的目标标签：0 对话 / 1 今天 / 2 体系 / 3 案板。 */
        const val EXTRA_TAB = "tab"

        /** App 是不是在前台：后台心跳据此决定“导演卡片”是显示在今天页还是发通知。 */
        @Volatile
        var visible = false
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

    override fun onResume() {
        super.onResume()
        com.moling.app.overlay.Presence.onResume(this)
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
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
    var readyOpen by remember { mutableStateOf(false) }
    var bridgeOpen by remember { mutableStateOf(false) }
    val state by BrainHost.state.collectAsState()
    // 顶栏的小墨滴跟着她的状态变（每 30 秒看一次表）
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); delay(30_000L) } }
    val mood = moodOf(state, now)
    // 返回手势：先关掉打开的页面，再回到“对话”标签，最后才退出
    androidx.activity.compose.BackHandler(enabled = settingsOpen || bridgeOpen || readyOpen || tab != 0) {
        when {
            settingsOpen -> settingsOpen = false
            bridgeOpen -> bridgeOpen = false
            readyOpen -> readyOpen = false
            else -> tab = 0
        }
    }
    val typing = com.moling.app.ui.rememberImeVisible()

    // 设置页等占满全屏，不套 Scaffold：它们自己带返回。
    if (settingsOpen) {
        Box(Modifier.inkEnter(0, 10.dp)) { SettingsScreen(onDone = { settingsOpen = false }) }
        return
    }
    if (bridgeOpen) {
        Box(Modifier.inkEnter(0, 10.dp)) { BridgeScreen(onDone = { bridgeOpen = false }) }
        return
    }
    if (readyOpen) {
        Box(Modifier.inkEnter(0, 10.dp)) {
            ReadyScreen(
                onDone = { readyOpen = false },
                openSettings = { readyOpen = false; settingsOpen = true },
                openBridge = { readyOpen = false; bridgeOpen = true },
            )
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        MolingFace(mood, Modifier.size(38.dp).clickable { tab = 0 })
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text("墨灵", style = MaterialTheme.typography.titleMedium)
                            Crossfade(mood, label = "moodLine") { m ->
                                Text(
                                    moodLine(m),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Paper),
                actions = {
                    IconButton(onClick = { readyOpen = true }) {
                        InkRing(readyRatio(state), Modifier.size(24.dp))
                    }
                    IconButton(onClick = { settingsOpen = true }) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
        bottomBar = {
            // 打字时收起底栏，给对话多留地方
            if (!typing) NavigationBar(containerColor = Paper, tonalElevation = 0.dp) {
                val itemColors = NavigationBarItemDefaults.colors(indicatorColor = Mist)
                // 选中的图标稍微放大；用 spring 回弹，点起来有手感。
                val s0 by animateFloatAsState(if (tab == 0) 1.10f else 0.92f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "tabScale0")
                val s1 by animateFloatAsState(if (tab == 1) 1.10f else 0.92f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "tabScale1")
                val s2 by animateFloatAsState(if (tab == 2) 1.10f else 0.92f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "tabScale2")
                val s3 by animateFloatAsState(if (tab == 3) 1.10f else 0.92f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow), label = "tabScale3")
                NavigationBarItem(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    icon = { Box(Modifier.graphicsLayer { scaleX = s0; scaleY = s0 }) { InkBubbles(Modifier.size(30.dp), animated = tab == 0) } },
                    label = { Text("对话") },
                    colors = itemColors,
                )
                NavigationBarItem(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    icon = { Box(Modifier.graphicsLayer { scaleX = s1; scaleY = s1 }) { InkWaves(Modifier.size(30.dp), lines = 7, animated = tab == 1) } },
                    label = { Text("今天") },
                    colors = itemColors,
                )
                NavigationBarItem(
                    selected = tab == 2,
                    onClick = { tab = 2 },
                    icon = { Box(Modifier.graphicsLayer { scaleX = s2; scaleY = s2 }) { InkGalaxy(Modifier.size(30.dp), animated = tab == 2) } },
                    label = { Text("体系") },
                    colors = itemColors,
                )
                NavigationBarItem(
                    selected = tab == 3,
                    onClick = { tab = 3 },
                    icon = { Box(Modifier.graphicsLayer { scaleX = s3; scaleY = s3 }) { InkBench(Modifier.size(30.dp)) } },
                    label = { Text("案板") },
                    colors = itemColors,
                )
            }
        },
    ) { inner ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(inner)
                .background(Brush.verticalGradient(listOf(PaperWarm, Paper, PaperCool))),
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val dir = if (targetState > initialState) 1 else -1
                    (slideInHorizontally(animationSpec = androidx.compose.animation.core.tween(320)) { it / 9 * dir } +
                        fadeIn(androidx.compose.animation.core.tween(240))) togetherWith
                        (slideOutHorizontally(animationSpec = androidx.compose.animation.core.tween(320)) { -it / 9 * dir } +
                            fadeOut(androidx.compose.animation.core.tween(180)))
                },
                label = "tabContent",
            ) { which ->
                Box(Modifier.fillMaxSize()) {
                    when (which) {
                        0 -> ChatScreen()
                        1 -> TodayScreen(onReady = { readyOpen = true })
                        2 -> AtlasScreen()
                        else -> BenchScreen()
                    }
                }
            }
        }
    }
}

/** 顶栏小圆环：不用打开清单也能看出准备了几成（只看不费事的几项）。 */
private fun readyRatio(s: com.moling.core.BrainState): Float {
    val now = System.currentTimeMillis()
    val ok = listOf(
        s.lastStatus?.let { now - it.at < 10 * 60_000L } == true,
        HeartService.isRunning(),
        s.flow?.day == com.moling.core.Brain.calendarDay(now),
    )
    return ok.count { it } / ok.size.toFloat()
}
