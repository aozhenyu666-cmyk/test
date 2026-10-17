package com.yishou.app.ui

import android.app.Application
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.card.Card
import com.yishou.app.data.CardSource
import com.yishou.app.data.EchoKind
import com.yishou.app.data.Soup
import com.yishou.app.line.Lines
import com.yishou.app.outside.AiApps
import com.yishou.app.soup.Reply
import com.yishou.app.soup.SoupPhase
import com.yishou.app.soup.SoupSession
import com.yishou.app.soup.SoupState
import com.yishou.app.soup.Soups
import com.yishou.app.think.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SoupViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.soups()
    val session = SoupSession(if (yishou.settings.llm.value.isComplete) yishou.soupHost else null)
    val state: StateFlow<SoupState> = session.state

    val stats: StateFlow<String> = dao.observe().map { Soups.line(Soups.stats(it)) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")
    private val _empty = MutableStateFlow(false)
    val empty: StateFlow<Boolean> = _empty.asStateFlow()
    /** 去了 ChatGPT：回来直接亮选项 */
    private val _outside = MutableStateFlow<String?>(null)
    val outside: StateFlow<String?> = _outside.asStateFlow()
    private var saved = false

    init {
        next()
    }

    /** 下一锅：题库里没煮过的真题，“为什么会这样”那类优先 */
    fun next() = viewModelScope.launch {
        _outside.value = null
        saved = false
        val played = dao.playedCardIds().toHashSet()
        val pool = yishou.database.cards().recent(3000)
            .filter { it.source == CardSource.BANK && it.id !in played }
            .mapNotNull { row -> runCatching { row.id to Card.parse(org.json.JSONObject(row.json)) }.getOrNull() }
            .filter { Soups.eligible(it.second) }
        val best = pool.maxOfOrNull { Soups.score(it.second) }
        val pick = pool.filter { Soups.score(it.second) == best }.randomOrNull()
        _empty.value = pick == null
        if (pick != null) session.start(pick.first, pick.second)
    }

    fun ask(q: String) = viewModelScope.launch { session.ask(q) }
    fun guess(g: String) = viewModelScope.launch { session.guess(g) }
    fun open() = session.open()

    fun pick(i: Int) {
        session.pick(i)
        save()
    }

    /** 带去 ChatGPT 用语音玩：汤面和规则发过去（不含选项和答案），答案锁在这边，回来核对 */
    fun handoff() {
        val s = state.value
        if (s.card == null) return
        val apps = AiApps.parse(yishou.settings.app.value.bankLinks).ifEmpty { AiApps.parse(AiApps.DEFAULT) }
        val gpt = apps.firstOrNull { it.name.contains("ChatGPT", ignoreCase = true) } ?: apps.first()
        _outside.value = Launch.sendPrompt(yishou, gpt, Soups.handoff(s.surface), "海龟汤开场白")
    }

    fun back() {
        session.backFromOutside()
    }

    private fun save() {
        val s = state.value
        if (saved || s.phase != SoupPhase.DONE) return
        saved = true
        viewModelScope.launch {
            dao.insert(
                Soup(
                    cardId = s.cardId, asks = if (_outside.value != null && s.log.isEmpty()) -1 else s.asks, guesses = s.guesses,
                    solved = s.solved, picked = s.picked, correct = s.correct,
                    log = Lines.json(s.log.map { "${it.q}｜${it.reply.label}" }), createdAt = System.currentTimeMillis(),
                ),
            )
        }
    }

    fun law(text: String) = viewModelScope.launch { yishou.echo(text, EchoKind.LAW) }
}

/**
 * 海龟汤：只许问是非问题，主持人只回“是 / 不是 / 部分是 / 无关”。问到能说出汤底，或者问够几问，再亮 ABCD 核对。
 */
@Composable
fun SoupScreen(onBack: () -> Unit, onBank: () -> Unit, vm: SoupViewModel = viewModel()) {
    val s by vm.state.collectAsStateWithLifecycle()
    val stats by vm.stats.collectAsStateWithLifecycle()
    val empty by vm.empty.collectAsStateWithLifecycle()
    val outside by vm.outside.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("海龟汤") { onBack() } }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (stats.isNotEmpty()) Text(stats, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val card = s.card
            when {
                empty -> {
                    Text("题库里没有能煮的题了。", style = MaterialTheme.typography.titleMedium)
                    Text("海龟汤用题库里带材料的真题（解释、原因、加强削弱、前提这类最好）。导入一批就能接着煮。", style = MaterialTheme.typography.bodyMedium)
                    OutlinedButton(onClick = onBank) { Text("去导入题库") }
                }
                card == null -> VortexLoader("挑一锅")
                else -> {
                    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("汤面" + if (card.type.isNotBlank()) " · ${card.type}" else "", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
                            LogicText(s.surface, MaterialTheme.typography.bodyLarge)
                        }
                    }
                    when (s.phase) {
                        SoupPhase.ASK -> Asking(s, outside, vm)
                        SoupPhase.OPTIONS -> Options(card, s, vm)
                        SoupPhase.DONE -> Done(card, s, vm)
                    }
                }
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}

