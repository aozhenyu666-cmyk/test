package com.yishou.app.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.Material
import com.yishou.app.data.MaterialSource
import com.yishou.app.llm.LlmResult
import com.yishou.app.system.ExternalGrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class PoolViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp

    val materials: StateFlow<List<Material>> = yishou.database.think().observeMaterials()
        .map { xs -> xs.filter { it.source != MaterialSource.BUILTIN } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val bank: StateFlow<Pair<Int, Int>> = yishou.database.cards().observeBank().map { xs -> xs.size to xs.count { it.usedAt == null } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0 to 0)
    val endgames: StateFlow<Int> = yishou.database.endgames().observe().map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _note = MutableStateFlow<String?>(null)
    val note: StateFlow<String?> = _note.asStateFlow()

    private fun run(label: String, block: suspend () -> String) {
        if (_busy.value != null) return
        _busy.value = label
        _note.value = null
        viewModelScope.launch {
            _note.value = try { block() } catch (e: Exception) { "没收进来：${e.message ?: e.javaClass.simpleName}" }
            _busy.value = null
        }
    }

    fun paste(text: String) = run("收进来……") { yishou.pool.take(text, MaterialSource.PASTE).message }

    fun photo(uri: Uri) = run("正在读图里的字……") {
        when (val t = yishou.materials.imageText(uri)) {
            is LlmResult.Err -> t.error.message
            is LlmResult.Ok -> yishou.pool.take(t.value, MaterialSource.PHOTO).message
        }
    }

    fun files(uris: List<Uri>) = run("读文件……") {
        yishou.fileImport.importAll(uris) { _busy.value = it }.joinToString("\n") { "${it.name}：${it.line}" }
    }

    fun delete(m: Material) = viewModelScope.launch { yishou.database.think().deleteMaterial(m) }
}

/**
 * 素材池：一切进来的唯一入口。粘贴、拍照、导入文件、在别的 App 里分享——都到这里。
 * 进来自动分：像题的进题库，像文章的切段进素材；海龟汤、残局、练思维、刷题、语音伙伴都从这里拿。
 */
@Composable
fun PoolScreen(onBack: () -> Unit, onRoute: (String) -> Unit, vm: PoolViewModel = viewModel()) {
    val materials by vm.materials.collectAsStateWithLifecycle()
    val bank by vm.bank.collectAsStateWithLifecycle()
    val endgames by vm.endgames.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val note by vm.note.collectAsStateWithLifecycle()
    var pasting by rememberSaveable { mutableStateOf(false) }
    var text by rememberSaveable { mutableStateOf("") }
    var open by rememberSaveable { mutableStateOf<Long?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::photo) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> if (uris.isNotEmpty()) vm.files(uris) }

    Scaffold(topBar = { BackTopBar("素材池") { onBack() } }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { Button(onClick = { onRoute("library") }, modifier = Modifier.fillMaxWidth()) { Text("分类标签页 · 搜索与整理") } }
            item { Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                val fresh = materials.count { it.uses == 0 }
                Text(
                    "文章 ${materials.size} 段（没练过 $fresh）· 题库 ${bank.first} 道（没做 ${bank.second}）· 残局 $endgames 局",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "什么都往这里放：像题的自动进题库，像文章的切成一段一段。所有练法都从这里拿，不用每个练法各交一遍。" +
                        "在别的 App 里点“分享 → 一手”也是进这里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { pasting = !pasting }, modifier = Modifier.weight(1f)) { Text("粘贴") }
                    OutlinedButton(onClick = {
                        ExternalGrace.open()
                        photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }, modifier = Modifier.weight(1f)) { Text("拍 / 截图") }
                    OutlinedButton(onClick = {
                        ExternalGrace.open()
                        files.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword", "application/pdf", "image/*"))
                    }, modifier = Modifier.weight(1f)) { Text("文件") }
                }
            }
            if (pasting) {
                item { Column {
                    VoiceTextField(text, { text = it }, "一篇文章、一段讲义，或者一批题", Modifier.fillMaxWidth(), minLines = 4)
                    Button(onClick = { vm.paste(text); text = ""; pasting = false }, enabled = text.isNotBlank() && busy == null, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("收进来") }
                } }
            }
            busy?.let { item { VortexLoader(it) } }
            note?.let { item { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary) } }
            item {
                Row {
                    TextButton(onClick = { onRoute("bank") }) { Text("题库详情、出题链接 →") }
                }
            }
            items(materials, key = { it.id }) { m ->
                Surface(
                    color = MaterialTheme.colorScheme.surface,
                    shape = RoundedCornerShape(14.dp),
                    tonalElevation = 1.dp,
                    modifier = Modifier.fillMaxWidth().clickable { open = if (open == m.id) null else m.id },
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(m.title, style = MaterialTheme.typography.titleSmall)
                        Text(
                            MaterialSource.label(m.source) + if (m.uses > 0) " · 练过 ${m.uses} 次" else " · 新",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (m.uses == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        if (open == m.id) {
                            Text(m.text.take(240) + if (m.text.length > 240) "……" else "", style = MaterialTheme.typography.bodySmall)
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                TextButton(onClick = { onRoute("think?m=${m.id}&lens=") }) { Text("练思维") }
                                TextButton(onClick = { onRoute("partner?m=${m.id}") }) { Text("语音一起聊") }
                                TextButton(onClick = { vm.delete(m) }) { Text("删掉", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
                }
            }
            item { androidx.compose.foundation.layout.Spacer(Modifier.height(24.dp)) }
        }
    }
}
