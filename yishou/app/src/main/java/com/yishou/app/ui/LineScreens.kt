package com.yishou.app.ui

import android.Manifest
import android.app.Application
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.Line
import com.yishou.app.data.LineVerdict
import com.yishou.app.data.Move
import com.yishou.app.line.Ask
import com.yishou.app.line.Level
import com.yishou.app.line.LineSession
import com.yishou.app.line.LineState
import com.yishou.app.line.Lines
import com.yishou.app.line.Talk
import com.yishou.app.line.TalkRules
import com.yishou.app.llm.Faces
import com.yishou.app.speech.Heard
import com.yishou.app.speech.ListenPhase
import com.yishou.app.speech.VoiceCommand
import com.yishou.app.speech.VoiceCommands
import com.yishou.app.ui.theme.reducedMotion
import com.yishou.app.window.Speaker
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class LineViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.lines()
    val session: LineSession = yishou.newLine()
    val state: StateFlow<LineState> = session.state

    val lines: StateFlow<List<Line>> = dao.observeLines()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val lineId = MutableStateFlow(0L)
    val moves: StateFlow<List<Move>> = lineId.flatMapLatest { if (it == 0L) flowOf(emptyList()) else dao.observeMoves(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun open(id: Long) = viewModelScope.launch {
        val l = dao.getLine(id) ?: return@launch
        lineId.value = l.id
        session.open(l, yishou.level)
    }

    fun create(title: String, anchor: String, materialId: Long? = null) = viewModelScope.launch {
        val l = session.create(title, anchor, yishou.level, materialId)
        lineId.value = l.id
    }

    fun answer(text: String) = viewModelScope.launch { session.answer(text) }
    fun answerCut(raw: String, cut: String) = viewModelScope.launch { session.answerCut(raw, cut) }
    fun park() = viewModelScope.launch { session.park() }
    fun resume() = session.resume()
    fun close() = viewModelScope.launch {
        session.close()
        lineId.value = 0
    }
    fun leave() {
        session.stop()
        lineId.value = 0
    }

    fun raise() {
        val next = state.value.level.up()
        session.setLevel(next)
        viewModelScope.launch { yishou.settings.updateApp { it.copy(talkLevel = next.name) } }
    }

    fun setLevel(l: Level) {
        session.setLevel(l)
        viewModelScope.launch { yishou.settings.updateApp { it.copy(talkLevel = l.name) } }
    }
}

/**
 * 主线：你现在正在想的那件事，一手一手往下走。
 * 教练每次只问一个问题；你的回答先过本机的规则（口水、先说结论、长度和结构），过了才算一手；
 * 不想答就一级一级降低门槛；每走几手回一次主线。断点随时存着。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LineScreen(lineId: Long?, onBack: () -> Unit, onLive: (Long) -> Unit, onRecite: () -> Unit, vm: LineViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val lines by vm.lines.collectAsStateWithLifecycle()
    val moves by vm.moves.collectAsStateWithLifecycle()
    LaunchedEffect(lineId) { if (lineId != null && lineId > 0) vm.open(lineId) }
    val line = s.line
    BackHandler(enabled = line != null && lineId == null) { vm.leave() }

    Scaffold(topBar = { BackTopBar(line?.title ?: "主线") { if (line != null && lineId == null) vm.leave() else onBack() } }) { padding ->
        if (line == null) {
            LinePicker(lines, onOpen = { vm.open(it) }, onCreate = { t, a -> vm.create(t, a) }, modifier = Modifier.padding(padding))
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { LineHeader(line, s, onLive = { onLive(line.id) }, onLevel = vm::setLevel) }
            item {
                if (s.parked) Parked(onResume = vm::resume, onRecite = onRecite, onClose = { vm.close() })
                else if (com.yishou.app.system.Variant.chalu) DumpCutBox(s, onCut = { raw, cut -> vm.answerCut(raw, cut) }, onRefuse = { vm.answer("想不出") }, onPark = { vm.park() })
                else MoveBox(s, onAnswer = { vm.answer(it) }, onRefuse = { vm.answer("想不出") }, onPark = { vm.park() })
            }
            if (s.canRaise) {
                item {
                    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("连着接住 ${s.streak} 手。加一档强度？", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            TextButton(onClick = vm::raise) { Text("加到${s.level.up().label}") }
                        }
                    }
                }
            }
            if (moves.isNotEmpty()) {
                item { Text("这一局走过的", style = MaterialTheme.typography.titleMedium) }
                items(moves.takeLast(30).reversed(), key = { it.id }) { m -> MoveRow(m) }
            }
            item {
                TextButton(onClick = { vm.close() }) { Text("收起这一局（结论都留着）", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun LineHeader(line: Line, s: LineState, onLive: () -> Unit, onLevel: (Level) -> Unit) {
    val conclusions = Lines.list(line.conclusions)
    val doubts = Lines.list(line.doubts)
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("「${line.anchor}」", style = MaterialTheme.typography.titleLarge)
        if (conclusions.isNotEmpty()) {
            Text("到现在：" + conclusions.takeLast(3).joinToString("；"), style = MaterialTheme.typography.bodyMedium)
        }
        if (doubts.isNotEmpty()) {
            Text("还没弄清：" + doubts.takeLast(2).joinToString("；"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("强度", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Level.entries.forEach { l ->
                FilterChip(selected = s.level == l, onClick = { onLevel(l) }, label = { Text(l.label) }, modifier = Modifier.padding(end = 6.dp))
            }
            Spacer(Modifier.weight(1f))
            OutlinedButton(onClick = onLive) { Text("语音对练") }
        }
    }
}

/** 这一手：问题、你的回答（边打边看规则）、交 / 想不出 / 先放着 */
@Composable
private fun MoveBox(s: LineState, onAnswer: (String) -> Unit, onRefuse: () -> Unit, onPark: () -> Unit) {
    var text by rememberSaveable(s.question, s.moves) { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth().animateContentSize()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (s.say.isNotBlank() && s.say != s.question) {
                Text(s.say.substringBefore(s.question).trim().removeSuffix("下一问：").removeSuffix("再说一次：").removeSuffix("再回答：").trim(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                DieFace(s.face, 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    when (s.ask) {
                        Ask.NORMAL -> Faces.of(s.face)?.short ?: ""
                        Ask.ANCHOR -> "回主线"
                        else -> "降一档"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(s.question, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Medium)
            VoiceTextField(text, { text = it }, if (s.ask == Ask.NORMAL || s.ask == Ask.ANCHOR) "先说结论，再说为什么" else "一个词、一个字都行", Modifier.fillMaxWidth(), minLines = 2)
            val rules = if (s.ask == Ask.NORMAL || s.ask == Ask.ANCHOR) TalkRules.of(s.level) else TalkRules.SMALL
            if (text.isNotBlank()) TalkMeter(text, rules)
            if (s.busy) VortexLoader("教练在看这一手")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(text) }, enabled = text.isNotBlank() && !s.busy, modifier = Modifier.weight(1f).height(50.dp)) { Text("下这一手") }
                OutlinedButton(onClick = onRefuse, enabled = !s.busy, modifier = Modifier.height(50.dp)) { Text("想不出") }
            }
            TextButton(onClick = onPark, enabled = !s.busy) { Text("先放着", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

/** 边打字边看：口水占比、第一句有没有结论、字数 */
@Composable
fun TalkMeter(text: String, rules: TalkRules) {
    val c = Talk.check(text, rules)
    val filler = (c.fillerRatio * 100).toInt()
    val bad = MaterialTheme.colorScheme.error
    val ok = MaterialTheme.colorScheme.tertiary
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("口水 $filler%", style = MaterialTheme.typography.labelMedium, color = if (c.fillerRatio > rules.maxFiller) bad else dim)
            if (rules.lead) Text(if (Talk.lead(text).let { com.yishou.app.think.Words.substance(it) } >= 4) "先说了结论 ✓" else "第一句还没结论", style = MaterialTheme.typography.labelMedium, color = if (Talk.lead(text).let { com.yishou.app.think.Words.substance(it) } >= 4) ok else bad)
            Text("${c.substance}/${rules.budget} 字", style = MaterialTheme.typography.labelMedium, color = if (c.substance > rules.budget) bad else dim)
        }
        c.hint?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = bad) }
    }
}

@Composable
private fun Parked(onResume: () -> Unit, onRecite: () -> Unit, onClose: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("这一手先放着，记下了。", style = MaterialTheme.typography.titleMedium)
            Text("不想想的时候，换个不用想的动作也算没断：背一段。或者过会儿再回来接着下。", style = MaterialTheme.typography.bodyMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onResume) { Text("接着下") }
                OutlinedButton(onClick = onRecite) { Text("去背一段") }
            }
            TextButton(onClick = onClose) { Text("收起这一局") }
        }
    }
}

