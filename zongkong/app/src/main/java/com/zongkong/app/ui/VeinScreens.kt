package com.zongkong.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.ApiResult
import com.zongkong.core.vein.Candidate
import com.zongkong.core.vein.NodeType
import com.zongkong.core.vein.Rel
import com.zongkong.core.vein.VNode
import com.zongkong.core.vein.VStatus
import com.zongkong.core.vein.Vein
import com.zongkong.core.vein.VeinAi
import com.zongkong.core.vein.Veins
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

// ---------- 小部件 ----------

/** 节点的印章：类型一个字；假设 = 琥珀虚线框，已验证 = 实心墨，已推翻 = 灰。 */
@Composable
fun NodeSeal(n: VNode, size: androidx.compose.ui.unit.Dp = 34.dp) {
    val sig = LocalSignals.current
    val st = n.vStatus
    val color = when (st) {
        VStatus.HYPO -> sig.warn
        VStatus.VERIFIED -> sig.think
        VStatus.OVERTURNED -> sig.muted
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val r = androidx.compose.ui.geometry.CornerRadius(this.size.minDimension * 0.22f)
            if (st == VStatus.VERIFIED) drawRoundRect(color, cornerRadius = r)
            else drawRoundRect(
                color, cornerRadius = r,
                style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f, pathEffect = if (st == VStatus.HYPO) PathEffect.dashPathEffect(floatArrayOf(7f, 5f)) else null),
            )
        }
        Text(n.nodeType.seal, color = if (st == VStatus.VERIFIED) Color.White else color, fontWeight = FontWeight.Black, fontSize = (size.value * 0.46f).sp)
    }
}

/** 把握表盘：40 格刻度，朱砂点是“秒针”。 */
@Composable
fun ConfidenceDial(conf: Int?, modifier: Modifier = Modifier) {
    val sig = LocalSignals.current
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val c = Offset(size.width / 2, size.height / 2)
            val r = size.minDimension / 2 * 0.92f
            for (i in 0 until 40) {
                val a = (-Math.PI / 2 + 2 * Math.PI * i / 40).toFloat()
                val long = i % 5 == 0
                val on = conf != null && i < conf * 40 / 100
                val r0 = r * if (long) 0.74f else 0.82f
                drawLine(if (on) ink else ink.copy(alpha = 0.18f), Offset(c.x + r0 * cos(a), c.y + r0 * sin(a)), Offset(c.x + r * cos(a), c.y + r * sin(a)), strokeWidth = if (long) 3f else 1.6f, cap = StrokeCap.Round)
            }
            if (conf != null) {
                val a = (-Math.PI / 2 + 2 * Math.PI * conf / 100).toFloat()
                drawCircle(sig.strict, r * 0.08f, Offset(c.x + r * 0.6f * cos(a), c.y + r * 0.6f * sin(a)))
            }
        }
        Text(conf?.let { "$it%" } ?: "—", style = MaterialTheme.typography.titleMedium.merge(Mono))
    }
}

@Composable
private fun NodeRow(n: VNode, depth: Int = 0, onClick: () -> Unit) {
    val sig = LocalSignals.current
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(start = (depth * 16).dp, top = 6.dp, bottom = 6.dp).testTag("vein-node-${n.id}"),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        NodeSeal(n, 28.dp)
        Column(Modifier.weight(1f)) {
            Text(
                n.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textDecoration = if (n.vStatus == VStatus.OVERTURNED) TextDecoration.LineThrough else null,
            )
            Text(n.claim, style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        n.confidence?.let { Text("$it%", style = MaterialTheme.typography.labelMedium.merge(Mono), color = sig.muted) }
    }
}

private fun clip(context: android.content.Context, text: String) {
    context.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("脉络", text))
}

// ---------- 首页：体系和结构 ----------

