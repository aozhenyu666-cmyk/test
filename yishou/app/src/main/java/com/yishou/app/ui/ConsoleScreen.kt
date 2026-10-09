package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.FaceCount
import com.yishou.app.data.Task
import com.yishou.app.llm.Faces
import com.yishou.app.look.ScreenLookService
import com.yishou.app.training.Training
import com.yishou.app.training.TrainingPlan
import com.yishou.app.ui.theme.BoardBackdrop
import com.yishou.app.wallpaper.BoardWallpaperService
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.ZoneId

data class ConsoleState(
    val plan: TrainingPlan? = null,
    val task: Task? = null,
    val breakpoint: Breakpoint? = null,
)

@OptIn(ExperimentalCoroutinesApi::class)
class ConsoleViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.dao()

    val state: StateFlow<ConsoleState> = run {
        val zone = ZoneId.systemDefault()
        val today = WindowClock.today(System.currentTimeMillis(), zone)
        val since = WindowClock.startOfDay(today.minusDays(60), zone)
        val taskBp = dao.observeCurrentTask().flatMapLatest { task ->
            if (task == null) flowOf<Pair<Task?, Breakpoint?>>(null to null)
            else dao.observeBreakpoint(task.id).map { task to it }
        }
        combine(dao.observeRoundsSince(since), taskBp, yishou.settings.app) { rounds, (task, bp), prefs ->
            val byDay = rounds.groupBy { WindowClock.today(it.createdAt, zone) }
                .mapValues { (_, rs) -> rs.count { it.effective } }
            ConsoleState(Training.plan(byDay, today, prefs.setSize, prefs.baseSets), task, bp)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ConsoleState())
    }
}

/**
 * 主控台：今天练到哪了、局面停在哪、各项开关的状态、所有入口，一页看全。
 */
@Composable
fun ConsoleScreen(
    onOpenBoard: () -> Unit,
    onOpenWindow: () -> Unit,
    onOpenSummary: () -> Unit,
    onOpenStatus: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPermissions: () -> Unit,
    onEditTask: (Long) -> Unit,
    vm: ConsoleViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val s by vm.state.collectAsStateWithLifecycle()
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val lookOn by ScreenLookService.running.collectAsStateWithLifecycle()
    var resumeKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeKey++ }
    val gateOn = remember(resumeKey) { PermissionStatus.accessibility(context) }
    val wallpaperOn = remember(resumeKey) { BoardWallpaperService.isActive(context) }
    val faces by produceState(emptyList<FaceCount>(), resumeKey, s.plan?.todayEffective) {
        value = app.database.dao().faceCounts(0)
    }
    val inWindow = WindowClock.active(System.currentTimeMillis(), prefs, ZoneId.systemDefault()) != null

    Box(Modifier.fillMaxSize()) {
        BoardBackdrop()
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Text("主控台", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            }
            s.plan?.let { plan ->
                item { TrainingCard(plan, inWindow, onOpenWindow, onOpenBoard) }
            }
            item { PositionCard(s.task, s.breakpoint, onOpenBoard, onEditTask) }
            item {
                Panel("开关") {
                    StatusRow("入口思考页", if (gateOn) "开着" else "没开，打开关注的应用不会拦", gateOn, onOpenPermissions)
                    StatusRow(
                        "开局规则",
                        if (prefs.windowStrict) "窗口里只能用学习应用（${prefs.windowAllowed.size} 个）" else "关着，窗口里不限制应用",
                        prefs.windowStrict,
                        onOpenWindow,
                    )
                    StatusRow(
                        "定时开局",
                        if (prefs.windowEnabled) "每天 %02d:%02d 自动开局，规则同时生效".format(prefs.windowHour, prefs.windowMinute) else "没开，在设置里选个时间",
                        prefs.windowEnabled,
                        onOpenSettings,
                    )
                    StatusRow(
                        "陪练看屏",
                        if (lookOn) "开着，下拉通知栏点“看一眼”" else "在陪练窗口的“规则与看屏”里开启",
                        lookOn,
                        onOpenWindow,
                    )
                    StatusRow("壁纸守护", if (wallpaperOn) "局面壁纸在用" else "没用，后台容易被清", wallpaperOn, onOpenPermissions)
                    StatusRow(
                        "每日检查",
                        if (prefs.nudgeEnabled) "%02d:%02d 还没应手就提醒".format(prefs.nudgeHour, prefs.nudgeMinute) else "关着",
                        prefs.nudgeEnabled,
                        onOpenSettings,
                    )
                }
            }
            item {
                Panel("六面", onClick = onOpenStatus) {
                    val byFace = faces.associateBy { it.face }
                    Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
                        Faces.ALL.forEach { f ->
                            val c = byFace[f.number]
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                DieFace(f.number, 30.dp)
                                Text(f.short, style = MaterialTheme.typography.labelMedium)
                                Text(
                                    if (c == null) "0" else "${c.effective}/${c.total}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = onOpenSummary) { Text("每晚总结") }
                    TextButton(onClick = onOpenStatus) { Text("盘点") }
                    TextButton(onClick = onOpenSettings) { Text("设置") }
                }
            }
        }
    }
}

