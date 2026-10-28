package com.yishou.app.ui

import android.app.Application
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.EchoKind
import com.yishou.app.data.Endgame
import com.yishou.app.data.EndgameSource
import com.yishou.app.endgame.Endgames
import com.yishou.app.system.Haptics
import com.yishou.app.think.Words
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 一局残局：读 → 点破绽 →（训练场：先开一枪）→ 翻底牌。
 * 入口用静音单手版（只点，不写不说）；训练场多一步“先开一枪”：同一个准星，你先打，再看高手怎么打。
 *
 * [onFlip] 在翻底牌那一刻调一次：第一下点没点中、你那一枪说了什么。
 */
@Composable
fun EndgameBoard(e: Endgame, shoot: Boolean, onFlip: (firstHit: Boolean, shot: String) -> Unit, onLaw: (String) -> Unit) {
    val segs = remember(e.id) { Endgames.segments(e.text) }
    val crux = remember(e.id) { Endgames.cruxSegs(e, segs) }
    var wrong by remember(e.id) { mutableStateOf(setOf<Int>()) }
    var found by remember(e.id) { mutableStateOf(false) }
    var flipped by remember(e.id) { mutableStateOf(false) }
    var mine by remember(e.id) { mutableStateOf("") }
    var lawSaved by remember(e.id) { mutableStateOf(false) }
    val unlockAt = remember(e.id) { System.currentTimeMillis() + Endgames.minReadMs(e.text) }
    var now by remember(e.id) { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(e.id) {
        while (now < unlockAt) {
            delay(200)
            now = System.currentTimeMillis()
        }
    }
    val locked = now < unlockAt
    val context = androidx.compose.ui.platform.LocalContext.current
    val flip by rememberUpdatedState(onFlip)

    fun reveal(firstHit: Boolean) {
        flipped = true
        flip(firstHit, mine.trim())
    }

    fun tap(i: Int) {
        // 手势回调只在第一帧挂上，这里直接看钟，不用那一帧的 locked
        if (System.currentTimeMillis() < unlockAt || found || flipped || i in wrong) return
        if (i in crux) {
            Haptics.play(context, Haptics.Kind.PASS)
            found = true
            if (!shoot) reveal(wrong.isEmpty())
        } else {
            Haptics.play(context, Haptics.Kind.REDO)
            wrong = wrong + i
            if (wrong.size >= Endgames.TRIES) reveal(false)
        }
    }

    val dim = if (flipped) emptySet() else Endgames.dimmed(e, segs, wrong)
    Column(verticalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.animateContentSize()) {
        Row {
            Text(EndgameSource.label(e.source) + " · 残局", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (e.seen > 0) Text("第 ${e.seen + 1} 次", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(e.ask, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
        Text("准星：${e.aim}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)

        // 正文：一句一句能点
        val on = MaterialTheme.colorScheme.onSurface
        val off = MaterialTheme.colorScheme.outlineVariant
        val bad = MaterialTheme.colorScheme.error
        val key = MaterialTheme.colorScheme.tertiaryContainer
        val annotated = remember(e.id, dim, wrong, flipped, found, on, off, bad, key) {
            buildAnnotatedString {
                append(e.text)
                segs.forEachIndexed { i, s ->
                    val style = when {
                        (flipped || found) && i in crux -> SpanStyle(color = on, background = key, fontWeight = FontWeight.Bold)
                        i in wrong -> SpanStyle(color = bad, textDecoration = TextDecoration.LineThrough)
                        i in dim -> SpanStyle(color = off)
                        else -> SpanStyle(color = on)
                    }
                    addStyle(style, s.start, s.end)
                }
            }
        }
        var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
        Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
            Text(
                annotated,
                style = MaterialTheme.typography.titleLarge.copy(lineHeight = MaterialTheme.typography.titleLarge.lineHeight * 1.2f),
                onTextLayout = { layout = it },
                modifier = Modifier
                    .padding(16.dp)
                    .pointerInput(e.id) {
                        detectTapGestures { p ->
                            val l = layout ?: return@detectTapGestures
                            val at = l.getOffsetForPosition(p)
                            val i = segs.indexOfFirst { at >= it.start && at < it.end }
                            if (i >= 0) tap(i)
                        }
                    },
            )
        }
        when {
            locked -> Text("先读完 · ${(unlockAt - now) / 1000 + 1} 秒后能点", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            !found && !flipped && wrong.isEmpty() -> Text("点破绽所在的那一句。", style = MaterialTheme.typography.labelLarge)
            !found && !flipped -> Text("不是这句。外围涂暗了——真凶在亮着的几句里，再点一次。", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            found && !flipped -> {
                // 训练场：同一个准星，你先开一枪
                Text(if (wrong.isEmpty()) "点中了。先别翻：这里为什么是破绽？接下来会怎样？" else "这次点中了。先说你的一枪：", style = MaterialTheme.typography.titleSmall)
                ShotClock(e.id)
                VoiceTextField(mine, { mine = it }, "一句话，打偏也行", Modifier.fillMaxWidth(), minLines = 2)
                Button(onClick = { reveal(wrong.isEmpty()) }, enabled = Words.substance(mine) >= 2, modifier = Modifier.fillMaxWidth().height(50.dp)) { Text("开枪，翻底牌") }
            }
        }
        if (flipped) {
            Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (found && wrong.isEmpty()) "第一下就点中了" else if (found) "第二下点中（这局明天再来一次）" else "两下没中（这局明天再来一次）",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                    if (mine.isNotBlank()) Text("你的一枪：$mine", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    Text("底牌：${e.shot}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    if (e.law.isNotBlank()) {
                        Text("铁律：${e.law}", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        if (!lawSaved) TextButton(onClick = { lawSaved = true; onLaw(e.law) }) { Text("收进棋谱") }
                        else Text("收进棋谱了：以后开局会推回来。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                    }
                }
            }
        }
    }
}

/** 先开一枪的读秒：10 秒。到点不拦你，只变红。 */
@Composable
private fun ShotClock(key: Long) {
    var left by remember(key) { mutableIntStateOf(10) }
    LaunchedEffect(key) {
        while (left > 0) {
            delay(1_000)
            left--
        }
    }
    Text(if (left > 0) "$left 秒" else "超时了，先打出去", style = MaterialTheme.typography.labelLarge, color = if (left > 3) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error)
}

class EndgameViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.endgames()

    val all: StateFlow<List<Endgame>> = dao.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    private val _current = MutableStateFlow<Endgame?>(null)
    val current: StateFlow<Endgame?> = _current.asStateFlow()
    private val _empty = MutableStateFlow(false)
    val empty: StateFlow<Boolean> = _empty.asStateFlow()
    private var lastKey: String? = null
    private var done = 0

    init {
        next()
    }

    fun next() = viewModelScope.launch {
        val now = System.currentTimeMillis()
        val e = Endgames.pick(dao.due(now, 60), now, lastKey)
        _current.value = e
        _empty.value = e == null
        if (e == null || dao.countDue(now) < 4) yishou.prefetchEndgames()
    }

    fun flip(e: Endgame, firstHit: Boolean) = viewModelScope.launch {
        dao.update(Endgames.after(e, firstHit, System.currentTimeMillis()))
        lastKey = e.sourceKey
        done++
    }

    fun law(text: String) = viewModelScope.launch { yishou.echo(text, EchoKind.LAW) }
}

/** 训练场：一局接一局。翻完底牌点“下一局”。 */
@Composable
fun EndgameScreen(onBack: () -> Unit, onBank: () -> Unit, vm: EndgameViewModel = viewModel()) {
    val e by vm.current.collectAsStateWithLifecycle()
    val empty by vm.empty.collectAsStateWithLifecycle()
    val all by vm.all.collectAsStateWithLifecycle()
    var flipped by remember(e?.id) { mutableStateOf(false) }
    Scaffold(topBar = { BackTopBar("残局") { onBack() } }) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(Endgames.line(all), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val g = e
            when {
                g != null -> {
                    EndgameBoard(g, shoot = true, onFlip = { hit, _ -> flipped = true; vm.flip(g, hit) }, onLaw = vm::law)
                    if (flipped) Button(onClick = { vm.next() }, modifier = Modifier.fillMaxWidth().height(52.dp)) { Text("下一局") }
                }
                empty -> {
                    Text("现在没有到期的残局。", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "残局只从你自己的东西里加工：题库里带依据句的题（本机直接变），以及你分享、粘贴、拍进素材库的文章（填了大模型接口后在后台加工）。AI 不现编材料。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = onBank) { Text("去导入题库") }
                    TextButton(onClick = { vm.next() }) { Text("再看一眼") }
                }
                else -> VortexLoader("摆残局")
            }
            Spacer(Modifier.height(80.dp))
        }
    }
}
