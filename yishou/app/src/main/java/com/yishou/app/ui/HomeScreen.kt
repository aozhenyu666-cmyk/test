package com.yishou.app.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.card.CardSession
import com.yishou.app.card.Conf
import com.yishou.app.card.Progress
import com.yishou.app.card.SessionState
import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CardMode
import com.yishou.app.llm.LlmResult
import com.yishou.app.settings.AppZone
import com.yishou.app.system.ExternalGrace
import com.yishou.app.system.Haptics
import com.yishou.app.ui.theme.StoneMark
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val session: CardSession = yishou.newSession()
    val state: StateFlow<SessionState> = session.state

    private val _warming = MutableStateFlow(false)
    val warming: StateFlow<Boolean> = _warming.asStateFlow()
    private val _importing = MutableStateFlow<String?>(null)
    /** 拍题整理中 / 失败原因 */
    val importing: StateFlow<String?> = _importing.asStateFlow()

    private val dayStart get() = WindowClock.startOfDay(WindowClock.today(System.currentTimeMillis(), AppZone.zone), AppZone.zone)

    /** 今天做过的题卡（首页那一枝） */
    val today: StateFlow<List<CardAttempt>> = yishou.database.cards().observeAttemptsSince(dayStart)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun start(warm: Boolean) {
        _warming.value = warm
        viewModelScope.launch { session.start(CardMode.HOME, null, yishou.settings.app.value.setCards) }
    }

    fun warmupDone() {
        _warming.value = false
    }

    val actions = CardActions(
        bet = { o, c -> act { session.bet(o, c) } },
        evidence = { e -> act { session.evidence(e) } },
        check = { o, f -> act { session.check(o, f) } },
        change = { o -> act { session.change(o) } },
        hint = { act { session.hint() } },
        cause = { c -> act { session.cause(c) } },
        key = { o -> act { session.officialKey(o) } },
        hide = { session.hide() },
        note = { session.note(it) },
    )

    private fun act(block: suspend () -> Unit) {
        viewModelScope.launch {
            val before = session.state.value.results.size
            block()
            val s = session.state.value
            if (s.results.size > before) {
                Haptics.play(yishou, if (s.results.last().firstCorrect) Haptics.Kind.PASS else Haptics.Kind.REDO)
                yishou.prefetchCards()
            }
        }
    }

    fun next() = viewModelScope.launch { session.next() }

    fun retry() = viewModelScope.launch { session.deal() }

    fun stop() = session.stop()

    /** 拍一道真题：转写整道题 → 整理成题卡 → 直接做（揭晓时点官方答案才计分） */
    fun importPhoto(uri: Uri) {
        if (!yishou.settings.vision.value.isComplete) {
            _importing.value = "还没有配置识图模型。到 设置 → 识图模型 里填写后才能拍题。"
            return
        }
        viewModelScope.launch {
            _importing.value = "正在识别图片……"
            val dataUrl = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { com.yishou.app.system.ImageEncoder.toDataUrl(yishou, uri) }
            } catch (e: Exception) {
                _importing.value = "读取图片失败：${e.message ?: e.javaClass.simpleName}"
                return@launch
            }
            val text = when (val t = yishou.vision.transcribeQuestion(dataUrl)) {
                is LlmResult.Err -> {
                    _importing.value = "识图失败：${t.error.message}"
                    return@launch
                }
                is LlmResult.Ok -> t.value
            }
            _importing.value = "正在整理成题卡……"
            when (val r = yishou.cardEngine.import(text)) {
                is LlmResult.Err -> _importing.value = "没整理成：${r.error.message}"
                is LlmResult.Ok -> {
                    if (!session.state.value.active) session.start(CardMode.HOME, null, 1, deal = false)
                    session.useDealt(r.value, checks = 1)
                    _importing.value = null
                }
            }
        }
    }

    fun dismissImport() {
        _importing.value = null
    }
}

/**
 * 首页：只有今天这一枝和一个按钮。点“练一组”就进题卡，其余都收在主控台和设置里。
 */
