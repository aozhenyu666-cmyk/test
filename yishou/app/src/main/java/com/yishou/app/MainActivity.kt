package com.yishou.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yishou.app.ui.ConsoleScreen
import com.yishou.app.ui.DieFace
import com.yishou.app.ui.theme.StoneMark
import androidx.navigation.navArgument
import com.yishou.app.ui.AboutScreen
import com.yishou.app.ui.BetScreen
import com.yishou.app.ui.LogScreen
import com.yishou.app.ui.HomeScreen
import com.yishou.app.ui.ThinkScreen
import com.yishou.app.ui.CardsScreen
import com.yishou.app.ui.DeedScreen
import com.yishou.app.ui.BankScreen
import com.yishou.app.ui.LineScreen
import com.yishou.app.ui.LiveScreen
import com.yishou.app.ui.JobsScreen
import com.yishou.app.ui.JobScreen
import com.yishou.app.ui.PermissionsScreen
import com.yishou.app.ui.SummaryScreen
import com.yishou.app.ui.WatchedAppsScreen
import com.yishou.app.ui.SettingsScreen
import com.yishou.app.ui.StatusScreen
import com.yishou.app.ui.TaskEditScreen
import com.yishou.app.ui.theme.YishouTheme
import com.yishou.app.window.WindowActivity