@Composable
private fun Log(s: SoupState) {
    if (s.log.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        s.log.forEachIndexed { i, qa ->
            val color = when (qa.reply) {
                Reply.YES, Reply.SOLVED -> MaterialTheme.colorScheme.tertiary
                Reply.PARTLY, Reply.CLOSE -> MaterialTheme.colorScheme.secondary
                Reply.NOT_YN -> MaterialTheme.colorScheme.error
                else -> MaterialTheme.colorScheme.onSurfaceVariant
            }
            Row(verticalAlignment = Alignment.Top) {
                Text(if (qa.guess) "猜" else "${i + 1}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                Text(qa.q, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(qa.reply.label.substringBefore('，'), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = color)
            }
        }
    }
}

@Composable
private fun Asking(s: SoupState, outside: String?, vm: SoupViewModel) {
    var text by rememberSaveable(s.cardId, s.log.size) { mutableStateOf("") }
    Log(s)
    if (outside != null) {
        Text(outside, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
        Text("在那边用语音问完、猜完，回来亮选项核对（答案锁在这边）。", style = MaterialTheme.typography.bodySmall)
        Button(onClick = vm::back, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("玩完了，亮选项") }
        return
    }
    Text(
        if (s.log.isEmpty()) "只许问能答“是 / 不是”的问题。好问题能一下切掉一半可能。" else "还能问 ${s.left} 问",
        style = MaterialTheme.typography.bodyMedium,
    )
    VoiceTextField(text, { text = it }, "例如：是因为成本变了吗？", Modifier.fillMaxWidth(), minLines = 2)
    s.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    if (s.busy) VortexLoader("主持人在想")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = { vm.ask(text) }, enabled = !s.busy && Words.substance(text) >= 2, modifier = Modifier.weight(1f).height(50.dp)) { Text("问") }
        OutlinedButton(onClick = { vm.guess(text) }, enabled = !s.busy && Words.substance(text) >= 4, modifier = Modifier.weight(1f).height(50.dp)) { Text("我猜汤底是这个") }
    }
    TextButton(onClick = vm::open, enabled = s.canOpen && !s.busy) {
        Text(if (s.canOpen) "不问了，亮选项" else "再问 ${Soups.MIN_ASKS - s.asks} 问才能亮选项")
    }
    TextButton(onClick = vm::handoff) { Text("去 ChatGPT 用语音玩这一锅") }
}

@Composable
private fun Options(card: Card, s: SoupState, vm: SoupViewModel) {
    Log(s)
    Text(if (s.solved) "你已经说中了汤底。一眼挑出来：" else "亮选项。按你问出来的东西挑：", style = MaterialTheme.typography.titleMedium)
    card.options.forEachIndexed { i, o ->
        OutlinedButton(onClick = { vm.pick(i) }, modifier = Modifier.fillMaxWidth()) {
            Text("${Card.letter(i)}. $o", modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable
private fun Done(card: Card, s: SoupState, vm: SoupViewModel) {
    var law by rememberSaveable(s.cardId) { mutableStateOf("") }
    var saved by rememberSaveable(s.cardId) { mutableStateOf(false) }
    val right = s.correct == true
    Text(if (right) "选对了" else "选错了", style = MaterialTheme.typography.headlineSmall, color = if (right) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error)
    Text(
        buildList {
            if (s.asks > 0) add("问了 ${s.asks} 问")
            if (s.solved) add("亮选项前就猜中了")
            s.picked?.let { add("你选 ${Card.letter(it)}") }
            add("答案 ${Card.letter(card.answer)}")
        }.joinToString(" · "),
        style = MaterialTheme.typography.bodyMedium,
    )
    Text("${Card.letter(card.answer)}. ${card.options[card.answer]}", style = MaterialTheme.typography.titleMedium)
    if (card.explain.isNotBlank()) Text(card.explain, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Log(s)
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("这锅的机关，压成一句（下次见到同类的先想它）：", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
            if (!saved) {
                VoiceTextField(law, { law = it }, "例如：只看到补贴，没看到谁在付成本", Modifier.fillMaxWidth())
                TextButton(onClick = { saved = true; vm.law(law) }, enabled = Words.substance(law) >= 6) { Text("收进棋谱") }
            } else {
                Text("收进棋谱了：「$law」", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
        }
    }
    Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("下一锅") }
}