/** 今日训练：棋子摆成一组一组，连续达标的天数，最近一周。 */
@Composable
private fun TrainingCard(plan: TrainingPlan, inWindow: Boolean, onOpenWindow: () -> Unit, onOpenBoard: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (plan.hitToday) "今天的目标完成了" else "今天练 ${plan.targetSets} 组，每组 ${plan.setSize} 手",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            val groups = maxOf(plan.targetSets, plan.doneSets + if (plan.inSet > 0) 1 else 0)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(groups) { g ->
                    val filled = (plan.todayEffective - g * plan.setSize).coerceIn(0, plan.setSize)
                    StoneGroup(plan.setSize, filled, extra = g >= plan.targetSets)
                }
            }
            val left = plan.setSize - plan.inSet
            Text(
                when {
                    plan.hitToday && plan.inSet == 0 -> "${plan.doneSets} 组都走完了。想多练就再开一组，不想就到此为止。"
                    plan.hitToday -> "目标已经完成，这一组还差 $left 手。"
                    plan.todayEffective == 0 -> "还没落子。先走一组 ${plan.setSize} 手，走完歇一下。"
                    else -> "完成 ${plan.doneSets} 组，这一组还差 $left 手。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (plan.note.isNotBlank()) {
                Text(plan.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            WeekStrip(plan)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpenWindow) { Text(if (inWindow) "回到陪练窗口" else "开一组") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpenBoard) { Text("在对局里应一手") }
            }
        }
    }
}

/** 一组棋子：实心是已有效的手，空心是还差的。超出目标的组用金色描边。 */
@Composable
private fun StoneGroup(size: Int, filled: Int, extra: Boolean) {
    val ink = MaterialTheme.colorScheme.onSurface
    val gold = MaterialTheme.colorScheme.tertiary
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        repeat(size) { i ->
            Canvas(Modifier.size(18.dp)) {
                val r = this.size.minDimension / 2
                if (i < filled) {
                    drawCircle(ink, r)
                    drawCircle(Color.White.copy(alpha = 0.2f), r * 0.3f, center = Offset(r * 0.7f, r * 0.68f))
                } else {
                    drawCircle(if (extra) gold else ink.copy(alpha = 0.35f), r - 1.5f, style = Stroke(width = 2.5f))
                }
            }
        }
    }
}

/** 最近七天：达标是金色星位，练过没达标是空心，没练是淡点。 */
@Composable
private fun WeekStrip(plan: TrainingPlan) {
    val gold = MaterialTheme.colorScheme.tertiary
    val faint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.25f)
    val names = listOf("一", "二", "三", "四", "五", "六", "日")
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            plan.days.forEach { d ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Canvas(Modifier.size(14.dp)) {
                        val r = size.minDimension / 2
                        when {
                            d.hit -> drawCircle(gold, r)
                            d.effective > 0 -> drawCircle(gold, r - 1.5f, style = Stroke(width = 2.5f))
                            else -> drawCircle(faint, r * 0.35f)
                        }
                    }
                    Text(names[d.date.dayOfWeek.value - 1], style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        Text(
            if (plan.streak > 0) "已经连续 ${plan.streak} 天达标" else "连续达标从今天开始算",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun PositionCard(task: Task?, bp: Breakpoint?, onOpenBoard: () -> Unit, onEditTask: (Long) -> Unit) {
    Panel("局面", onClick = onOpenBoard) {
        if (task == null) {
            Text("还没有当前任务。到对局里新建一个。")
            return@Panel
        }
        Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        val next = bp?.nextQuestion?.takeIf { it.isNotBlank() }
        if (next != null) Text("下一问：$next", style = MaterialTheme.typography.bodyMedium)
        bp?.stuck?.takeIf { it.isNotBlank() }?.let {
            Text("卡在：$it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = { onEditTask(task.id) }) { Text("改任务或断点") }
    }
}

@Composable
private fun Panel(title: String, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.92f),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

/** 一行开关状态：金点是开着，空心是没开。点一下去对应的地方。 */
@Composable
private fun StatusRow(name: String, detail: String, on: Boolean, onClick: () -> Unit) {
    val gold = MaterialTheme.colorScheme.tertiary
    val off = MaterialTheme.colorScheme.outline
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(10.dp)) {
            val r = size.minDimension / 2
            if (on) drawCircle(gold, r) else drawCircle(off, r - 1f, style = Stroke(width = 2f))
        }
        Spacer(Modifier.width(10.dp))
        Text(name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.width(76.dp))
        Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
