package com.yishou.app.window

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.MainActivity
import com.yishou.app.YishouApp
import com.yishou.app.coach.CoachState
import com.yishou.app.settings.AppPrefs
import com.yishou.app.settings.AppZone
import com.yishou.app.speech.ListenPhase
import com.yishou.app.ui.AnswerComposer
import com.yishou.app.ui.BackTopBar
import com.yishou.app.ui.ChainTimeline
import com.yishou.app.ui.FeedbackLine
import com.yishou.app.ui.FlipClock
import com.yishou.app.ui.HelpRow
import com.yishou.app.ui.MaterialCard
import com.yishou.app.ui.MicGlyph
import com.yishou.app.ui.QuestionBlock
import com.yishou.app.ui.RecallCard
import com.yishou.app.ui.SetSummary
import com.yishou.app.ui.ThinProgress
import com.yishou.app.ui.TypingBubble
import com.yishou.app.ui.VoiceNote
import com.yishou.app.ui.VoiceRings
import com.yishou.app.ui.WarmupPanel
import com.yishou.app.ui.busyLabel
import com.yishou.app.ui.theme.BoardBackdrop
import com.yishou.app.ui.theme.YishouTheme

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
                val c by vm.coach.collectAsStateWithLifecycle()
                WindowScreen(
                    s = s,
                    c = c,
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
        vm.onVisible()
    }

    override fun onPause() {
        isVisible = false
        vm.onHidden()
        super.onPause()
    }

    companion object {
        /** 窗口页在前台。看屏服务据此决定由谁朗读新的一问。 */
        @Volatile
        var isVisible = false
            private set
    }
}

