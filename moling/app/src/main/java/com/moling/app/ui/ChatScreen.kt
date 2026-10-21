package com.moling.app.ui

import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moling.app.BrainHost
import com.moling.core.Proposal
import com.moling.core.spar.Chess
import com.moling.core.spar.Chesses
import com.moling.core.talk.TalkMode
import androidx.compose.foundation.horizontalScroll
import com.moling.core.mind.Opening
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 发消息、点“好”不跟着界面取消：切到“今天”再回来，回复和决定都已经做完。 */
private val talkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/**
 * 对话页：聊天列表 + 输入框 + 麦克风 + 提议卡。
 *
 * 聊天和提议都来自大脑：界面只发话、只把用户的决定交回去，自己不判断也不执行。
 * 卡片挂在回复下面，是从 state.proposals 按时间算出来的，所以重开 App 也还在。
 */
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val state by BrainHost.state.collectAsState()
    val list = rememberLazyListState()
    val clip = LocalClipboardManager.current

    var input by remember { mutableStateOf("") }
    val presenceDraft by com.moling.app.overlay.Presence.draftText.collectAsState()
    LaunchedEffect(presenceDraft) {
        presenceDraft?.let { input = it; com.moling.app.overlay.Presence.draftText.value = null }
    }
    var sending by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    // 模式条：null = 让她按局面判断（显示她这次用的是哪种）；点了某个模式就按它说，一小时内有效
    var mode by remember { mutableStateOf<TalkMode?>(null) }
    var usedMode by remember { mutableStateOf<TalkMode?>(null) }
    // 去聊的开场白：点“好”之后弹出来，供用户复制
    var opening by remember { mutableStateOf<Opening?>(null) }
    // 正在执行的提议 id：本地挡一下连点（真正防重复在 Brain 里，状态一变就点不动了）
    val deciding = remember { mutableSetOf<String>() }

    // 语音识别只建一个，离开这一页就释放（系统的识别界面不归我们管）
    val voice = remember { VoiceInput(context.applicationContext) }
    DisposableEffect(Unit) {
        onDispose { voice.destroy() }
    }
    // 朗读引擎第一次真要念才初始化；这里只把 application context 递过去
    LaunchedEffect(Unit) { Speaker.prepare(context.applicationContext) }

    // 来了新消息就滚到底（对话满 40 条后条数不变，所以连最后一条的时间也看着）
    LaunchedEffect(state.talk.size, state.talk.lastOrNull()?.at) {
        if (state.talk.isNotEmpty()) list.animateScrollToItem(state.talk.lastIndex)
    }

    fun toast(msg: String) {
        Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
    }

    /** 同一张卡正在执行时挡一下连点；做完就放开，失败后还能重试。 */
    fun once(id: String, block: suspend () -> Unit) {
        if (!deciding.add(id)) return
        talkScope.launch {
            try {
                block()
            } finally {
                deciding.remove(id)
            }
        }
    }

    fun accept(p: Proposal) = once(p.id) {
        val o = runCatching { BrainHost.accept(p.id, System.currentTimeMillis()) }.getOrNull()
        if (o == null) {
            toast("没接上，再试一次")
        } else {
            toast(o.message)
            o.opening?.let { opening = it }
        }
    }

    fun decline(p: Proposal) = once(p.id) {
        runCatching { BrainHost.decline(p.id, System.currentTimeMillis()) }
    }

    fun retry(p: Proposal) = once(p.id) {
        val o = runCatching { BrainHost.retry(p.id, System.currentTimeMillis()) }.getOrNull()
        if (o == null) {
            toast("没接上，再试一次")
        } else {
            toast(o.message)
            o.opening?.let { opening = it }
        }
    }

    // 对讲（v8）：你说完自动发，她念完自动接着听；连着两次没听清、或出错就停
    var talkLoop by remember { mutableStateOf(false) }
    // 她给的点选项（破雾模式里一定有）：点一下就算回她
    var choices by remember { mutableStateOf<List<String>>(emptyList()) }
    // E1 三手棋：正在下的一局 / 收官分数 / 新结论 / “我自己说一句”。
    var chess by remember { mutableStateOf(BrainHost.state.value.chess) }
    var chessScore by remember { mutableIntStateOf(-1) }
    var chessConclusion by remember { mutableStateOf("") }
    var freeMode by remember { mutableStateOf(false) }
    var misses by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) { onDispose { talkLoop = false; Speaker.stop() } }

    // send 和 listen 互相调用：先占个位，listen 定义完再填上
    lateinit var listenLater: (Boolean) -> Unit

    fun send() {
        val text = input.trim()
        if (text.isEmpty() || sending) return
        if (listening) {
            voice.stop()
            listening = false
        }
        input = ""
        sending = true
        talkScope.launch {
            // 大脑自己兜底（模型 / 总控失败都有本地回复）；再兜一层，界面不会永远停在“在想”
            val res = runCatching { BrainHost.chat(text, System.currentTimeMillis(), mode) }.getOrNull()
            sending = false
            if (res == null) {
                toast("这条没接上，再说一次")
                return@launch
            }
            usedMode = res.mode
            choices = res.choices
            if (talkLoop) Speaker.speak(res.text) { if (talkLoop) listenLater(true) }
            else if (BrainHost.settingsStore.load().speak) Speaker.speak(res.text)
        }
    }

    /** 听一句。[auto] = 对讲里：听到就直接发。 */
    fun listen(auto: Boolean) {
        if (listening) return
        Speaker.stop()
        listening = true
        voice.start(
            onResult = { said ->
                listening = false
                misses = 0
                if (auto && talkLoop) {
                    input = said
                    send()
                } else {
                    input = if (input.isBlank()) said else "${input.trim()} $said"
                }
            },
            onError = { msg ->
                listening = false
                if (auto && talkLoop && msg.contains("没听清") && misses < 1) {
                    misses++
                    listen(true)
                } else {
                    if (talkLoop) { talkLoop = false; toast("对讲停了：$msg") } else toast(msg)
                }
            },
        )
    }
    listenLater = { listen(it) }

    /** E1：点选项（三手棋的“我自己说一句”先展开输入框）。 */
    fun pickChoice(c: String) {
        if (c == Chesses.FREE) {
            freeMode = true
            return
        }
        sending = true
        talkScope.launch {
            val t = runCatching { BrainHost.tap(c) }.getOrNull()
            sending = false
            if (t == null) {
                toast("没接上，再点一次")
                return@launch
            }
            choices = t.choices
            if (t.chess) {
                val s = BrainHost.state.value
                chess = s.chess
                if (s.chess == null) {
                    val last = s.chessDone.lastOrNull()
                    chessScore = last?.score ?: -1
                    chessConclusion = last?.conclusion.orEmpty()
                }
            }
            if (talkLoop || BrainHost.settingsStore.load().speak) Speaker.speak(t.line)
        }
    }

    // 她此刻的样子：在想 → 好奇，在听 → 平静，否则按状态
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state.talk.size) { now = System.currentTimeMillis() }
    val mood = when {
        sending -> com.moling.core.live.Mood.CURIOUS
        listening -> com.moling.core.live.Mood.CALM
        else -> moodOf(state, now)
    }
    var showThoughts by remember { mutableStateOf(false) }

    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    val typing = androidx.compose.foundation.layout.WindowInsets.isImeVisible
    // 键盘一出来就滚到最新一条
    LaunchedEffect(typing) { if (typing && state.talk.isNotEmpty()) list.scrollToItem(state.talk.lastIndex) }
    Column(Modifier.fillMaxSize()) {
        // ---------------------------------------------------------------- 她的台子：形象、此刻、想好要说的、她的推断
        // 打字时收起台子和模式条，让对话和输入框露出来
        if (!typing) Stage(
            mood = mood,
            line = if (sending) "在想…" else if (listening) "在听…" else moodLine(mood),
            say = state.sayQueue.firstOrNull()?.text,
            thoughts = com.moling.core.think.Think.open(state.thoughts).takeLast(4),
            expanded = showThoughts,
            onToggle = { showThoughts = !showThoughts },
            onSay = { talkScope.launch { BrainHost.sayNow() } },
            onDropSay = { talkScope.launch { BrainHost.dropSay() } },
            onJudge = { id, right -> talkScope.launch { BrainHost.judgeThought(id, right) } },
            onThink = {
                sending = true
                talkScope.launch {
                    val r = runCatching { BrainHost.thinkNow() }.getOrNull()
                    sending = false
                    toast(if (r == null) "她现在想不了（模型没接上）" else "她想了想：新判断 ${r.made.size} 条" + if (r.say.isNotEmpty()) "，有话想跟你说" else "")
                }
            },
            onSpar = {
                sending = true
                talkScope.launch {
                    val t = runCatching { BrainHost.spar() }.getOrNull()
                    sending = false
                    if (t == null) {
                        toast("体系图里还没东西可下，先在对话里说‘记下’几条")
                    } else {
                        choices = t.choices
                        chess = BrainHost.state.value.chess
                        toast("来一局三手棋")
                    }
                }
            },

        )

        if (state.talk.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "跟墨灵说一句开始。\n她只提议：你说“好”之前，她不会动任何东西。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                itemsIndexed(state.talk) { index, turn ->
                    Column(Modifier.fillMaxWidth()) {
                        if (turn.who == "墨灵") HerNote(turn.text) else HisLine(turn.text)
                        if (turn.who == "墨灵") {
                            // 这条回复到下一句墨灵之间产生的提议，挂在这条下面；重开 App 也能看到
                            val nextAt = state.talk.drop(index + 1)
                                .firstOrNull { it.who == "墨灵" }?.at ?: Long.MAX_VALUE
                            state.proposals
                                .filter { it.createdAt >= turn.at && it.createdAt < nextAt }
                                .forEach { p ->
                                    ProposalCard(
                                        proposal = p,
                                        onAccept = { accept(it) },
                                        onDecline = { decline(it) },
                                        onRetry = { retry(it) },
                                    )
                                }
                        }
                    }
                }
            }
        }

        if (listening) {
            Row(Modifier.padding(start = 16.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                com.moling.app.ui.art.InkRipple(Modifier.size(28.dp))
                Text("在听，说完会自动停", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (sending) {
            com.moling.app.ui.art.InkThinking("墨灵在想…", Modifier.padding(start = 16.dp, bottom = 2.dp))
        }

        // E1 三手棋：第几手、暴论（第一手）、收官分数。
        if (chess != null || chessScore >= 0) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                val ch = chess
                ChessHeader(turn = ch?.turn ?: 3, done = ch == null)
                if (ch != null && ch.moves.isEmpty()) ChessBait(ch.bait, Modifier.padding(top = 6.dp))
                if (ch == null && chessScore >= 0) ChessScore(chessScore, chessConclusion)
            }
        }
        if (freeMode && !sending) ChessFreeInput(
            onSend = { said ->
                freeMode = false
                sending = true
                talkScope.launch {
                    val t = runCatching { BrainHost.tap(said) }.getOrNull()
                    sending = false
                    if (t == null) {
                        toast("没接上，再写一句")
                        return@launch
                    }
                    choices = t.choices
                    if (t.chess) {
                        val s = BrainHost.state.value
                        chess = s.chess
                        if (s.chess == null) {
                            val last = s.chessDone.lastOrNull()
                            chessScore = last?.score ?: -1
                            chessConclusion = last?.conclusion.orEmpty()
                        }
                    }
                    if (talkLoop || BrainHost.settingsStore.load().speak) Speaker.speak(t.line)
                }
            },
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        // 她给的选项：点一下就回她（不用打字）
        if (choices.isNotEmpty() && !sending) Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            choices.forEach { c ->
                OutlinedButton(
                    onClick = {
                        choices = emptyList()
                        pickChoice(c)
                    },
                    // D4 破雾：选项是这一轮的主入口，用印泥色细边和更大的可点区域。
                    border = BorderStroke(1.dp, com.moling.app.ui.Seal),
                    colors = ButtonDefaults.outlinedButtonColors(contentColor = com.moling.app.ui.Seal),
                    shape = RoundedCornerShape(14.dp),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                ) { Text(c, style = MaterialTheme.typography.bodyLarge) }
            }
        }

        // 模式条 + 两个特别的按钮（v4）：反驳我、疯想—冷判
        if (!typing) Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val auto = mode == null
            TextButton(onClick = { mode = null }) {
                Text(if (auto) "【自动${usedMode?.let { "·" + it.label } ?: ""}】" else "自动", style = MaterialTheme.typography.labelMedium)
            }
            TalkMode.entries.forEach { m ->
                TextButton(onClick = { mode = if (mode == m) null else m }) {
                    Text(if (mode == m) "【${m.label}】" else m.label, style = MaterialTheme.typography.labelMedium)
                }
            }
            TextButton(enabled = !sending, onClick = {
                sending = true
                talkScope.launch {
                    val r = runCatching { BrainHost.devil(System.currentTimeMillis()) }.getOrNull()
                    sending = false
                    if (r == null) toast("没接上，再试一次")
                }
            }) { Text("反驳我", style = MaterialTheme.typography.labelMedium) }
            TextButton(enabled = !sending, onClick = {
                val topic = input.trim().ifBlank { state.talk.lastOrNull { it.who == "他" }?.text.orEmpty() }
                if (topic.isBlank()) {
                    toast("先在输入框写想疯想的题目")
                } else {
                    input = ""
                    sending = true
                    talkScope.launch {
                        val r = runCatching { BrainHost.wild(topic, System.currentTimeMillis()) }.getOrNull()
                        sending = false
                        if (r == null) toast("没接上，再试一次")
                    }
                }
            }) { Text("疯想", style = MaterialTheme.typography.labelMedium) }
        }

        // D4 破雾：不用打字，点她给的就行。
        if (mode == TalkMode.FOG || usedMode == TalkMode.FOG) {
            Text(
                "不用打字，点她给的就行",
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 2.dp),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            OutlinedTextField(
                value = input,
                onValueChange = { if (it.length <= 4000) input = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text(if (mode == TalkMode.FOG || usedMode == TalkMode.FOG) "不用打字，点上面的选项" else "和墨灵说点什么") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
            )
            IconButton(
                onClick = {
                    if (listening) {
                        voice.stop()
                        listening = false
                    } else {
                        listen(false)
                    }
                },
                enabled = !sending && !talkLoop,
            ) {
                Text(if (listening && !talkLoop) "停" else "🎤", fontSize = 18.sp)
            }
            TextButton(
                onClick = {
                    if (talkLoop) {
                        talkLoop = false
                        voice.stop()
                        listening = false
                        Speaker.stop()
                    } else {
                        talkLoop = true
                        misses = 0
                        listen(true)
                    }
                },
                contentPadding = PaddingValues(horizontal = 6.dp),
            ) {
                if (talkLoop) com.moling.app.ui.art.InkRipple(Modifier.size(22.dp), color = Seal)
                Text(if (talkLoop) "停对讲" else "对讲", style = MaterialTheme.typography.labelMedium)
            }
            Button(
                onClick = { send() },
                enabled = input.isNotBlank() && !sending,
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
            ) {
                Text(if (sending) "想…" else "发送")
            }
        }
    }

    // 去聊：把大脑准备好的开场白给用户，复制走就能贴进 Gemini / ChatGPT（打开演员是下一批）
    val op = opening
    if (op != null) {
        OpeningDialog(opening = op, clip = clip, onDismiss = { opening = null })
    }
}

