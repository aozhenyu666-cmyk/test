package com.yishou.app.ui

import android.app.Application
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.brake.Brake
import com.yishou.app.data.Impulse
import com.yishou.app.data.ImpulseDecision
import com.yishou.app.line.Lines
import com.yishou.app.llm.LlmResult
import com.yishou.app.system.Reminders
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

enum class BrakeStep { DUMP, CUT, WORST, DECIDE, DONE }

data class BrakeState(
    val step: BrakeStep = BrakeStep.DUMP,
    val raw: String = "",
    val split: Brake.Split = Brake.Split(emptyList(), emptyList()),
    /** 最大那条脑补 */
    val fantasy: String = "",
    val proof: String = "",
    val hasProof: Boolean? = null,
    val worst: String = "",
    /** 模型在切（本机已经先切好了一版） */
    val cutting: Boolean = false,
    val id: Long = 0,
    val decision: String = "",
)

class BrakeViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.impulses()
    private val _state = MutableStateFlow(BrakeState())
    val state: StateFlow<BrakeState> = _state.asStateFlow()

    val pending: StateFlow<List<Impulse>> = dao.observePending().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _tally = MutableStateFlow("")
    val tally: StateFlow<String> = _tally.asStateFlow()

    init {
        refreshTally()
    }

    private fun refreshTally() = viewModelScope.launch { _tally.value = Brake.line(Brake.tally(dao.recent())) }

    /** 吐完：本机先切一版马上给你看，模型切得回来（8 秒内）就换成模型的 */
    fun dump(raw: String) {
        val text = raw.trim()
        if (text.isEmpty()) return
        val local = Brake.localSplit(text)
        val fantasy = Brake.biggest(local, text)
        _state.value = BrakeState(step = BrakeStep.CUT, raw = text, split = local, fantasy = fantasy, proof = Brake.localProof(fantasy), cutting = yishou.settings.llm.value.isComplete)
        viewModelScope.launch {
            val id = dao.insert(Impulse(raw = text, facts = Lines.json(local.facts), fantasies = Lines.json(local.fantasies), proof = _state.value.proof, createdAt = System.currentTimeMillis()))
            _state.update { it.copy(id = id) }
            if (!yishou.settings.llm.value.isComplete) return@launch
            val r = withTimeoutOrNull(8_000) { yishou.brakeCoach.split(text) }
            val cut = (r as? LlmResult.Ok)?.value
            if (cut != null && _state.value.step == BrakeStep.CUT) {
                val f = Brake.biggest(cut.split, text)
                _state.update { it.copy(split = cut.split, fantasy = f, proof = cut.proof.ifEmpty { Brake.localProof(f) }, cutting = false) }
            } else {
                _state.update { it.copy(cutting = false) }
            }
        }
    }

    fun proof(has: Boolean) = _state.update { it.copy(hasProof = has, step = BrakeStep.WORST) }

    fun worst(text: String) {
        if (!Brake.worstOk(text)) return
        _state.update { it.copy(worst = text.trim(), step = BrakeStep.DECIDE) }
    }

    /** 自己决定做不做。不拦你；去做的话 30 分钟后回来核对最坏后果出没出现 */
    fun decide(go: Boolean) {
        val s = _state.value
        val now = System.currentTimeMillis()
        val checkAt = if (go) now + Brake.CHECK_AFTER_MS else null
        _state.update { it.copy(decision = if (go) ImpulseDecision.GO else ImpulseDecision.HOLD, step = BrakeStep.DONE) }
        viewModelScope.launch {
            val row = dao.get(s.id) ?: return@launch
            dao.update(
                row.copy(
                    facts = Lines.json(s.split.facts), fantasies = Lines.json(s.split.fantasies), proof = s.proof, hasProof = s.hasProof,
                    worst = s.worst, decision = if (go) ImpulseDecision.GO else ImpulseDecision.HOLD, checkAt = checkAt,
                ),
            )
            if (checkAt != null) Reminders.scheduleImpulseCheck(yishou, checkAt)
            refreshTally()
        }
    }

    fun check(i: Impulse, happened: Boolean) = viewModelScope.launch {
        dao.update(i.copy(happened = happened))
        refreshTally()
    }

    fun again() {
        _state.value = BrakeState()
    }
}

/**
 * 急刹：吐 → 切（事实 / 脑补）→ 证（硬证据有没有）→ 押（30 分钟后最坏会怎样）→ 你自己决定。
 * 不讲道理、不安慰、不拦你。十几秒的输入摩擦，就是把方向盘从杂念手里抢回来的那一下。
 */
