package com.yishou.app.window

import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Surface
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.MainActivity
import com.yishou.app.YishouApp
import com.yishou.app.settings.AppPrefs
import com.yishou.app.round.AnswerRules
import com.yishou.app.ui.BackTopBar
import com.yishou.app.ui.AnswerComposer
import com.yishou.app.ui.CoachBubble
import com.yishou.app.ui.ElapsedClock
import com.yishou.app.ui.FaceChip
import com.yishou.app.ui.MoveTools
import com.yishou.app.ui.TypingBubble
import com.yishou.app.ui.UserBubble
import com.yishou.app.ui.VerdictLine
import com.yishou.app.ui.theme.BoardBackdrop
import com.yishou.app.ui.theme.YishouTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 陪练窗口页。窗口期间保持屏幕常亮，方便放在桌上边听边想。 */
class WindowActivity : ComponentActivity() {

    private val vm: WindowViewModel by lazy {
        ViewModelProvider(
            (application as YishouApp).windowOwner,
            ViewModelProvider.AndroidViewModelFactory.getInstance(application),
        )[WindowViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        vm.refresh()
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

    override fun onResume() {
        super.onResume()
        isVisible = true
    }

    override fun onPause() {
        isVisible = false
        super.onPause()
    }

    companion object {
        /** 窗口页在前台。看屏服务据此决定由谁朗读新的一手。 */
        @Volatile
        var isVisible = false
            private set
    }
}

@Composable
private fun WindowScreen(s: WindowState, vm: WindowViewModel, onBack: () -> Unit, onOpenApp: () -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }
    var pauseMenu by remember { mutableStateOf(false) }
    var showPanels by remember { mutableStateOf(false) }
    val attachment by vm.attachment.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as YishouApp
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val startersPair by vm.starters.collectAsStateWithLifecycle()
    val starters = startersPair?.takeIf { it.first == s.coachMove }?.second.orEmpty()
    val change: ((AppPrefs) -> AppPrefs) -> Unit = { f -> app.settings.updateApp(f) }

    Scaffold(topBar = { BackTopBar("陪练窗口", onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
        BoardBackdrop()
        Column(
            modifier = Modifier
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
                RulesCard(prefs, change)
                Button(onClick = vm::startNow, modifier = Modifier.fillMaxWidth()) {
                    Text("按这个规则开局：${s.windowMinutes} 分钟")
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
                    style = MaterialTheme.typography.displayMedium,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                Column(horizontalAlignment = Alignment.End) {
                    Text("有效 ${s.effectiveCount} 轮", style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { confirmStop = true }) { Text("提前结束") }
                }
            }

            TextButton(onClick = { showPanels = !showPanels }) {
                Text(if (showPanels) "收起 规则与看屏" else "规则与看屏 ▾")
            }
            if (showPanels) {
                LookCard(prefs, change)
                RulesCard(prefs, change)
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

            s.lastRound?.let { r ->
                UserBubble(r.userAnswer)
                VerdictLine(r)
            }

            if (s.paused) {
                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("休息中，窗口计时继续", fontWeight = FontWeight.Bold)
                        if (s.pauseUntil > 0) {
                            Text(
                                "还剩 ${formatRemaining((s.pauseUntil - System.currentTimeMillis()).coerceAtLeast(0))}，到点会叫你回来。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                        Button(onClick = vm::resume, modifier = Modifier.fillMaxWidth()) { Text("提前回来，开始下一轮") }
                    }
                }
                return@Column
            }

            if (!s.moveLoading && !s.submitting) s.breakpoint?.pendingFace?.takeIf { it > 0 }?.let { FaceChip(it) }
            when {
                s.moveLoading -> TypingBubble("陪练在想这一手")
                s.submitting -> TypingBubble("陪练在判定")
                s.coachMove != null -> CoachBubble(s.coachMove) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        s.breakpoint?.updatedAt?.let { ElapsedClock(it) }
                        Spacer(Modifier.width(8.dp))
                        if (s.noResponse) {
                            Text("未回应", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                        } else if (s.reminders > 0) {
                            Text("已提醒 ${s.reminders} 次", style = MaterialTheme.typography.labelMedium)
                        }
                    }
                }
                s.moveError != null -> {
                    Text("暂时出不了题：${s.moveError}", color = MaterialTheme.colorScheme.error)
                    OutlinedButton(onClick = vm::retryMove) { Text("重试") }
                }
            }

            if (s.coachMove != null) {
                if (!s.submitting && !s.moveLoading) {
                    MoveTools(shake = prefs.shakeToRoll, onRoll = vm::roll, onDemo = vm::demo)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(onClick = vm::thinking, modifier = Modifier.weight(1f), enabled = !s.submitting) { Text("我在想") }
                    OutlinedButton(onClick = vm::dontKnow, modifier = Modifier.weight(1f), enabled = !s.submitting) { Text("我不知道") }
                    Box(Modifier.weight(1f)) {
                        OutlinedButton(onClick = { pauseMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = !s.submitting) { Text("先停") }
                        DropdownMenu(expanded = pauseMenu, onDismissRequest = { pauseMenu = false }) {
                            listOf(5, 10, 15, 30).forEach { m ->
                                DropdownMenuItem(text = { Text("休息 $m 分钟") }, onClick = { pauseMenu = false; vm.pause(m) })
                            }
                        }
                    }
                }
                AnswerComposer(
                    starters = starters,
                    answer = s.answer,
                    onAnswerChange = vm::onAnswerChange,
                    attachment = attachment,
                    onAttach = vm::attach,
                    onClearAttachment = vm.attachment::clear,
                    submitting = s.submitting,
                    onSubmit = vm::submit,
                    hint = s.hint,
                    error = s.judgeError,
                )
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
