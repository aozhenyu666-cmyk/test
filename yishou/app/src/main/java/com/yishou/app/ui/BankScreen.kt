package com.yishou.app.ui

import android.app.Application
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.bank.BankPrompt
import com.yishou.app.card.Card
import com.yishou.app.card.Progress
import com.yishou.app.data.CardAttempt
import com.yishou.app.data.CardRow
import com.yishou.app.data.Material
import com.yishou.app.data.MaterialKey
import com.yishou.app.system.ExternalGrace
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class BankViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp

    val bank: StateFlow<List<CardRow>> = yishou.database.cards().observeBank()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val attempts: StateFlow<List<CardAttempt>> = yishou.database.cards().observeAttemptsSince(System.currentTimeMillis() - 30L * 86_400_000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val keys: StateFlow<List<MaterialKey>> = yishou.database.think().observeKeys()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val materials: StateFlow<List<Material>> = yishou.database.think().observeMaterials()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val outside: StateFlow<List<com.yishou.app.data.Outside>> = yishou.database.jobs().observeOutsideSince(System.currentTimeMillis() - 30L * 86_400_000)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()
    private val _progress = MutableStateFlow<String?>(null)
    /** 导入文件时的进度 */
    val progress: StateFlow<String?> = _progress.asStateFlow()
    private val _results = MutableStateFlow<List<com.yishou.app.system.FileImport.FileResult>>(emptyList())
    val results: StateFlow<List<com.yishou.app.system.FileImport.FileResult>> = _results.asStateFlow()

    fun importFiles(uris: List<android.net.Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _results.value = emptyList()
        _progress.value = "准备读 ${uris.size} 个文件……"
        _results.value = yishou.fileImport.importAll(uris) { _progress.value = it }
        _progress.value = null
    }

    /** 去刷题 App 做一组：开一件“做事”（一步：在那边做完），回来截练习报告 */
    fun startOutside(app: com.yishou.app.outside.AiApp, subject: String, count: Int, onJob: (Long) -> Unit) = viewModelScope.launch {
        val dao = yishou.database.jobs()
        val now = System.currentTimeMillis()
        val title = "${app.name}：$subject $count 题"
        val id = dao.insertJob(com.yishou.app.data.Job(title = title, mode = com.yishou.app.data.JobMode.APP, estimateMinutes = null, createdAt = now, app = app.pkg ?: app.url))
        dao.insertStep(com.yishou.app.data.JobStep(jobId = id, idx = 0, text = "在${app.name}里做 $count 道$subject", startedAt = now))
        onJob(id)
    }

    fun import(text: String?) = viewModelScope.launch {
        if (text.isNullOrBlank()) {
            _message.value = "剪贴板是空的。先在 GPT / Gemini 那边长按回答 → 复制。"
            return@launch
        }
        _message.value = yishou.bankImport.import(text).line()
    }

    fun dismiss() {
        _message.value = null
    }
}

