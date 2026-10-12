package com.yishou.app.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.card.CardSession
import com.yishou.app.card.Progress
import com.yishou.app.card.SessionState
import com.yishou.app.card.Step
import com.yishou.app.data.CardMode
import com.yishou.app.llm.LlmResult
import com.yishou.app.system.ExternalGrace
import com.yishou.app.system.Haptics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class CardsViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val session: CardSession = yishou.newSession()
    val state: StateFlow<SessionState> = session.state

    private val _warming = MutableStateFlow(false)
    val warming: StateFlow<Boolean> = _warming.asStateFlow()
    private val _importing = MutableStateFlow<String?>(null)
    /** 拍题整理中 / 失败原因 */
    val importing: StateFlow<String?> = _importing.asStateFlow()

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
                withContext(Dispatchers.IO) { com.yishou.app.system.ImageEncoder.toDataUrl(yishou, uri) }
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
            importText(text)
        }
    }

    /** 一段题目文字（素材库里像题的那种）直接整理成题卡来做 */
    fun importText(text: String) {
        viewModelScope.launch {
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
 * 题卡：答案在出题时就定死的单选题，一组几张。只是练思维的一种练法。
 * [materialId] 不为空时，把素材库里那一段（像一道题）整理成题卡来做。
 */
@Composable
fun CardsScreen(onBack: () -> Unit, materialId: Long? = null, vm: CardsViewModel = viewModel()) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val s by vm.state.collectAsStateWithLifecycle()
    val warming by vm.warming.collectAsStateWithLifecycle()
    val importing by vm.importing.collectAsStateWithLifecycle()
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    var confirmStop by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::importPhoto) }
    var started by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        if (started || s.active) return@LaunchedEffect
        started = true
        if (materialId != null) {
            app.database.think().getMaterial(materialId)?.let { vm.importText(it.text) }
        } else {
            vm.start(prefs.warmup)
        }
    }
    fun leave() {
        if (!s.active || s.done || s.results.isEmpty()) {
            vm.stop()
            onBack()
        } else {
            confirmStop = true
        }
    }

    androidx.activity.compose.BackHandler { leave() }

    Scaffold(topBar = { BackTopBar("题卡") { leave() } }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            when {
                warming -> WarmupPanel(ready = s.round != null || s.error != null, onDone = { vm.warmupDone() })
                s.active && s.done -> SetDone(s, onAgain = { vm.stop(); vm.start(warm = false) }, onHome = { vm.stop(); onBack() })
                s.active -> {
                    if (s.loading || (s.round == null && s.error == null)) MatrixLoader("教练在出题、核对答案")
                    s.error?.let {
                        Text("出不了题：$it", color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = { vm.retry() }) { Text("重试") }
                    }
                    if (!s.loading && s.round != null) CardRoundView(s, vm.actions)
                    val r = s.round
                    if (r != null && r.step == Step.REVEAL && !s.needsKey && !s.done) {
                        Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("下一张") }
                    }
                }
            }
            importing?.let {
                if (it.startsWith("正在")) MatrixLoader(it) else {
                    Text(it, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = vm::dismissImport) { Text("知道了") }
                }
            }
            if (!s.active && importing == null && started) {
                Text("这一组收起了。", style = MaterialTheme.typography.bodyLarge)
                Button(onClick = { vm.start(warm = false) }) { Text("再练一组（${prefs.setCards} 张）") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    ExternalGrace.open()
                    picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                }) { Text("拍一道真题来做") }
                Spacer(Modifier.width(4.dp))
                Text("核对过官方答案才计分", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(80.dp))
        }
    }

    if (confirmStop) {
        AlertDialog(
            onDismissRequest = { confirmStop = false },
            title = { Text("先收起这一组？") },
            text = { Text("做完的几张都记着。没做完的这一组不算进今天的组数。") },
            confirmButton = { TextButton(onClick = { confirmStop = false; vm.stop(); onBack() }) { Text("收起") } },
            dismissButton = { TextButton(onClick = { confirmStop = false }) { Text("接着做") } },
        )
    }
}

@Composable
private fun SetDone(s: SessionState, onAgain: () -> Unit, onHome: () -> Unit) {
    val xs = s.results
    val stats = Progress.stats(xs)
    Spacer(Modifier.height(8.dp))
    Text("这一组做完了", style = MaterialTheme.typography.headlineMedium)
    androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        SeedSpiral(xs.map { Seed(MarkKind.CARD, it.firstCorrect && it.help == 0) }, Modifier.fillMaxWidth(0.6f))
    }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Line("首答对", Progress.Stats.ratio(stats.firstCorrect, stats.total))
        Line("查完再改", "改对 ${stats.changedRight}，改错 ${stats.changedWrong}")
        Line("把握高的对了", Progress.Stats.ratio(stats.highConfCorrect, stats.highConf))
        Line("自己完成", Progress.Stats.ratio(stats.independent, stats.total))
    }
    Button(onClick = onAgain, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("再来一组") }
    TextButton(onClick = onHome) { Text("回去") }
}

@Composable
private fun Line(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.titleMedium)
    }
}
