package com.zongkong.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.link.YishouClient
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.Defaults
import com.zongkong.core.Dept
import com.zongkong.core.GatePhase
import com.zongkong.core.GateStatus
import com.zongkong.core.Link
import com.zongkong.core.YishouStatus
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.NoteType
import com.zongkong.core.work.Rec
import com.zongkong.core.work.ThreadStatus
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import kotlinx.coroutines.launch
import java.time.LocalDate

private val FOUR = listOf(Dept.INFO, Dept.THINK, Dept.PLAN, Dept.ACT)

private fun short(d: Dept) = when (d) {
    Dept.INFO -> "收：材料、问题、念头挂到事项上"
    Dept.THINK -> "谋：想清楚判断和下一步"
    Dept.PLAN -> "筹：把下一步排进时间"
    Dept.ACT -> "行：一手练思考，总控管住手"
    Dept.HQ -> "控：每一步要交、要验收"
}

/** 四部：四张大卡片（stoic 式），点进去是各部门自己的页面。 */
@Composable
fun DeptsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val sig = LocalSignals.current
    val now = rememberNow(30_000)
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    val status = remember(config, day, now) { store.status(now) }
    val method = Defaults.methodOf(LocalDate.parse(DayClock.dateKey(now, store.zone)))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        ScreenTitle("四个部门", "一件事从收到做完，依次经过四个部门。每个部门一页，只放它自己的事。", Modifier.enter(0))
        FOUR.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { i, d ->
                    val gates = status.gates.filter { it.gate.dept == d }
                    BigDeptCard(d, gates, Modifier.weight(1f).enter(1 + row * 2 + i)) { nav.navigate("dept/${d.name}") }
                }
            }
        }
        SectionLabel("一天怎么流转")
        Panel(modifier = Modifier.enter(5)) {
            (FOUR + Dept.HQ).forEachIndexed { i, d ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    DeptSeal(d, size = 28.dp, filled = true)
                    Text(short(d), style = MaterialTheme.typography.bodyMedium)
                }
                if (i < FOUR.size) Box(Modifier.padding(start = 13.dp).width(2.dp).height(10.dp).background(sig.line))
            }
        }
        Panel(accent = sig.think, modifier = Modifier.enter(6)) {
            Text("今天的方法卡 · ${method.name}", style = MaterialTheme.typography.titleSmall, color = sig.think)
            Text(method.how, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun BigDeptCard(d: Dept, gates: List<GateStatus>, modifier: Modifier, onClick: () -> Unit) {
    val sig = LocalSignals.current
    val c = sig.dept(d)
    val source = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier.height(178.dp).pressScale(source).clickable(interactionSource = source, indication = ripple(), onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(24.dp),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Text(d.label, style = MaterialTheme.typography.titleMedium.merge(SerifTitle))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                ProgressRing(if (gates.isEmpty()) 0f else gates.count { it.done }.toFloat() / gates.size, c, size = 64.dp, stroke = 4.dp) {
                    Text(d.seal, color = c, fontWeight = FontWeight.Black, fontSize = 26.sp)
                }
            }
            Text(
                if (gates.isEmpty()) "今天没有关卡" else "关卡 ${gates.count { it.done }}/${gates.size}" + if (gates.any { it.phase == GatePhase.OVERDUE }) " · 有超时" else "",
                style = MaterialTheme.typography.labelMedium,
                color = if (gates.any { it.phase == GatePhase.OVERDUE }) sig.strict else sig.muted,
            )
        }
    }
}

