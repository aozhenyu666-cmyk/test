package com.yishou.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.yishou.app.ui.AboutScreen
import com.yishou.app.ui.BoardScreen
import com.yishou.app.ui.SettingsScreen
import com.yishou.app.ui.TaskEditScreen
import com.yishou.app.ui.theme.YishouTheme

/** 主界面。页面：棋盘（主页）→ 设置 → 编辑任务 / 关于。 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            YishouTheme {
                val nav = rememberNavController()
                NavHost(navController = nav, startDestination = "board") {
                    composable("board") {
                        BoardScreen(
                            onOpenSettings = { nav.navigate("settings") },
                            onNewTask = { nav.navigate("newtask") },
                        )
                    }
                    composable("settings") {
                        SettingsScreen(
                            onBack = { nav.navigateUp() },
                            onEditTask = { id -> nav.navigate("task/$id") },
                            onNewTask = { nav.navigate("newtask") },
                            onAbout = { nav.navigate("about") },
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
                }
            }
        }
    }
}