/**
 * 练功底：有标准答案的题。题从你信得过的地方来——
 * 在 GPT、Gemini 里按一手给的格式出题，或者从粉笔、真题册里复制过来，粘贴或分享进一手，本机直接拆，不经过模型。
 * 一手自己出题只是题库空了时的后备。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BankScreen(onBack: () -> Unit, onPractice: () -> Unit, onEssay: (Long) -> Unit, onJob: (Long) -> Unit, vm: BankViewModel = viewModel()) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val clipboard = LocalClipboardManager.current
    val bank by vm.bank.collectAsStateWithLifecycle()
    val attempts by vm.attempts.collectAsStateWithLifecycle()
    val keys by vm.keys.collectAsStateWithLifecycle()
    val materials by vm.materials.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val progress by vm.progress.collectAsStateWithLifecycle()
    val results by vm.results.collectAsStateWithLifecycle()
    val outside by vm.outside.collectAsStateWithLifecycle()
    val files = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        vm.importFiles(uris)
    }
    var drillSubject by rememberSaveable { mutableStateOf("资料分析") }
    var drillCount by rememberSaveable { mutableIntStateOf(20) }
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    var type by rememberSaveable { mutableStateOf(BankPrompt.TYPES.first()) }
    var count by rememberSaveable { mutableIntStateOf(10) }
    var level by rememberSaveable { mutableStateOf(BankPrompt.LEVELS[1]) }

    val left = bank.count { it.usedAt == null }
    val bankIds = remember(bank) { bank.map { it.id }.toSet() }
    val done = attempts.filter { it.cardId in bankIds }
    val stats = Progress.stats(done)
    val keyed = remember(keys) { keys.map { it.materialId }.toSet() }
    val essays = materials.filter { it.id in keyed }
    val types = remember(bank) {
        bank.mapNotNull { runCatching { Card.fromJson(it.json).type }.getOrNull() }
            .map { it.ifBlank { "未标题型" } }
            .groupingBy { it }.eachCount().entries.sortedByDescending { it.value }
    }

    Scaffold(topBar = { BackTopBar("练功底 · 题库", onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("选择题 ${bank.size} 道，还没做 $left 道；申论 ${essays.size} 道", style = MaterialTheme.typography.titleMedium)
                    if (done.isNotEmpty()) {
                        Text(
                            "最近 30 天做了 ${done.size} 道，首答对 ${Progress.Stats.ratio(stats.firstCorrect, stats.total)}，把握高的对了 ${Progress.Stats.ratio(stats.highConfCorrect, stats.highConf)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (types.isNotEmpty()) {
                        Text(types.take(6).joinToString("  ") { "${it.key} ${it.value}" }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Button(onClick = onPractice, modifier = Modifier.fillMaxWidth().height(56.dp)) {
                    Text(if (left > 0) "练一组（先做题库里的）" else "练一组（题库空了，教练现出）", style = MaterialTheme.typography.titleMedium)
                }
            }
            item {
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("去出题", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "挑好题型，点一个 AI：装了 App 就直接把出题要求发进去（没装就开网页）。那边出完后，长按回答 → 分享 → 收进一手；或者复制，回来点“粘贴题包”。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            BankPrompt.TYPES.forEach { t -> FilterChip(selected = type == t, onClick = { type = t }, label = { Text(t) }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                            listOf(5, 10, 20).forEach { n -> FilterChip(selected = count == n, onClick = { count = n }, label = { Text("$n 道") }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            BankPrompt.LEVELS.forEach { l -> FilterChip(selected = level == l, onClick = { level = l }, label = { Text(l) }) }
                        }
                        val n = if (BankPrompt.isEssay(type)) minOf(count, 3) else count
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            com.yishou.app.outside.AiApps.parse(prefs.bankLinks).forEach { a ->
                                val has = Launch.installed(context, a.pkg)
                                OutlinedButton(onClick = {
                                    Toast.makeText(context, Launch.sendPrompt(context, a, BankPrompt.build(type, n, level)), Toast.LENGTH_LONG).show()
                                }) { Text(if (has) a.name else "${a.name}（网页）") }
                            }
                            TextButton(onClick = {
                                clipboard.setText(AnnotatedString(BankPrompt.build(type, n, level)))
                                Toast.makeText(context, "出题要求已复制", Toast.LENGTH_SHORT).show()
                            }) { Text("只复制要求") }
                        }
                        if (BankPrompt.isEssay(type)) {
                            Text("申论一次出 1–3 道就好，材料长。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Text("列表可以在 设置 → 题库 里改成你常用的。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { vm.import(clipboard.getText()?.text) }, modifier = Modifier.weight(1f)) { Text("粘贴题包") }
                        OutlinedButton(
                            onClick = {
                                ExternalGrace.open()
                                files.launch(arrayOf("text/*", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/msword", "application/pdf", "image/*"))
                            },
                            modifier = Modifier.weight(1f),
                        ) { Text("导入文件") }
                    }
                    Text(
                        "文件可以一次选很多个：Word（.docx）、txt 本机直接拆；PDF、图片要识图模型一页页读。文末集中给答案的（“参考答案 1-5 BCADA”）也认。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    progress?.let { VortexLoader(it) }
                    results.forEach { r ->
                        Text("${if (r.ok) "✓" else "✗"} ${r.name}：${r.line}", style = MaterialTheme.typography.bodySmall, color = if (r.ok) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
                    }
                    Text(
                        "认【题目】【材料】A–D【答案】【依据】【解析】【要点】；粉笔、真题册里复制的“1. … A. … 答案：B”也认。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    message?.let {
                        Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                        TextButton(onClick = vm::dismiss) { Text("知道了") }
                    }
                }
            }
            item {
                val apps = com.yishou.app.outside.AiApps.parse(prefs.drillApps)
                Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("去刷题 App 做一组", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "在粉笔这类 App 里做，做完截一张练习报告（题数、正确率、用时），回来交给一手记下。一手只看你给的截图。",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        Text(com.yishou.app.outside.Outsides.line(com.yishou.app.outside.Outsides.summary(outside)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.tertiary)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("言语理解", "判断推理", "数量关系", "资料分析", "常识判断", "申论").forEach { t ->
                                FilterChip(selected = drillSubject == t, onClick = { drillSubject = t }, label = { Text(t) })
                            }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(10, 20, 40).forEach { c -> FilterChip(selected = drillCount == c, onClick = { drillCount = c }, label = { Text("$c 题") }) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            apps.forEach { a ->
                                Button(onClick = {
                                    vm.startOutside(a, drillSubject, drillCount) { id ->
                                        onJob(id)
                                        if (!Launch.open(context, a)) Toast.makeText(context, "打不开 ${a.name}，在设置里检查包名", Toast.LENGTH_SHORT).show()
                                    }
                                }) { Text("去${a.name}") }
                            }
                        }
                    }
                }
            }
            if (essays.isNotEmpty()) {
                item { Text("申论", style = MaterialTheme.typography.titleMedium) }
                items(essays, key = { it.id }) { m ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.fillMaxWidth().clickable { onEssay(m.id) },
                    ) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row {
                                Text(m.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(if (m.uses > 0) "写过 ${m.uses} 次" else "新", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.tertiary)
                            }
                            Text(m.text, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
