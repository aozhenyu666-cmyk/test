package com.yishou.app.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.CardAttempt
import com.yishou.app.data.DrillRow
import com.yishou.app.data.Material
import com.yishou.app.data.MaterialSource
import com.yishou.app.llm.LlmResult
import com.yishou.app.system.ExternalGrace
import com.yishou.app.think.DrillKey
import com.yishou.app.think.DrillSession
import com.yishou.app.think.DrillState
import com.yishou.app.think.DrillStats
import com.yishou.app.think.Lens
import com.yishou.app.think.StanceItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class ThinkViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.think()
    private val session: DrillSession = yishou.newDrill()
    val drill: StateFlow<DrillState> = session.state

    private val since = System.currentTimeMillis() - 30L * 24 * 3600 * 1000

    val materials: StateFlow<List<Material>> = dao.observeMaterials()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val drills: StateFlow<List<DrillRow>> = dao.observeDrillsSince(since)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    val cards: StateFlow<List<CardAttempt>> = yishou.database.cards().observeAttemptsSince(since)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
    /** 题库申论题锁住的参考要点：materialId → 答案 */
    val keys: StateFlow<Map<Long, DrillKey>> = dao.observeKeys()
        .map { list -> list.mapNotNull { k -> runCatching { k.materialId to DrillKey.fromJson(k.key) }.getOrNull() }.toMap() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _busy = MutableStateFlow<String?>(null)
    /** 素材进来时的进度或失败原因 */
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _opened = MutableStateFlow<Long?>(null)
    /** 展开练法的那段素材 */
    val opened: StateFlow<Long?> = _opened.asStateFlow()

    fun open(id: Long?) {
        _opened.value = if (_opened.value == id) null else id
    }

    fun show(id: Long?) {
        _opened.value = id
    }

    private fun bring(label: String, block: suspend () -> LlmResult<Material>) {
        viewModelScope.launch {
            _busy.value = label
            when (val r = block()) {
                is LlmResult.Err -> _busy.value = r.error.message
                is LlmResult.Ok -> {
                    _busy.value = null
                    _opened.value = r.value.id
                }
            }
        }
    }

    fun paste(text: String) = bring("存进素材库……") { yishou.materials.addText(text, MaterialSource.PASTE) }
    fun typed(text: String) = bring("存进素材库……") { yishou.materials.addText(text, MaterialSource.TYPED) }
    fun photo(uri: Uri) = bring("正在读图里的正文……") { yishou.materials.addImage(uri, MaterialSource.PHOTO) }
    fun make(topic: String?) = bring("教练在写一段素材……") { yishou.materials.make(topic) }

    fun dismissBusy() {
        _busy.value = null
    }

    fun delete(m: Material) = viewModelScope.launch { dao.deleteMaterial(m) }

    fun begin(m: Material?, lens: Lens) = viewModelScope.launch { session.start(m, lens) }

    /** 带参考要点的题（申论）：答案导入时就锁住了，直接作答 */
    fun beginLocked(id: Long) = viewModelScope.launch {
        val m = dao.getMaterial(id) ?: return@launch
        val k = dao.getKey(id)?.let { runCatching { DrillKey.fromJson(it.key) }.getOrNull() } ?: return@launch
        session.startLocked(m, k)
    }

    fun selfMark(marks: List<Boolean>) = viewModelScope.launch { session.selfMark(marks) }
    fun retry() = viewModelScope.launch { session.retry() }
    fun answer(text: String) = viewModelScope.launch { session.answer(text) }
    fun stance(parts: Map<StanceItem, String>) = viewModelScope.launch { session.stance(parts) }
    fun reply(text: String) = viewModelScope.launch { session.reply(text) }
    fun skipReply() = viewModelScope.launch { session.skipReply() }
    fun range(low: Double, high: Double) = viewModelScope.launch { session.range(low, high) }
    fun predict(claim: String, conf: Int) = viewModelScope.launch { session.predict(claim, conf) }
    fun dispute() = viewModelScope.launch { session.dispute() }
    fun takeaway(text: String) = viewModelScope.launch { session.takeaway(text) }
    fun stop() = session.stop()
}

