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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
    var sending by remember { mutableStateOf(false) }
    var listening by remember { mutableStateOf(false) }
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
            val res = runCatching { BrainHost.chat(text, System.currentTimeMillis()) }.getOrNull()
            sending = false
            if (res == null) {
                toast("这条没接上，再说一次")
                return@launch
            }
            if (BrainHost.settingsStore.load().speak) Speaker.speak(res.text)
        }
    }

    Column(Modifier.fillMaxSize()) {
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
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                itemsIndexed(state.talk) { index, turn ->
                    Column(Modifier.fillMaxWidth()) {
                        Bubble(who = turn.who, text = turn.text)
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
                                toast(msg)
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

    // 去聊：把大脑准备好的开场白给用户，复制走就能贴进 Gemini / ChatGPT（打开演员是下一批）
    val op = opening
    if (op != null) {
        OpeningDialog(opening = op, clip = clip, onDismiss = { opening = null })
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

/** 开场白对话框：长文可滚动，“复制”只把字放进剪贴板，不会自己跳去别的 App。 */
@Composable
private fun OpeningDialog(opening: Opening, clip: ClipboardManager, onDismiss: () -> Unit) {
    val context = LocalContext.current
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("开场白") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 360.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Text(opening.text, style = MaterialTheme.typography.bodyMedium)
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