/** 她说的：一张纸条（细墨线边、左边一道印泥色的线）。 */
@Composable
private fun HerNote(text: String) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .widthIn(max = 340.dp)
                .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(0.7.dp, Ink.copy(alpha = 0.14f), RoundedCornerShape(topStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp, bottomStart = 14.dp)),
        ) {
            Row(Modifier.height(IntrinsicSize.Min)) {
                Box(Modifier.width(3.dp).fillMaxHeight().background(Seal.copy(alpha = 0.55f)))
                Text(
                    text = text,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                )
            }
        }
    }
}

/** 他说的：靠右，一道细竖线，像在纸边写的批注。 */
@Composable
private fun HisLine(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Row(Modifier.widthIn(max = 300.dp).height(IntrinsicSize.Min), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                color = Ink.copy(alpha = 0.75f),
                modifier = Modifier.padding(end = 10.dp, top = 2.dp, bottom = 2.dp),
            )
            Box(Modifier.width(1.dp).fillMaxHeight().background(Ink.copy(alpha = 0.35f)))
        }
    }
}

/**
 * 对话页最上面的“台子”：她的样子和此刻；她想好要说的（聊这个 / 以后再说）；
 * 她自己推的判断（展开后可以点对 / 不对）；“让她想想”。
 */
@Composable
private fun Stage(
    mood: com.moling.core.live.Mood,
    line: String,
    say: String?,
    thoughts: List<com.moling.core.think.Thought>,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSay: () -> Unit,
    onDropSay: () -> Unit,
    onJudge: (String, Boolean) -> Unit,
    onThink: () -> Unit,
    onSpar: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().background(Paper).padding(horizontal = 14.dp, vertical = 6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(contentAlignment = Alignment.Center) {
                if (say != null) com.moling.app.ui.art.InkRipple(Modifier.size(84.dp), color = Seal)
                MolingFace(mood, Modifier.size(64.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(line, style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = onToggle, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text(if (thoughts.isEmpty()) "她还没推出什么" else "她的推断（${thoughts.size}）${if (expanded) " ▴" else " ▾"}", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onThink, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("让她想想", style = MaterialTheme.typography.labelMedium)
                    }
                    Spacer(Modifier.width(8.dp))
                    TextButton(onClick = onSpar, contentPadding = PaddingValues(horizontal = 0.dp)) {
                        Text("来一局", style = MaterialTheme.typography.labelMedium)
                    }

                }
            }
        }
        if (say != null) {
            Row(
                Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Seal.copy(alpha = 0.07f)).padding(start = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("她想跟你说：$say", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f).padding(vertical = 8.dp))
                TextButton(onClick = onDropSay) { Text("以后") }
                TextButton(onClick = onSay) { Text("聊这个") }
            }
        }
        if (expanded) thoughts.forEach { t ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "${t.text}（把握${t.confidence}）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { onJudge(t.id, true) }) { Text("对") }
                TextButton(onClick = { onJudge(t.id, false) }) { Text("不对") }
            }
        }
        com.moling.app.ui.art.InkTitle("", Modifier.padding(top = 2.dp))
    }
}