/** 一个部门的页面：它今天的关卡、它自己的事、参谋给它加的按钮、它的职责说明。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DeptScreen(name: String, nav: NavHostController) {
    val dept = Dept.entries.firstOrNull { it.name == name } ?: Dept.INFO
    val context = LocalContext.current
    val app = context.zk
    val store = app.store
    val sig = LocalSignals.current
    val c = sig.dept(dept)
    val now = rememberNow(15_000)
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    val work by store.work.collectAsStateWithLifecycle()
    val status = remember(config, day, now, work.session) { store.status(now) }
    val gates = status.gates.filter { it.gate.dept == dept }
    val guide = Defaults.guides.firstOrNull { it.dept == dept }
    var showGuide by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar("", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            // ---------- 头部 ----------
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.enter(0)) {
                Stamp(dept.seal, c, size = 72.dp)
                Column(Modifier.weight(1f)) {
                    Text(dept.label, style = MaterialTheme.typography.headlineLarge)
                    Text(short(dept), style = MaterialTheme.typography.bodyMedium, color = sig.muted)
                }
            }

            // ---------- 部门的线条母题 ----------
            Box(Modifier.fillMaxWidth().height(96.dp).enter(1)) {
                when (dept) {
                    Dept.INFO -> {
                        val book by store.intake.collectAsStateWithLifecycle()
                        val st = remember(work, book, now) { com.zongkong.core.info.Intake.stats(work, book, now, store.zone) }
                        TideLines(0.15f + 0.7f * st.level.coerceAtMost(1f), c, Modifier.fillMaxSize())
                    }
                    Dept.THINK -> LeafVein(1f, c, Modifier.fillMaxSize())
                    Dept.PLAN -> {
                        val planned = remember(work, now) { work.actions().count { a -> a.plan?.let { p -> TimeParse.startMillis(p, store.zone)?.let { DayClock.dateKey(it, store.zone) == DayClock.dateKey(now, store.zone) } } == true } }
                        Lattice((planned / 3f).coerceAtMost(1f), c, Modifier.fillMaxSize())
                    }
                    Dept.ACT -> Lightning(0, if (status.strict) sig.strict else c, Modifier.fillMaxSize(), ambient = true)
                    Dept.HQ -> FlowLines(c, Modifier.fillMaxSize())
                }
            }

            // ---------- 今天的关卡 ----------
            if (gates.isNotEmpty()) {
                SectionLabel("今天的关卡")
                gates.forEachIndexed { i, g -> GateLine(g, nav, Modifier.enter(1 + i)) }
            }

            // ---------- 战役：总控带着走完一整段深度工作 ----------
            when (dept) {
                Dept.INFO -> {
                    CampaignCard(com.zongkong.core.agent.Campaign.INTAKE, nav)
                    Panel(onClick = { nav.navigate("quick") }, modifier = Modifier.testTag("quick-entry")) {
                        Text("记一下 / 贴一段讨论", style = MaterialTheme.typography.titleSmall)
                        Hint("随手一句直接进收集箱；在 Gemini、GPT 里聊过的，整段贴进来，总控帮你提炼、挂到事项上、定下一步。桌面宠物的气泡里也有“记一下”。")
                    }
                }
                Dept.THINK -> CampaignCard(com.zongkong.core.agent.Campaign.GRAND_THINK, nav, askTopic = true)
                Dept.PLAN -> {
                    CampaignCard(com.zongkong.core.agent.Campaign.GRAND_PLAN, nav)
                    CampaignCard(com.zongkong.core.agent.Campaign.PROJECT, nav, askTopic = true)
                }
                else -> {}
            }

            // ---------- 部门自己的事 ----------
            when (dept) {
                Dept.INFO -> InfoPart(work, now, nav)
                Dept.THINK -> ThinkPart(work, now, nav)
                Dept.PLAN -> PlanPart(work, now, nav)
                Dept.ACT -> ActPart(work, now, status, nav)
                Dept.HQ -> {}
            }

            // ---------- 快捷按钮 ----------
            SectionLabel("这个部门的按钮")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                config.shortcuts.filter { it.dept == dept }.forEach { s -> QuickChip(s.label, c) { runShortcut(s, app, nav) } }
                QuickChip("＋ 让总控加一个", sig.ai) {
                    app.chatDraft = "给${dept.label}加一个首页快捷按钮：（说说你想点一下就做什么）"
                    nav.navigate("chat")
                }
            }

            // ---------- 职责 ----------
            if (guide != null) {
                Panel(onClick = { showGuide = !showGuide }) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("这个部门管什么", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Text(if (showGuide) "收起" else "展开", style = MaterialTheme.typography.labelMedium, color = sig.muted)
                    }
                    Text(guide.duty, style = MaterialTheme.typography.bodyMedium)
                    AnimatedVisibility(showGuide, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GuideField("输入", guide.input)
                            GuideField("产出", guide.output)
                            GuideField("节奏", guide.rhythm)
                            GuideField("工具", guide.tools)
                            GuideField("最常见的坑", guide.pitfall)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun GuideField(label: String, text: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelLarge, color = LocalSignals.current.muted, fontWeight = FontWeight.Bold)
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun GateLine(g: GateStatus, nav: NavHostController, modifier: Modifier) {
    val sig = LocalSignals.current
    val color = phaseColor(g.phase)
    Panel(modifier = modifier, onClick = { nav.navigate("gate/${g.gate.id}") }) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                Text(g.gate.title, style = MaterialTheme.typography.titleMedium)
                Text("${DayClock.hhmm(g.gate.openAt)}–${DayClock.hhmm(g.gate.deadline)} · ${g.gate.verify.label}", style = MaterialTheme.typography.bodySmall, color = sig.muted)
            }
            if (g.done) Stamp("已验", sig.free, size = 48.dp, play = false) else Pill(g.phase.label, color, dot = g.phase == GatePhase.OVERDUE)
        }
    }
}

private fun createdToday(r: Rec, now: Long) = DayClock.dateKey(r.createdAt, com.zongkong.core.DayClock.BEIJING) == DayClock.dateKey(now, com.zongkong.core.DayClock.BEIJING)

// ---------- 信息收集部 ----------

/**
 * 信息收集部：漏斗。收得进，更要消化得了——每条都要回答三问：这是什么（一句话收获）、给谁用（归位）、什么时候过期。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InfoPart(work: Work, now: Long, nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val book by app.store.intake.collectAsStateWithLifecycle()
    val items = remember(work, book, now) { com.zongkong.core.info.Intake.items(work, book, now) }
    val st = remember(work, book, now) { com.zongkong.core.info.Intake.stats(work, book, now, app.store.zone) }
    val sources = remember(work, book, now) { com.zongkong.core.info.Intake.sources(work, book, now) }
    var distilling by remember { mutableStateOf<com.zongkong.core.info.IntakeItem?>(null) }
    BigButton("收一条", { nav.navigate("capture") }, Modifier.enter(2), color = sig.info)
    Panel(accent = if (st.overBacklog || st.overDaily) sig.warn else null, modifier = Modifier.enter(3).testTag("intake-funnel")) {
        Text("漏斗", style = MaterialTheme.typography.titleSmall)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FunnelNum("今天收", "${st.todayIn}/${st.dailyCap}", if (st.overDaily) sig.warn else sig.info)
            FunnelNum("待提炼", "${st.backlog}/${st.backlogCap}", if (st.overBacklog) sig.warn else sig.info)
            FunnelNum("已提炼", "${st.distilled}", sig.think)
            FunnelNum("已归位", "${st.placed}", sig.free)
            FunnelNum("过期", "${st.expired}", sig.muted)
        }
        Hint(
            when {
                st.overDaily -> "今天收得太多了。先把待提炼的消化掉，再收。"
                st.overBacklog -> "积压超了上限：收进来不消化，等于没收。"
                else -> "每条回答三问：这是什么（一句话收获）、给谁用（挂到哪件事）、什么时候过期（念头 3 天、材料 7 天、问题 14 天）。"
            },
        )
    }
    val pending = items.filter { it.state == com.zongkong.core.info.IntakeState.NEW }.sortedBy { it.daysLeft }
    SectionLabel("待提炼（${pending.size}）")
    if (pending.isEmpty()) Hint("收集箱是空的。")
    pending.take(8).forEach { it ->
        Panel(onClick = { distilling = it }, modifier = Modifier.testTag("intake-${it.rec.key}")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(it.rec.noteType.label, sig.info)
                Tag(it.source, sig.muted)
                Spacer(Modifier.weight(1f))
                Text(if (it.daysLeft <= 0) "今天过期" else "还剩 ${it.daysLeft} 天", style = MaterialTheme.typography.labelSmall, color = if (it.daysLeft <= 1) sig.warn else sig.muted)
            }
            Text(it.rec.title, style = MaterialTheme.typography.bodyLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
    }
    val expired = items.filter { it.state == com.zongkong.core.info.IntakeState.EXPIRED }
    if (expired.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Hint("过期没处理的 ${expired.size} 条：说明当初不该收。", Modifier.weight(1f))
            TextButton(onClick = { app.store.updateIntake { b -> expired.fold(b) { acc, e -> com.zongkong.core.info.Intake.drop(acc, e.rec.key) } } }) { Text("全部丢掉", color = sig.muted) }
        }
    }
    val distilled = items.filter { it.state == com.zongkong.core.info.IntakeState.DISTILLED }
    if (distilled.isNotEmpty()) {
        SectionLabel("提炼了、还没归位（${distilled.size}）")
        Panel { distilled.take(6).forEach { d -> MiniRow(d.gist, d.rec.title + " · 点开挂到一件事", sig.think) { distilling = d } } }
    }
    if (sources.isNotEmpty()) {
        SectionLabel("来源账本")
        Panel(modifier = Modifier.testTag("intake-sources")) {
            sources.take(6).forEach { s ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.source, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("收 ${s.total} · 用上 ${s.placed}", style = MaterialTheme.typography.labelMedium.merge(Mono), color = sig.muted)
                    Text("  ${s.useful}%", style = MaterialTheme.typography.labelMedium.merge(Mono), color = if (s.total >= 5 && s.useful < 20) sig.strict else sig.free)
                }
            }
            Hint("有用率 = 提炼、归位的占比。收得多用得少的来源，该断了。")
        }
    }
    distilling?.let { d -> DistillDialog(d, work, onDismiss = { distilling = null }, nav = nav) }
}

@Composable
private fun FunnelNum(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    Column {
        Text(value, style = MaterialTheme.typography.titleMedium.merge(Mono), color = color)
        Text(label, style = MaterialTheme.typography.labelSmall, color = LocalSignals.current.muted)
    }
}

/** 提炼：一句话收获 → 归位到一件事，或者送进脉络录入，或者丢掉。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DistillDialog(d: com.zongkong.core.info.IntakeItem, work: Work, onDismiss: () -> Unit, nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    var gist by remember { mutableStateOf(d.gist) }
    val threads = remember(work) { work.threads().filter { it.threadStatus.open }.sortedByDescending { it.updatedAt }.take(6) }
    fun saveGist() { if (gist.isNotBlank()) app.store.updateIntake { com.zongkong.core.info.Intake.distill(it, d.rec.key, gist, System.currentTimeMillis()) } }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("提炼") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(d.rec.title, style = MaterialTheme.typography.titleSmall)
                if (d.rec[F.RAW].isNotBlank() && d.rec[F.RAW] != d.rec.title) Text(d.rec[F.RAW], style = MaterialTheme.typography.bodySmall, maxLines = 5, overflow = TextOverflow.Ellipsis, color = sig.muted)
                androidx.compose.material3.OutlinedTextField(gist, { gist = it }, label = { Text("一句话收获：它对我有什么用") }, minLines = 2, modifier = Modifier.fillMaxWidth().testTag("distill-gist"))
                Text("给谁用：挂到一件事", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    threads.forEach { t ->
                        QuickChip(t.title.take(10), sig.info) {
                            saveGist()
                            app.actions.work.update(d.rec.key, mapOf(F.THREAD to t.key))
                            app.store.updateIntake { com.zongkong.core.info.Intake.place(it, d.rec.key, "thread:${t.key}", System.currentTimeMillis()) }
                            onDismiss()
                        }
                    }
                    QuickChip("送进脉络拆", sig.think) {
                        saveGist()
                        app.veinDraft = listOf(d.rec.title, d.rec[F.RAW], if (gist.isNotBlank()) "收获：$gist" else "").filter { it.isNotBlank() }.distinct().joinToString("\n")
                        app.store.updateIntake { com.zongkong.core.info.Intake.place(it, d.rec.key, "vein", System.currentTimeMillis()) }
                        onDismiss()
                        nav.navigate("vein/capture")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { saveGist(); onDismiss() }, modifier = Modifier.testTag("distill-save")) { Text("只存收获") } },
        dismissButton = {
            TextButton(onClick = { app.store.updateIntake { com.zongkong.core.info.Intake.drop(it, d.rec.key) }; onDismiss() }) { Text("丢掉", color = sig.muted) }
        },
    )
}

@Composable
private fun NoteLine(n: Rec, work: Work, onClick: () -> Unit) {
    val sig = LocalSignals.current
    val color = when (n.noteType) {
        NoteType.QUESTION -> sig.think
        NoteType.MATERIAL -> sig.info
        NoteType.CONCLUSION -> sig.plan
        NoteType.RESULT -> sig.free
        else -> sig.muted
    }
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Tag(n.noteType.label, color)
        Column(Modifier.weight(1f)) {
            Text(n.title, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            work.rec(n.threadKey)?.let { Text(it.title, style = MaterialTheme.typography.labelSmall, color = sig.muted, maxLines = 1) }
        }
    }
}

// ---------- 谋划思考部 ----------

@Composable
private fun ThinkPart(work: Work, now: Long, nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val needs = work.threads().filter { it.threadStatus == ThreadStatus.COLLECTING || it.threadStatus == ThreadStatus.THINKING || it.threadStatus == ThreadStatus.STUCK }
        .sortedByDescending { it.updatedAt }
    val conclusions = work.notes().filter { it.noteType == NoteType.CONCLUSION }.sortedByDescending { it.createdAt }
    val vein by app.store.vein.collectAsStateWithLifecycle()
    val today = remember { app.store.dateToday() }
    val hypo = vein.nodes.count { it.status == com.zongkong.core.vein.VStatus.HYPO.label }
    val dueN = remember(vein) { com.zongkong.core.vein.Veins.dueReviews(vein, today).size }
    Panel(accent = sig.think, onClick = { nav.navigate("vein") }, modifier = Modifier.enter(2).testTag("vein-card")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.width(72.dp).height(52.dp)) { LeafVein(if (vein.nodes.isEmpty()) 0.5f else 1f, sig.think, Modifier.fillMaxSize()) }
            Column(Modifier.weight(1f)) {
                Text("脉络", style = MaterialTheme.typography.titleMedium)
                Text(
                    if (vein.nodes.isEmpty()) "把想法拆成 体系 → 层级 → 节点 → 关系。点开开始。"
                    else "${vein.systems.size} 个体系 · ${vein.nodes.size} 个节点 · $hypo 个假设待验证" + (if (vein.candidates.isNotEmpty()) " · 候选 ${vein.candidates.size}" else ""),
                    style = MaterialTheme.typography.bodySmall, color = sig.muted,
                )
            }
        }
        if (vein.nodes.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            QuickChip("录入", sig.think) { nav.navigate("vein/capture") }
            if (dueN > 0) QuickChip("温故 $dueN", sig.plan) { nav.navigate("vein/review") }
            if (vein.candidates.isNotEmpty()) QuickChip("候选 ${vein.candidates.size}", sig.ai) { nav.navigate("vein/cands") }
        }
    }
    SectionLabel("等你想清楚的事（${needs.size}）")
    if (needs.isEmpty()) Hint("没有在等判断的事。")
    needs.take(6).forEachIndexed { i, t ->
        Panel(modifier = Modifier.enter(2 + i), onClick = { nav.navigate("thread/${t.key}") }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Tag(t.threadStatus.label, threadColor(t.threadStatus))
            }
            if (t[F.JUDGE].isNotBlank()) Text("当前判断：${t[F.JUDGE]}", style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (t[F.UNSURE].isNotBlank()) Text("还不确定：${t[F.UNSURE]}", style = MaterialTheme.typography.bodySmall, color = sig.warn, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    app.prompt = app.actions.work.gptPrompt(t.key)
                    app.promptTitle = t.title
                    app.store.awaitingGpt = t.key
                    nav.navigate("gpt")
                }) { Text("带前情去 GPT") }
                TextButton(onClick = {
                    app.chatDraft = "帮我想清楚 ${t[F.CODE]}「${t.title}」：现在该下什么判断、下一步做什么？想好了直接写进去。"
                    nav.navigate("chat")
                }) { Text("问总控", color = sig.ai) }
            }
        }
    }
    SectionLabel("最近的判断")
    Panel {
        if (conclusions.isEmpty()) Text("还没有判断记录。GPT 交接导入、在关卡里写的、总控记的都算。", style = MaterialTheme.typography.bodySmall, color = sig.muted)
        conclusions.take(6).forEach { n ->
            Column(Modifier.fillMaxWidth().clickable { if (n.threadKey.isNotBlank()) nav.navigate("thread/${n.threadKey}") }.padding(vertical = 4.dp)) {
                Text(n.title, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    (work.rec(n.threadKey)?.title ?: "没挂事项") + " · " + clock(n.createdAt),
                    style = MaterialTheme.typography.labelSmall, color = sig.muted,
                )
            }
        }
    }
}

// ---------- 统筹规划部 ----------

@Composable
private fun PlanPart(work: Work, now: Long, nav: NavHostController) {
    val sig = LocalSignals.current
    val zone = com.zongkong.core.DayClock.BEIJING
    val todayKey = DayClock.dateKey(now, zone)
    val todays = work.actions().filter { a ->
        a.actionStatus != ActionStatus.CANCELLED &&
            a.plan?.let { p -> TimeParse.startMillis(p, zone)?.let { DayClock.dateKey(it, zone) == todayKey } } == true
    }.sortedBy { a -> a.plan?.let { TimeParse.startMillis(it, zone) } ?: 0L }
    val unplanned = Focus.unplannedThreads(work)
    val app = LocalContext.current.zk
    val book by app.store.plans.collectAsStateWithLifecycle()
    val weekPlan = remember(book, now) { com.zongkong.core.plan.Plans.active(book, now, zone) }
    Panel(accent = sig.plan, onClick = { nav.navigate("plan") }, modifier = Modifier.enter(2).testTag("dept-plan-card")) {
        Text("本周计划", style = MaterialTheme.typography.titleMedium)
        if (weekPlan == null) {
            Hint("还没有。进入计划模式，五分钟定个参照。")
        } else {
            Text(weekPlan.goals.joinToString(" · ") { it.title }, style = MaterialTheme.typography.bodyMedium)
            Hint(com.zongkong.core.plan.Plans.headline(weekPlan, now, zone))
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.enter(2)) {
        BigButton("晨间部署", { nav.navigate("wizard/plan") }, Modifier.weight(1f), color = sig.plan)
        BigButton("今天的清单", { nav.navigate("today") }, Modifier.weight(1f), color = sig.hq)
    }
    OutlinedButton(onClick = { nav.navigate("playbook") }, modifier = Modifier.fillMaxWidth().enter(2).testTag("dept-playbook")) { Text("今天的预案（试验）：排成几段，每段带 B 方案") }
    PlanInstruments(work, now, nav)
    SectionLabel("今天的时间线（${todays.size}）")
    Panel(modifier = Modifier.enter(3)) {
        if (todays.isEmpty()) Text("今天还没有排进时间的行动。用“晨间部署”排一件。", style = MaterialTheme.typography.bodySmall, color = sig.muted)
        todays.forEach { a ->
            val s = a.plan?.let { TimeParse.startMillis(it, zone) }
            Row(
                Modifier.fillMaxWidth().clickable { nav.navigate("action/${a.key}") }.padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(s?.let { clock(it) } ?: "--:--", style = MaterialTheme.typography.titleSmall.merge(Mono), modifier = Modifier.width(52.dp))
                Box(Modifier.width(10.dp).height(10.dp).background(actionColor(a.actionStatus), RoundedCornerShape(50)))
                Column(Modifier.weight(1f)) {
                    Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(a.actionStatus.label + (a[F.CRITERIA].takeIf { it.isNotBlank() }?.let { " · 做到：$it" } ?: " · 没写完成依据"), style = MaterialTheme.typography.bodySmall, color = if (a[F.CRITERIA].isBlank()) sig.warn else sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
    if (unplanned.isNotEmpty()) {
        SectionLabel("有下一步、还没排时间的")
        Panel {
            unplanned.take(6).forEach { t -> MiniRow(t[F.NEXT].ifBlank { t.title }, t.title + " · 点开排进时间", sig.warn) { nav.navigate("thread/${t.key}") } }
        }
    }
}

/** 估时校准、精力地图、在制品上限、倒推规划。 */
@Composable
private fun PlanInstruments(work: Work, now: Long, nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val zone = app.store.zone
    val samples = remember(work) { com.zongkong.core.plan.Calibrate.samples(work, zone) }
    val factor = remember(samples) { com.zongkong.core.plan.Calibrate.factor(samples) }
    val energy = remember(work, now / 3_600_000) { com.zongkong.core.plan.Calibrate.energy(work, zone, now) }
    val best = remember(energy) { com.zongkong.core.plan.Calibrate.bestHours(energy) }
    val wip = remember(work) { com.zongkong.core.plan.Calibrate.wip(work) }
    Panel(modifier = Modifier.enter(3).testTag("plan-instruments")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("估时系数", style = MaterialTheme.typography.labelLarge, color = sig.muted)
                Text(factor?.let { "×%.1f".format(it) } ?: "—", style = MaterialTheme.typography.headlineSmall.merge(Mono), color = if ((factor ?: 1.0) > 1.3) sig.warn else sig.plan)
            }
            Column(Modifier.weight(2f)) {
                Hint(factor?.let { "计划 60 分钟的事，你实际要 ${(60 * it).toInt()} 分钟。排时间时按这个放大。" } ?: "做完 3 步有计划时长的事就能算出来（现在 ${samples.size}/3）。")
            }
        }
        Text("精力地图（最近 14 天，每格一个钟头）", style = MaterialTheme.typography.labelLarge, color = sig.muted)
        EnergyStrip(energy, sig.plan)
        Hint(if (best.isEmpty()) "还没有记录。开始 / 做完一步，总控就记下你在哪个钟头真干成了事。" else "最好的钟头：${best.joinToString("、") { "$it 点" }}。主线排在这里。")
        if (wip > com.zongkong.core.plan.Calibrate.WIP_LIMIT) Text("同时进行中 $wip 步，超过 ${com.zongkong.core.plan.Calibrate.WIP_LIMIT} 步就是在假装忙：先收掉一步。", style = MaterialTheme.typography.bodySmall, color = sig.warn)
        OutlinedButton(onClick = {
            app.chatDraft = "帮我倒推规划：（哪件事、截止哪天、大概要做哪几步）"
            nav.navigate("chat")
        }) { Text("倒推一件事") }
    }
}

