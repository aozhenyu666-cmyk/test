package cn.companion.pet.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import cn.companion.pet.PetApp
import cn.companion.pet.core.partner.*
import cn.companion.pet.service.VoiceInput
import cn.companion.pet.ui.theme.CompanionTheme
import cn.companion.pet.ui.theme.CompanionShapes
import cn.companion.pet.ui.theme.LocalCompanionColors

/** Same application-owned session as the overlay; navigation never creates a second conversation. */
class ChatActivity : ComponentActivity() {
    private val app get() = application as PetApp
    private var ownsCapture = false
    private val voice by lazy { VoiceInput(this) }
    private val micPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        if (allowed) listen()
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { CompanionTheme { ChatPage(app, ::finish, ::requestVoice) } }
    }
    override fun onResume() { super.onResume(); app.partner.pageVisible = true; app.partner.open() }
    override fun onPause() {
        app.partner.pageHidden()
        app.partner.typing = false
        voice.cancel()
        if (ownsCapture) app.partner.voiceCapturing = false
        ownsCapture = false
        app.partner.away()
        super.onPause()
    }
    private fun requestVoice() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) micPermission.launch(Manifest.permission.RECORD_AUDIO)
        else listen()
    }
    private fun listen() {
        if (app.settings.partnerRiding || app.partner.busy.value || app.partner.voiceCapturing) return
        if (!voice.available) { android.widget.Toast.makeText(this, "系统语音识别不可用，可以用输入法话筒。", android.widget.Toast.LENGTH_LONG).show(); return }
        ownsCapture = true
        app.partner.voiceCapturing = true
        voice.start { text ->
            ownsCapture = false
            app.partner.voiceCapturing = false
            if (!text.isNullOrBlank()) app.partner.send(text, TurnChannel.VOICE, voice.lastUncertain)
            else android.widget.Toast.makeText(this, "没有听清，可以重试或打字。", android.widget.Toast.LENGTH_SHORT).show()
        }
    }
    companion object {
        fun open(context: Context) { context.startActivity(Intent(context, ChatActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)) }
    }
}

@Composable
private fun ChatPage(app: PetApp, back: () -> Unit, listen: () -> Unit) {
    val partner = app.partner
    val session by partner.session.collectAsState()
    val busy by partner.busy.collectAsState()
    val status by partner.status.collectAsState()
    val persistenceErrors by partner.persistenceErrors.collectAsState()
    val proposal by partner.proposal.collectAsState()
    val pending by partner.presentation.collectAsState()
    val colors = LocalCompanionColors.current
    val listState = rememberLazyListState()
    var text by remember { mutableStateOf("") }
    var lookQuestion by remember { mutableStateOf(false) }
    var question by remember { mutableStateOf("") }
    val turns = session?.turns.orEmpty()
    LaunchedEffect(pending?.id) {
        val id = pending?.id ?: return@LaunchedEffect
        withFrameNanos { }
        withFrameNanos { }
        partner.pageRendered(id)
    }
    LaunchedEffect(turns.size, busy) { if (turns.isNotEmpty()) listState.animateScrollToItem(turns.lastIndex + if (session?.summary?.isNotBlank() == true) 1 else 0) }
    Column(Modifier.fillMaxSize().background(colors.paper).safeDrawingPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = back) { Text("收起") }
            Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                Text("和同桌聊", style = MaterialTheme.typography.titleLarge)
                Text(if (app.settings.partnerRiding) "语音陪伴中" else "话题可以接着聊", style = MaterialTheme.typography.labelMedium, color = colors.ink2)
            }
            TextButton(onClick = { partner.close(); text = "" }) { Text("结束话题") }
        }
        HorizontalDivider(color = colors.rule)
        LazyColumn(state = listState, modifier = Modifier.weight(1f).fillMaxWidth(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (session?.summary?.isNotBlank() == true) item {
                Text("前面聊过\n" + session!!.summary, style = MaterialTheme.typography.bodySmall, color = colors.ink2)
            }
            if (turns.isEmpty()) item {
                Column(Modifier.padding(top = 40.dp, bottom = 20.dp)) {
                    Text("刚才想到什么？", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(12.dp))
                    Text("说一件正在发生的事，或继续上次的话题。", style = MaterialTheme.typography.bodyLarge, color = colors.ink2)
                }
            }
            itemsIndexed(turns) { _, turn ->
                val isUser = turn.speaker == Speaker.USER
                Column(Modifier.fillMaxWidth(), horizontalAlignment = if (isUser) Alignment.End else Alignment.Start) {
                    Text((if (isUser) "你" else "同桌") + "  " + when (turn.channel) {
                        TurnChannel.TYPED -> "打字"; TurnChannel.VOICE -> "语音"; TurnChannel.PROACTIVE -> "主动聊起"; TurnChannel.REPLY -> "回应"
                    } + if (turn.uncertain) "，可能听错" else "", style = MaterialTheme.typography.labelMedium, color = colors.ink2)
                    Spacer(Modifier.height(6.dp))
                    Surface(color = if (isUser) colors.chip else colors.panel, shape = CompanionShapes.small, modifier = Modifier.widthIn(max = 340.dp)) {
                        Text(turn.text, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                    }
                }
            }
        }
        if (pending != null) Text(pending!!.text, Modifier.fillMaxWidth().padding(20.dp), style = MaterialTheme.typography.bodyLarge)
        if (lookQuestion) AlertDialog(
            onDismissRequest = { lookQuestion = false },
            title = { Text("想一起看什么？") },
            text = { OutlinedTextField(question, { question = it }, placeholder = { Text("例如：这句字幕的判断成立吗？") }) },
            confirmButton = { TextButton(onClick = {
                val q = question; lookQuestion = false
                if (partner.look == null) android.widget.Toast.makeText(app, "先在首页启动桌宠，再切到要看的页面。", android.widget.Toast.LENGTH_LONG).show()
                else { partner.look?.invoke(q); back() }
            }) { Text("切回上一页看一眼") } },
            dismissButton = { TextButton(onClick = { lookQuestion = false }) { Text("取消") } },
        )
        if (proposal != null) Surface(color = colors.signal, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("要我记住吗：" + proposal!!.text, style = MaterialTheme.typography.bodyMedium)
                Row {
                    TextButton(onClick = { partner.answerMemory(true) }) { Text("记住") }
                    TextButton(onClick = { partner.answerMemory(false) }) { Text("这次不记") }
                }
            }
        }
        persistenceErrors.values.forEach { Text(it, Modifier.padding(horizontal = 20.dp), style = MaterialTheme.typography.bodySmall, color = colors.danger) }
        if (status.isNotBlank()) Text(status, Modifier.padding(horizontal = 20.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { lookQuestion = true }, enabled = !busy && !app.settings.partnerRiding) { Text("带问题看一眼") }
            Text("画面只用于这次交流", style = MaterialTheme.typography.bodySmall, color = colors.ink2)
        }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = listen, enabled = !busy && !app.settings.partnerRiding) { Text("语音") }
            OutlinedTextField(text, { text = it; partner.typing = it.isNotBlank() }, modifier = Modifier.weight(1f), placeholder = { Text("接着说……") }, maxLines = 5)
            Button(onClick = { val said = text; text = ""; partner.typing = false; partner.send(said) }, enabled = text.isNotBlank() && (!busy || partner.proactivePending)) { Text("发送") }
        }
    }
}