/** 主界面。底部两个页签：今天（时之轮和两个入口）和主控台；其余页面从这两处进入。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        routeFrom(intent)
        setContent {
            YishouTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
                // 分享页过来的：打开它指定的那一页
                val app = application as YishouApp
                val pending by app.pendingRoute.collectAsState()
                androidx.compose.runtime.LaunchedEffect(pending, entry) {
                    val to = pending ?: return@LaunchedEffect
                    if (entry == null) return@LaunchedEffect
                    nav.navigate(to) { launchSingleTop = true }
                    app.pendingRoute.value = null
                }
                fun tab(to: String) = nav.navigate(to) {
                    popUpTo("board") { saveState = true }
                    launchSingleTop = true
                    restoreState = true
                }
                Scaffold(
                    bottomBar = {
                        if (route == "board" || route == "console") {
                            NavigationBar(tonalElevation = 0.dp) {
                                NavigationBarItem(
                                    selected = route == "board",
                                    onClick = { tab("board") },
                                    icon = { StoneMark(22.dp) },
                                    label = { Text("今天") },
                                )
                                NavigationBarItem(
                                    selected = route == "console",
                                    onClick = { tab("console") },
                                    icon = { DieFace(5, 22.dp) },
                                    label = { Text("主控台") },
                                )
                            }
                        }
                    },
                ) { outer ->
                NavHost(navController = nav, startDestination = "board", modifier = Modifier.padding(outer)) {
                    composable("board") {
                        HomeScreen(
                            onEndgame = { nav.navigate("endgame") },
                            onSoup = { nav.navigate("soup") },
                            onPartner = { nav.navigate("partner") },
                            onRoute = { nav.navigate(it) },
                            onBank = { nav.navigate("bank") },
                            onLine = { id -> nav.navigate(if (id == null) "line" else "line?id=$id") },
                            onLive = { id -> nav.navigate("live/$id") },
                            onThink = { nav.navigate("think") },
                            onJobs = { nav.navigate("jobs") },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenWindow = { startActivity(Intent(this@MainActivity, WindowActivity::class.java)) },
                        )
                    }
                    composable(
                        "think?m={m}&lens={lens}",
                        arguments = listOf(
                            navArgument("m") { type = NavType.LongType; defaultValue = -1L },
                            navArgument("lens") { type = NavType.StringType; defaultValue = "" },
                        ),
                    ) { e ->
                        ThinkScreen(
                            onBack = { nav.navigateUp() },
                            onCards = { id -> nav.navigate(if (id == null) "cards" else "cards?m=$id") },
                            initialMaterial = e.arguments?.getLong("m")?.takeIf { it > 0 },
                            initialLens = e.arguments?.getString("lens")?.takeIf { it.isNotEmpty() },
                            onLine = { id -> nav.navigate("line?id=$id") },
                        )
                    }
                    composable(
                        "line?id={id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
                    ) { e ->
                        LineScreen(
                            lineId = e.arguments?.getLong("id")?.takeIf { it > 0 },
                            onBack = { nav.navigateUp() },
                            onLive = { id -> nav.navigate("live/$id") },
                            onRecite = { nav.navigate("think") },
                        )
                    }
                    composable(
                        "live/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { e ->
                        LiveScreen(lineId = e.arguments?.getLong("id") ?: 0L, onBack = { nav.navigateUp() })
                    }
                    composable("assistant") { com.yishou.app.ui.AssistantScreen(onBack = { nav.popBackStack() }, onSettings = { nav.navigate("settings") }) }
                    composable("library") { com.yishou.app.ui.LibraryScreen(onBack = { nav.popBackStack() }, onRoute = { nav.navigate(it) }) }
                    composable("strand") { com.yishou.app.ui.StrandScreen(onBack = { nav.popBackStack() }) }
                    composable("bank") {
                        BankScreen(
                            onBack = { nav.navigateUp() },
                            onPractice = { nav.navigate("cards") },
                            onEssay = { id -> nav.navigate("think?m=$id&lens=ESSAY") },
                            onJob = { id -> nav.navigate("job/$id") },
                        )
                    }
                    composable("jobs") {
                        JobsScreen(
                            onBack = { nav.navigateUp() },
                            onOpen = { id -> nav.navigate("job/$id") },
                            onTools = { nav.navigate("deeds") },
                            onDrill = { nav.navigate("bank") },
                        )
                    }
                    composable(
                        "job/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { e ->
                        JobScreen(jobId = e.arguments?.getLong("id") ?: 0L, onBack = { nav.navigateUp() })
                    }
                    composable(
                        "cards?m={m}",
                        arguments = listOf(navArgument("m") { type = NavType.LongType; defaultValue = -1L }),
                    ) { e ->
                        CardsScreen(onBack = { nav.navigateUp() }, materialId = e.arguments?.getLong("m")?.takeIf { it > 0 })
                    }
                    composable("deeds") {
                        DeedScreen(onBack = { nav.navigateUp() })
                    }
                    composable("console") {
                        ConsoleScreen(
                            onOpenBoard = { nav.navigate("cards") },
                            onOpenWindow = { startActivity(Intent(this@MainActivity, WindowActivity::class.java)) },
                            onOpenSummary = { nav.navigate("summary") },
                            onOpenStatus = { nav.navigate("status") },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenPermissions = { nav.navigate("permissions") },
                            onEditTask = { id -> nav.navigate("task/$id") },
                            onOpen = { route -> nav.navigate(route) },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.navigateUp() },
                            onEditTask = { id -> nav.navigate("task/$id") },
                            onNewTask = { nav.navigate("newtask") },
                            onAbout = { nav.navigate("about") },
                            onPermissions = { nav.navigate("permissions") },
                            onWatchedApps = { nav.navigate("watched") },
                            onLog = { nav.navigate("log") },
                        )
                    }
                    composable("newtask") {
                        TaskEditScreen(taskId = null, onBack = { nav.navigateUp() })
                    }
                    composable(
                        "task/{id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType }),
                    ) { entry ->
                        TaskEditScreen(taskId = entry.arguments?.getLong("id"), onBack = { nav.navigateUp() })
                    }
                    composable("about") {
                        AboutScreen(onBack = { nav.navigateUp() })
                    }
                    composable("permissions") {
                        PermissionsScreen(onBack = { nav.navigateUp() })
                    }
                    composable("watched") {
                        WatchedAppsScreen(onBack = { nav.navigateUp() })
                    }
                    composable("summary") {
                        SummaryScreen(onBack = { nav.navigateUp() })
                    }
                    composable("status") {
                        StatusScreen(onBack = { nav.navigateUp() })
                    }
                    composable("log") {
                        LogScreen(onBack = { nav.navigateUp() })
                    }
                    composable("bets") {
                        BetScreen(onBack = { nav.navigateUp() })
                    }
                    composable("endgame") {
                        com.yishou.app.ui.EndgameScreen(onBack = { nav.navigateUp() }, onBank = { nav.navigate("pool") })
                    }
                    composable(
                        "partner?m={m}",
                        arguments = listOf(navArgument("m") { type = NavType.LongType; defaultValue = -1L }),
                    ) { e ->
                        com.yishou.app.ui.PartnerScreen(onBack = { nav.navigateUp() }, materialId = e.arguments?.getLong("m")?.takeIf { it > 0 })
                    }
                    composable(
                        "soup?card={card}",
                        arguments = listOf(navArgument("card") { type = NavType.LongType; defaultValue = -1L }),
                    ) { e ->
                        com.yishou.app.ui.SoupScreen(onBack = { nav.navigateUp() }, onBank = { nav.navigate("pool") }, cardId = e.arguments?.getLong("card")?.takeIf { it > 0 })
                    }
                    composable(
                        "redo?id={id}",
                        arguments = listOf(navArgument("id") { type = NavType.LongType; defaultValue = -1L }),
                    ) { e ->
                        com.yishou.app.ui.RedoScreen(id = e.arguments?.getLong("id") ?: -1L, onBack = { nav.navigateUp() })
                    }
                    composable("pool") {
                        com.yishou.app.ui.PoolScreen(onBack = { nav.navigateUp() }, onRoute = { nav.navigate(it) })
                    }

                }
                }
            }
        }
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        routeFrom(intent)
    }

    /** 长按图标的“急刹”、通知里的按钮：直接打开指定的那一页 */
    private fun routeFrom(intent: android.content.Intent?) {
        val to = intent?.getStringExtra(EXTRA_ROUTE) ?: return
        intent.removeExtra(EXTRA_ROUTE)
        (application as YishouApp).pendingRoute.value = to
    }

    companion object {
        const val EXTRA_ROUTE = "route"
    }
}