/** 开场白对话框：长文可滚动；“复制”放进剪贴板；装了演员的话还能直接打开它的 App。 */
@Composable
private fun OpeningDialog(opening: Opening, clip: ClipboardManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    // 演员的包名可能是空的（NONE），手机也可能没装：取不到启动入口就不显示“打开”，只留复制。
    val launchIntent = remember(opening.actor, context) {
        val pkg = opening.actor.pkg
        if (pkg.isBlank()) null
        else runCatching { context.packageManager.getLaunchIntentForPackage(pkg) }.getOrNull()
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开场白") },
        text = {
            Column {
                Column(
                    modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(opening.text, style = MaterialTheme.typography.bodyMedium)
                }
                launchIntent?.let { intent ->
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = {
                            // 打开失败（被系统拦、演员被卸了）只提示，不关对话框，方便接着复制
                            val actorStartedAt = System.currentTimeMillis()
                            val ok = runCatching { context.startActivity(intent) }.isSuccess
                            if (ok) {
                                com.moling.app.overlay.Presence.startSession(context, opening, actorStartedAt)
                                onDismiss()
                            } else {
                                Toast.makeText(context.applicationContext, "没打开，复制开场白去粘贴吧", Toast.LENGTH_LONG).show()
                            }
                        }) { Text("打开 ${opening.actor.label}") }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
        dismissButton = {
            TextButton(onClick = {
                clip.setText(AnnotatedString(opening.text))
                Toast.makeText(context.applicationContext, "已复制", Toast.LENGTH_SHORT).show()
            }) { Text("复制") }
        },
    )
}