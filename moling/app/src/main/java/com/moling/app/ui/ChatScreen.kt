package com.moling.app.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.moling.app.BrainHost
import com.moling.app.ChatReply
import com.moling.core.link.describe
import com.moling.core.talk.TalkAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** 发送和点“好”不跟着界面取消：切到“今天”再回来，回复还在，动作也不会做一半。 */
private val talkScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

/** 一条回复下面的动作卡。只存在内存里：没点“好”的动作重启 App 后不会再执行。 */
private data class ActionCardUi(val action: TalkAction, val done: Boolean = false)

/** 按回复的时间戳挂动作卡。放进程级：切到别的标签再回来，还没点的卡还在。 */
private val replyCards = mutableStateMapOf<Long, List<ActionCardUi>>()

/**
 * 对话页：聊天列表 + 输入框 + 麦克风 + 动作卡。
 * 界面不自己算，全部从 [BrainHost] 取状态；动作也得用户点“好”才交给它执行。
 */
@Composable
fun ChatScreen() {
    val context = LocalContext.current
    val state by BrainHost.state.collectAsState()
    val list = rememberLazyListState()

    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
    // 动作卡按回复的 at 挂：talk 只留 40 条，回复被裁掉时卡片跟着一起走
    val cards = replyCards

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

    /** 把这条回复的动作挂到它的时间戳上（用刚出现的“墨灵”那条的 at）。 */
    fun attach(reply: ChatReply, since: Long) {
        if (reply.actions.isEmpty()) return
        val at = BrainHost.state.value.talk.lastOrNull { it.who == "墨灵" && it.at >= since }?.at ?: return
        cards[at] = reply.actions.map { ActionCardUi(it) }
    }

    fun accept(at: Long, index: Int) {
        val current = cards[at] ?: return
        val card = current.getOrNull(index) ?: return
        if (card.done) return // 一张卡只执行一次
        cards[at] = current.mapIndexed { i, c -> if (i == index) c.copy(done = true) else c }
        talkScope.launch {
            // 执行失败也只弹一句，不把界面卡住
            val msg = runCatching { BrainHost.accept(card.action) }
                .getOrElse { "没接上：${it.message ?: "未知原因"}" }
            Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()
        }
    }

    fun skip(at: Long, index: Int) {
        val current = cards[at] ?: return
        if (index !in current.indices) return
        val left = current.filterIndexed { i, _ -> i != index }
        if (left.isEmpty()) cards.remove(at) else cards[at] = left
    }

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
            val since = System.currentTimeMillis()
            // BrainHost.send 自己兜底，正常不抛；这里再兜一层，界面不会永远停在“在想”
            val reply = runCatching { BrainHost.send(text) }.getOrNull()
            sending = false
            if (reply == null) {
                Toast.makeText(context.applicationContext, "这条没接上，再说一次", Toast.LENGTH_SHORT).show()
                return@launch
            }
            attach(reply, since)
            if (BrainHost.settingsStore.load().speak) Speaker.speak(reply.text)
        }
    }

    Column(Modifier.fillMaxSize()) {
        if (state.talk.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "跟墨灵说一句开始。\n她是伙伴，不陪着说空话：发“嗯”试试，她会指出这句空，给你一个二选一。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                state = list,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.talk) { turn ->
                    Column(Modifier.fillMaxWidth()) {
                        Bubble(who = turn.who, text = turn.text)
                        if (turn.who == "墨灵") {
                            cards[turn.at].orEmpty().forEachIndexed { index, card ->
                                ActionCardView(
                                    card = card,
                                    onAccept = { accept(turn.at, index) },
                                    onSkip = { skip(turn.at, index) },
                                )
                            }
                        }
                    }
                }
            }
        }

        if (listening || sending) {
            Text(
                if (listening) "在听，说完会自动停" else "墨灵在想…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 20.dp, bottom = 2.dp),
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
                placeholder = { Text("和墨灵说点什么") },
                maxLines = 4,
                shape = RoundedCornerShape(22.dp),
            )
            IconButton(
                onClick = {
                    if (listening) {
                        voice.stop()
                        listening = false
                    } else {
                        listening = true
                        voice.start(
                            onResult = { said ->
                                listening = false
                                input = if (input.isBlank()) said else "${input.trim()} $said"
                            },
                            onError = { msg ->
                                listening = false
                                Toast.makeText(context.applicationContext, msg, Toast.LENGTH_SHORT).show()
                            },
                        )
                    }
                },
                enabled = !sending,
            ) {
                Text(if (listening) "停" else "🎤", fontSize = 18.sp)
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
}

/** 一句话气泡：他靠右灰底，墨灵靠左浅底。 */
@Composable
private fun Bubble(who: String, text: String) {
    val mine = who != "墨灵"
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (mine) Arrangement.End else Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .widthIn(max = 320.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(
                    if (mine) MaterialTheme.colorScheme.surfaceVariant
                    else MaterialTheme.colorScheme.surface,
                )
                .padding(horizontal = 14.dp, vertical = 9.dp),
        ) {
            Text(
                text = text,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** 动作卡：一句话说清要做什么，用户点“好”才执行。 */
@Composable
private fun ActionCardView(card: ActionCardUi, onAccept: () -> Unit, onSkip: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 8.dp, top = 10.dp, bottom = 2.dp)) {
            Text(
                card.action.explain(),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (card.done) {
                    Text(
                        "记下了",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                    )
                } else {
                    TextButton(onClick = onSkip) { Text("不用") }
                    TextButton(onClick = onAccept) { Text("好") }
                }
            }
        }
    }
}

/** 动作卡上的那句话。 */
private fun TalkAction.explain(): String = when (this) {
    is TalkAction.Zk -> command.describe()
    is TalkAction.Summon -> "去聊 ${actor.label}：$topic"
    is TalkAction.AtlasNote -> "记进体系图「$topic」：$claim"
    is TalkAction.Reroute -> "改道：$cause"
}
