package com.zongkong.app.ui

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController

class MainActivity : ComponentActivity() {
    /** 从通知或拦截页带进来的目标页面。 */
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        pendingRoute.value = intent.getStringExtra(EXTRA_ROUTE)
        setContent {
            ZkTheme {
                val nav = rememberNavController()
                val route by pendingRoute
                LaunchedEffect(route) {
                    route?.let {
                        nav.navigate(it) { launchSingleTop = true }
                        pendingRoute.value = null
                    }
                }
                AppNav(nav)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_ROUTE)?.let { pendingRoute.value = it }
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}

private data class TabItem(val route: String, val seal: String, val label: String)

private val tabs = listOf(
    TabItem("home", "控", "总控"),
    TabItem("report", "报", "汇报"),
    TabItem("depts", "部", "部门"),
    TabItem("settings", "设", "设置"),
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AppNav(nav: NavHostController) {
    val entry by nav.currentBackStackEntryAsState()
    val current = entry?.destination?.route
    val showBar = tabs.any { it.route == current }
    val sig = LocalSignals.current
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
                            icon = { Seal(t.seal, if (selected) MaterialTheme.colorScheme.primary else sig.muted, size = 26.dp, filled = selected) },
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
                composable("home") { HomeScreen(nav) }
                composable("report") { ReportScreen() }
                composable("depts") { DeptsScreen() }
                composable("settings") { SettingsScreen(nav) }
                composable("gate/{id}") { e -> GateScreen(e.arguments?.getString("id").orEmpty(), nav) }
                composable("history") { HistoryScreen(nav) }
                composable("settings/block") { BlockListScreen(nav) }
                composable("settings/gates") { GatesScreen(nav) }
                composable("settings/gate/{id}") { e -> GateEditScreen(e.arguments?.getString("id").orEmpty(), nav) }
                composable("settings/rules") { RulesScreen(nav) }
                composable("settings/ai") { AiScreen(nav) }
                composable("settings/notion") { NotionScreen(nav) }
                composable("settings/pending") { PendingScreen(nav) }
                composable("settings/perm") { PermissionsScreen(nav) }
            }
        }
    }
}
