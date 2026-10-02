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
import com.behaviordept.app.data.Template
import com.behaviordept.app.guard.GuardScreen
import com.behaviordept.app.guard.UrgeScreen
import com.behaviordept.app.record.WeeklyScreen
import com.behaviordept.app.today.NextAction
import com.behaviordept.app.training.DrillFocusScreen
import com.behaviordept.app.training.ExamLogScreen
import com.behaviordept.app.training.MatchLogScreen
import com.behaviordept.app.training.NewSkillScreen
import com.behaviordept.app.training.RetestState
import com.behaviordept.app.training.SkillScreen
import com.behaviordept.app.training.TrainingScreen
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
    const val TRAINING = "training"
    const val GUARD = "guard"
    const val RECORD = "record"
    const val SETTINGS = "settings"
    const val STUDY = "study"
    const val UNIT = "unit/{id}"
    const val FOCUS = "focus/{id}"
    const val SKILL = "skill/{id}"
    const val MATCH = "match/{id}"
    const val EXAM = "exam/{id}"
    const val DRILL = "drill/{id}"
    const val NEW_SKILL = "newskill"
    const val URGE = "urge"
    const val WEEKLY = "weekly"

    fun unit(id: Long) = "unit/$id"

    /** id = -1 表示新建学习单元。 */
    fun focus(id: Long) = "focus/$id"
    fun skill(id: Long) = "skill/$id"

    /** id = -1 表示用第一个对抗竞技技能（桌面快捷方式）。 */
    fun match(id: Long) = "match/$id"
    fun exam(id: Long) = "exam/$id"
    fun drill(id: Long) = "drill/$id"
}

private data class Tab(val route: String, val glyph: String, val label: String)

private val tabs = listOf(
    Tab(Routes.TODAY, "今", "今日"),
    Tab(Routes.TRAINING, "练", "训练"),
    Tab(Routes.GUARD, "防", "防线"),
    Tab(Routes.RECORD, "录", "记录"),
    Tab(Routes.SETTINGS, "设", "设置"),
)

/** 今日一件事（或“我想刷”里的那一件）点开始后去哪。 */
private fun NavHostController.start(action: NextAction) {
    when (action) {
        is NextAction.Review, is NextAction.Step, NextAction.NewUnit -> navigate(Routes.focus(action.unitId ?: -1L))
        is NextAction.Drill -> navigate(Routes.drill(action.drill.id))
        is NextAction.Retest -> {
            val id = action.plan.skill.id
            when {
                action.state is RetestState.Ready -> navigate(Routes.skill(id))
                action.plan.skill.template == Template.COMPETITIVE -> navigate(Routes.match(id))
                action.plan.skill.template == Template.EXAM -> navigate(Routes.exam(id))
                else -> navigate(Routes.skill(id))
            }
        }
    }
}

private val idArg = listOf(navArgument("id") { type = NavType.LongType })

@Composable
fun AppRoot(request: RouteRequest?) {
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
    LaunchedEffect(request) {
        when (request?.route) {
            null -> Unit
            MainActivity.ROUTE_TODAY -> nav.goTab(Routes.TODAY)
            MainActivity.ROUTE_URGE -> nav.navigate(Routes.URGE)
            MainActivity.ROUTE_MATCH -> nav.navigate(Routes.match(-1))
            MainActivity.ROUTE_WEEKLY -> nav.navigate(Routes.WEEKLY)
        }
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
                TodayScreen(onStart = { nav.start(it) }, onUrge = { nav.navigate(Routes.URGE) })
            }
            composable(Routes.TRAINING) {
                TrainingScreen(
                    onOpenStudy = { nav.navigate(Routes.STUDY) },
                    onOpenSkill = { nav.navigate(Routes.skill(it)) },
                    onNewSkill = { nav.navigate(Routes.NEW_SKILL) },
                )
            }
            composable(Routes.GUARD) { GuardScreen(onUrge = { nav.navigate(Routes.URGE) }) }
            composable(Routes.RECORD) { RecordScreen(onWeekly = { nav.navigate(Routes.WEEKLY) }) }
            composable(Routes.SETTINGS) {
                SettingsScreen(onBackToTraining = { nav.goTab(Routes.TODAY) })
            }
            composable(Routes.STUDY) {
                StudyScreen(
                    onOpenUnit = { id -> nav.navigate(Routes.unit(id)) },
                    onNewUnit = { nav.navigate(Routes.focus(-1)) },
                )
            }
            composable(Routes.UNIT, arguments = idArg) { e ->
                val id = e.arguments?.getLong("id") ?: -1L
                UnitDetailScreen(
                    unitId = id,
                    onBack = { nav.popBackStack() },
                    onContinue = { nav.navigate(Routes.focus(id)) },
                )
            }
            composable(Routes.FOCUS, arguments = idArg) { e ->
                FocusScreen(unitId = e.arguments?.getLong("id") ?: -1L, onExit = { nav.popBackStack() })
            }
            composable(Routes.SKILL, arguments = idArg) { e ->
                SkillScreen(
                    skillId = e.arguments?.getLong("id") ?: -1L,
                    onBack = { nav.popBackStack() },
                    onLogMatch = { nav.navigate(Routes.match(it)) },
                    onLogExam = { nav.navigate(Routes.exam(it)) },
                    onStartDrill = { nav.navigate(Routes.drill(it)) },
                )
            }
            composable(Routes.MATCH, arguments = idArg) { e ->
                MatchLogScreen(
                    skillId = e.arguments?.getLong("id") ?: -1L,
                    onBack = { nav.popBackStack() },
                    onOpenSkill = { nav.navigate(Routes.skill(it)) },
                )
            }
            composable(Routes.EXAM, arguments = idArg) { e ->
                ExamLogScreen(skillId = e.arguments?.getLong("id") ?: -1L, onBack = { nav.popBackStack() })
            }
            composable(Routes.DRILL, arguments = idArg) { e ->
                DrillFocusScreen(drillId = e.arguments?.getLong("id") ?: -1L, onExit = { nav.popBackStack() })
            }
            composable(Routes.NEW_SKILL) {
                NewSkillScreen(
                    onBack = { nav.popBackStack() },
                    onCreated = { id ->
                        nav.popBackStack()
                        nav.navigate(Routes.skill(id))
                    },
                )
            }
            composable(Routes.URGE) {
                UrgeScreen(
                    onBack = { nav.popBackStack() },
                    onStart = { action ->
                        nav.popBackStack()
                        nav.start(action)
                    },
                )
            }
            composable(Routes.WEEKLY) { WeeklyScreen(onBack = { nav.popBackStack() }) }
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
                        .padding(horizontal = 8.dp),
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