/**
 * 脉络：谋划思考部的知识结构。体系 → 层级 → 节点 → 关系。
 * AI 拆、你确认；假设要标把握和验证方式；体检、溯源、温故都不花 token。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VeinScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val v by app.store.vein.collectAsStateWithLifecycle()
    val today = remember { app.store.dateToday() }
    var sel by remember(v.systems.size) { mutableStateOf(v.systems.firstOrNull()?.name) }
    var menu by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var newSystem by remember { mutableStateOf(false) }
    var newLevel by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val collapsed = remember { mutableStateListOf<String>() }
    val health = remember(v, sel) { Veins.health(v, sel, today) }
    val due = remember(v) { Veins.dueReviews(v, today) }
    val pickFolder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) message = app.actions.vein.importTree(uri)
    }
    val pickZip = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) message = app.actions.vein.importZip(uri)
    }
    val saveZip = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) message = app.actions.vein.exportZip(uri)
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("脉络", { nav.popBackStack() }) {
            Box {
                TextButton(onClick = { menu = true }, modifier = Modifier.testTag("vein-menu")) { Text("···", fontSize = 20.sp, color = sig.muted) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("新建体系") }, onClick = { menu = false; newSystem = true })
                    if (sel != null) DropdownMenuItem(text = { Text("新建层级") }, onClick = { menu = false; newLevel = true })
                    DropdownMenuItem(text = { Text("载入示例体系「思维问题」") }, onClick = { menu = false; message = app.actions.vein.seed() })
                    DropdownMenuItem(text = { Text("从脉络 App 的文件夹导入") }, onClick = { menu = false; pickFolder.launch(null) })
                    DropdownMenuItem(text = { Text("导入 zip") }, onClick = { menu = false; pickZip.launch(arrayOf("application/zip", "application/octet-stream")) })
                    DropdownMenuItem(text = { Text("导出 Obsidian 仓库（zip）") }, onClick = { menu = false; saveZip.launch("脉络-$today.zip") })
                    if (sel != null) DropdownMenuItem(text = { Text("复制「$sel」大纲") }, onClick = { menu = false; clip(context, VeinAi.outline(v, sel!!)); message = "大纲复制好了，可以贴给任何聊天 App" })
                }
            }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Box(Modifier.fillMaxWidth().height(120.dp)) {
                LeafVein(if (v.nodes.isEmpty()) 0.4f else 1f, sig.think, Modifier.fillMaxSize())
            }
            Text("把零散的想法拆成「体系 → 层级 → 节点 → 关系」。AI 只负责提议，结构由你决定；假设要标把握和验证方式。", style = MaterialTheme.typography.bodyMedium, color = sig.muted)

            // ---------- 四个入口 ----------
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VeinTile("录入", "贴材料，AI 拆", sig.think, Modifier.weight(1f).testTag("vein-capture")) { nav.navigate("vein/capture") }
                VeinTile("候选 ${v.candidates.size}", "逐张确认", if (v.candidates.isNotEmpty()) sig.ai else sig.muted, Modifier.weight(1f).testTag("vein-cands")) { nav.navigate("vein/cands") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                VeinTile("温故 ${due.size}", "回想结论", sig.plan, Modifier.weight(1f).testTag("vein-review")) { nav.navigate("vein/review") }
                VeinTile("体检 ${health.score}", "${health.issues.sumOf { it.nodes.size }} 处问题", if (health.score >= 80) sig.free else sig.warn, Modifier.weight(1f).testTag("vein-health")) { nav.navigate("vein/health") }
            }

            if (v.systems.isEmpty()) {
                Panel {
                    Text("还没有体系", style = MaterialTheme.typography.titleMedium)
                    Hint("新建一个体系（例如“思维问题”“考公行测”），或者载入示例体系看看长什么样；已经在用「脉络」App 的话，从它的文件夹导入。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { message = app.actions.vein.seed() }, modifier = Modifier.testTag("vein-seed")) { Text("载入示例") }
                        OutlinedButton(onClick = { newSystem = true }) { Text("新建体系") }
                    }
                }
            }

            // ---------- 搜索 ----------
            OutlinedTextField(query, { query = it }, placeholder = { Text("搜标题、结论、激活词") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (query.isNotBlank()) {
                val hits = Veins.search(v, query)
                Panel {
                    if (hits.isEmpty()) Hint("没搜到")
                    hits.take(12).forEach { n -> NodeRow(n) { nav.navigate("vein/node/${n.id}") } }
                }
            }

            // ---------- 体系切换 + 结构树 ----------
            if (v.systems.isNotEmpty()) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    v.systems.forEach { s -> QuickChip(s.name + " · " + v.list(s.name).size + if (sel == s.name) " ✓" else "", sig.think) { sel = s.name } }
                }
                sel?.let { name ->
                    val sys = v.system(name)
                    Panel(modifier = Modifier.testTag("vein-tree")) {
                        if (!sys?.desc.isNullOrBlank()) Hint(sys!!.desc)
                        fun parentOf(l: String) = if (l.lastIndexOf('/') < 0) "" else l.substring(0, l.lastIndexOf('/'))
                        @Composable
                        fun level(parent: String, depth: Int) {
                            v.list(name).filter { it.parent == parent }.forEach { n -> NodeRow(n, depth) { nav.navigate("vein/node/${n.id}") } }
                            sys?.levels.orEmpty().filter { parentOf(it) == parent }.forEach { lv ->
                                val open = lv !in collapsed
                                Row(
                                    Modifier.fillMaxWidth().clickable { if (open) collapsed += lv else collapsed -= lv }.padding(start = (depth * 16).dp, top = 8.dp, bottom = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(if (open) "▾ " else "▸ ", color = sig.think)
                                    Text(lv.substringAfterLast('/'), style = MaterialTheme.typography.titleSmall, color = sig.think, modifier = Modifier.weight(1f))
                                    Text("${v.list(name).count { it.parent == lv || it.parent.startsWith("$lv/") }}", style = MaterialTheme.typography.labelSmall, color = sig.muted)
                                }
                                if (open) level(lv, depth + 1)
                            }
                        }
                        level("", 0)
                        if (v.list(name).isEmpty()) Hint("这个体系还没有节点。点“录入”贴一段材料。")
                    }
                }
            }
        }
    }

    if (newSystem) {
        EditDialog("新建体系", "", hint = "例：思维问题、考公行测、人际边界", singleLine = true, onDismiss = { newSystem = false }) { name ->
            app.actions.vein.update { Veins.addSystem(it, name, "", today.toString()) }
            sel = name.trim()
            newSystem = false
        }
    }
    if (newLevel && sel != null) {
        EditDialog("在「$sel」里新建层级", "", hint = "例：加工层；子层级用 / 分隔：加工层/二次加工（最多 3 层）", singleLine = true, onDismiss = { newLevel = false }) { path ->
            var err: String? = null
            app.actions.vein.update { Veins.addLevel(it, sel!!, path, today.toString()).getOrElse { e -> err = e.message; it } }
            message = err
            newLevel = false
        }
    }
    OutcomeDialog(message) { message = null }
}

@Composable
private fun VeinTile(title: String, sub: String, color: Color, modifier: Modifier, onClick: () -> Unit) {
    Panel(modifier = modifier, onClick = onClick) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = color)
        Text(sub, style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted)
    }
}

// ---------- 录入 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VeinCaptureScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val v by app.store.vein.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var system by remember { mutableStateOf(v.systems.firstOrNull()?.name ?: "") }
    var source by remember { mutableStateOf("") }
    var text by remember { mutableStateOf(app.veinDraft.also { app.veinDraft = "" }) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var manual by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar("录入", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("放进哪个体系", style = MaterialTheme.typography.labelLarge, color = sig.muted)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                v.systems.forEach { s -> QuickChip(s.name + if (system == s.name) " ✓" else "", sig.think) { system = s.name } }
            }
            OutlinedTextField(system, { system = it }, label = { Text("体系（可以新起一个名字）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("vein-system"))
            OutlinedTextField(source, { source = it }, label = { Text("来源（GPT 总结、书、课、自己想的……）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(text, { text = it }, label = { Text("材料：笔记、AI 的回复、自己的想法") }, minLines = 6, modifier = Modifier.fillMaxWidth().testTag("vein-text"))
            Text("约 ${VeinAi.tokens(text)} token", style = MaterialTheme.typography.labelSmall, color = sig.muted)
            if (busy) Box(Modifier.fillMaxWidth().height(120.dp)) { Vortex(sig.think, Modifier.fillMaxSize(), intensity = 1.6f) }
            BigButton(if (busy) "拆分中…" else "让 AI 拆成候选卡片", {
                busy = true
                scope.launch {
                    val r = app.actions.vein.split(system.trim().ifBlank { "未分类" }, text, source.trim())
                    // 网络回来可能不在主线程：跳页面要回到主线程
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                        busy = false
                        if (r.startsWith("拆出")) nav.navigate("vein/cands") { popUpTo("vein") } else message = r
                    }
                }
            }, Modifier.testTag("vein-split"), color = sig.think, enabled = !busy && text.isNotBlank() && config.ai.ready)
            if (!config.ai.ready) Hint("没配 AI 接口：先用下面的“手动新建节点”，或者去 我 → AI 接口。")
            TextButton(onClick = { manual = true }) { Text("手动新建节点", color = sig.think) }
        }
    }
    if (manual) NodeEditor(null, system.ifBlank { v.systems.firstOrNull()?.name ?: "未分类" }, onDismiss = { manual = false }) { id -> manual = false; nav.navigate("vein/node/$id") }
    OutcomeDialog(message) { message = null }
}

// ---------- 候选卡片 ----------

@Composable
fun VeinCandScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val v by app.store.vein.collectAsStateWithLifecycle()
    val today = remember { app.store.dateToday().toString() }
    var message by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxSize()) {
        BackBar("候选卡片（${v.candidates.size}）", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Hint("AI 拆出来的东西先放在这里。逐张看：接受、丢弃，或者先改一改。只有你接受的才写进结构。")
            if (v.candidates.isEmpty()) {
                Panel { Text("没有候选。", style = MaterialTheme.typography.titleSmall); Hint("在“录入”里贴材料，或者让总控提议。") }
            } else {
                OutlinedButton(onClick = {
                    var skipped = 0
                    app.actions.vein.update { Veins.acceptAll(it, today).also { r -> skipped = r.second }.first }
                    message = if (skipped > 0) "接受了；跳过 $skipped 张没写验证方式的假设，补上再接受" else "全部接受"
                }, modifier = Modifier.fillMaxWidth().testTag("cand-all")) { Text("接受全部（跳过没写验证方式的假设）") }
            }
            v.candidates.forEach { c -> CandCard(c, v, today) { message = it } }
            val links = v.candLinks
            if (links.isNotEmpty()) {
                SectionLabel("候选关系（${links.size}）")
                Panel {
                    links.forEach { l ->
                        val from = v.candidates.firstOrNull { it.tmp == l.from }?.title ?: v.node(l.from)?.title ?: l.from
                        val to = v.candidates.firstOrNull { it.tmp == l.to }?.title ?: v.node(l.to)?.title ?: l.to
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("$from → ${l.type} → $to", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f), color = if (l.on) MaterialTheme.colorScheme.onSurface else sig.muted)
                            TextButton(onClick = { app.actions.vein.update { vv -> vv.copy(candLinks = vv.candLinks.map { if (it == l) it.copy(on = !it.on) else it }) } }) { Text(if (l.on) "要" else "不要", color = sig.muted) }
                        }
                    }
                }
            }
        }
    }
    OutcomeDialog(message) { message = null }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CandCard(c: Candidate, v: Vein, today: String, onMessage: (String) -> Unit) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    var editVerify by remember { mutableStateOf(false) }
    val dup = remember(c, v.nodes.size) { if (c.mergeInto.isNotBlank()) v.node(c.mergeInto) else Veins.likelyDuplicate(v, c.title, c.keywords) }
    Panel(accent = if (c.status == VStatus.HYPO.label && c.verify.isBlank()) sig.warn else null, modifier = Modifier.testTag("cand-${c.tmp}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tag(c.type, sig.think)
            Tag(c.status + " · ${c.confidence}%", if (c.status == VStatus.HYPO.label) sig.warn else sig.free)
            Spacer(Modifier.weight(1f))
            Text(c.system + if (c.parent.isNotBlank()) " › ${c.parent}" else "", style = MaterialTheme.typography.labelSmall, color = sig.muted)
        }
        Text(c.title, style = MaterialTheme.typography.titleMedium)
        Text(c.claim, style = MaterialTheme.typography.bodyMedium)
        if (c.keywords.isNotEmpty()) Text("激活词：" + c.keywords.joinToString("、"), style = MaterialTheme.typography.bodySmall, color = sig.muted)
        Text("验证：" + c.verify.ifBlank { "（没写——假设要写清楚怎么验证）" }, style = MaterialTheme.typography.bodySmall, color = if (c.verify.isBlank()) sig.warn else sig.muted, modifier = Modifier.clickable { editVerify = true })
        dup?.let { Text("可能重复：「${it.title}」" + if (c.mergeInto.isNotBlank()) "（接受时并进去）" else "", style = MaterialTheme.typography.bodySmall, color = sig.warn) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                var id: String? = null
                app.actions.vein.update { Veins.accept(it, c.tmp, today).also { r -> id = r.second }.first }
                onMessage("写进了结构" + (id?.let { "（$it）" } ?: ""))
            }, modifier = Modifier.testTag("cand-accept")) { Text("接受") }
            if (dup != null && c.mergeInto.isBlank()) TextButton(onClick = {
                app.actions.vein.update { vv -> vv.copy(candidates = vv.candidates.map { if (it.tmp == c.tmp) it.copy(mergeInto = dup.id) else it }) }
            }) { Text("并进「${dup.title.take(6)}」", color = sig.ai) }
            TextButton(onClick = { app.actions.vein.update { Veins.discard(it, c.tmp) } }, modifier = Modifier.testTag("cand-discard")) { Text("丢弃", color = sig.muted) }
        }
    }
    if (editVerify) EditDialog("怎么验证", c.verify, hint = "一个本周能做的小动作：记什么、怎样算成立", onDismiss = { editVerify = false }) { t ->
        app.actions.vein.update { vv -> vv.copy(candidates = vv.candidates.map { if (it.tmp == c.tmp) it.copy(verify = t.trim()) else it }) }
        editVerify = false
    }
}

// ---------- 节点 ----------

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VeinNodeScreen(id: String, nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val v by app.store.vein.collectAsStateWithLifecycle()
    val n = v.node(id)
    val today = remember { app.store.dateToday().toString() }
    var edit by remember { mutableStateOf(false) }
    var backfill by remember { mutableStateOf(false) }
    var addRel by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    if (n == null) {
        Column(Modifier.fillMaxSize()) { BackBar("节点", { nav.popBackStack() }); Hint("这个节点已经不在了。", Modifier.padding(20.dp)) }
        return
    }
    Column(Modifier.fillMaxSize()) {
        BackBar(n.system, { nav.popBackStack() }) {
            TextButton(onClick = { edit = true }) { Text("编辑", color = sig.think) }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(v.pathOf(n).dropLast(1).joinToString(" › "), style = MaterialTheme.typography.labelMedium, color = sig.muted)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                NodeSeal(n, 52.dp)
                Column(Modifier.weight(1f)) {
                    Text(n.title, style = MaterialTheme.typography.headlineSmall, textDecoration = if (n.vStatus == VStatus.OVERTURNED) TextDecoration.LineThrough else null, modifier = Modifier.testTag("vein-title"))
                    Text("${n.type} · ${n.status}", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                }
                ConfidenceDial(n.confidence, Modifier.size(76.dp))
            }
            Text(n.claim, style = MaterialTheme.typography.titleMedium.merge(SerifTitle))
            if (n.note.isNotBlank()) Text(n.note, style = MaterialTheme.typography.bodyMedium)
            if (n.keywords.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { n.keywords.forEach { Tag(it, sig.think) } }
            Panel(accent = if (n.vStatus == VStatus.HYPO && n.verify.isBlank()) sig.warn else null) {
                Text("验证方式", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                Text(n.verify.ifBlank { "（没写）" }, style = MaterialTheme.typography.bodyMedium)
                n.checks.takeLast(4).reversed().forEach { c -> Text("${c.date} · ${c.status}${c.confidence?.let { " · $it%" } ?: ""}：${c.result}", style = MaterialTheme.typography.bodySmall, color = sig.muted) }
                OutlinedButton(onClick = { backfill = true }, modifier = Modifier.testTag("vein-backfill")) { Text("回填验证结果") }
            }

            // ---------- 动作 ----------
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("提问", sig.ai) { nav.navigate("vein/ask/${n.id}/ask") }
                QuickChip("延展", sig.ai) { nav.navigate("vein/ask/${n.id}/extend") }
                if (n.vStatus == VStatus.HYPO) QuickChip("设计验证", sig.ai) { nav.navigate("vein/ask/${n.id}/verify") }
                QuickChip("溯源", sig.think) { nav.navigate("vein/trace/${n.id}") }
                QuickChip("加关系", sig.think) { addRel = true }
                QuickChip("问总控", sig.ai) {
                    app.chatDraft = "看一下脉络里的「${n.title}」（${n.id}）：它${if (n.vStatus == VStatus.HYPO) "还是假设，验证方式是“${n.verify}”" else "是${n.status}"}。帮我追问它，最后把结论落到一步行动上。"
                    nav.navigate("chat")
                }
            }

            // ---------- 关系 ----------
            val rels = v.relations(n.id)
            SectionLabel("关系（${rels.size}）")
            if (rels.isEmpty()) Hint("还没有关系。孤立的节点用不上——点“加关系”。")
            val groups = listOf(
                "上游 · 导致它的" to rels.filter { it.type == Rel.CAUSE && it.dir == "in" },
                "下游 · 它导致的" to rels.filter { it.type == Rel.CAUSE && it.dir == "out" },
                "支撑 · 证据" to rels.filter { it.type == Rel.SUPPORT },
                "反例 · 削弱" to rels.filter { it.type == Rel.COUNTER },
                "对立 · 竞争解释" to rels.filter { it.type == Rel.RIVAL },
                "前置 · 学习顺序" to rels.filter { it.type == Rel.PRE },
                "类似 · 可类比" to rels.filter { it.type == Rel.LIKE },
                "属于 · 组成" to rels.filter { it.type == Rel.PART },
            ).filter { it.second.isNotEmpty() }
            groups.forEach { (label, list) ->
                Panel {
                    Text(label, style = MaterialTheme.typography.labelLarge, color = if (label.startsWith("对立")) sig.strict else sig.think)
                    list.forEach { r ->
                        Column {
                            NodeRow(r.other) { nav.navigate("vein/node/${r.other.id}") }
                            if (r.why.isNotBlank()) Text("— ${r.why}", style = MaterialTheme.typography.labelSmall, color = sig.muted, modifier = Modifier.padding(start = 38.dp))
                        }
                    }
                }
            }
            if (rels.any { it.type == Rel.RIVAL }) Hint("有对立的解释：两边都要写验证方式，才分得开谁对。")
            TextButton(onClick = { confirmDelete = true }) { Text("删除这个节点", color = sig.muted) }
        }
    }
    if (edit) NodeEditor(n, n.system, onDismiss = { edit = false }) { edit = false }
    if (backfill) BackfillDialog(n, onDismiss = { backfill = false }) { result, st, conf ->
        app.actions.vein.update { Veins.backfill(it, n.id, result, st, conf, today).getOrDefault(it) }
        backfill = false
    }
    if (addRel) RelDialog(n, v, onDismiss = { addRel = false }) { other, type, why, reverse ->
        app.actions.vein.update { vv -> (if (reverse) Veins.link(vv, other, n.id, type, why, today) else Veins.link(vv, n.id, other, type, why, today)).getOrDefault(vv) }
        addRel = false
    }
    if (confirmDelete) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("删除「${n.title}」？") },
        text = { Text("指向它的关系也会一起删掉。") },
        confirmButton = { TextButton(onClick = { app.actions.vein.update { Veins.remove(it, n.id) }; confirmDelete = false; nav.popBackStack() }) { Text("删除") } },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("算了") } },
    )
}

/** 新建（[n] = null）或编辑节点。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NodeEditor(n: VNode?, system: String, onDismiss: () -> Unit, onSaved: (String) -> Unit) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val today = remember { app.store.dateToday().toString() }
    var title by remember { mutableStateOf(n?.title ?: "") }
    var claim by remember { mutableStateOf(n?.claim ?: "") }
    var note by remember { mutableStateOf(n?.note ?: "") }
    var keywords by remember { mutableStateOf(n?.keywords?.joinToString("、") ?: "") }
    var type by remember { mutableStateOf(n?.type ?: NodeType.CLAIM.label) }
    var status by remember { mutableStateOf(n?.status ?: VStatus.HYPO.label) }
    var conf by remember { mutableIntStateOf(n?.confidence ?: 50) }
    var verify by remember { mutableStateOf(n?.verify ?: "") }
    var parent by remember { mutableStateOf(n?.parent ?: "") }
    var sys by remember { mutableStateOf(n?.system ?: system) }
    val levels = app.store.vein.value.system(sys)?.levels.orEmpty()
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (n == null) "新建节点" else "编辑节点") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("标题") }, singleLine = true, modifier = Modifier.testTag("ne-title"))
                OutlinedTextField(claim, { claim = it }, label = { Text("一句结论") }, modifier = Modifier.testTag("ne-claim"))
                OutlinedTextField(note, { note = it }, label = { Text("说明（最多 3 行）") })
                OutlinedTextField(keywords, { keywords = it }, label = { Text("激活词 3–5 个，顿号分隔") })
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { NodeType.entries.forEach { t -> QuickChip(t.label + if (type == t.label) " ✓" else "", sig.think) { type = t.label } } }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { VStatus.entries.forEach { t -> QuickChip(t.label + if (status == t.label) " ✓" else "", sig.warn) { status = t.label } } }
                Text("把握 $conf%", style = MaterialTheme.typography.labelLarge)
                Slider(conf.toFloat(), { conf = it.toInt() }, valueRange = 0f..100f)
                OutlinedTextField(verify, { verify = it }, label = { Text(if (status == VStatus.HYPO.label) "验证方式（假设必填）" else "验证方式") })
                OutlinedTextField(sys, { sys = it }, label = { Text("体系") }, singleLine = true)
                OutlinedTextField(parent, { parent = it }, label = { Text("层级（例：加工层/二次加工）") }, singleLine = true)
                if (levels.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { levels.forEach { l -> QuickChip(l, sig.muted) { parent = l } } }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) return@TextButton
                var id = n?.id ?: ""
                app.actions.vein.update { v ->
                    var w = v
                    if (id.isEmpty()) { val (w2, nid) = Veins.nextId(w); w = w2; id = nid }
                    val base = n ?: VNode(id = id, title = title, system = sys)
                    Veins.put(w, base.copy(
                        title = title.trim(), claim = claim.trim(), note = note.trim().lines().take(3).joinToString("\n"),
                        keywords = keywords.split(Regex("[、,，\\s]+")).filter { it.isNotBlank() },
                        type = type, status = status, confidence = conf, verify = verify.trim(), parent = parent.trim().trim('/'), system = sys.trim().ifBlank { "未分类" },
                        source = base.source.ifBlank { "手动" },
                    ), today)
                }
                onSaved(id)
            }, modifier = Modifier.testTag("ne-save")) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackfillDialog(n: VNode, onDismiss: () -> Unit, onSave: (String, VStatus, Int) -> Unit) {
    val sig = LocalSignals.current
    var result by remember { mutableStateOf("") }
    var st by remember { mutableStateOf(n.vStatus) }
    var conf by remember { mutableIntStateOf(n.confidence ?: 50) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("回填：${n.title}") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("验证方式：${n.verify.ifBlank { "（没写）" }}", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                OutlinedTextField(result, { result = it }, label = { Text("做了什么、看到了什么") }, minLines = 2, modifier = Modifier.testTag("bf-result"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) { VStatus.entries.forEach { t -> QuickChip(t.label + if (st == t) " ✓" else "", sig.warn) { st = t } } }
                Text("把握 $conf%", style = MaterialTheme.typography.labelLarge)
                Slider(conf.toFloat(), { conf = it.toInt() }, valueRange = 0f..100f)
            }
        },
        confirmButton = { TextButton(onClick = { if (result.isNotBlank()) onSave(result, st, conf) }, modifier = Modifier.testTag("bf-save")) { Text("回填") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RelDialog(n: VNode, v: Vein, onDismiss: () -> Unit, onSave: (String, String, String, Boolean) -> Unit) {
    val sig = LocalSignals.current
    var q by remember { mutableStateOf("") }
    var other by remember { mutableStateOf<VNode?>(null) }
    var type by remember { mutableStateOf(Rel.CAUSE) }
    var reverse by remember { mutableStateOf(false) }
    var why by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("给「${n.title}」加关系") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(q, { q = it }, label = { Text("找另一个节点") }, singleLine = true)
                (if (q.isBlank()) v.list(n.system) else Veins.search(v, q)).filter { it.id != n.id }.take(6).forEach { o ->
                    Text((if (other?.id == o.id) "✓ " else "") + o.title, modifier = Modifier.fillMaxWidth().clickable { other = o }.padding(vertical = 4.dp), color = if (other?.id == o.id) sig.think else MaterialTheme.colorScheme.onSurface)
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) { Rel.TYPES.forEach { t -> QuickChip(t + if (type == t) " ✓" else "", sig.think) { type = t } } }
                Text(Rel.HINT[type].orEmpty() + if (reverse) "（A = 另一个，B = 本节点）" else "（A = 本节点，B = 另一个）", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                TextButton(onClick = { reverse = !reverse }) { Text("调换方向") }
                OutlinedTextField(why, { why = it }, label = { Text("为什么（一句理由）") }, singleLine = true)
            }
        },
        confirmButton = { TextButton(onClick = { other?.let { onSave(it.id, type, why, reverse) } }) { Text("加上") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------- 提问 / 延展 / 设计验证 ----------

@Composable
fun VeinAskScreen(id: String, mode: String, nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val v by app.store.vein.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val n = v.node(id) ?: run { BackBar("提问", { nav.popBackStack() }); return }
    var question by remember { mutableStateOf(VeinAi.MODE_QUESTIONS[mode] ?: "") }
    val removed = remember { mutableStateListOf<String>() }
    var chain by remember { mutableStateOf<List<String>>(emptyList()) }
    val pack = remember(v, question, removed.toList(), chain) { VeinAi.pack(v, id, question, removed = removed.toSet(), chain = chain) }
    var busy by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<VeinAi.Answer?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var added by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar(when (mode) { "extend" -> "延展"; "verify" -> "设计验证"; else -> "提问" } + " · ${n.title}", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(question, { question = it }, label = { Text("问题") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("ask-q"))
            if (chain.isEmpty()) {
                val tree = remember(v, id) { Veins.trace(v, id, up = true) }
                if (tree.isNotEmpty()) TextButton(onClick = { chain = Veins.longestChain(tree, n.title) }) { Text("带上因果链（只发标题）", color = sig.think) }
            }
            pack?.let { p ->
                Panel(modifier = Modifier.testTag("ask-pack")) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("发送前预览", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text("${p.tokens} / ${p.budget} token", style = MaterialTheme.typography.labelMedium.merge(Mono), color = if (p.tokens > p.budget) sig.strict else sig.muted)
                    }
                    LinearProgressIndicator(progress = { (p.tokens.toFloat() / p.budget).coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth(), color = sig.think)
                    if (p.libraryTokens > p.tokens) Text("整库约 ${p.libraryTokens} token → 本次 ${p.tokens}，省 ${100 - p.tokens * 100 / p.libraryTokens.coerceAtLeast(1)}%", style = MaterialTheme.typography.labelSmall, color = sig.free)
                    p.items.forEach { it ->
                        val shown = it.state == "full" || it.state == "short"
                        Row(verticalAlignment = Alignment.Top) {
                            Text(
                                if (it.state == "short") it.short else it.full, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                                color = if (shown) MaterialTheme.colorScheme.onSurface else sig.muted, textDecoration = if (shown) null else TextDecoration.LineThrough, maxLines = 4, overflow = TextOverflow.Ellipsis,
                            )
                            if (!it.fixed) Text(if (it.key in removed) "↺" else "✕", color = sig.muted, modifier = Modifier.clickable { if (it.key in removed) removed -= it.key else removed += it.key }.padding(start = 8.dp))
                        }
                    }
                }
            }
            if (busy) Box(Modifier.fillMaxWidth().height(110.dp)) { Vortex(sig.ai, Modifier.fillMaxSize(), intensity = 1.4f) }
            BigButton(if (busy) "在想…" else "发送", {
                val p = pack ?: return@BigButton
                busy = true; error = null; answer = null; added = false
                scope.launch {
                    when (val r = app.actions.vein.think(p.text)) {
                        is ApiResult.Ok -> answer = r.value
                        is ApiResult.Err -> error = r.message
                    }
                    busy = false
                }
            }, Modifier.testTag("ask-send"), color = sig.ai, enabled = !busy && config.ai.ready)
            if (!config.ai.ready) {
                Hint("没配 AI 接口：复制下面这段（提示词 + 打包内容）贴到任何聊天 App。")
                val ctx = LocalContext.current
                OutlinedButton(onClick = { clip(ctx, VeinAi.PROMPT_THINK + "\n\n" + (pack?.text ?: "")) }) { Text("复制指令") }
            }
            error?.let { Text(it, color = sig.strict) }
            answer?.let { a ->
                AnimatedContent(a, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "answer") { aa ->
                    Panel(accent = sig.ai, modifier = Modifier.testTag("ask-answer")) {
                        if (!aa.ok) Text(aa.raw, style = MaterialTheme.typography.bodyMedium)
                        else {
                            Text(aa.core, style = MaterialTheme.typography.titleMedium.merge(SerifTitle))
                            AnswerLine("结构", aa.structure)
                            AnswerLine("另一种可能", aa.alt)
                            AnswerLine("如果我错了", aa.wrong)
                            AnswerLine("把握", aa.confidence)
                            AnswerLine("本周验证", aa.verify)
                        }
                    }
                }
                if (a.suggest.isNotEmpty()) {
                    SectionLabel("建议新增（${a.suggest.size}）")
                    Panel {
                        a.suggest.forEach { s -> Text("${s.type}｜${s.title}：${s.claim}", style = MaterialTheme.typography.bodySmall) }
                        OutlinedButton(onClick = { app.actions.vein.suggest(id, a.suggest); added = true }, enabled = !added, modifier = Modifier.testTag("ask-suggest")) { Text(if (added) "已放进候选" else "放进候选卡片") }
                    }
                }
                if (a.verify.isNotBlank() && n.vStatus == VStatus.HYPO && n.verify.isBlank()) {
                    TextButton(onClick = { app.actions.vein.update { vv -> Veins.put(vv, n.copy(verify = a.verify), app.store.dateToday().toString()) } }) { Text("把“本周验证”写成这个节点的验证方式", color = sig.think) }
                }
            }
        }
    }
}

@Composable
private fun AnswerLine(label: String, text: String) {
    if (text.isBlank()) return
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalSignals.current.muted)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------- 溯源 ----------

@Composable
fun VeinTraceScreen(id: String, nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val v by app.store.vein.collectAsStateWithLifecycle()
    val n = v.node(id) ?: run { BackBar("溯源", { nav.popBackStack() }); return }
    val up = remember(v, id) { Veins.trace(v, id, up = true) }
    val down = remember(v, id) { Veins.trace(v, id, up = false) }
    val roots = remember(up) { Veins.roots(up) }
    val path = remember(v, id) { Veins.learningPath(v, id) }
    Column(Modifier.fillMaxSize()) {
        BackBar("溯源 · ${n.title}", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            SectionLabel("根因候选（${roots.size}）")
            if (roots.isEmpty()) Hint("没有“导致”它的节点。")
            roots.forEach { r -> Panel(accent = sig.strict, onClick = { nav.navigate("vein/node/${r.id}") }) { NodeRow(r) { nav.navigate("vein/node/${r.id}") } } }
            if (up.isNotEmpty()) { SectionLabel("往上：什么导致了它"); Panel { TraceTree(up, 0, nav) } }
            Panel(accent = sig.think) { NodeRow(n) {} }
            if (down.isNotEmpty()) { SectionLabel("往下：它导致了什么"); Panel { TraceTree(down, 0, nav) } }
            if (path.size > 1) {
                SectionLabel("学习路线（沿“前置”）")
                Panel { path.forEachIndexed { i, p -> Text("${i + 1}. ${p.title}", modifier = Modifier.clickable { nav.navigate("vein/node/${p.id}") }.padding(vertical = 3.dp)) } }
            }
            if (up.isNotEmpty()) OutlinedButton(onClick = { nav.navigate("vein/ask/$id/ask") }) { Text("带着因果链提问") }
        }
    }
}

@Composable
private fun TraceTree(t: List<Veins.Trace>, depth: Int, nav: NavHostController) {
    val sig = LocalSignals.current
    t.forEach { x ->
        Row(Modifier.padding(start = (depth * 18).dp)) {
            Box(Modifier.padding(top = 14.dp).width(10.dp).height(2.dp).background(sig.think))
            Column {
                NodeRow(x.node) { nav.navigate("vein/node/${x.node.id}") }
                if (x.why.isNotBlank()) Text("— ${x.why}", style = MaterialTheme.typography.labelSmall, color = sig.muted, modifier = Modifier.padding(start = 38.dp))
            }
        }
        TraceTree(x.children, depth + 1, nav)
    }
}

// ---------- 体检 ----------

@Composable
fun VeinHealthScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val v by app.store.vein.collectAsStateWithLifecycle()
    val h = remember(v) { Veins.health(v, null, app.store.dateToday()) }
    Column(Modifier.fillMaxSize()) {
        BackBar("体检", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Panel(accent = if (h.score >= 80) sig.free else sig.warn) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CredGauge(h.score, if (h.score >= 80) sig.free else sig.warn, Modifier.size(width = 140.dp, height = 90.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${h.total} 个节点", style = MaterialTheme.typography.titleMedium)
                        Hint("查的是会让“好听的故事”冒充规则的地方。不花 token。")
                    }
                }
            }
            if (h.issues.isEmpty()) Hint("没有问题。")
            h.issues.forEach { i ->
                Panel(accent = when (i.level) { 6 -> sig.strict; 3 -> sig.warn; else -> null }) {
                    Text((when (i.level) { 6 -> "● "; 3 -> "◐ "; else -> "○ " }) + i.label + "（${i.nodes.size}）", style = MaterialTheme.typography.titleSmall)
                    i.nodes.take(8).forEach { n -> NodeRow(n) { nav.navigate("vein/node/${n.id}") } }
                }
            }
        }
    }
}

// ---------- 温故 ----------

@Composable
fun VeinReviewScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val v by app.store.vein.collectAsStateWithLifecycle()
    val today = remember { app.store.dateToday() }
    val queue = remember { Veins.dueReviews(app.store.vein.value, today, 20).map { it.id } }
    var idx by remember { mutableIntStateOf(0) }
    var flipped by remember { mutableStateOf(false) }
    var last by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        BackBar("温故 ${minOf(idx + 1, queue.size)}/${queue.size}", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val n = queue.getOrNull(idx)?.let { v.node(it) }
            if (n == null) {
                Box(Modifier.fillMaxWidth().height(160.dp)) { TreeRings(queue.size.coerceAtLeast(3), 0.9f, sig.think, Modifier.fillMaxSize()) }
                Text(if (queue.isEmpty()) "今天没有要温故的。" else "今天的温故做完了。$last", style = MaterialTheme.typography.titleMedium)
                return@Column
            }
            Panel(onClick = { flipped = !flipped }, modifier = Modifier.fillMaxWidth().height(320.dp).testTag("review-card")) {
                AnimatedContent(flipped, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "flip") { back ->
                    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) { NodeSeal(n); Text(n.system, color = sig.muted, style = MaterialTheme.typography.labelMedium) }
                        Text(n.title, style = MaterialTheme.typography.headlineSmall)
                        if (!back) {
                            Text("激活词：" + n.keywords.joinToString("、"), color = sig.muted)
                            Spacer(Modifier.weight(1f))
                            Text("先自己回想结论，再点开对照", style = MaterialTheme.typography.labelMedium, color = sig.muted)
                        } else {
                            Text(n.claim, style = MaterialTheme.typography.titleMedium.merge(SerifTitle))
                            if (n.vStatus == VStatus.HYPO) Text("这还是假设。本周验证了吗？验证方式：${n.verify.ifBlank { "（没写）" }}", color = sig.warn, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            if (flipped) Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                listOf(Veins.Grade.FORGOT to "忘了", Veins.Grade.FUZZY to "模糊", Veins.Grade.GOOD to "记得").forEach { (g, label) ->
                    OutlinedButton(onClick = {
                        var days = 0
                        app.actions.vein.update { Veins.grade(it, n.id, g, today).also { r -> days = r.second }.first }
                        last = "最后一张 $days 天后再见。"
                        flipped = false
                        idx++
                    }, modifier = Modifier.weight(1f).testTag("grade-${g.name}")) { Text(label) }
                }
            }
        }
    }
}