/** 24 格：一格一个钟头，线越长越亮这个钟头干得越多。 */
@Composable
private fun EnergyStrip(energy: IntArray, color: androidx.compose.ui.graphics.Color) {
    val max = (energy.maxOrNull() ?: 0).coerceAtLeast(1)
    val muted = LocalSignals.current.muted
    Column {
        androidx.compose.foundation.Canvas(Modifier.fillMaxWidth().height(44.dp)) {
            val cw = size.width / 24
            for (h in 0 until 24) {
                val f = energy[h].toFloat() / max
                val cx = cw * (h + 0.5f)
                val len = size.height * (0.15f + 0.75f * f)
                val ang = (Math.PI / 2 * (1 - f)).toFloat()
                drawLine(
                    if (energy[h] > 0) color.copy(alpha = 0.35f + 0.65f * f) else muted.copy(alpha = 0.25f),
                    androidx.compose.ui.geometry.Offset(cx - len / 2 * kotlin.math.cos(ang), size.height / 2 + len / 2 * kotlin.math.sin(ang)),
                    androidx.compose.ui.geometry.Offset(cx + len / 2 * kotlin.math.cos(ang), size.height / 2 - len / 2 * kotlin.math.sin(ang)),
                    strokeWidth = 3f, cap = androidx.compose.ui.graphics.StrokeCap.Round,
                )
            }
        }
        Row(Modifier.fillMaxWidth()) { listOf("0", "6", "12", "18", "24").forEachIndexed { i, t -> Text(t, style = MaterialTheme.typography.labelSmall, color = muted, modifier = Modifier.weight(if (i == 4) 0.3f else 1f)) } }
    }
}

