package com.yishou.app.ui

import com.yishou.app.settings.AppZone

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
import com.yishou.app.bet.Calibration
import com.yishou.app.log.RunLog
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.SetStatus
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
import com.yishou.app.link.Partner
import com.yishou.app.link.ZongkongClient
import com.yishou.app.link.ZongkongStatus
import com.yishou.app.healthwire.ZongkongLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val zone = AppZone.zone
        val today = WindowClock.today(System.currentTimeMillis(), zone)
        val since = WindowClock.startOfDay(today.minusDays(60), zone)
        val taskBp = dao.observeCurrentTask().flatMapLatest { task ->
            if (task == null) flowOf<Pair<Task?, Breakpoint?>>(null to null)
            else dao.observeBreakpoint(task.id).map { task to it }
        }
        // 训练量按练完的教练组算：一组就是一组
        combine(yishou.database.coach().observeSetsSince(since), taskBp, yishou.settings.app) { sets, (task, bp), prefs ->
            val byDay = sets.filter { it.status == SetStatus.DONE }
                .groupBy { WindowClock.today(it.endedAt ?: it.startedAt, zone) }
                .mapValues { (_, ss) -> ss.size }
            ConsoleState(Training.plan(byDay, today, 1, prefs.baseSets), task, bp)
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
    onOpen: (String) -> Unit = {},
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
    val inWindow = WindowClock.active(System.currentTimeMillis(), prefs, AppZone.zone) != null
    val cardHist by produceState(emptyList<com.yishou.app.data.CardAttempt>(), resumeKey) { value = app.cardEngine.history() }
    val redoDue = remember(cardHist) { com.yishou.app.card.Progress.dueRedo(cardHist, System.currentTimeMillis()).size }
    val calibration by produceState("", resumeKey) {
        val resolved = app.database.practice().resolvedBets()
        value = Calibration.verdict(Calibration.of(resolved.map { it.confidence to (it.outcome > 0) }))
    }
    val logCount by produceState(0, resumeKey) { value = RunLog.current?.entries()?.size ?: 0 }

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
                item { TrainingCard(plan, prefs.setCards, inWindow, onOpenWindow, onOpenBoard) }
            }
            item { SkillPanel(cardHist) }
            item { ZongkongPanel(resumeKey) }
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
                Panel("练在平时") {
                    StatusRow(
                        "错题重做",
                        if (redoDue > 0) "$redoDue 张到期了，练一组时会先出" else "没有到期的错题",
                        redoDue > 0,
                    ) { onOpenBoard() }
                    StatusRow("预判本", calibration.ifEmpty { "先押再看：下注和预演" }, false) { onOpen("bets") }
                    StatusRow("练功底", "题库：导入的真题和申论", false) { onOpen("bank") }
                    StatusRow("练思维", "素材库、抓主干、挖前提、站立场、估个数", false) { onOpen("think") }
                    StatusRow("做事", "一步一步走完一件事", false) { onOpen("jobs") }
                    StatusRow(
                        "运行日志",
                        if (logCount > 0) "记了 $logCount 条出错，点开看" else "没出过错",
                        false,
                    ) { onOpen("log") }
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
private fun TrainingCard(plan: TrainingPlan, setMoves: Int, inWindow: Boolean, onOpenWindow: () -> Unit, onOpenBoard: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                if (plan.hitToday) "今天的目标完成了" else "今天练 ${plan.targetSets} 组",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                repeat(maxOf(plan.targetSets, plan.doneSets)) { g ->
                    StoneGroup(1, if (g < plan.doneSets) 1 else 0, extra = g >= plan.targetSets)
                }
            }
            Text(
                when {
                    plan.hitToday -> "${plan.doneSets} 组都练完了。想多练就再开一组，不想就到此为止。"
                    plan.doneSets == 0 -> "还没开组。一组 $setMoves 张题卡。"
                    else -> "练完 ${plan.doneSets} 组，还差 ${plan.targetSets - plan.doneSets} 组。"
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (plan.note.isNotBlank()) {
                Text(plan.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            WeekStrip(plan)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Button(onClick = onOpenWindow) { Text(if (inWindow) "回到陪练窗口" else "开陪练窗口") }
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onOpenBoard) { Text("开一组题卡") }
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

/** 总控那边此刻的状态：严管没有、正在做哪一步。点开回总控。没装总控就不显示。 */
@Composable
private fun ZongkongPanel(resumeKey: Int) {
    val context = LocalContext.current
    val installed = remember(resumeKey) { Partner.installed(context) }
    if (!installed) return
    val zk by produceState<ZongkongStatus?>(null, resumeKey) {
        // v2：带超时的一次性读取（这条在 IO 上跑，界面不会卡住）。
        value = withContext(Dispatchers.IO) { ZongkongLink.fetchOnce(context) }
    }
    val open = { ZongkongClient.open(context); Unit }
    Panel("总控", onClick = open) {
        val s = zk
        if (s == null) {
            StatusRow("连接", "连不上总控：把总控和一手都更新到最新版再试", false, open)
        } else {
            StatusRow("状态", if (s.strict) "严管中：${s.headline}" else "放行中", s.strict, open)
            if (s.actionTitle.isNotBlank()) {
                StatusRow(
                    "当前一步",
                    s.actionTitle + (if (s.step.isNotBlank()) "\n回来先：${s.step}" else ""),
                    true,
                    open,
                )
            }
            if (s.gatesTotal > 0) StatusRow("今天的关卡", "交了 ${s.gatesDone} / ${s.gatesTotal}", s.gatesDone >= s.gatesTotal, open)
        }
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

/**
 * 小技能：七类题各自的掌握度（最近 10 张里自己做对的比例），以及几条总数。
 * 判对错全靠题卡比对，所以这些数字说的是真做对了多少，不是说得好不好。
 */
@Composable
private fun SkillPanel(hist: List<com.yishou.app.data.CardAttempt>) {
    val P = com.yishou.app.card.Progress
    val stats = P.stats(hist)
    Panel("小技能（最近 30 天）") {
        if (stats.total == 0) {
            Text("还没做过题卡。到“对局”里练一组。", style = MaterialTheme.typography.bodySmall)
            return@Panel
        }
        com.yishou.app.card.Skill.entries.forEach { sk ->
            val m = P.mastery(hist, sk)
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row {
                    Text(sk.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        if (m == null) "没练过" else "${(m * 100).toInt()}%  难度 ${P.difficulty(hist, sk)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ThinProgress((m ?: 0.0).toFloat())
            }
        }
        Text(
            "首答对 ${com.yishou.app.card.Progress.Stats.ratio(stats.firstCorrect, stats.total)}，查完改对 ${stats.changedRight} 次、改错 ${stats.changedWrong} 次，" +
                "把握高的对了 ${com.yishou.app.card.Progress.Stats.ratio(stats.highConfCorrect, stats.highConf)}，关屏练到第 ${P.level(hist)} 级。",
            style = MaterialTheme.typography.bodySmall,
        )
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
