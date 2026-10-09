package com.zongkong.app.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.zongkong.app.Shared
import com.zongkong.app.zk
import com.zongkong.core.work.Handoff
import java.io.File

class MainActivity : ComponentActivity() {
    /** 从通知、拦截页、分享带进来的目标页面。 */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        handle(intent)
        setContent {
            ZkTheme {
                val nav = rememberNavController()
                AppNav(nav, pendingRoute)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    override fun onStop() {
        super.onStop()
        // 做一步行动时离开了总控：记下时刻，回来时问一句要不要记断点
        if (zk.store.work.value.session != null) zk.actions.work.left()
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it; return }
        if (intent.action != Intent.ACTION_SEND) return
        val text = listOfNotNull(intent.getStringExtra(Intent.EXTRA_SUBJECT), intent.getStringExtra(Intent.EXTRA_TEXT))
            .filter { it.isNotBlank() }.distinct().joinToString("\n")
        val image = if (intent.type?.startsWith("image/") == true) saveImage(intent) else ""
        if (text.isBlank() && image.isBlank()) return
        zk.shared.value = Shared(text, image)
        pendingRoute.value = if (image.isBlank() && Handoff.contains(text)) "import" else "capture"
    }

    /** 分享来的截图复制到本机目录（原图的权限只在这一次有效）。 */
    private fun saveImage(intent: Intent): String {
        val uri: Uri = (if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)) ?: return ""
        return try {
            val dir = File(filesDir, "captures").apply { mkdirs() }
            val f = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            contentResolver.openInputStream(uri)?.use { input -> f.outputStream().use { input.copyTo(it) } }
            f.absolutePath
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}

private data class TabItem(val route: String, val seal: String, val label: String)

private val tabs = listOf(
    TabItem("home", "今", "当前"),
    TabItem("threads", "事", "事项"),
    TabItem("capture", "收", "收集"),
    TabItem("settings", "设", "设置"),
)

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun AppNav(nav: NavHostController, pendingRoute: androidx.compose.runtime.MutableState<String?>) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBar = tabs.any { it.route == current }
    val sig = LocalSignals.current
    val context = LocalContext.current
    val work by context.zk.store.work.collectAsStateWithLifecycle()
    val newChanges = work.changes.count { it.at > context.zk.store.seenChanges }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            if (showBar) {
                NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                    tabs.forEach { t ->
                        val selected = current == t.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo("home") { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                BadgedBox(badge = { if (t.route == "home" && newChanges > 0) Badge { Text("$newChanges") } }) {
                                    Seal(t.seal, if (selected) MaterialTheme.colorScheme.primary else sig.muted, size = 26.dp, filled = selected)
                                }
                            },
                            label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(indicatorColor = MaterialTheme.colorScheme.background),
                        )
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding).imePadding()) {
            NavHost(nav, startDestination = "home") {
                composable("home") { NowScreen(nav) }
                composable("threads") { ThreadsScreen(nav) }
                composable("capture") { CaptureScreen(nav) }
                composable("capture/{thread}") { e -> CaptureScreen(nav, e.arguments?.getString("thread").orEmpty()) }
                composable("settings") { SettingsScreen(nav) }

                composable("thread/{key}") { e -> ThreadScreen(e.arguments?.getString("key").orEmpty(), nav) }
                composable("action/{key}") { e -> ActionScreen(e.arguments?.getString("key").orEmpty(), nav) }
                composable("start/{key}") { e -> StartRoute(e.arguments?.getString("key").orEmpty(), nav) }
                composable("session") { SessionScreen(nav) }
                composable("pause/{key}") { e -> PauseScreen(e.arguments?.getString("key").orEmpty(), nav) }
                composable("complete/{key}") { e -> CompleteScreen(e.arguments?.getString("key").orEmpty(), nav) }
                composable("stuck/{key}") { e -> StuckScreen(e.arguments?.getString("key").orEmpty(), nav) }
                composable("import") { ImportScreen(nav) }
                composable("gpt") { GptScreen(nav) }
                composable("results") { ResultsScreen(nav) }

                composable("rhythm") { RhythmScreen(nav) }
                composable("report") { CaptureScreen(nav) }
                composable("depts") { DeptsScreen() }
                composable("gate/{id}") { e -> GateScreen(e.arguments?.getString("id").orEmpty(), nav) }
                composable("history") { HistoryScreen(nav) }
                composable("settings/block") { BlockListScreen(nav) }
                composable("settings/gates") { GatesScreen(nav) }
                composable("settings/gate/{id}") { e -> GateEditScreen(e.arguments?.getString("id").orEmpty(), nav) }
                composable("settings/rules") { RulesScreen(nav) }
                composable("settings/ai") { AiScreen(nav) }
                composable("settings/notion") { NotionScreen(nav) }
                composable("settings/gptguide") { GptGuideScreen(nav) }
                composable("settings/pending") { PendingScreen(nav) }
                composable("settings/perm") { PermissionsScreen(nav) }
            }
            // 从通知、分享、拦截页带进来的页面。必须放在 NavHost 之后：Scaffold 的内容是延后组合的，
            // 导航图要等 NavHost 组合完才设置好，太早跳转会崩（冷启动时点通知里的“开始”就会触发）。
            val route by pendingRoute
            LaunchedEffect(route) {
                route?.let {
                    runCatching { nav.navigate(it) { launchSingleTop = true } }
                    pendingRoute.value = null
                }
            }
        }
    }
}