/**
 * 练思维。题卡只是其中一种练法；更多时候是拿一段真东西（你分享来的文章、截图、自己的一段话），换着练法练：
 * 抓主干、挖前提、站立场、估个数、押后果。判分的标准都在你作答前锁住。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThinkScreen(
    onBack: () -> Unit,
    onCards: (Long?) -> Unit,
    initialMaterial: Long? = null,
    initialLens: String? = null,
    vm: ThinkViewModel = viewModel(),
) {
    val drill by vm.drill.collectAsStateWithLifecycle()
    val materials by vm.materials.collectAsStateWithLifecycle()
    val drills by vm.drills.collectAsStateWithLifecycle()
    val cards by vm.cards.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val opened by vm.opened.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var writing by rememberSaveable { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::photo) }
    val keys by vm.keys.collectAsStateWithLifecycle()
    LaunchedEffect(initialMaterial, initialLens) {
        if (initialMaterial == null) return@LaunchedEffect
        vm.show(initialMaterial)
        if (initialLens == Lens.ESSAY.name) vm.beginLocked(initialMaterial)
    }

    BackHandler(enabled = drill.active) { vm.stop() }

    Scaffold(topBar = { BackTopBar(if (drill.active) drill.lens.label else "练思维") { if (drill.active) vm.stop() else onBack() } }) { padding ->
        if (drill.active) {
            DrillView(
                s = drill,
                vm = vm,
                onOtherLens = { m -> vm.stop(); vm.show(m?.id) },
                modifier = Modifier.padding(padding),
            )
            return@Scaffold
        }
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Overview(drills, cards) }
            item {
                Text(
                    "同一段东西，换个练法。标准在你作答前就锁住；说你“说到了”，必须拿得出你的原话。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = { vm.make(null) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("教练写一段") }
                    OutlinedButton(onClick = { vm.begin(null, Lens.ESTIMATE) }, modifier = Modifier.weight(1f).height(52.dp)) { Text("估个数") }
                }
            }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("素材库", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { clipboard.getText()?.text?.let(vm::paste) }, label = { Text("粘贴") })
                        AssistChip(onClick = {
                            ExternalGrace.open()
                            picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                        }, label = { Text("照片 / 截图") })
                        AssistChip(onClick = { writing = true }, label = { Text("自己写") })
                    }
                    Text(
                        "在别的应用里点“分享 → 收进一手”，文章、帖子、截图都能直接进来。",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            busy?.let { b ->
                item {
                    if (b.endsWith("……")) VortexLoader(b) else {
                        Column {
                            Text(b, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = vm::dismissBusy) { Text("知道了") }
                        }
                    }
                }
            }
            if (materials.isEmpty() && busy == null) {
                item {
                    Text(
                        "还没有素材。读到一段觉得有道理（或者觉得哪里不对）的话，就分享进来；或者先让教练写一段。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            items(materials, key = { it.id }) { m ->
                MaterialCard(
                    m = m,
                    open = opened == m.id,
                    locked = m.id in keys,
                    onLocked = { vm.beginLocked(m.id) },
                    onToggle = { vm.open(m.id) },
                    onLens = { lens -> vm.begin(m, lens) },
                    onCards = { onCards(m.id) },
                    onDelete = { vm.delete(m) },
                )
            }
        }
    }

    if (writing) {
        var text by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { writing = false },
            title = { Text("自己写一段") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("一个观点、一件想不通的事、今天听到的一句话都行。", style = MaterialTheme.typography.bodySmall)
                    VoiceTextField(text, { text = it }, "写下来", Modifier.fillMaxWidth(), minLines = 4)
                }
            },
            confirmButton = {
                TextButton(onClick = { writing = false; vm.typed(text) }, enabled = text.isNotBlank()) { Text("存下") }
            },
            dismissButton = { TextButton(onClick = { writing = false }) { Text("算了") } },
        )
    }
}

/** 最近 30 天的螺旋，加上各练法的成绩 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Overview(drills: List<DrillRow>, cards: List<CardAttempt>) {
    val seeds = remember(drills, cards) {
        (cards.filter { it.verified }.map { it.createdAt to Seed(MarkKind.CARD, it.firstCorrect && it.help == 0) } +
            drills.filter { !it.disputed }.map { it.createdAt to Seed(MarkKind.DRILL, if (it.score < 0) null else DrillStats.good(it)) })
            .sortedBy { it.first }
            .map { it.second }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Box(Modifier.weight(0.45f)) { SeedSpiral(seeds, Modifier.fillMaxWidth()) }
        Column(Modifier.weight(0.55f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("最近 30 天 ${seeds.size} 次", style = MaterialTheme.typography.titleMedium)
            Text("黑子是题卡，金子是别的练法；空心是没做到。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            DrillStats.lines(drills).forEach { l ->
                Row {
                    Text(l.lens.label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Text(l.avg?.let { "$it 分" } ?: "没练过", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

private val QUESTION = Regex("(?m)^\\s*[A-D][.、．]")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MaterialCard(
    m: Material,
    open: Boolean,
    locked: Boolean,
    onLocked: () -> Unit,
    onToggle: () -> Unit,
    onLens: (Lens) -> Unit,
    onCards: () -> Unit,
    onDelete: () -> Unit,
) {
    var confirmDelete by remember { mutableStateOf(false) }
    Surface(
        color = if (open) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(18.dp),
        tonalElevation = if (open) 1.dp else 0.dp,
        modifier = Modifier.fillMaxWidth().animateContentSize().clickable(onClick = onToggle),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(m.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    MaterialSource.label(m.source) + if (m.uses > 0) " · 练过 ${m.uses} 次" else " · 新",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (m.uses == 0) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Text(
                m.text,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (open) 30 else 2,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (open) {
                val lenses = Lens.suggest(m.text)
                Text("怎么练", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (locked) Button(onClick = onLocked) { Text("按题目作答（有参考要点）") }
                    lenses.forEachIndexed { i, lens ->
                        if (i == 0 && !locked) {
                            Button(onClick = { onLens(lens) }) { Text("${lens.label}（推荐）") }
                        } else {
                            OutlinedButton(onClick = { onLens(lens) }) { Text(lens.label) }
                        }
                    }
                    if (QUESTION.containsMatchIn(m.text)) OutlinedButton(onClick = onCards) { Text("当题卡做") }
                }
                Text(lenses.first().short, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row {
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = { confirmDelete = true }) { Text("删掉", color = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text("删掉这段素材？") },
            text = { Text("练过的记录还留着。") },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete() }) { Text("删掉") } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("留着") } },
        )
    }
}
