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
import com.yishou.app.card.SessionState
import com.yishou.app.card.Step
import com.yishou.app.settings.AppPrefs
import com.yishou.app.settings.AppZone
import com.yishou.app.speech.ListenPhase
import com.yishou.app.ui.BackTopBar
import com.yishou.app.ui.CardRoundView
import com.yishou.app.ui.MatrixLoader
import com.yishou.app.ui.TodayBranch
import com.yishou.app.ui.FlipClock
import com.yishou.app.ui.ThinProgress
import com.yishou.app.ui.VoiceNote
import com.yishou.app.ui.VoiceRings
import com.yishou.app.ui.WarmupPanel
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
                val c by vm.card.collectAsStateWithLifecycle()
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
private fun WindowScreen(s: WindowState, c: SessionState, vm: WindowViewModel, onBack: () -> Unit, onOpenApp: () -> Unit) {
    var confirmStop by remember { mutableStateOf(false) }
    var pauseMenu by remember { mutableStateOf(false) }
    var showPanels by remember { mutableStateOf(false) }
    val app = LocalContext.current.applicationContext as YishouApp
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val change: ((AppPrefs) -> AppPrefs) -> Unit = { f -> app.settings.updateApp(f) }
    val level by vm.level.collectAsStateWithLifecycle()
    val micPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> vm.onMicPermission(ok) }
    LaunchedEffect(s.needMic) {
        if (s.needMic) micPermission.launch(Manifest.permission.RECORD_AUDIO)
    }

    Scaffold(topBar = { BackTopBar("陪练窗口", onBack) }) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {}
            Column(
                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (s.loading) {
                    MatrixLoader("准备窗口")
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
                    Text("窗口结束", style = MaterialTheme.typography.headlineSmall)
                    Text("本窗口练完 ${s.setsDone} 组。做过的每一张都记着。")
                    Button(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("返回") }
                    return@Column
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    FlipClock(s.remainingMs, tile = 52.dp, modifier = Modifier.weight(1f))
                    Column(horizontalAlignment = Alignment.End) {
                        Text("练完 ${s.setsDone} 组", style = MaterialTheme.typography.titleSmall)
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

                val r = c.round
                when {
                    s.warmingUp -> WarmupPanel(ready = r != null || c.error != null, onDone = { vm.warmupDone() })
                    s.paused -> PausedCard(s, vm::resume)
                    c.done -> {
                        Text("这一组做完了", style = MaterialTheme.typography.headlineSmall)
                        TodayBranch(c.results.map { it.firstCorrect && it.help == 0 }, target = c.results.size)
                        Text("自己做对 ${c.results.count { it.firstCorrect && it.help == 0 }} / ${c.results.size} 张", style = MaterialTheme.typography.bodyLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Button(onClick = vm::againAfterSet) { Text("再来一组") }
                            OutlinedButton(onClick = vm::restAfterSet) { Text("歇 ${s.restMinutes} 分钟") }
                        }
                    }
                    c.error != null && r == null -> {
                        Text("出不了题：${c.error}", color = MaterialTheme.colorScheme.error)
                        OutlinedButton(onClick = vm::retry) { Text("重试") }
                    }
                    r == null || c.loading -> MatrixLoader("教练在出题、核对答案")
                    else -> {
                        if (s.voiceOn) VoiceStage(s, level, onTap = vm::micTap)
                        CardRoundView(c, vm.actions)
                        if (r.step == Step.REVEAL && !c.needsKey) {
                            Button(onClick = vm::next, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("下一张") }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(onClick = vm::thinking, modifier = Modifier.weight(1f)) { Text("我在想") }
                            Box(Modifier.weight(1f)) {
                                OutlinedButton(onClick = { pauseMenu = true }, modifier = Modifier.fillMaxWidth()) { Text("先停") }
                                DropdownMenu(expanded = pauseMenu, onDismissRequest = { pauseMenu = false }) {
                                    listOf(5, 10, 15, 30).forEach { m ->
                                        DropdownMenuItem(text = { Text("休息 $m 分钟") }, onClick = { pauseMenu = false; vm.pause(m) })
                                    }
                                }
                            }
                        }
                        if (s.reminders > 0 || s.pulled > 0) {
                            Text(
                                if (s.pulled > 0) "已经把你拉回来 ${s.pulled} 次。说一句也行：“提示”“先停”。" else "已提醒 ${s.reminders} 次",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (s.noResponse) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
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
            text = { Text("做过的每一张都已保存。结束后思考页恢复正常放行。") },
            confirmButton = { TextButton(onClick = { confirmStop = false; vm.stopEarly() }) { Text("结束") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("继续陪练") } },
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
 * 点一下：教练在说就打断、开始听；正在听就当说完了。
 */
@Composable
private fun VoiceStage(s: WindowState, level: Float, onTap: () -> Unit) {
    val label = when {
        s.speaking -> "教练在说，点一下直接开口"
        s.listen == ListenPhase.LISTENING -> "在听，说完停一下就行"
        s.listen == ListenPhase.TRANSCRIBING -> "在听写"
        else -> "点一下，开口作答"
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(84.dp).clickable(onClick = onTap), contentAlignment = Alignment.Center) {
            VoiceRings(level, s.listen, s.speaking, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.titleSmall)
            VoiceNote(s.voiceNote)
        }
    }
}

private fun formatRemaining(ms: Long): String {
    val total = (ms + 999) / 1000
    return "%02d:%02d".format(total / 60, total % 60)
}
