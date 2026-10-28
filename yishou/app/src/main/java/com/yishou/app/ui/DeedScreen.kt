package com.yishou.app.ui

import android.app.Application
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.YishouApp
import com.yishou.app.data.Deed
import com.yishou.app.data.DrillRow
import com.yishou.app.deed.Compare
import com.yishou.app.deed.Deeds
import com.yishou.app.deed.Goods
import com.yishou.app.llm.LlmResult
import com.yishou.app.settings.AppZone
import com.yishou.app.system.ExternalGrace
import com.yishou.app.think.Check
import com.yishou.app.think.Estimate
import com.yishou.app.think.Words
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/** 比一比：认出的商品、你挑的那件、揭晓没有 */
data class CompareState(val goods: List<Goods>, val pick: Int? = null, val revealed: Boolean = false)

class DeedViewModel(app: Application) : AndroidViewModel(app) {
    private val yishou = app as YishouApp
    private val dao = yishou.database.think()

    val deeds: StateFlow<List<Deed>> = dao.observeDeeds()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy.asStateFlow()
    private val _compare = MutableStateFlow<CompareState?>(null)
    val compare: StateFlow<CompareState?> = _compare.asStateFlow()
    /** 从截图里读出的实际数：deed.id → (数, 图里这个数是什么) */
    private val _read = MutableStateFlow<Map<Long, Pair<Double, String>>>(emptyMap())
    val read: StateFlow<Map<Long, Pair<Double, String>>> = _read.asStateFlow()

    init {
        // 分享页认好的商品
        yishou.pendingGoods.value?.let {
            _compare.value = CompareState(it)
            yishou.pendingGoods.value = null
        }
    }

    fun add(title: String, plan: String, estimate: Double, unit: String) = viewModelScope.launch {
        dao.insertDeed(Deed(title = title.trim(), plan = plan.trim(), estimate = estimate, unit = unit.trim(), createdAt = System.currentTimeMillis()))
    }

    fun resolve(d: Deed, actual: Double, note: String) = viewModelScope.launch {
        dao.updateDeed(d.copy(actual = actual, note = note.trim(), resolvedAt = System.currentTimeMillis()))
        _read.value = _read.value - d.id
    }

    fun delete(d: Deed) = viewModelScope.launch { dao.deleteDeed(d) }

    fun readActual(d: Deed, uri: Uri) = viewModelScope.launch {
        _busy.value = "正在从截图里找“${d.title}”的实际数……"
        when (val r = yishou.materials.amount(uri, d.title, d.unit)) {
            is LlmResult.Err -> _busy.value = r.error.message
            is LlmResult.Ok -> {
                val (v, what) = r.value
                if (v == null) {
                    _busy.value = "图里没找到能对照的数${if (what.isNotBlank()) "（看到的是：$what）" else ""}。手填也行。"
                } else {
                    _busy.value = null
                    _read.value = _read.value + (d.id to (v to what))
                }
            }
        }
    }

    fun scanGoods(uri: Uri) = viewModelScope.launch {
        _busy.value = "正在认商品和规格……"
        when (val r = yishou.materials.goods(uri)) {
            is LlmResult.Err -> _busy.value = r.error.message
            is LlmResult.Ok -> if (r.value.size < 2) {
                _busy.value = "只认出 ${r.value.size} 件商品，比不起来。换一张能看到两件以上的图。"
            } else {
                _busy.value = null
                _compare.value = CompareState(r.value)
            }
        }
    }

    fun pick(i: Int) {
        _compare.value = _compare.value?.takeIf { !it.revealed }?.copy(pick = i) ?: _compare.value
    }

    /** 揭晓：本机算单价，记一笔 */
    fun reveal() = viewModelScope.launch {
        val c = _compare.value ?: return@launch
        val pick = c.pick ?: return@launch
        _compare.value = c.copy(revealed = true)
        val ok = Compare.correct(c.goods, pick) ?: return@launch
        val over = Compare.overpay(c.goods, pick) ?: 0
        val key = JSONObject().put("goods", JSONArray().apply {
            c.goods.forEach { put(JSONObject().put("name", it.name).put("price", it.price).put("spec", it.spec)) }
        }).toString()
        val now = System.currentTimeMillis()
        dao.insertDrill(
            DrillRow(
                materialId = null,
                lens = DrillRow.COMPARE,
                key = key,
                answer = c.goods[pick].name,
                checks = Check.listToJson(listOf(Check("挑最划算的", ok, note = if (ok) "" else "贵了 $over%"))),
                score = if (ok) 100 else (100 - over * 2).coerceAtLeast(0),
                durationMs = 0,
                createdAt = now,
            ),
        )
    }

    fun closeCompare() {
        _compare.value = null
    }

