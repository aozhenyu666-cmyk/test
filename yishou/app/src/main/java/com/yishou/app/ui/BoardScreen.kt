package com.yishou.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Round
import com.yishou.app.round.AnswerRules

/** 主页：一轮的完整界面。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    onOpenSettings: () -> Unit,
    onNewTask: () -> Unit,
    vm: BoardViewModel = viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("一手") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "设置")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!s.loaded) return@Column

            if (!s.configured) {
                NoticeCard(
                    text = "还没有填写大模型接口。先到设置里填写接口地址、密钥和模型名。",
                    action = "去设置",
                    onAction = onOpenSettings,
                )
            }

            val task = s.task
            if (task == null) {
                NoticeCard(text = "还没有当前任务。先填一个真实的学习任务。", action = "新建任务", onAction = onNewTask)
                return@Column
            }

            // 任务与断点
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text("目标：${task.goal}", style = MaterialTheme.typography.bodyMedium)
                    s.breakpoint?.let { BreakpointBlock(it) }
                }
            }

            s.lastRound?.let { ResultCard(it) }

            // 陪练的一手
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("陪练的一手", style = MaterialTheme.typography.labelLarge)
                    when {
                        s.coachMove != null -> Text(s.coachMove!!, style = MaterialTheme.typography.bodyLarge)
                        s.loadingMove -> Row(verticalAlignment = Alignment.CenterVertically) {
                            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("陪练在想……")
                        }
                        s.moveError != null -> {
                            Text("暂时出不了题：${s.moveError}", color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = vm::requestMove) { Text("重试") }
                        }
                        !s.configured -> Text("填好接口后，陪练会在这里出题。")
                        else -> OutlinedButton(onClick = vm::requestMove) { Text("请陪练出一手") }
                    }
                }
            }

            // 应一手
            if (s.coachMove != null) {
                OutlinedTextField(
                    value = s.answer,
                    onValueChange = vm::onAnswerChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    enabled = !s.submitting,
                    label = { Text("你的一手") },
                    supportingText = {
                        Text("已写 ${s.answerChars} 字，至少 ${AnswerRules.MIN_CHARS} 字（不算标点空格）")
                    },
                )
                s.hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                s.judgeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(
                    onClick = vm::submit,
                    enabled = !s.submitting,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (s.submitting) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(8.dp))
                        Text("判定中……")
                    } else {
                        Text(if (s.judgeError != null) "重试判定" else "应一手")
                    }
                }
            }
        }
    }
}

@Composable
private fun BreakpointBlock(bp: Breakpoint) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (bp.nextQuestion.isNotBlank()) {
            Text("下一问：${bp.nextQuestion}", style = MaterialTheme.typography.bodyMedium)
        }
        if (expanded) {
            Text("已知：${bp.known.ifBlank { "（空）" }}", style = MaterialTheme.typography.bodySmall)
            Text("卡点：${bp.stuck.ifBlank { "（空）" }}", style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { expanded = !expanded }) {
            Text(if (expanded) "收起断点" else "展开断点")
        }
    }
}

@Composable
private fun ResultCard(round: Round) {
    val color = if (round.effective) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = color)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                if (round.effective) "有效 · ${round.moveType}" else "这一手不算",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
            )
            if (round.feedback.isNotBlank()) Text(round.feedback, style = MaterialTheme.typography.bodyMedium)
            if (round.reason.isNotBlank()) Text("理由：${round.reason}", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun NoticeCard(text: String, action: String, onAction: () -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text)
            Button(onClick = onAction) { Text(action) }
        }
    }
}
