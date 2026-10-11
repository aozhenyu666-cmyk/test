@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.yishou.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.yishou.app.recall.EchoRepo
import com.yishou.app.system.ExternalGrace
import com.yishou.app.ui.theme.BoardBackdrop
import com.yishou.app.ui.theme.StoneMark

/**
 * 主页（对局）：教练带着练。没有在练的组时是“开一组”；在练时只有三块——材料、结论链、这一问。
 */
@Composable
fun CoachScreen(
    onOpenSettings: () -> Unit,
    onOpenWindow: () -> Unit,
    onOpenEcho: () -> Unit,
    onOpenPermissions: () -> Unit,
    vm: CoachViewModel = viewModel(),
) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val s by vm.coach.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val attachment by vm.attachment.state.collectAsStateWithLifecycle()
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val llm by app.settings.llm.collectAsStateWithLifecycle()
    var gateOn by remember { mutableStateOf(true) }
    var resumeKey by remember { mutableIntStateOf(0) }
    var confirmDrop by remember { mutableStateOf(false) }
    LifecycleEventEffect(Lifecycle.Event.ON_PAUSE) { vm.onHidden() }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        gateOn = PermissionStatus.accessibility(context)
        resumeKey++
        vm.onVisible()
    }
    val echoDue by produceState(0, resumeKey) { value = EchoRepo(app).due().size }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::attach) }

    // 热身时开组失败了：结束热身，回到开组面板显示原因
    LaunchedEffect(ui.warmingUp, s.set, s.busy, s.error) {
        if (ui.warmingUp && s.set == null && s.busy == null && s.error != null) vm.warmupDone(null)
    }

    val current = s.current
    val inSet = s.set != null && !ui.warmingUp && ui.recallAway == null && s.ended == null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        StoneMark()
                        Column(Modifier.padding(start = 10.dp)) {
                            Text("一手", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            tasks.firstOrNull { it.isCurrent }?.let {
                                Text(it.title, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onOpenWindow) { Icon(Icons.Filled.PlayArrow, contentDescription = "陪练窗口") }
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
                },
            )
        },
        bottomBar = {
            if (inSet && current != null) {
                AnswerComposer(
                    answer = ui.answer,
                    onAnswerChange = vm::onAnswerChange,
                    attachment = attachment,
                    onAttach = vm::attach,
                    onClearAttachment = vm.attachment::clear,
                    submitting = s.busy != null,
                    onSubmit = vm::submit,
                    error = s.error,
                    modifier = Modifier.imePadding(),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            BoardBackdrop()
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (!s.loaded) return@Column
                if (!llm.isComplete) NoticeCard("还没有填写大模型接口。先到设置里填写接口地址、密钥和模型名。", "去设置", onOpenSettings)
                if (!gateOn) NoticeCard("入口思考页还没开启：需要在系统里打开「一手」的无障碍服务。", "去开启权限", onOpenPermissions)

                val ended = s.ended
                val set = s.set
                when {
                    ended != null -> SetSummary(ended, s.endedMoves, onSave = vm::saveLesson, onSkip = vm::dismissEnded)
                    ui.warmingUp -> WarmupPanel(ready = set != null, onDone = vm::warmupDone)
                    set == null -> {
                        if (echoDue > 0) NoticeCard("有 $echoDue 条前几天的结论回来找你了。先凭记忆说一遍，再对照原话。", "回响", onOpenEcho)
                        busyLabel(s.busy)?.let { TypingBubble(it) }
                        s.error?.let { Text("没开成：$it", color = MaterialTheme.colorScheme.error) }
                        OpenSetPanel(
                            tasks = tasks,
                            suggestedFace = ui.suggestedFace,
                            warmupDefault = prefs.warmup,
                            shake = prefs.shakeToRoll,
                            stats = ui.stats,
                            attachment = attachment,
                            onAttach = {
                                ExternalGrace.open()
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                            onClearAttachment = vm.attachment::clear,
                            onStart = vm::open,
                        )
                    }
                    ui.recallAway != null -> RecallCard(s.chain, ui.recallAway ?: 0, vm::recallDone)
                    else -> {
                        MaterialCard(set)
                        ChainTimeline(s.chain, pending = current != null)
                        s.feedback?.let { FeedbackLine(it) }
                        busyLabel(s.busy)?.let { TypingBubble(it) }
                        current?.let { m ->
                            QuestionBlock(m)
                            HelpRow(
                                m,
                                enabled = s.busy == null,
                                onHint = { vm.help() },
                                onExplain = { vm.help(explain = true) },
                                onDemo = { vm.help(demo = true) },
                            )
                        }
                        TextButton(onClick = { confirmDrop = true }) { Text("换一组") }
                    }
                }
            }
        }
    }

    if (confirmDrop) {
        AlertDialog(
            onDismissRequest = { confirmDrop = false },
            title = { Text("不练这一组了？") },
            text = { Text("已经走过的几手都记着。换主练、换材料都从新的一组开始。") },
            confirmButton = { TextButton(onClick = { confirmDrop = false; vm.drop() }) { Text("换一组") } },
            dismissButton = { TextButton(onClick = { confirmDrop = false }) { Text("接着练") } },
        )
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
