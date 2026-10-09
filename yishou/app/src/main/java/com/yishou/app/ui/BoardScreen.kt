package com.yishou.app.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.data.Task
import com.yishou.app.llm.CoachMessages
import com.yishou.app.ui.theme.BoardBackdrop
import com.yishou.app.ui.theme.StoneMark

/** 主页：今天的对话流 + 局面面板 + 底部作答区。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    onOpenSettings: () -> Unit,
    onNewTask: () -> Unit,
    onEditTask: (Long) -> Unit,
    onOpenPermissions: () -> Unit,
    onOpenWindow: () -> Unit,
    onOpenSummary: () -> Unit,
    onOpenStatus: () -> Unit,
    vm: BoardViewModel = viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()
    val attachment by vm.attachment.state.collectAsStateWithLifecycle()
    val startersPair by vm.starters.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val prefs by (context.applicationContext as YishouApp).settings.app.collectAsStateWithLifecycle()
    var gateOn by remember { mutableStateOf(true) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { gateOn = PermissionStatus.accessibility(context) }
    val listState = rememberLazyListState()

    // 新的一手、新的判定出现时滚到底部
    LaunchedEffect(s.todayRounds.size, s.coachMove, s.submitting, s.loadingMove) {
        val last = listState.layoutInfo.totalItemsCount - 1
        if (last >= 0) listState.animateScrollToItem(last)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StoneMark()
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("一手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            s.task?.let {
                                Text(it.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenWindow) { Icon(Icons.Filled.PlayArrow, contentDescription = "陪练窗口") }
                    IconButton(onClick = onOpenSummary) { Icon(Icons.Filled.DateRange, contentDescription = "每晚总结") }
                    IconButton(onClick = onOpenStatus) { Icon(Icons.Filled.Info, contentDescription = "盘点") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
                },
            )
        },
        bottomBar = {
            if (s.task != null && s.coachMove != null) {
                AnswerComposer(
                    starters = startersPair?.takeIf { it.first == s.coachMove }?.second.orEmpty(),
                    answer = s.answer,
                    onAnswerChange = vm::onAnswerChange,
                    attachment = attachment,
                    onAttach = vm::attach,
                    onClearAttachment = vm.attachment::clear,
                    submitting = s.submitting,
                    onSubmit = vm::submit,
                    hint = s.hint,
                    error = s.judgeError,
                    modifier = Modifier.imePadding(),
                )
            }
        },
    ) { padding ->
        if (!s.loaded) return@Scaffold
        Box(Modifier.padding(padding).fillMaxSize()) {
        BoardBackdrop()
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!s.configured) {
                item {
                    NoticeCard("还没有填写大模型接口。先到设置里填写接口地址、密钥和模型名。", "去设置", onOpenSettings)
                }
            }
            val task = s.task
            if (task == null) {
                item { NoticeCard("还没有当前任务。先填一个真实的学习任务。", "新建任务", onNewTask) }
                return@LazyColumn
            }
            if (!gateOn) {
                item { NoticeCard("入口思考页还没开启：需要在系统里打开「一手」的无障碍服务。", "去开启权限", onOpenPermissions) }
            }
            item { PositionPanel(task, s.breakpoint, onEdit = { onEditTask(task.id) }) }
            item {
                Text(
                    if (s.todayRounds.isEmpty()) "今天还没落子" else "今天走了 ${s.todayRounds.size} 手，有效 ${s.effectiveToday} 手",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            s.resumeFrom?.let { last ->
                item {
                    ResumeCard(
                        gapMinutes = (System.currentTimeMillis() - last.createdAt) / 60_000,
                        lastAnswer = last.userAnswer,
                        stuck = s.breakpoint?.stuck,
                    )
                }
            }
            s.todayRounds.forEachIndexed { i, r ->
                item(key = "c${r.id}") { CoachBubble(r.coachMove) }
                item(key = "u${r.id}") { UserBubble(r.userAnswer) }
                item(key = "v${r.id}") { VerdictLine(r, number = i + 1) }
            }
            item(key = "current") {
                CurrentMove(
                    s = s,
                    shake = prefs.shakeToRoll,
                    onRetry = vm::requestMove,
                    onRoll = vm::roll,
                    onDemo = vm::demo,
                )
            }
        }
        }
    }
}

@Composable
private fun CurrentMove(
    s: BoardState,
    shake: Boolean,
    onRetry: () -> Unit,
    onRoll: (Int) -> Unit,
    onDemo: () -> Unit,
) {
    val move = s.coachMove
    when {
        s.submitting -> Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.answer.isNotBlank()) UserBubble(s.answer)
            TypingBubble("陪练在判定")
        }
        s.loadingMove -> TypingBubble("陪练在想这一手")
        move != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            s.breakpoint?.pendingFace?.takeIf { it > 0 }?.let { FaceChip(it) }
            CoachBubble(move) {
                s.breakpoint?.updatedAt?.let { ElapsedClock(it) }
            }
            MoveTools(shake = shake, onRoll = onRoll, onDemo = onDemo)
        }
        s.moveError != null -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("暂时出不了题：${s.moveError}", color = MaterialTheme.colorScheme.error)
            OutlinedButton(onClick = onRetry) { Text("重试") }
        }
        !s.configured -> Text("填好接口后，陪练会在这里出题。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        else -> OutlinedButton(onClick = onRetry) { Text("请陪练出一手") }
    }
}

/** 掷骰换一手、看个示范。 */
@Composable
fun MoveTools(shake: Boolean, onRoll: (Int) -> Unit, onDemo: () -> Unit, enabled: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        DiceRoller(enabled = enabled, shake = shake && enabled, onRolled = onRoll, size = 36.dp)
        Text(
            if (shake) "点骰子或摇一摇，换一面练" else "点骰子，换一面练",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onDemo, enabled = enabled) { Text("看个示范") }
    }
}

/**
 * 局面面板：把断点摆成调度器的四句话。收起时只显示“下一步”。
 */
@Composable
private fun PositionPanel(task: Task, bp: Breakpoint?, onEdit: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier
            .fillMaxWidth()
            .animateContentSize()
            .clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("局面", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (expanded) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
            if (expanded) {
                PositionLine("问题", task.goal)
                PositionLine("刚才得到", bp?.known)
                PositionLine("还缺", bp?.stuck)
                PositionLine("下一步", bp?.nextQuestion)
                Text(
                    "改任务或断点",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.clickable(onClick = onEdit).padding(top = 4.dp),
                )
            } else {
                PositionLine("下一步", bp?.nextQuestion?.ifBlank { null } ?: bp?.stuck)
            }
        }
    }
}

@Composable
private fun PositionLine(label: String, value: String?) {
    Row {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp),
        )
        Text(value?.takeIf { it.isNotBlank() } ?: "（空）", style = MaterialTheme.typography.bodyMedium)
    }
}

/** 一轮的判定结果卡片（思考页和陪练窗口用）。 */
@Composable
fun ResultCard(round: Round) {
    val color = if (round.effective) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (round.effective) "有效，${round.moveType}" else "这一手不算",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (round.feedback.isNotBlank()) Text(round.feedback, style = MaterialTheme.typography.bodyMedium)
            if (round.reason.isNotBlank()) Text("理由：${round.reason}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
fun NoticeCard(text: String, action: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text)
            Button(onClick = onAction) { Text(action) }
        }
    }
}

/** 给其他页面用的距离文案。 */
fun gapText(minutes: Long): String = CoachMessages.formatGap(minutes)
