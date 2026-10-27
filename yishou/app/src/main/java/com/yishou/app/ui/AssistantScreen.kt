package com.yishou.app.ui

import android.app.Application
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.assistant.*
import com.yishou.app.data.AssistantChat
import com.yishou.app.llm.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn

class AssistantViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.everyday()
    private val client = ChatClient({ yishou.settings.llm.value }, http = ChatClient.defaultHttpClient(90), usage = yishou.stats)
    val chats = dao.chats().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var id by mutableLongStateOf(0); private set
    var lines by mutableStateOf<List<ChatLine>>(emptyList()); private set
    var draftReply by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    var error by mutableStateOf<String?>(null); private set
    private var job: Job? = null
    init { viewModelScope.launch { val old = dao.chats().first().firstOrNull(); old?.let { load(it.id) } } }
    suspend fun load(next: Long) {
        if (busy) return
        dao.chat(next)?.let { row -> id = row.id; lines = ChatHistory.decode(row.json); error = null }
    }
    fun choose(next: Long) { viewModelScope.launch { load(next) } }
    fun fresh() { if (!busy) { id = 0; lines = emptyList(); error = null; draftReply = "" } }
    private suspend fun save() {
        if (lines.isEmpty()) return
        val row = AssistantChat(id, lines.firstOrNull { it.role == "user" }?.text?.take(24) ?: "新对话", ChatHistory.encode(lines), System.currentTimeMillis())
        if (id == 0L) id = dao.insertChat(row) else dao.updateChat(row)
    }
    fun send(text: String) {
        if (busy || text.isBlank()) return
        lines = lines.filter { it.role != "error" } + ChatLine("user", text.trim())
        request()
    }
    fun retry() { if (!busy && error != null && lines.any { it.role == "user" }) { lines = lines.filter { it.role != "error" }; request() } }
    private fun request() {
        busy = true; error = null; draftReply = ""
        job = viewModelScope.launch {
            try {
                save()
                val idx = lines.indexOfLast { it.role == "user" }
                val result = client.streamConversation(ChatHistory.system, ChatHistory.context(lines.take(idx)), lines[idx].text, "助手对话") { draftReply += it }
                when (result) {
                    is LlmResult.Ok -> { lines = lines + ChatLine("assistant", result.value); draftReply = "" }
                    is LlmResult.Err -> { error = result.error.message; lines = lines + ChatLine("error", result.error.message); draftReply = "" }
                }
                save()
            } catch (e: CancellationException) {
                if (draftReply.isNotBlank()) lines = lines + ChatLine("assistant", draftReply + "\n（已停止生成）")
                draftReply = ""
                withContext(NonCancellable) { save() }
                throw e
            } catch (e: Exception) { error = "这次没能完成对话，请重试" }
            finally { busy = false }
        }
    }
    fun stop() { job?.cancel() }
    fun delete() {
        if (busy || id == 0L) return
        val old = id
        viewModelScope.launch { dao.deleteChat(old); if (id == old) fresh() }
    }
}

@Composable
fun AssistantScreen(onBack: () -> Unit, onSettings: () -> Unit, vm: AssistantViewModel = viewModel()) {
    val chats by vm.chats.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    val list = rememberLazyListState()
    LaunchedEffect(vm.lines.size, vm.draftReply.length) { if (vm.lines.isNotEmpty()) list.animateScrollToItem(vm.lines.size) }
    Scaffold(topBar = { BackTopBar("AI 助手", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().padding(horizontal = 16.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = vm::fresh, enabled = !vm.busy) { Text("新对话") }
                Box {
                    TextButton(onClick = { menu = true }, enabled = !vm.busy) { Text("历史 ${chats.size}") }
                    DropdownMenu(menu, { menu = false }) {
                        chats.forEach { row -> DropdownMenuItem(text = { Text(row.title) }, onClick = { menu = false; vm.choose(row.id) }) }
                    }
                }
                TextButton(onClick = vm::delete, enabled = !vm.busy && vm.id > 0) { Text("删除本段") }
                TextButton(onClick = onSettings) { Text("接口设置") }
            }
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(12.dp), contentPadding = PaddingValues(vertical = 12.dp)) {
                itemsIndexed(vm.lines) { _, line ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = if (line.role == "user") MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp)) {
                            Text(when (line.role) { "user" -> "你"; "assistant" -> "助手"; else -> "这次没发成" }, style = MaterialTheme.typography.labelMedium)
                            SelectionContainer { Text(line.text) }
                        }
                    }
                }
                item {
                    if (vm.busy) Text(vm.draftReply.ifBlank { "正在想……" })
                    else if (vm.lines.isEmpty()) Text("直接说你想聊的事。这里用设置里的文字模型，聊天会保存在本机。")
                }
            }
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error); TextButton(onClick = vm::retry) { Text("重试这一条") } }
            VoiceTextField(text, { text = it }, "想问什么，或者说一句", Modifier.fillMaxWidth())
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (vm.busy) OutlinedButton(onClick = vm::stop) { Text("停止生成") }
                else Button(onClick = { vm.send(text); text = "" }, enabled = text.isNotBlank()) { Text("发送") }
            }
        }
    }
}
