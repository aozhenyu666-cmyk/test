package com.yishou.app.window

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.MainActivity
import com.yishou.app.round.AnswerRules
import com.yishou.app.ui.BackTopBar
import com.yishou.app.ui.ResultCard
import com.yishou.app.ui.theme.YishouTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 陪练窗口页。窗口期间保持屏幕常亮，方便放在桌上边听边想。 */
class WindowActivity : ComponentActivity() {

    private val vm: WindowViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContent {
            YishouTheme {
                val s by vm.state.collectAsStateWithLifecycle()
                WindowScreen(
                    s = s,
                    vm = vm,
                    onBack = { finish() },
                    onOpenApp = { startActivity(Intent(this, MainActivity::class.java)) },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        vm.refresh()
    }
}

@Composable
private fun WindowScreen(s: WindowState, vm: WindowViewModel, onBack: () -> Unit, onOpenApp: () -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }

    Scaffold(topBar = { BackTopBar("陪练窗口", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (s.loading) {
                CircularProgressIndicator()
                return@Column
            }

            s.ttsProblem?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }

            val span = s.span
            if (span == null) {
                Text("现在不在陪练窗口里。", style = MaterialTheme.typography.titleMedium)
                Text(
                    s.nextStart?.let { "下一次定时窗口：${formatTime(it)}" } ?: "定时窗口没有开启，可以在设置里开启。",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Button(onClick = vm::startNow, modifier = Modifier.fillMaxWidth()) {
                    Text("现在开始一个 ${s.windowMinutes} 分钟的窗口")
                }
                return@Column
            }

            if (s.ended) {
                Text("窗口结束", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text("本窗口有效 ${s.effectiveCount} 轮。断点已保存，下次从这里接着走。")
                Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
                return@Column
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    formatRemaining(s.remainingMs),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("有效 ${s.effectiveCount} 轮", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { confirmStop = true }) { Text("提前结束") }
                }
            }

            val task = s.task
            if (task == null) {
                Text("还没有当前任务。先在「一手」里填一个真实的学习任务。")
                Button(onClick = onOpenApp) { Text("打开一手") }
                return@Column
            }

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(task.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    s.breakpoint?.let { bp ->
                        if (bp.known.isNotBlank()) Text("已知：${bp.known}", style = MaterialTheme.typography.bodySmall)
                        if (bp.stuck.isNotBlank()) Text("卡点：${bp.stuck}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            s.lastRound?.let { ResultCard(it) }

            if (s.paused) {
                Text("本轮已停，窗口计时继续。准备好了再开始下一轮。")
                Button(onClick = vm::resume, modifier = Modifier.fillMaxWidth()) { Text("继续下一轮") }
                return@Column
            }

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("陪练的一手", style = MaterialTheme.typography.labelLarge)
                    when {
                        s.moveLoading -> Text("陪练在想……")
                        s.coachMove != null -> Text(s.coachMove, style = MaterialTheme.typography.bodyLarge)
                        s.moveError != null -> {
                            Text("暂时出不了题：${s.moveError}", color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = vm::retryMove) { Text("重试") }
                        }
                    }
                    if (s.noResponse) {
                        Text("未回应", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                    } else if (s.reminders > 0) {
                        Text("已提醒 ${s.reminders} 次", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (s.coachMove != null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = vm::thinking, modifier = Modifier.weight(1f), enabled = !s.submitting) { Text("我在想") }
                    OutlinedButton(onClick = vm::dontKnow, modifier = Modifier.weight(1f), enabled = !s.submitting) { Text("我不知道") }
                    OutlinedButton(onClick = vm::pause, modifier = Modifier.weight(1f), enabled = !s.submitting) { Text("先停") }
                }
                OutlinedTextField(
                    value = s.answer,
                    onValueChange = vm::onAnswerChange,
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 3,
                    enabled = !s.submitting,
                    label = { Text("你的一手") },
                    supportingText = { Text("已写 ${s.answerChars} 字，至少 ${AnswerRules.MIN_CHARS} 字。可以用输入法的语音输入。") },
                )
                s.hint?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                s.judgeError?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                Button(onClick = vm::submit, enabled = !s.submitting, modifier = Modifier.fillMaxWidth()) {
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

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("提前结束窗口？") },
            text = { Text("断点已经保存。结束后思考页会恢复正常放行。") },
            confirmButton = {
                TextButton(onClick = { confirmStop = false; vm.stopEarly() }) { Text("结束") }
            },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续陪练") } },
        )
    }
}

private fun formatRemaining(ms: Long): String {
    val total = (ms + 999) / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}

private fun formatTime(t: Long): String = SimpleDateFormat("M月d日 HH:mm", Locale.CHINA).format(Date(t))
