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

            // ---------- 今天的关卡 ----------
            if (gates.isNotEmpty()) {
                SectionLabel("今天的关卡")
                gates.forEachIndexed { i, g -> GateLine(g, nav, Modifier.enter(1 + i)) }
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
                QuickChip("＋ 让参谋加一个", sig.ai) {
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

private fun createdToday(r: Rec, now: Long) = DayClock.dateKey(r.createdAt, java.time.ZoneId.systemDefault()) == DayClock.dateKey(now, java.time.ZoneId.systemDefault())

// ---------- 信息收集部 ----------

@Composable
private fun InfoPart(work: Work, now: Long, nav: NavHostController) {
    val sig = LocalSignals.current
    val today = work.notes().filter { createdToday(it, now) && it.noteType in setOf(NoteType.MATERIAL, NoteType.QUESTION, NoteType.IDEA) }.sortedByDescending { it.createdAt }
    val loose = work.notes().filter { it.threadKey.isBlank() && it.noteType in setOf(NoteType.MATERIAL, NoteType.QUESTION, NoteType.IDEA) }.sortedByDescending { it.createdAt }
    BigButton("收一条", { nav.navigate("capture") }, Modifier.enter(2), color = sig.info)
    Hint("说出来、粘贴、或者在别的 App 里分享给总控。类型和标题会自动猜好。")
    SectionLabel("今天收进来的（${today.size}）")
    if (today.isEmpty()) Hint("还没有。看到什么、想到什么、卡在哪，先收下。")
    Panel(modifier = Modifier.enter(3)) {
        today.take(8).forEach { n -> NoteLine(n, work) { if (n.threadKey.isNotBlank()) nav.navigate("thread/${n.threadKey}") else nav.navigate("capture") } }
        if (today.isEmpty()) Text("—", color = sig.muted)
    }
    if (loose.isNotEmpty()) {
        SectionLabel("还没挂到事项上的（${loose.size}）")
        Hint("挂到一件事上才用得上。点开收集页，选一件事挂上。")
        Panel {
            loose.take(6).forEach { n -> NoteLine(n, work) { nav.navigate("capture") } }
        }
    }
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
                }) { Text("问参谋", color = sig.ai) }
            }
        }
    }
    SectionLabel("最近的判断")
    Panel {
        if (conclusions.isEmpty()) Text("还没有判断记录。GPT 交接导入、在关卡里写的、参谋记的都算。", style = MaterialTheme.typography.bodySmall, color = sig.muted)
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
    val zone = java.time.ZoneId.systemDefault()
    val todayKey = DayClock.dateKey(now, zone)
    val todays = work.actions().filter { a ->
        a.actionStatus != ActionStatus.CANCELLED &&
            a.plan?.let { p -> TimeParse.startMillis(p, zone)?.let { DayClock.dateKey(it, zone) == todayKey } } == true
    }.sortedBy { a -> a.plan?.let { TimeParse.startMillis(it, zone) } ?: 0L }
    val unplanned = Focus.unplannedThreads(work)
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.enter(2)) {
        BigButton("晨间部署", { nav.navigate("wizard/plan") }, Modifier.weight(1f), color = sig.plan)
        BigButton("晚间复盘", { nav.navigate("wizard/review") }, Modifier.weight(1f), color = sig.hq)
    }
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
    var msg by remember { mutableStateOf<String?>(null) }
    val cur = remember(work, now) { Focus.current(work, now, app.store.zone) }

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
                        is YishouClient.Result.Ok -> "今天 ${r.value.doneSets}/${r.value.targetSets} 组 · 有效 ${r.value.effectiveToday} 手" + if (r.value.streak > 1) " · 连续 ${r.value.streak} 天" else ""
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