    fun dismissBusy() {
        _busy.value = null
    }
}

/**
 * 做实操：思维用在真事上。
 * - 先估再做：做之前估一个数（花多少钱、用多久、买几样），做完拿小票、订单、计时的截图或手填来对照，判卷的是现实；
 * - 比一比：截一张商品列表，先凭感觉挑最划算的，再看本机折算的单价。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeedScreen(onBack: () -> Unit, vm: DeedViewModel = viewModel()) {
    val deeds by vm.deeds.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    val compare by vm.compare.collectAsStateWithLifecycle()
    val read by vm.read.collectAsStateWithLifecycle()
    var reading by remember { mutableStateOf<Deed?>(null) }
    val goodsPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let(vm::scanGoods) }
    val actualPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        val d = reading
        if (uri != null && d != null) vm.readActual(d, uri)
        reading = null
    }
    fun pickImage(launch: () -> Unit) {
        ExternalGrace.open()
        launch()
    }
    val summary = remember(deeds) { Deeds.summary(deeds) }

    Scaffold(topBar = { BackTopBar("小工具：估数与比价", onBack) }) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(0.4f)) {
                        SeedSpiral(
                            deeds.sortedBy { it.createdAt }.map { d -> Seed(MarkKind.DEED, d.actual?.let { Deeds.good(d.estimate, it) }) },
                            Modifier.fillMaxWidth(),
                        )
                    }
                    Column(Modifier.weight(0.6f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("先估，再做，再对照", style = MaterialTheme.typography.titleMedium)
                        Text(Deeds.line(summary), style = MaterialTheme.typography.bodySmall)
                        Text("判卷的是现实：小票、订单、计时器。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            item { NewDeed(onAdd = { t, p, e, u -> vm.add(t, p, e, u) }) }
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("比一比", style = MaterialTheme.typography.titleMedium)
                        Text("截一张商品列表或价签：先凭感觉挑最划算的那件，再看折算成每斤、每升、每个的价格。", style = MaterialTheme.typography.bodySmall)
                        OutlinedButton(onClick = { pickImage { goodsPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) } }) {
                            Text("选一张截图")
                        }
                    }
                }
            }
            busy?.let { b ->
                item {
                    if (b.endsWith("……")) MatrixLoader(b) else {
                        Column {
                            Text(b, color = MaterialTheme.colorScheme.error)
                            TextButton(onClick = vm::dismissBusy) { Text("知道了") }
                        }
                    }
                }
            }
            compare?.let { c -> item { ComparePanel(c, onPick = vm::pick, onReveal = { vm.reveal() }, onClose = vm::closeCompare) } }
            val open = deeds.filter { it.actual == null }
            if (open.isNotEmpty()) {
                item { Text("等对照", style = MaterialTheme.typography.titleMedium) }
                items(open, key = { it.id }) { d ->
                    OpenDeed(
                        d = d,
                        read = read[d.id],
                        onScan = {
                            reading = d
                            pickImage { actualPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }
                        },
                        onResolve = { a, note -> vm.resolve(d, a, note) },
                        onDelete = { vm.delete(d) },
                    )
                }
            }
            val done = deeds.filter { it.actual != null }
            if (done.isNotEmpty()) {
                item { Text("对照过的", style = MaterialTheme.typography.titleMedium) }
                items(done, key = { it.id }) { d -> DoneDeed(d) }
            }
        }
    }
}

private val UNITS = listOf("元", "分钟", "个", "斤")

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NewDeed(onAdd: (String, String, Double, String) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var plan by rememberSaveable { mutableStateOf("") }
    var estimate by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableStateOf("元") }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("先估再做", style = MaterialTheme.typography.titleMedium)
            VoiceTextField(title, { title = it }, "要做的事（例如：去超市买这周的菜）", Modifier.fillMaxWidth())
            VoiceTextField(plan, { plan = it }, "打算怎么做 / 买什么（可不填）", Modifier.fillMaxWidth(), minLines = 2)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    estimate, { estimate = it },
                    label = { Text("我估") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedTextField(unit, { unit = it }, label = { Text("单位") }, singleLine = true, modifier = Modifier.weight(0.6f))
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                UNITS.forEach { u -> FilterChip(selected = unit == u, onClick = { unit = u }, label = { Text(u) }) }
            }
            val value = estimate.toDoubleOrNull() ?: Words.numbers(estimate).firstOrNull()
            Button(
                onClick = {
                    if (value != null) onAdd(title, plan, value, unit)
                    title = ""; plan = ""; estimate = ""
                },
                enabled = title.isNotBlank() && value != null && unit.isNotBlank(),
                modifier = Modifier.fillMaxWidth().height(48.dp),
            ) { Text("估好了，去做") }
        }
    }
}

@Composable
private fun OpenDeed(d: Deed, read: Pair<Double, String>?, onScan: () -> Unit, onResolve: (Double, String) -> Unit, onDelete: () -> Unit) {
    var actual by rememberSaveable(d.id, read) {
        mutableStateOf(read?.first?.let { if (it == Math.floor(it)) it.toLong().toString() else it.toString() } ?: "")
    }
    var note by rememberSaveable(d.id) { mutableStateOf("") }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            DeedHead(d)
            read?.let { (v, what) ->
                Text("截图里读到：${Estimate.show(v)}${d.unit}${if (what.isNotBlank()) "（$what）" else ""}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    actual, { actual = it },
                    label = { Text("实际（${d.unit}）") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(onClick = onScan) { Text("读截图") }
            }
            VoiceTextField(note, { note = it }, "差在哪（一句话）", Modifier.fillMaxWidth())
            val a = actual.toDoubleOrNull()
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { if (a != null) onResolve(a, note) }, enabled = a != null) { Text("对照") }
                TextButton(onClick = onDelete) { Text("不做了", color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun DoneDeed(d: Deed) {
    val a = d.actual ?: return
    val verdict = Deeds.verdict(d.estimate, a)
    Surface(color = MaterialTheme.colorScheme.surfaceContainer, shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(d.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                Text(
                    verdict,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (Deeds.good(d.estimate, a)) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.error,
                )
            }
            val pct = (Deeds.error(d.estimate, a) * 100).toInt()
            Text("估 ${Estimate.show(d.estimate)}${d.unit} · 实际 ${Estimate.show(a)}${d.unit} · 差 $pct%", style = MaterialTheme.typography.bodyMedium)
            if (d.note.isNotBlank()) Text(d.note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DeedHead(d: Deed) {
    Text(d.title, style = MaterialTheme.typography.titleMedium)
    if (d.plan.isNotBlank()) Text(d.plan, style = MaterialTheme.typography.bodyMedium)
    Text(
        "估：${Estimate.show(d.estimate)}${d.unit} · ${AppZone.format("M月d日 HH:mm", d.createdAt)}",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun ComparePanel(c: CompareState, onPick: (Int) -> Unit, onReveal: () -> Unit, onClose: () -> Unit) {
    val priced = remember(c.goods) { Compare.price(c.goods) }
    val group = remember(c.goods) { Compare.group(c.goods).map { it.index }.toSet() }
    val best = remember(c.goods) { Compare.best(c.goods) }
    Surface(color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(18.dp), tonalElevation = 1.dp, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (c.revealed) "揭晓" else "先凭感觉：哪件最划算？", style = MaterialTheme.typography.titleMedium)
            priced.forEach { p ->
                val chosen = c.pick == p.index
                val isBest = c.revealed && p.index == best
                Surface(
                    color = when {
                        isBest -> MaterialTheme.colorScheme.tertiaryContainer
                        chosen -> MaterialTheme.colorScheme.secondaryContainer
                        else -> MaterialTheme.colorScheme.surfaceContainer
                    },
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !c.revealed) { onPick(p.index) },
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(p.goods.name, style = MaterialTheme.typography.bodyLarge, fontWeight = if (chosen) FontWeight.SemiBold else FontWeight.Normal)
                            Text("${Compare.show(p.goods.price)} 元 · ${p.goods.spec.ifBlank { "规格没认出" }}", style = MaterialTheme.typography.bodySmall)
                        }
                        if (c.revealed) {
                            Text(
                                when {
                                    p.unitPrice == null -> "没法折算"
                                    p.index !in group -> "${Compare.show(p.unitPrice)}/${p.dim?.per}（不同类）"
                                    else -> "${Compare.show(p.unitPrice)} 元/${p.dim?.per}"
                                },
                                style = MaterialTheme.typography.labelLarge,
                            )
                        } else if (chosen) {
                            Text("我挑这件", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            if (!c.revealed) {
                Button(onClick = onReveal, enabled = c.pick != null, modifier = Modifier.fillMaxWidth()) { Text("揭晓") }
            } else {
                val pick = c.pick
                val ok = pick?.let { Compare.correct(c.goods, it) }
                Text(
                    when (ok) {
                        null -> "能比的不到两件（规格不同类或没认出），这次不算。"
                        true -> "挑对了。"
                        false -> "最划算的是“${c.goods[best!!].name}”，你挑的贵了 ${Compare.overpay(c.goods, pick ?: 0)}%。"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text("单价是本机按规格算的，不是模型算的。规格认错了可以对着图自己核一下。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TextButton(onClick = onClose) { Text("收起") }
        }
    }
}
