package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.EchoKind
import com.yishou.app.data.ScrollQuizRow
import com.yishou.app.llm.LlmResult
import com.yishou.app.scroll.Judged
import com.yishou.app.scroll.Lens
import com.yishou.app.scroll.Quiz
import com.yishou.app.scroll.ScrollQuiz
import com.yishou.app.scroll.Verdict
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RedoState(
    val row: ScrollQuizRow? = null,
    val missing: Boolean = false,
    val busy: Boolean = false,
    /** 判完了；null 表示判卷接口出错 */
    val judged: Judged? = null,
    val done: Boolean = false,
    val saved: Boolean = false,
    /** 回的是套话：正在追问的这一刀 */
    val cutAsk: String? = null,
    val cutHint: String? = null,
    val cutStep: Int = 0,
    val cutTotal: Int = 0,
)

/** 重想：刷着练里没接住的一问，静下来再答一次 */
class RedoViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.scrollQuizzes()
    private val _state = MutableStateFlow(RedoState())
    val state: StateFlow<RedoState> = _state.asStateFlow()

    fun open(id: Long) = viewModelScope.launch {
        if (_state.value.row?.id == id) return@launch
        val row = dao.get(id)
        _state.value = RedoState(row = row, missing = row == null)
    }

    private fun quiz(r: ScrollQuizRow) = Quiz(r.seen, r.question, Lens.entries.firstOrNull { it.name == r.lens } ?: Lens.CLAIM, r.reference, r.text)

    private var drill: com.yishou.app.sharp.Drill? = null

    /** 交一句。套话先追问几刀再判，追问出来的最多算“差一点” */
    fun submit(answer: String) {
        val r = _state.value.row ?: return
        if (_state.value.busy || _state.value.done) return
        val d = drill
        var text = answer
        var chased = false
        if (d != null) {
            d.reply(answer)
            if (!d.done) {
                _state.update { it.copy(cutAsk = d.current?.ask, cutHint = d.hint, cutStep = d.index) }
                return
            }
            drill = null
            text = d.transcript()
            chased = true
            _state.update { it.copy(cutAsk = null, cutHint = null) }
        } else {
            val h = com.yishou.app.sharp.Sharp.hollow(answer)
            if (h != null) {
                val nd = com.yishou.app.sharp.Drill(answer, com.yishou.app.sharp.Sharp.cuts(h, r.text.isNotBlank()), r.text)
                drill = nd
                _state.update { it.copy(cutAsk = nd.current?.ask, cutHint = null, cutStep = 0, cutTotal = nd.cuts.size) }
                return
            }
        }
        _state.update { it.copy(busy = true) }
        viewModelScope.launch {
            val q = quiz(r)
            val raw = (if (chased) null else ScrollQuiz.quick(text)) ?: when (val res = yishou.scrollJudge.complete(ScrollQuiz.judgeSystem(), ScrollQuiz.judgeUser(q, text), "重想判")) {
                is LlmResult.Ok -> ScrollQuiz.parseJudge(res.value) ?: Judged(Verdict.NEAR, "")
                is LlmResult.Err -> null
            }
            val judged = if (chased && raw?.verdict == Verdict.HIT) Judged(Verdict.NEAR, "追问出来的，下次第一句就说到这儿。" + raw?.line.orEmpty()) else raw
            dao.redo(r.id, judged?.verdict?.name.orEmpty(), System.currentTimeMillis())
            _state.update { it.copy(busy = false, judged = judged, done = true) }
        }
    }

    /** 自己写一句下次怎么想，进回响队列 */
    fun keep(rule: String) = viewModelScope.launch {
        // 记在刷到它的那个应用上：下次打开这个应用，入口先把这句推给你
        yishou.echo(rule, EchoKind.RULE, _state.value.row?.pkg.orEmpty())
        _state.update { it.copy(saved = true) }
    }
}

@Composable
fun RedoScreen(id: Long, onBack: () -> Unit, vm: RedoViewModel = viewModel()) {
    LaunchedEffect(id) { vm.open(id) }
    val s by vm.state.collectAsStateWithLifecycle()
    var answer by rememberSaveable(id) { mutableStateOf("") }
    var rule by rememberSaveable(id) { mutableStateOf("") }
    var showText by rememberSaveable(id) { mutableStateOf(false) }
    Scaffold(topBar = { BackTopBar("重想一次", onBack) }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val r = s.row
            when {
                s.missing -> {
                    Text("这一问找不到了。")
                    Button(onClick = onBack) { Text("回去") }
                }
                r == null -> Text("正在找那一问……")
                else -> {
                    Text("刷到：${r.seen.ifBlank { "一条内容" }}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (r.text.isNotBlank()) {
                        TextButton(onClick = { showText = !showText }) { Text(if (showText) "收起屏幕上的字" else "看屏幕上的字") }
                        if (showText) {
                            Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth()) {
                                Text(r.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(12.dp))
                            }
                        }
                    }
                    val lens = Lens.entries.firstOrNull { it.name == r.lens }
                    if (lens != null) Text(lens.label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(r.question, style = MaterialTheme.typography.headlineSmall)
                    if (r.answer.isNotBlank() && r.answer != "不知道") Text("当时你说：${r.answer}", style = MaterialTheme.typography.bodySmall)
                    if (!s.done) {
                        val chasing = s.cutAsk != null
                        if (chasing) {
                            Text("追问 ${s.cutStep + 1}/${s.cutTotal}：${s.cutAsk}", style = MaterialTheme.typography.titleSmall)
                            s.cutHint?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                        }
                        val pulse = idlePulse(answer, listOf("先只说一件事：它最想让你信哪一句？", "抄一句屏幕上的字也行。"))
                        pulse?.let { Text(it, color = MaterialTheme.colorScheme.tertiary) }
                        VoiceTextField(answer, { answer = it }, if (chasing) "这一刀" else "这次怎么想", Modifier.fillMaxWidth(), minLines = 2, enabled = !s.busy)
                        Button(
                            onClick = {
                                val before = s.cutStep
                                vm.submit(answer)
                                // 收下了这一刀（或者进了追问）就清空，好写下一刀；没收下留着改
                                if (vm.state.value.cutStep != before || vm.state.value.cutAsk != s.cutAsk) answer = ""
                            },
                            enabled = answer.isNotBlank() && !s.busy,
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(if (s.busy) "判着……" else if (chasing) "交这一刀" else "交")
                        }
                    } else {
                        val j = s.judged
                        Text(j?.verdict?.label ?: "这次没判成", style = MaterialTheme.typography.titleLarge)
                        if (j != null && j.line.isNotBlank()) Text(j.line)
                        if (r.reference.isNotBlank()) Text("参考：${r.reference}", style = MaterialTheme.typography.bodyMedium)
                        if (!s.saved) {
                            Text("用自己的话收一句：下次刷到这种，先想什么？（下次打开这个应用时，入口先把这句推给你）", style = MaterialTheme.typography.bodySmall)
                            VoiceTextField(rule, { rule = it }, "下次遇到……先……", Modifier.fillMaxWidth())
                            Button(onClick = { vm.keep(rule) }, enabled = com.yishou.app.line.Echoes.usable(rule), modifier = Modifier.fillMaxWidth()) { Text("收下这句") }
                        } else {
                            Text("收好了。")
                        }
                        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text("回去") }
                    }
                }
            }
        }
    }
}
