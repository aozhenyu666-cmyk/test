package com.yishou.app.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.yishou.app.card.Card as QuestionCard
import com.yishou.app.data.*
import com.yishou.app.llm.LlmResult
import com.yishou.app.system.ExternalGrace
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class LibraryEntry(val kind: String, val id: Long, val title: String, val text: String, val category: Long, val at: Long) { val key get() = "$kind:$id" }

class LibraryViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.everyday()
    val categories = dao.categories().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val entries = combine(dao.cards(), dao.materials(), dao.assignments()) { cards, mats, tags ->
        val ids = tags.associateBy { it.kind to it.refId }
        cards.mapNotNull { row -> runCatching {
            val c = QuestionCard.fromJson(row.json)
            LibraryEntry("card", row.id, c.question, (c.sentences + c.question + c.options.mapIndexed { i, s -> "${'A' + i}．$s" } + "答案：${'A' + c.answer}" + "解析：${c.explain}").joinToString("\n"), ids["card" to row.id]?.categoryId ?: 0, row.createdAt)
        }.getOrNull() } + mats.map { LibraryEntry("material", it.id, it.title, it.text, ids["material" to it.id]?.categoryId ?: 0, it.createdAt) }
    }.map { it.sortedByDescending { e -> e.at } }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    var busy by mutableStateOf(false); private set
    var note by mutableStateOf<String?>(null); private set
    init { work { yishou.library.indexMissing(); "已有内容已归类，随时可以手动移动" } }
    private fun work(block: suspend () -> String?) {
        if (busy) return
        busy = true
        viewModelScope.launch {
            try { note = block() } catch (e: CancellationException) { throw e }
            catch (e: Exception) { note = "这步没有完成：${e.message ?: "请重试"}" }
            finally { busy = false }
        }
    }
    fun add(name: String) = work { yishou.library.add(name) ?: "分类建好了" }
    fun rename(id: Long, name: String) = work { yishou.library.rename(id, name) ?: "名字改好了" }
    fun remove(id: Long) = work { yishou.library.remove(id); "分类已删除，内容移到其他" }
    fun move(e: LibraryEntry, id: Long) = work { yishou.library.assign(e.kind, e.id, e.text, id); "移好了" }
    fun paste(text: String, target: Long?) = work { yishou.pool.take(text, MaterialSource.PASTE, target).message }
    fun files(uris: List<Uri>, target: Long?) = work { yishou.fileImport.importAll(uris, target) { note = it }.joinToString("\n") { "${it.name}：${it.line}" } }
    fun photo(uri: Uri, target: Long?) = work {
        when (val t = yishou.materials.imageText(uri)) { is LlmResult.Err -> t.error.message; is LlmResult.Ok -> yishou.pool.take(t.value, MaterialSource.PHOTO, target).message }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LibraryScreen(onBack: () -> Unit, onRoute: (String) -> Unit, vm: LibraryViewModel = viewModel()) {
    val entries by vm.entries.collectAsStateWithLifecycle()
    val categories by vm.categories.collectAsStateWithLifecycle()
    var selected by rememberSaveable { mutableStateOf<Long?>(null) }
    var query by rememberSaveable { mutableStateOf("") }
    var name by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var importing by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf(false) }
    var expanded by rememberSaveable { mutableStateOf<String?>(null) }
    // 系统选择器回来时沿用打开前选的分类，不因重组换目标。
    var importTarget by rememberSaveable { mutableStateOf<Long?>(null) }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { if (it.isNotEmpty()) vm.files(it, importTarget) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { it?.let { uri -> vm.photo(uri, importTarget) } }
    val shown = remember(entries, selected, query) { entries.filter { (selected == null || it.category == selected) && (query.isBlank() || it.title.contains(query, true) || it.text.contains(query, true)) } }
    Scaffold(topBar = { BackTopBar("题库与素材", onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text("按分类收，按关键词找。选中某一页后导入，就收到这一页；在“全部”导入会自动分类。")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected == null, { selected = null }, label = { Text("全部 ${entries.size}") })
                    categories.forEach { tab -> FilterChip(selected == tab.id, { selected = tab.id }, label = { Text("${tab.name} ${entries.count { it.category == tab.id }}") }) }
                }
                Row {
                    OutlinedTextField(name, { name = it }, label = { Text("新标签页名称") }, modifier = Modifier.weight(1f), singleLine = true)
                    TextButton(onClick = { vm.add(name); name = "" }, enabled = name.isNotBlank() && !vm.busy) { Text("新建") }
                }
                selected?.let { id ->
                    TextButton(onClick = { editing = !editing; name = categories.firstOrNull { it.id == id }?.name.orEmpty() }) { Text("管理这一页") }
                    if (editing) Row {
                        TextButton(onClick = { vm.rename(id, name); editing = false }, enabled = name.isNotBlank() && !vm.busy) { Text("改成上面的名字") }
                        if (id > 5) TextButton(onClick = { vm.remove(id); selected = null; editing = false }, enabled = !vm.busy) { Text("删分类，内容保留") }
                    }
                }
                OutlinedTextField(query, { query = it }, label = { Text("搜题目或正文") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(onClick = { importing = !importing }, enabled = !vm.busy) { Text("粘贴导入") }
                    OutlinedButton(onClick = { importTarget = selected; ExternalGrace.open(); files.launch(arrayOf("text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "image/*")) }, enabled = !vm.busy) { Text("文件") }
                    OutlinedButton(onClick = { importTarget = selected; ExternalGrace.open(); photo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, enabled = !vm.busy) { Text("图片") }
                }
                if (importing) {
                    VoiceTextField(text, { text = it }, "题包、汤面汤底、文章都能放", Modifier.fillMaxWidth(), minLines = 4)
                    Button(onClick = { vm.paste(text, selected); text = "" }, enabled = text.isNotBlank() && !vm.busy) { Text(if (selected == null) "收进来并自动分类" else "收进当前分类") }
                }
                if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
                vm.note?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
                Text("找到 ${shown.size} 条", style = MaterialTheme.typography.labelLarge)
            }
            items(shown, key = { it.key }) { entry ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(entry.title, style = MaterialTheme.typography.titleMedium)
                        Text(categories.firstOrNull { it.id == entry.category }?.name ?: "待归类", style = MaterialTheme.typography.labelMedium)
                        TextButton(onClick = { expanded = if (expanded == entry.key) null else entry.key }) { Text(if (expanded == entry.key) "收起" else "展开内容 / 移动") }
                        if (expanded == entry.key) {
                            SelectionContainer { Text(entry.text) }
                            var moving by remember(entry.key) { mutableStateOf(false) }
                            Row {
                                TextButton(onClick = { moving = true }) { Text("移动到分类") }
                                if (entry.kind == "material") TextButton(onClick = { onRoute("think?m=${entry.id}&lens=") }) { Text("用这段练思维") }
                            }
                            DropdownMenu(moving, { moving = false }) {
                                categories.forEach { tab -> DropdownMenuItem(text = { Text(tab.name) }, onClick = { vm.move(entry, tab.id); moving = false }) }
                            }
                        }
                    }
                }
            }
            if (shown.isEmpty()) item { Text(if (query.isNotBlank()) "没有找到，换个关键词试试" else "这一页还没内容，可以直接在这里导入") }
            item { TextButton(onClick = { onRoute("bank") }) { Text("题库练习和题包格式") } }
        }
    }
}