@Composable
fun HomeScreen(onOpenSettings: () -> Unit, onOpenWindow: () -> Unit, vm: HomeViewModel = viewModel()) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val s by vm.state.collectAsStateWithLifecycle()
    val warming by vm.warming.collectAsStateWithLifecycle()
    val today by vm.today.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val llm by app.settings.llm.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importPhoto) }

    Box(Modifier.fillMaxSize()) {
        Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {}
        if (!s.active) TideBackdrop(Modifier.fillMaxWidth().height(220.dp).align(Alignment.BottomCenter))
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StoneMark(24.dp)
                Spacer(Modifier.width(10.dp))
                Text("一手", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                if (s.active) {
                    IconButton(onClick = { if (s.done || s.results.isEmpty()) vm.stop() else confirmStop = true }) {
                        Icon(Icons.Filled.Close, contentDescription = "收起")
                    }
                } else {
                    IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Settings, contentDescription = "设置") }
                }
            }

            when {
                warming -> WarmupPanel(ready = s.round != null || s.error != null, onDone = { vm.warmupDone() })
                s.active && s.done -> SetDone(s, onAgain = { vm.stop(); vm.start(warm = false) }, onHome = vm::stop)
                s.active -> {
                    if (s.loading || (s.round == null && s.error == null)) MatrixLoader("教练在出题、核对答案")
                    s.error?.let {
                        Text("出不了题：$it", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.retry() }) { Text("重试") }
                    }
                    if (!s.loading && s.round != null) CardRoundView(s, vm.actions)
                    val r = s.round
                    if (r != null && r.step == com.yishou.app.card.Step.REVEAL && !s.needsKey && !s.done) {
                        Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("下一张") }
                    }
                }
                else -> Landing(
                    today = today,
                    setCards = prefs.setCards,
                    configured = llm.isComplete,
                    importing = importing,
                    onStart = { vm.start(prefs.warmup) },
                    onPhoto = {
                        ExternalGrace.open()
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                    onWindow = onOpenWindow,
                    onSettings = onOpenSettings,
                    onDismissImport = vm::dismissImport,
                )
            }
            Spacer(Modifier.height(120.dp))
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("先收起这一组？") },
            text = { Text("做完的几张都记着。没做完的这一组不算进今天的组数。") },
            confirmButton = { TextButton(onClick = { confirmStop = false; vm.stop() }) { Text("收起") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("接着做") } },
        )
    }
}

@Composable
private fun Landing(
    today: List<CardAttempt>,
    setCards: Int,
    configured: Boolean,
    importing: String?,
    onStart: () -> Unit,
    onPhoto: () -> Unit,
    onWindow: () -> Unit,
    onSettings: () -> Unit,
    onDismissImport: () -> Unit,
) {
    val done = today.filter { it.verified }
    val right = done.count { it.firstCorrect && it.help == 0 }
    Spacer(Modifier.height(36.dp))
    Text(AppZone.format("M月d日", System.currentTimeMillis()), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text(
        if (done.isEmpty()) "今天还没落子" else "今天 ${done.size} 张，自己做对 $right 张",
        style = MaterialTheme.typography.headlineMedium,
    )
    TodayBranch(done.map { it.firstCorrect && it.help == 0 }, target = setCards * 2)
    val high = done.filter { it.conf == Conf.HIGH.label }
    if (high.isNotEmpty()) {
        Text(
            "说“把握高”的 ${high.size} 张里对了 ${high.count { it.firstCorrect }} 张",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(12.dp))
    if (!configured) {
        Text("还没有填写大模型接口，出不了题。", color = MaterialTheme.colorScheme.error)
        Button(onClick = onSettings) { Text("去设置") }
        return
    }
    Button(onClick = onStart, modifier = Modifier.fillMaxWidth().height(60.dp)) {
        Text("练一组（$setCards 张）", style = MaterialTheme.typography.titleMedium)
    }
    Text(
        "每张四步：先押一个答案和把握，再点出依据句，多查一个没选的，最后揭晓。判对错只看答案和依据。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        TextButton(onClick = onPhoto) { Text("拍一道题") }
        TextButton(onClick = onWindow) { Text("陪练窗口") }
    }
    importing?.let {
        if (it.startsWith("正在")) MatrixLoader(it) else {
            Text(it, color = MaterialTheme.colorScheme.error)
            TextButton(onClick = onDismissImport) { Text("知道了") }
        }
    }
}

@Composable
private fun SetDone(s: SessionState, onAgain: () -> Unit, onHome: () -> Unit) {
    val xs = s.results
    val stats = Progress.stats(xs)
    Spacer(Modifier.height(24.dp))
    Text("这一组做完了", style = MaterialTheme.typography.headlineMedium)
    TodayBranch(xs.map { it.firstCorrect && it.help == 0 }, target = xs.size)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Line("首答对", Progress.Stats.ratio(stats.firstCorrect, stats.total))
        Line("查完再改", "改对 ${stats.changedRight}，改错 ${stats.changedWrong}")
        Line("把握高的对了", Progress.Stats.ratio(stats.highConfCorrect, stats.highConf))
        Line("自己完成", Progress.Stats.ratio(stats.independent, stats.total))
    }
    Button(onClick = onAgain, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("再来一组") }
    TextButton(onClick = onHome) { Text("回首页") }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
