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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.yishou.app.ui.ConsoleScreen
import com.yishou.app.ui.DieFace
import com.yishou.app.ui.theme.StoneMark
import androidx.navigation.navArgument
import com.yishou.app.ui.AboutScreen
import com.yishou.app.ui.BoardScreen
import com.yishou.app.ui.PermissionsScreen
import com.yishou.app.ui.SummaryScreen
import com.yishou.app.ui.WatchedAppsScreen
import com.yishou.app.ui.SettingsScreen
import com.yishou.app.ui.StatusScreen
import com.yishou.app.ui.TaskEditScreen
import com.yishou.app.ui.theme.YishouTheme
import com.yishou.app.window.WindowActivity

/** 主界面。底部两个页签：对局（对话）和主控台；其余页面从这两处进入。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YishouTheme {
                val nav = rememberNavController()
                val entry by nav.currentBackStackEntryAsState()
                val route = entry?.destination?.route
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
                                    label = { Text("对局") },
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
                        BoardScreen(
                            onOpenSettings = { nav.navigate("settings") },
                            onNewTask = { nav.navigate("newtask") },
                            onEditTask = { id -> nav.navigate("task/$id") },
                            onOpenPermissions = { nav.navigate("permissions") },
                            onOpenWindow = { startActivity(Intent(this@MainActivity, WindowActivity::class.java)) },
                        )
                    }
                    composable("console") {
                        ConsoleScreen(
                            onOpenBoard = { tab("board") },
                            onOpenWindow = { startActivity(Intent(this@MainActivity, WindowActivity::class.java)) },
                            onOpenSummary = { nav.navigate("summary") },
                            onOpenStatus = { nav.navigate("status") },
                            onOpenSettings = { nav.navigate("settings") },
                            onOpenPermissions = { nav.navigate("permissions") },
                            onEditTask = { id -> nav.navigate("task/$id") },
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
                }
                }
            }
        }
    }
}