@Composable
fun BrakeScreen(onBack: () -> Unit, vm: BrakeViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val pending by vm.pending.collectAsStateWithLifecycle()
    val tally by vm.tally.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("急刹") { onBack() } }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            val now = System.currentTimeMillis()
            pending.filter { (it.checkAt ?: Long.MAX_VALUE) <= now }.take(3).forEach { i -> CheckCard(i, onAnswer = { vm.check(i, it) }) }
            when (s.step) {
                BrakeStep.DUMP -> Dump(onDump = vm::dump)
                BrakeStep.CUT -> Cut(s, onProof = vm::proof)
                BrakeStep.WORST -> Worst(onWorst = vm::worst)
                BrakeStep.DECIDE -> Decide(s, onDecide = vm::decide)
                BrakeStep.DONE -> {
                    Text(if (s.decision == ImpulseDecision.HOLD) "先没做。记下了。" else "去吧。30 分钟后我问你：最坏的后果出现了没有。", style = MaterialTheme.typography.headlineSmall)
                    Text("押的最坏后果：${s.worst}", style = MaterialTheme.typography.bodyLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = onBack) { Text("好") }
                        OutlinedButton(onClick = vm::again) { Text("再刹一个") }
                    }
                }
            }
            if (tally.isNotEmpty()) Text(tally, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun Dump(onDump: (String) -> Unit) {
    var raw by rememberSaveable { mutableStateOf("") }
    Text("脑子里现在是什么？原样倒出来。", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
    Text("不用组织，不用讲理，脏话也行。按麦克风说最快。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    VoiceTextField(raw, { raw = it }, "我现在想……因为他……", Modifier.fillMaxWidth(), minLines = 4)
    Button(onClick = { onDump(raw) }, enabled = raw.isNotBlank(), modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("倒完了，切开") }
}

@Composable
private fun Cut(s: BrakeState, onProof: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.animateContentSize()) {
        Box2("摄像机拍得到的", s.split.facts.ifEmpty { listOf("（一条都没有）") }, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
        Box2("你脑子补的", s.split.fantasies.ifEmpty { listOf("（没认出来——看看上面哪句其实是你猜的）") }, MaterialTheme.colorScheme.errorContainer, MaterialTheme.colorScheme.onErrorContainer)
        if (s.cutting) Text("模型在细切……", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text("要证明「${s.fantasy}」是真的：", style = MaterialTheme.typography.titleMedium)
        Text(s.proof, style = MaterialTheme.typography.bodyLarge)
        Text("这个证据，你现在手里有吗？", style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onProof(true) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("有") }
            Button(onClick = { onProof(false) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("没有，纯属脑补") }
        }
    }
}

@Composable
private fun Box2(title: String, lines: List<String>, bg: Color, fg: Color) {
    Surface(color = bg, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge, color = fg)
            lines.forEach { Text("· $it", style = MaterialTheme.typography.bodyLarge, color = fg) }
        }
    }
}

@Composable
private fun Worst(onWorst: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var left by remember { mutableIntStateOf(Brake.WORST_SECONDS) }
    LaunchedEffect(Unit) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    Text("顺着它现在就去做——30 分钟后，最坏的、看得见的后果是什么？", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
    Text(if (left > 0) "$left 秒，几个字就行" else "超时了，说出来再走", style = MaterialTheme.typography.labelLarge, color = if (left > 3) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
    VoiceTextField(text, { text = it }, "例如：吵翻、删好友、白花八百", Modifier.fillMaxWidth())
    Button(onClick = { onWorst(text) }, enabled = Brake.worstOk(text), modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("押好了") }
}

@Composable
private fun Decide(s: BrakeState, onDecide: (Boolean) -> Unit) {
    Text("现在由你定。", style = MaterialTheme.typography.headlineSmall)
    Text("脑补：${s.fantasy}", style = MaterialTheme.typography.bodyLarge)
    Text("证据：" + if (s.hasProof == true) "你说手里有" else "没有", style = MaterialTheme.typography.bodyLarge)
    Text("最坏：${s.worst}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.error)
    Button(onClick = { onDecide(false) }, modifier = Modifier.fillMaxWidth().height(56.dp)) { Text("先不做，记一笔") }
    OutlinedButton(
        onClick = { onDecide(true) },
        colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
        modifier = Modifier.fillMaxWidth(),
    ) { Text("还是去做（30 分钟后回来核对）") }
}

@Composable
private fun CheckCard(i: Impulse, onAnswer: (Boolean) -> Unit) {
    Surface(color = MaterialTheme.colorScheme.tertiaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("上次急刹后你还是去做了。当时押的最坏后果：", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text("「${i.worst}」", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text("出现了吗？", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { onAnswer(true) }) { Text("出现了") }
                OutlinedButton(onClick = { onAnswer(false) }) { Text("没出现") }
            }
        }
    }
}