@Composable
private fun WindowScreen(s: WindowState, c: CoachState, vm: WindowViewModel, onBack: () -> Unit, onOpenApp: () -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }
    var confirmDrop by remember { mutableStateOf(false) }
    var pauseMenu by remember { mutableStateOf(false) }
    var showPanels by remember { mutableStateOf(false) }
    val attachment by vm.attachment.state.collectAsStateWithLifecycle()
    val app = LocalContext.current.applicationContext as YishouApp
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val change: ((AppPrefs) -> AppPrefs) -> Unit = { f -> app.settings.updateApp(f) }
    val level by vm.level.collectAsStateWithLifecycle()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> vm.onMicPermission(ok) }
    LaunchedEffect(s.needMic) {
        if (s.needMic) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // 热身时开组失败：结束热身，显示原因
    LaunchedEffect(s.warmingUp, c.set, c.busy, c.error) {
        if (s.warmingUp && c.set == null && c.busy == null && c.error != null) vm.warmupDone(null)
    }

    val current = c.current
    val showComposer = s.span != null && !s.ended && !s.paused && !s.warmingUp && s.recallAway == null && c.ended == null && current != null

    Scaffold(
        topBar = { BackTopBar("陪练窗口", onBack) },
        bottomBar = {
            if (showComposer) {
                AnswerComposer(
                    answer = s.answer,
                    onAnswerChange = vm::onAnswerChange,
                    attachment = attachment,
                    onAttach = vm::attach,
                    onClearAttachment = vm.attachment::clear,
                    submitting = c.busy != null,
                    onSubmit = vm::submit,
                    error = c.error,
                    mic = { MicTapButton(s, c, onTap = vm::micTap) },
                    modifier = Modifier.imePadding(),
                )
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            BoardBackdrop()
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
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
                        s.nextStart?.let { "下一次定时窗口：${AppZone.format("M月d日 HH:mm", it)}" } ?: "定时窗口没有开启，可以在设置里开启。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    RulesCard(prefs, change)
                    Button(onClick = vm::startNow, modifier = Modifier.fillMaxWidth()) { Text("按这个规则开局：${s.windowMinutes} 分钟") }
                    return@Column
                }

                if (s.ended) {
                    Text("窗口结束", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                    Text("本窗口练完 ${s.setsDone} 组。没练完的那组记着，下次从这里接着来。")
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
                    return@Column
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlipClock(s.remainingMs, tile = 56.dp, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("练完 ${s.setsDone} 组", style = MaterialTheme.typography.titleMedium)
                        TextButton(onClick = { confirmStop = true }) { Text("提前结束") }
                    }
                }
                ThinProgress(1f - s.remainingMs.toFloat() / (span.end - span.start).coerceAtLeast(1))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("语音陪练", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Switch(checked = s.voiceOn, onCheckedChange = vm::setVoice)
                }
                TextButton(onClick = { showPanels = !showPanels }) { Text(if (showPanels) "收起 规则与看屏" else "规则与看屏 ▾") }
                if (showPanels) {
                    LookCard(prefs, change)
                    RulesCard(prefs, change)
                }

                val ended = c.ended
                val set = c.set
                when {
                    s.warmingUp -> WarmupPanel(ready = set != null, onDone = vm::warmupDone)
                    ended != null -> {
                        SetSummary(ended, c.endedMoves, onSave = { vm.saveLessonAndNext(it) }, onSkip = { vm.saveLessonAndNext(null) }) { lesson ->
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = { vm.restAfterSet(lesson) }) { Text("先歇 ${s.restMinutes} 分钟") }
                            }
                        }
                    }
                    s.paused -> PausedCard(s, vm::resume)
                    s.recallAway != null -> RecallCard(c.chain, s.recallAway, vm::recallDone)
                    set == null -> {
                        busyLabel(c.busy)?.let { TypingBubble(it) }
                        c.error?.let {
                            Text("没开成：$it", color = MaterialTheme.colorScheme.error)
                            OutlinedButton(onClick = { vm.openSet(warm = false) }) { Text("重试") }
                        }
                        if (c.busy == null && c.error == null) {
                            Button(onClick = { vm.openSet(warm = prefs.warmup) }) { Text("开一组") }
                        }
                    }
                    else -> {
                        MaterialCard(set)
                        ChainTimeline(c.chain, pending = current != null)
                        c.feedback?.let { FeedbackLine(it) }
                        if (s.voiceOn && current != null) VoiceStage(s, c, level, onTap = vm::micTap, onEdit = vm::cancelAutoSend)
                        busyLabel(c.busy)?.let { TypingBubble(it) }
                        current?.let { m ->
                            QuestionBlock(m)
                            HelpRow(
                                m,
                                enabled = c.busy == null,
                                onHint = { vm.help() },
                                onExplain = { vm.help(explain = true) },
                                onDemo = { vm.help(demo = true) },
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                OutlinedButton(onClick = vm::thinking, modifier = Modifier.weight(1f), enabled = c.busy == null) { Text("我在想") }
                                Box(Modifier.weight(1f)) {
                                    OutlinedButton(onClick = { pauseMenu = true }, modifier = Modifier.fillMaxWidth(), enabled = c.busy == null) { Text("先停") }
                                    DropdownMenu(expanded = pauseMenu, onDismissRequest = { pauseMenu = false }) {
                                        listOf(5, 10, 15, 30).forEach { m ->
                                            DropdownMenuItem(text = { Text("休息 $m 分钟") }, onClick = { pauseMenu = false; vm.pause(m) })
                                        }
                                    }
                                }
                                TextButton(onClick = { confirmDrop = true }, enabled = c.busy == null) { Text("换一组") }
                            }
                            if (s.reminders > 0 || s.pulled > 0) {
                                Text(
                                    if (s.pulled > 0) "已经把你拉回来 ${s.pulled} 次。说一句也行：“提示”“没看懂”“先停”。" else "已提醒 ${s.reminders} 次",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = if (s.noResponse) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("提前结束窗口？") },
            text = { Text("每一手都已经保存。结束后思考页恢复正常放行。") },
            confirmButton = { TextButton(onClick = { confirmStop = false; vm.stopEarly() }) { Text("结束") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续陪练") } },
        )
    }
    if (confirmDrop) {
        AlertDialog(
            onDismissRequest = { confirmDrop = false },
            title = { Text("换一组？") },
            text = { Text("这一组走过的几手都记着。教练会重新排一组。") },
            confirmButton = { TextButton(onClick = { confirmDrop = false; vm.drop() }) { Text("换一组") } },
            dismissButton = { TextButton(onClick = { confirmDrop = false }) { Text("接着练") } },
        )
    }
}

@Composable
private fun PausedCard(s: WindowState, onResume: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("休息中，窗口计时继续", fontWeight = FontWeight.Bold)
            if (s.pauseUntil > 0) {
                Text("还剩 ${formatRemaining((s.pauseUntil - System.currentTimeMillis()).coerceAtLeast(0))}，到点会叫你回来。")
            }
            Button(onClick = onResume, modifier = Modifier.fillMaxWidth()) { Text("提前回来") }
        }
    }
}

/**
 * 语音台：一颗子和它的涟漪。教练说话时金环荡开，在听时环跟着你的音量，在听写时收成转动的弧。
 * 点一下：教练在说就打断、开始听；正在听就当说完了。听写好后倒数自动发出，点“改一下”留下来改。
 */
@Composable
private fun VoiceStage(s: WindowState, c: CoachState, level: Float, onTap: () -> Unit, onEdit: () -> Unit) {
    val label = when {
        c.busy != null -> busyLabel(c.busy).orEmpty()
        s.speaking -> "教练在说，点一下直接开口"
        s.listen == ListenPhase.LISTENING -> "在听，说完停一下就行"
        s.listen == ListenPhase.TRANSCRIBING -> "在听写"
        s.autoSendLeft > 0 -> "${s.autoSendLeft} 秒后发出"
        else -> "点一下，开口作答"
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(150.dp).clickable(enabled = c.busy == null, onClick = onTap), contentAlignment = Alignment.Center) {
            VoiceRings(level, s.listen, s.speaking, Modifier.fillMaxSize())
        }
        Text(label, style = MaterialTheme.typography.titleSmall)
        if (s.autoSendLeft > 0) OutlinedButton(onClick = onEdit) { Text("改一下") }
        VoiceNote(s.voiceNote)
    }
}

@Composable
private fun MicTapButton(s: WindowState, c: CoachState, onTap: () -> Unit) {
    val listening = s.listen == ListenPhase.LISTENING
    FilledTonalIconButton(
        onClick = onTap,
        enabled = c.busy == null && s.listen != ListenPhase.TRANSCRIBING,
        colors = if (listening) IconButtonDefaults.filledTonalIconButtonColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
        else IconButtonDefaults.filledTonalIconButtonColors(),
        modifier = Modifier.size(44.dp),
    ) {
        if (s.listen == ListenPhase.TRANSCRIBING) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        else MicGlyph(active = listening)
    }
}

private fun formatRemaining(ms: Long): String {
    val total = (ms + 999) / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}
