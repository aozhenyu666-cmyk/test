package com.yishou.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.focus.*
import kotlinx.coroutines.delay

@Composable
fun FocusScreen(onBack: () -> Unit, onPlan: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val session by app.focus.state.collectAsStateWithLifecycle()
    val history by app.focus.history.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var waiting by remember { mutableStateOf(false) }
    var tuning by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val owner = LocalLifecycleOwner.current
    fun cancelWait() { app.focus.update { FocusEngine.cancelQuit(it) }; waiting = false }
    val waitingNow by rememberUpdatedState(waiting)
    DisposableEffect(owner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) app.focus.update { FocusEngine.cancelQuit(it) }
            if (event == Lifecycle.Event.ON_START && waitingNow) app.focus.update { FocusEngine.askQuit(it, System.currentTimeMillis()) }
        }
        owner.lifecycle.addObserver(observer)
        onDispose {
            owner.lifecycle.removeObserver(observer)
            app.focus.update { FocusEngine.cancelQuit(it) }
        }
    }
    BackHandler(waiting) { cancelWait() }
    LaunchedEffect(Unit) {
        if (app.focus.active() != null) runCatching { FocusService.start(context) }.onFailure { error = "计时服务没能恢复，请检查后台权限" }
        while (true) { now = System.currentTimeMillis(); app.focus.active(now); delay(1000) }
    }
    Scaffold(topBar = { BackTopBar("专注流", { if (waiting) cancelWait() else onBack() }) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val s = session
            if (s != null) {
                val p = s.phase(now)
                if (waiting) {
                    val left = (FocusEngine.quitRemainingMs(s, now) ?: FocusEngine.QUIT_WAIT_MS).coerceAtMost(FocusEngine.QUIT_WAIT_MS)
                    Text("提前结束 · 等待中", style = MaterialTheme.typography.titleLarge)
                    Text(FocusRuntime.clock(left), style = MaterialTheme.typography.displayLarge)
                    Text("等满才能结束；离开这一页重新计时")
                    if (left == 0L) Button(onClick = {
                        app.focus.update { FocusEngine.quit(it, System.currentTimeMillis()) }
                        waiting = false
                    }) { Text("结束") }
                    OutlinedButton(onClick = { cancelWait() }) { Text("继续专注") }
                } else {
                    Text(if (p.kind == PhaseKind.REST) "休息" else "工作", style = MaterialTheme.typography.titleLarge)
                    Text(FocusRuntime.clock(p.remainingMs(now)), style = MaterialTheme.typography.displayLarge)
                    Text("第 ${p.round} 组 · 已做完 ${s.doneRounds} 组")
                    Text(s.plan.task, style = MaterialTheme.typography.titleLarge)
                    if (s.plan.goal.isNotBlank()) Text("目标：${s.plan.goal}")
                    Text("整场还剩 ${FocusRuntime.clock(s.endsAt - now)}")
                    if (p.kind == PhaseKind.REST) Text("站起来走走，喝口水。手机只能用白名单")
                    OutlinedButton(onClick = { tuning = true }) { Text("换节奏") }
                    Text("延长（总共最多 240 分钟）")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(5, 10, 15).forEach { min ->
                            OutlinedButton(onClick = {
                                if (!app.focus.update { FocusEngine.extend(it, min, System.currentTimeMillis()) }) error = "没能延长，总共最多 240 分钟"
                            }) { Text("+$min 分钟") }
                        }
                    }
                    TextButton(onClick = {
                        app.focus.update { FocusEngine.askQuit(it, System.currentTimeMillis()) }
                        waiting = true
                    }) { Text("提前结束") }
                    FocusRoundMarks(s, now, app)
                }
            } else {
                Text("专注流已结束", style = MaterialTheme.typography.titleLarge)
                history.lastOrNull()?.session?.let { ended ->
                    Text("${ended.plan.task} · 做完 ${ended.doneRounds} / 共 ${FocusRuntime.totalRounds(ended)} 组")
                    FocusRoundMarks(ended, now, app)
                }
                Button(onClick = onPlan) { Text("排一场专注流") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
    if (tuning && session != null) {
        val s = session!!
        var work by remember { mutableStateOf((s.retunes.lastOrNull()?.workMin ?: s.plan.workMin).toString()) }
        var rest by remember { mutableStateOf((s.retunes.lastOrNull()?.restMin ?: s.plan.restMin).toString()) }
        var note by remember { mutableStateOf("") }
        var issue by remember { mutableStateOf<String?>(null) }
        AlertDialog(onDismissRequest = { tuning = false }, title = { Text("换节奏 · 从现在重新排") }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                FocusNumber("每组（分钟）", work) { work = it }
                FocusNumber("休息（分钟）", rest) { rest = it }
                OutlinedTextField(note, { note = it }, label = { Text("一句为什么") })
                Text("总时长不变")
                issue?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        }, confirmButton = { TextButton(onClick = {
            if (note.isBlank()) issue = "写一句为什么换节奏"
            else if (app.focus.update { FocusEngine.retune(it, work.toIntOrNull() ?: -1, rest.toIntOrNull() ?: -1, note, System.currentTimeMillis()) }) tuning = false
            else issue = "每组 3–60 分钟，休息 0–15 分钟"
        }) { Text("就这样换") } }, dismissButton = { TextButton(onClick = { tuning = false }) { Text("返回") } })
    }
}

@Composable private fun FocusRoundMarks(s: FocusSession, now: Long, app: YishouApp) {
    val pending = FocusRuntime.completedRounds(s, now).filter { round -> s.marks.none { it.round == round } }
    pending.reversed().forEach { round ->
        key(s.startedAt, round) {
            var note by remember { mutableStateOf("") }
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("第 $round 组：这一组做完了吗？")
                    OutlinedTextField(note, { note = it }, label = { Text("可选一句") }, modifier = Modifier.fillMaxWidth())
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { app.focus.mark(s.startedAt, round, true, note) }) { Text("做完了") }
                        OutlinedButton(onClick = { app.focus.mark(s.startedAt, round, false, note) }) { Text("没做完") }
                    }
                }
            }
        }
    }
}

@Composable
fun FocusBanner(onOpen: () -> Unit) {
    val app = LocalContext.current.applicationContext as YishouApp
    val session by app.focus.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); app.focus.active(now); delay(1000) } }
    session?.takeIf { it.active(now) }?.let { s ->
        Button(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
            Text("专注中 · 第 ${s.phase(now).round} 组 · 还剩 ${FocusRuntime.clock(s.endsAt - now)}")
        }
    }
}

@Composable
fun FocusGate(onOpen: () -> Unit, onHome: () -> Unit) {
    val app = LocalContext.current.applicationContext as YishouApp
    val session by app.focus.state.collectAsStateWithLifecycle()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { now = System.currentTimeMillis(); app.focus.active(now); delay(1000) } }
    Surface(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("专注中", style = MaterialTheme.typography.headlineLarge)
            val s = session
            Text(if (s != null) "专注中：「${s.plan.task}」· 还剩 ${FocusRuntime.clock(s.endsAt - now)}" else "这一场专注流已结束")
            Button(onClick = onOpen) { Text("回到专注页") }
            OutlinedButton(onClick = onHome) { Text("回桌面") }
        }
    }
}
