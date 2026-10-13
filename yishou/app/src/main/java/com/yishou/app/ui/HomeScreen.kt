package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.CardAttempt
import com.yishou.app.data.Deed
import com.yishou.app.data.DrillRow
import com.yishou.app.data.Material
import com.yishou.app.data.Job
import com.yishou.app.data.JobStep
import com.yishou.app.data.StepResult
import com.yishou.app.deed.Deeds
import com.yishou.app.settings.AppZone
import com.yishou.app.think.DrillStats
import com.yishou.app.ui.theme.StoneMark
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dayStart = WindowClock.startOfDay(WindowClock.today(System.currentTimeMillis(), AppZone.zone), AppZone.zone)

    val cards: StateFlow<List<CardAttempt>> = yishou.database.cards().observeAttemptsSince(dayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val drills: StateFlow<List<DrillRow>> = yishou.database.think().observeDrillsSince(dayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val deeds: StateFlow<List<Deed>> = yishou.database.think().observeDeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val materials: StateFlow<List<Material>> = yishou.database.think().observeMaterials()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val steps: StateFlow<List<JobStep>> = yishou.database.jobs().observeStepsDoneSince(dayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val bankLeft: StateFlow<Int> = yishou.database.cards().observeBank().map { xs -> xs.count { it.usedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val jobs: StateFlow<List<Job>> = yishou.database.jobs().observeJobs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val start: Long get() = dayStart
}

/** 今天落在时之轮上的子 */
fun todayMarks(cards: List<CardAttempt>, drills: List<DrillRow>, deeds: List<Deed>, steps: List<JobStep>, dayStart: Long): List<WheelMark> =
    steps.filter { it.result != StepResult.SKIP }.mapNotNull { s -> s.doneAt?.let { WheelMark(it, MarkKind.DEED, s.result == StepResult.OK) } } +
        cards.filter { it.verified }.map { WheelMark(it.createdAt, MarkKind.CARD, it.firstCorrect && it.help == 0) } +
        drills.filter { !it.disputed }.map { WheelMark(it.createdAt, MarkKind.DRILL, DrillStats.good(it)) } +
        deeds.flatMap { d ->
            val a = d.actual
            listOfNotNull(
                WheelMark(d.createdAt, MarkKind.DEED, true).takeIf { d.createdAt >= dayStart },
                if (a != null && (d.resolvedAt ?: 0) >= dayStart) WheelMark(d.resolvedAt ?: d.createdAt, MarkKind.DEED, Deeds.good(d.estimate, a)) else null,
            )
        }

/**
 * 首页：一只时之轮，三个入口：练功底（题库）、练思维（素材）、做事（一步一步）。
 * 底下是潮汐，点一下水面会荡开。
 */
@Composable
fun HomeScreen(
    onBank: () -> Unit,
    onThink: () -> Unit,
    onJobs: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenWindow: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val app = LocalContext.current.applicationContext as YishouApp
    val cards by vm.cards.collectAsStateWithLifecycle()
    val drills by vm.drills.collectAsStateWithLifecycle()
    val deeds by vm.deeds.collectAsStateWithLifecycle()
    val materials by vm.materials.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val bankLeft by vm.bankLeft.collectAsStateWithLifecycle()
    val jobs by vm.jobs.collectAsStateWithLifecycle()
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val llm by app.settings.llm.collectAsStateWithLifecycle()
    val marks = remember(cards, drills, deeds, steps) { todayMarks(cards, drills, deeds, steps, vm.start) }
    val tide = remember { TideState() }
    val tideHeight = 240.dp

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // 在最外层先看一眼点击（不拦下），落在水面上就荡一圈
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                    if (down.position.y > size.height - tideHeight.toPx()) tide.poke(down.position.x, down.position.y)
                }
            },
    ) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {}
        TideBackdrop(Modifier.fillMaxSize(), tide, tideHeight)
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoneMark(24.dp)
                Spacer(Modifier.width(10.dp))
                Text("一手", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                Text(AppZone.format("M月d日", System.currentTimeMillis()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
            }

            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                TimeWheel(
                    marks = marks,
                    windowStart = if (prefs.windowEnabled) prefs.windowHour * 60 + prefs.windowMinute else null,
                    windowMinutes = prefs.windowMinutes,
                    modifier = Modifier.fillMaxWidth(0.88f),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${marks.size}", style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Light)
                        Text(if (marks.isEmpty()) "今天还没落子" else "今天落子", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Legend()

            if (!llm.isComplete) {
                Text("还没有填写大模型接口：出题、判卷都要用它。", color = MaterialTheme.colorScheme.error)
                Button(onClick = onOpenSettings) { Text("去设置") }
            }

            val running = jobs.count { it.finishedAt == null }
            Entry(
                title = "练功底",
                sub = if (bankLeft > 0) "题库里还有 $bankLeft 道没做 · 行测 · 申论" else "导入真题：GPT / Gemini / 粉笔",
                accent = MaterialTheme.colorScheme.onBackground,
                onClick = onBank,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Entry(
                    title = "练思维",
                    sub = "任何素材都能练：抓主干 · 挖前提 · 站立场",
                    accent = MaterialTheme.colorScheme.tertiary,
                    onClick = onThink,
                    modifier = Modifier.weight(1f),
                )
                Entry(
                    title = "做事",
                    sub = if (running > 0) "$running 件在做，接着走下一步" else "一步一步走完一件事",
                    accent = MaterialTheme.colorScheme.secondary,
                    onClick = onJobs,
                    modifier = Modifier.weight(1f),
                )
            }
            val fresh = materials.count { it.uses == 0 }
            if (fresh > 0) {
                TextButton(onClick = onThink) { Text("素材库里有 $fresh 段还没练过 →") }
            }
            TextButton(onClick = onOpenWindow) { Text("陪练窗口") }
            Spacer(Modifier.height(tideHeight))
        }
    }
}

@Composable
private fun Legend() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
        listOf(
            "功底" to MaterialTheme.colorScheme.onBackground,
            "思维" to MaterialTheme.colorScheme.tertiary,
            "做事" to MaterialTheme.colorScheme.secondary,
        ).forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 8.dp)) {
                androidx.compose.foundation.Canvas(Modifier.width(8.dp).height(8.dp)) { drawCircle(color) }
                Spacer(Modifier.width(5.dp))
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun Entry(title: String, sub: String, accent: Color, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        tonalElevation = 1.dp,
        shadowElevation = 1.dp,
        modifier = modifier.clickable(onClick = onClick),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.Canvas(Modifier.width(28.dp).height(3.dp)) { drawRect(accent) }
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, minLines = 2)
        }
    }
}
