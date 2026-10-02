package com.behaviordept.app

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.behaviordept.app.record.RecordScreen
import com.behaviordept.app.settings.OnboardingScreen
import com.behaviordept.app.settings.SettingsScreen
import com.behaviordept.app.study.StudyScreen
import com.behaviordept.app.study.UnitDetailScreen
import com.behaviordept.app.today.FocusScreen
import com.behaviordept.app.today.TodayScreen
import com.behaviordept.app.ui.components.Divider
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.ui.theme.SerifSC
import kotlinx.coroutines.launch

object Routes {
    const val TODAY = "today"
    const val STUDY = "study"
    const val RECORD = "record"
    const val SETTINGS = "settings"
    const val UNIT = "unit/{id}"
    const val FOCUS = "focus/{id}"

    fun unit(id: Long) = "unit/$id"

    /** id = -1 表示新建学习单元。 */
    fun focus(id: Long) = "focus/$id"
}

private data class Tab(val route: String, val glyph: String, val label: String)

private val tabs = listOf(
    Tab(Routes.TODAY, "今", "今日"),
    Tab(Routes.STUDY, "学", "学习"),
    Tab(Routes.RECORD, "录", "记录"),
    Tab(Routes.SETTINGS, "设", "设置"),
)

@Composable
fun AppRoot(openTodaySignal: Int) {
    val c = LocalContext.current.container
    val settings by c.settings.settings.collectAsStateWithLifecycle(initialValue = null)
    val scope = rememberCoroutineScope()
    val p = Paper.colors

    val s = settings
    if (s == null) {
        Box(Modifier.fillMaxSize().background(p.paper))
        return
    }
    if (!s.onboardingDone) {
        OnboardingScreen(onDone = { scope.launch { c.settings.setOnboardingDone() } })
        return
    }

    val nav = rememberNavController()
    LaunchedEffect(openTodaySignal) {
        if (openTodaySignal > 0) nav.goTab(Routes.TODAY)
    }
    val entry by nav.currentBackStackEntryAsState()
    val route = entry?.destination?.route
    val showBar = tabs.any { it.route == route }

    Scaffold(
        containerColor = p.paper,
        bottomBar = { if (showBar) BottomBar(route) { nav.goTab(it) } },
    ) { inner ->
        NavHost(
            navController = nav,
            startDestination = Routes.TODAY,
            modifier = Modifier.fillMaxSize().padding(inner).consumeWindowInsets(inner),
        ) {
            composable(Routes.TODAY) {
                TodayScreen(
                    onStart = { id -> nav.navigate(Routes.focus(id)) },
                )
            }
            composable(Routes.STUDY) {
                StudyScreen(
                    onOpenUnit = { id -> nav.navigate(Routes.unit(id)) },
                    onNewUnit = { nav.navigate(Routes.focus(-1)) },
                )
            }
            composable(Routes.RECORD) { RecordScreen() }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBackToTraining = { nav.goTab(Routes.TODAY) })
            }
            composable(Routes.UNIT, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("id") ?: -1L
                UnitDetailScreen(
                    unitId = id,
                    onBack = { nav.popBackStack() },
                    onContinue = { nav.navigate(Routes.focus(id)) },
                )
            }
            composable(Routes.FOCUS, arguments = listOf(navArgument("id") { type = NavType.LongType })) { e ->
                val id = e.arguments?.getLong("id") ?: -1L
                FocusScreen(
                    unitId = id,
                    onExit = { nav.popBackStack() },
                )
            }
        }
    }
}

private fun NavHostController.goTab(route: String) {
    navigate(route) {
        popUpTo(graph.findStartDestination().id) { saveState = true }
        launchSingleTop = true
        restoreState = true
    }
}

/** 底部导航：每个入口是一格田字格里的一个宋体字。 */
@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    val p = Paper.colors
    Column(Modifier.fillMaxWidth().background(p.page)) {
        Divider()
        Row(
            Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(top = 8.dp, bottom = 6.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEach { tab ->
                val selected = tab.route == current
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onSelect(tab.route) }
                        .padding(horizontal = 14.dp),
                ) {
                    val box = Modifier.size(32.dp)
                    Box(
                        if (selected) box.background(p.ink) else box.border(1.dp, p.divider),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            tab.glyph,
                            fontFamily = SerifSC,
                            fontWeight = FontWeight.Black,
                            style = MaterialTheme.typography.titleMedium,
                            color = if (selected) p.page else p.ink2,
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        tab.label,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (selected) p.ink else p.ink2,
                    )
                }
            }
        }
    }
}