@Composable
private fun MoveRow(m: Move) {
    val color = when (m.verdict) {
        LineVerdict.OK -> MaterialTheme.colorScheme.tertiary
        LineVerdict.PARKED -> MaterialTheme.colorScheme.outline
        else -> MaterialTheme.colorScheme.error
    }
    val label = when (m.verdict) {
        LineVerdict.OK -> "接住"
        LineVerdict.THIN -> "退回"
        LineVerdict.DRIFT -> "没接住"
        LineVerdict.REFUSED -> "不想答"
        else -> "放着"
    }
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = color)
            if (m.voice) Text("  · 语音", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("问：${m.question}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (m.answer.isNotBlank()) Text("答：${m.answer}", style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
        if (m.gist.isNotBlank()) Text("→ ${m.gist}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
        if (m.note.isNotBlank()) Text(m.note, style = MaterialTheme.typography.labelSmall, color = color)
    }
}

/** 没有在下的局：接着哪一局，或者开一局新的 */
@Composable
private fun LinePicker(lines: List<Line>, onOpen: (Long) -> Unit, onCreate: (String, String) -> Unit, modifier: Modifier = Modifier) {
    var title by rememberSaveable { mutableStateOf("") }
    var anchor by rememberSaveable { mutableStateOf("") }
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(20.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("开一局", style = MaterialTheme.typography.titleMedium)
                    Text("你现在在想、在学、在纠结的那件事。一局只围着一个核心问题转。", style = MaterialTheme.typography.bodySmall)
                    VoiceTextField(title, { title = it }, "这件事（例如：资料分析）", Modifier.fillMaxWidth())
                    VoiceTextField(anchor, { anchor = it }, "核心问题（例如：资料分析怎么从 25 分钟压到 18 分钟？）", Modifier.fillMaxWidth(), minLines = 2)
                    Button(onClick = { onCreate(title, anchor) }, enabled = anchor.isNotBlank() || title.isNotBlank(), modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("开始") }
                }
            }
        }
        val open = lines.filter { it.closedAt == null }
        if (open.isNotEmpty()) {
            item { Text("在下的", style = MaterialTheme.typography.titleMedium) }
            items(open, key = { it.id }) { l -> LineRow(l, onClick = { onOpen(l.id) }) }
        }
        val closed = lines.filter { it.closedAt != null }.take(10)
        if (closed.isNotEmpty()) {
            item { Text("收起的", style = MaterialTheme.typography.titleMedium) }
            items(closed, key = { it.id }) { l -> LineRow(l, onClick = { onOpen(l.id) }) }
        }
    }
}