// ---------- 行为管理部 ----------

@Composable
private fun ActPart(work: Work, now: Long, status: com.zongkong.core.Status, nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    var resumeKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeKey++ }
    val installed = remember(resumeKey) { YishouClient.installed(context) }
    val ys by produceState<YishouClient.Result<YishouStatus>?>(null, resumeKey) { value = if (installed) YishouClient.status(context) else null }
    // 打开这一页时顺手把一手的刷题记录拉进来（后台本来每 15 分钟拉一次）
    androidx.compose.runtime.LaunchedEffect(resumeKey) { if (installed) runCatching { app.actions.importYishouLedger() } }
    val yBook by app.store.yishouBook.collectAsStateWithLifecycle()
    var msg by remember { mutableStateOf<String?>(null) }
    val cur = remember(work, now) { Focus.current(work, now, app.store.zone, app.store.mainKey(now)) }

    // ---------- 一手 ----------
    Panel(accent = sig.act, modifier = Modifier.enter(2).testTag("yishou-card")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            val y = (ys as? YishouClient.Result.Ok)?.value
            ProgressRing(if (y != null && y.targetSets > 0) y.doneSets.toFloat() / y.targetSets else 0f, sig.act, size = 64.dp, stroke = 5.dp) {
                Text(if (y != null) "${y.doneSets}/${y.targetSets}" else "一手", style = MaterialTheme.typography.titleSmall.merge(Mono), color = sig.act)
            }
            Column(Modifier.weight(1f)) {
                Text("一手", style = MaterialTheme.typography.titleMedium)
                Text(
                    when (val r = ys) {
                        null -> if (installed) "正在读…" else "还没装一手"
                        is YishouClient.Result.Ok -> if (r.value.ledgerLine.isNotBlank()) "今天 ${r.value.roundsToday} 手 · 接住 ${r.value.effectiveToday}（折 ${r.value.doneSets} 组）"
                        else "今天 ${r.value.doneSets}/${r.value.targetSets} 组 · 有效 ${r.value.effectiveToday} 手" + if (r.value.streak > 1) " · 连续 ${r.value.streak} 天" else ""
                        is YishouClient.Result.Err -> r.message
                    },
                    style = MaterialTheme.typography.bodySmall, color = sig.muted,
                )
            }
        }
        (ys as? YishouClient.Result.Ok)?.value?.let { y ->
            if (y.taskTitle.isNotBlank()) {
                Text("在练：${y.taskTitle}" + if (y.ref.isNotBlank()) "（总控交过去的）" else "", style = MaterialTheme.typography.bodyMedium)
                if (y.next.isNotBlank()) Text("下一问：${y.next}", style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (y.ledgerLine.isNotBlank()) Text(y.ledgerLine, style = MaterialTheme.typography.bodySmall, color = sig.muted)
            if (y.windowActive) Pill("陪练窗口进行中 · 到 ${clock(y.windowEnd)}", sig.act, dot = true)
            if (!y.gateOn) Text("一手的入口思考页没开：打开娱乐应用时不会先问你一手。", style = MaterialTheme.typography.bodySmall, color = sig.warn)
            if (y.rule.isNotBlank()) {
                Column(Modifier.fillMaxWidth().background(sig.wash.copy(alpha = 0.6f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                    Text("${y.summaryDate} 总结的规则", style = MaterialTheme.typography.labelMedium, color = sig.muted)
                    Text(y.rule, style = MaterialTheme.typography.bodyMedium)
                    if (y.tomorrowQuestion.isNotBlank()) Text("第一问：${y.tomorrowQuestion}", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                }
            }
        }
        if (yBook.entries.isNotEmpty()) {
            val week = remember(yBook, now) { com.zongkong.core.YishouLedger.between(yBook, now - 7 * 86_400_000L, now + 1) }
            Text(
                "已导入总控：近 7 天 ${week.size} 手，接住 ${week.count { it.hit }}，没接住 ${week.count { it.miss }}（参谋能看到这些记录）",
                style = MaterialTheme.typography.bodySmall, color = sig.muted,
            )
            TextButton(onClick = {
                app.prompt = ""
                app.promptTitle = "拿一手的题去聊"
                nav.navigate("gpt")
            }, modifier = Modifier.testTag("yishou-to-talk")) { Text("去 GPT / Gemini 聊没接住的 →") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (installed) {
                OutlinedButton(onClick = { YishouClient.open(context) }, modifier = Modifier.weight(1f)) { Text("打开一手") }
                val a = cur.action
                if (a != null) {
                    OutlinedButton(onClick = {
                        scope.launch {
                            val order = Link.taskOrder(app.store.work.value, a.key)
                            msg = if (order == null) "找不到当前一步" else when (val r = YishouClient.setTask(context, order)) {
                                is YishouClient.Result.Ok -> r.value.ifBlank { "已交给一手" }
                                is YishouClient.Result.Err -> r.message
                            }
                            resumeKey++
                        }
                    }, modifier = Modifier.weight(1f)) { Text("当前一步交给它") }
                }
            } else {
                BigButton("下载一手", { launchTarget(context, YishouClient.DOWNLOAD) }, color = sig.act)
            }
        }
        msg?.let { Hint(it) }
    }

    // ---------- 专注锁 ----------
    val s = work.session
    Panel(modifier = Modifier.enter(3)) {
        Text("专注锁", style = MaterialTheme.typography.titleSmall)
        when {
            s != null && s.lockUntil > now -> {
                Text("锁着：「${work.rec(s.actionKey)?.title}」到 ${clock(s.lockUntil)}", style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = { nav.navigate("session") }) { Text("回到计时页") }
            }
            cur.action != null -> {
                Hint("开始一步时打开：做完之前，拦截名单里的应用一打开就被弹回。")
                OutlinedButton(onClick = { nav.navigate("start/${cur.action!!.key}") }) { Text("开始「${cur.action!!.title.take(12)}」并上锁") }
            }
            else -> Hint("没有待做的一步。先排一件。")
        }
    }

    // ---------- 总控对一手的要求 ----------
    val config by app.store.config.collectAsStateWithLifecycle()
    val book by app.store.plans.collectAsStateWithLifecycle()
    val inf = remember(status, config, work, book) { Link.influence(status, config, work, book, app.store.zone) }
    var outcome by remember { mutableStateOf<String?>(null) }
    Panel(modifier = Modifier.enter(4).testTag("yishou-influence")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("总控管一手", style = MaterialTheme.typography.titleSmall)
                Hint(if (config.yishouInfluence) "落后于计划或严管时，一手每次最多放行 ${config.yishouPassCap} 分钟、不能离线放行；专注锁时一手不放行。" else "关着：一手按自己的设置放行。")
            }
            androidx.compose.material3.Switch(checked = config.yishouInfluence, onCheckedChange = { on ->
                var msg = ""
                app.store.updateConfig { c -> com.zongkong.core.Policy.setYishou(c, on, c.yishouPassCap, System.currentTimeMillis()).also { msg = it.message }.config }
                outcome = msg
            })
        }
        Text(
            when {
                !config.yishouInfluence -> "现在：不影响一手"
                inf.hold -> "现在：${inf.note}，一手不放行"
                inf.passCap > 0 -> "现在：${inf.note}，一手最多放行 ${inf.passCap} 分钟"
                else -> "现在：按计划走着，一手照常"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (inf.hold || inf.passCap > 0) sig.warn else sig.free,
        )
        if (config.yishouInfluence) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("收紧时最多放行", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                listOf(3, 5, 10).forEach { m ->
                    QuickChip("$m 分钟" + if (config.yishouPassCap == m) " ✓" else "", sig.act) {
                        var msg = ""
                        app.store.updateConfig { c -> com.zongkong.core.Policy.setYishou(c, true, m, System.currentTimeMillis()).also { msg = it.message }.config }
                        outcome = msg
                    }
                }
            }
        }
        if (installed) {
            OutlinedButton(onClick = {
                scope.launch {
                    val a = cur.action
                    if (a != null) Link.taskOrder(app.store.work.value, a.key)?.let { YishouClient.setTask(context, it) }
                    msg = when (val r = YishouClient.startWindow(context)) {
                        is YishouClient.Result.Ok -> r.value.also { YishouClient.openWindow(context) }
                        is YishouClient.Result.Err -> r.message
                    }
                }
            }, modifier = Modifier.fillMaxWidth()) { Text(if (cur.action != null) "让一手现在开一局（练「${cur.action!!.title.take(10)}」）" else "让一手现在开一局陪练") }
        }
    }
    OutcomeDialog(outcome) { outcome = null }

    // ---------- 娱乐 ----------
    Panel(modifier = Modifier.enter(4), onClick = { nav.navigate("settings/block") }) {
        Text("今天的娱乐", style = MaterialTheme.typography.titleSmall)
        val quota = status.quotaMin
        val used = status.playMin
        Row(verticalAlignment = Alignment.Bottom) {
            Text("$used", style = MaterialTheme.typography.displaySmall.merge(Mono).copy(letterSpacing = 0.sp))
            Text(if (quota > 0) " / $quota 分钟" else " 分钟（不限）", style = MaterialTheme.typography.bodyMedium, color = sig.muted, modifier = Modifier.padding(bottom = 6.dp))
        }
        if (quota > 0) {
            val p = (used.toFloat() / quota).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(8.dp).background(sig.line, RoundedCornerShape(50))) {
                Box(Modifier.fillMaxWidth(p).height(8.dp).background(if (p >= 1f) sig.strict else if (p > 0.7f) sig.warn else sig.free, RoundedCornerShape(50)))
            }
        }
        Hint("被弹回 ${app.store.today.value.bounces} 次 · 拦截名单 ${app.store.config.value.blocked.size} 个应用（点开管理、紧急放行）")
    }
    Spacer(Modifier.height(2.dp))
}