@Composable
private fun LineRow(l: Line, onClick: () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(l.anchor, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("断点：${l.nextQuestion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 语音对练的阶段 */
enum class LivePhase { IDLE, SPEAKING, LISTENING, THINKING, PAUSED }

data class LiveUi(val phase: LivePhase = LivePhase.IDLE, val heard: String = "", val note: String? = null, val silent: Int = 0)

/**
 * 语音对练：教练念问题 → 自动开始听 → 你说完自动停 → 本机先查口水和拒绝 → 教练念回应和下一问 → 接着听。
 * 手机可以放下，像跟人对话。连着两次不出声，算一次“不想答”，按阶梯降低门槛。
 */
class LiveViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val speaker = Speaker(app)
    val voice = yishou.voice
    val session: LineSession = yishou.newLine()
    val state: StateFlow<LineState> = session.state
    private val _ui = MutableStateFlow(LiveUi())
    val ui: StateFlow<LiveUi> = _ui
    private var loop: Job? = null

    fun open(id: Long) = viewModelScope.launch {
        val l = yishou.database.lines().getLine(id) ?: return@launch
        // 语音对练至少是“中”：口水规则要过
        session.open(l, maxOf(yishou.level, Level.MID))
        start()
    }

    fun start() {
        loop?.cancel()
        _ui.update { it.copy(phase = LivePhase.SPEAKING, note = null) }
        loop = viewModelScope.launch { run() }
    }

    private suspend fun say(text: String) {
        _ui.update { it.copy(phase = LivePhase.SPEAKING) }
        suspendCancellableCoroutine { cont ->
            speaker.speak(text) { if (cont.isActive) cont.resume(Unit) }
            cont.invokeOnCancellation { speaker.stop() }
        }
    }

    private suspend fun run() {
        var toSay = state.value.say.ifBlank { state.value.question }
        while (true) {
            if (state.value.parked) {
                say(com.yishou.app.line.Resist.PARK)
                _ui.update { it.copy(phase = LivePhase.PAUSED) }
                return
            }
            say(toSay)
            if (!voice.hasPermission()) {
                _ui.update { it.copy(phase = LivePhase.PAUSED, note = "要先允许麦克风") }
                return
            }
            _ui.update { it.copy(phase = LivePhase.LISTENING, heard = "") }
            when (val h = voice.listen()) {
                is Heard.Text -> {
                    _ui.update { it.copy(heard = h.text, silent = 0) }
                    when (VoiceCommands.parse(h.text)) {
                        VoiceCommand.PAUSE, VoiceCommand.CANCEL -> {
                            _ui.update { it.copy(phase = LivePhase.PAUSED) }
                            return
                        }
                        VoiceCommand.REPEAT -> {
                            toSay = state.value.question
                            continue
                        }
                        else -> {}
                    }
                    _ui.update { it.copy(phase = LivePhase.THINKING) }
                    session.answer(h.text, voice = true)
                    toSay = state.value.say
                }
                is Heard.Nothing -> {
                    val n = _ui.value.silent + 1
                    _ui.update { it.copy(silent = n) }
                    if (n >= 2) {
                        _ui.update { it.copy(silent = 0, phase = LivePhase.THINKING) }
                        session.answer("", voice = true)
                        toSay = "两次没出声，降一档。" + state.value.say
                    } else {
                        toSay = "我在听。" + state.value.question
                    }
                }
                is Heard.Failed -> {
                    _ui.update { it.copy(phase = LivePhase.PAUSED, note = h.message) }
                    return
                }
            }
        }
    }

    /** 说完了：马上停，拿去听写 */
    fun done() = voice.finish()

    fun pause() {
        loop?.cancel()
        voice.cancel()
        speaker.stop()
        _ui.update { it.copy(phase = LivePhase.PAUSED) }
    }

    fun resume() {
        if (state.value.parked) session.resume()
        start()
    }

    override fun onCleared() {
        loop?.cancel()
        voice.cancel()
        speaker.shutdown()
    }
}

/**
 * 语音对练页：黑底，中间一个“日冕”——教练说话时外圈舒展，你说话时随音量起伏，等教练想时慢慢转。
 * 下面只有一行字：现在的问题；你刚说的；规则没过的那条。
 */
@Composable
fun LiveScreen(lineId: Long, onBack: () -> Unit, vm: LiveViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val s by vm.state.collectAsStateWithLifecycle()
    val level by vm.voice.level.collectAsStateWithLifecycle()
    val listen by vm.voice.phase.collectAsStateWithLifecycle()
    val mic = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.resume() }
    var asked by remember { mutableStateOf(false) }
    LaunchedEffect(lineId) {
        if (!vm.voice.hasPermission() && !asked) {
            asked = true
            mic.launch(Manifest.permission.RECORD_AUDIO)
        }
        vm.open(lineId)
    }
    DisposableEffect(Unit) { onDispose { vm.pause() } }
    val night = Color(0xFF0B1020)
    val gold = Color(0xFFD9B44A)
    Box(Modifier.fillMaxSize().background(night)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(s.line?.anchor.orEmpty(), color = Color(0xFFE6E4DC), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                TextButton(onClick = { vm.pause(); onBack() }) { Text("结束", color = gold) }
            }
            Spacer(Modifier.height(12.dp))
            Corona(ui.phase, if (listen == ListenPhase.LISTENING) level else 0f, gold, Modifier.size(220.dp).clickable {
                if (ui.phase == LivePhase.LISTENING) vm.done()
            })
            Text(
                when (ui.phase) {
                    LivePhase.SPEAKING -> "教练在说"
                    LivePhase.LISTENING -> "在听你说（说完停一下，或点一下圆）"
                    LivePhase.THINKING -> "在看这一手"
                    LivePhase.PAUSED -> "停着"
                    LivePhase.IDLE -> ""
                },
                color = Color(0xFF9AA3B5), style = MaterialTheme.typography.labelLarge,
            )
            Text(s.question, color = Color(0xFFE6E4DC), style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            if (ui.heard.isNotBlank()) Text("你：${ui.heard}", color = Color(0xFF9AA3B5), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            s.check?.hint?.let { Text(it, color = Color(0xFFF0938A), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center) }
            ui.note?.let { Text(it, color = Color(0xFFF0938A), style = MaterialTheme.typography.bodySmall) }
            Spacer(Modifier.weight(1f))
            Text("接住 ${s.caught} 手 · 强度 ${s.level.label}", color = Color(0xFF9AA3B5), style = MaterialTheme.typography.labelMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (ui.phase == LivePhase.PAUSED) {
                    Button(onClick = { if (vm.voice.hasPermission()) vm.resume() else mic.launch(Manifest.permission.RECORD_AUDIO) }) { Text("接着练") }
                } else {
                    OutlinedButton(onClick = vm::pause) { Text("暂停", color = gold) }
                }
            }
            Text("说“暂停”“再说一遍”也行。", color = Color(0xFF4A5568), style = MaterialTheme.typography.labelSmall)
        }
    }
}

/** 日冕：一圈细线围着中心，说话时随音量舒展，教练说话时有节律地呼吸，等待时慢慢转 */
@Composable
private fun Corona(phase: LivePhase, level: Float, gold: Color, modifier: Modifier = Modifier) {
    val still = reducedMotion()
    val time = rememberSeconds(still)
    Canvas(modifier) {
        val t = time.value
        val c = center
        val r0 = size.minDimension * 0.22f
        val breath = when (phase) {
            LivePhase.SPEAKING -> 0.5f + 0.5f * sin(t * 4.2f)
            LivePhase.LISTENING -> level.coerceIn(0f, 1f)
            LivePhase.THINKING -> 0.3f
            else -> 0.1f
        }
        val spin = if (phase == LivePhase.THINKING) t * 1.2f else t * 0.15f
        drawCircle(Brush.radialGradient(listOf(gold.copy(alpha = 0.35f + 0.25f * breath), Color.Transparent), center = c, radius = r0 * (2.2f + breath)), radius = r0 * (2.2f + breath), center = c)
        val rays = 72
        for (i in 0 until rays) {
            val a = i * 2f * PI.toFloat() / rays + spin
            val wobble = 0.5f + 0.5f * sin(i * 1.7f + t * 2.3f)
            val len = r0 * (0.25f + breath * (0.6f + 0.6f * wobble))
            val start = androidx.compose.ui.geometry.Offset(c.x + r0 * 1.05f * cos(a), c.y + r0 * 1.05f * sin(a))
            val end = androidx.compose.ui.geometry.Offset(c.x + (r0 * 1.05f + len) * cos(a), c.y + (r0 * 1.05f + len) * sin(a))
            drawLine(gold.copy(alpha = 0.25f + 0.5f * wobble * breath), start, end, strokeWidth = 1.5.dp.toPx(), cap = StrokeCap.Round)
        }
        drawCircle(gold, r0, c)
        drawCircle(Color(0xFF0B1020).copy(alpha = 0.25f), r0 * 0.6f, c)
    }
}
