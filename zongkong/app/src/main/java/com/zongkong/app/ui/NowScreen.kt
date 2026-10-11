package com.zongkong.app.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
import com.zongkong.core.Dept
import com.zongkong.core.GatePhase
import com.zongkong.core.Reason
import com.zongkong.core.Shortcut
import com.zongkong.core.Status
import com.zongkong.core.YishouStatus
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.Handoff
import com.zongkong.core.work.TimeParse
import com.zongkong.core.work.Work
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 今天：一打开就知道现在该干什么。
 * 从上到下：问候和状态 → 该你处理的提示（严管怎么解、早上部署、晚上复盘）→ 现在这一步 →
 * 四个部门 → 快捷按钮 → 等你处理 → 接下来。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NowScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val store = app.store
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    val work by store.work.collectAsStateWithLifecycle()
    val now = rememberNow(10_000)
    val status = remember(config, day, now, work.session) { store.status(now) }
    val book by store.plans.collectAsStateWithLifecycle()
    val plan = remember(book, now) { com.zongkong.core.plan.Plans.active(book, now, store.zone) }
    val cur = remember(work, book, now) { Focus.current(work, now, store.zone, store.mainKey(now)) }
    val upcoming = remember(work, now, cur) { Focus.upcoming(work, cur.action?.key, now, store.zone, 4) }
    val attention = remember(work) { Focus.needsAttention(work) }
    val unplanned = remember(work) { Focus.unplannedThreads(work) }
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val notionReady = config.notion.workReady
    var resumeKey by remember { mutableIntStateOf(0) }
    var clipHandoff by remember { mutableStateOf(false) }
    var autoPassed by remember { mutableStateOf<List<String>>(emptyList()) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeKey++ }
    LaunchedEffect(resumeKey) {
        if (notionReady && System.currentTimeMillis() - work.lastSyncAt > 60_000) scope.launch { app.actions.work.sync() }
        scope.launch { autoPassed = runCatching { app.actions.autoVerify() }.getOrDefault(emptyList()) }
        if (store.awaitingGpt.isNotBlank()) {
            delay(400) // 等窗口拿到焦点才能读剪贴板
            clipHandoff = Handoff.contains(Gpt.readClipboard(context))
        }
    }
    val yishou by produceState<YishouStatus?>(null, resumeKey) {
        value = if (YishouClient.installed(context)) (YishouClient.status(context) as? YishouClient.Result.Ok)?.value else null
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        // ---------- 问候 + 状态 ----------
        val d = Instant.ofEpochMilli(now).atZone(store.zone)
        Row(verticalAlignment = Alignment.Top, modifier = Modifier.enter(0)) {
            Column(Modifier.weight(1f)) {
                Text(greeting(d.hour), style = MaterialTheme.typography.displayMedium.copy(fontSize = 40.sp))
                Text(
                    "%d月%d日 %s · %s".format(d.monthValue, d.dayOfMonth, "周" + "一二三四五六日"[d.dayOfWeek.value - 1], clock(now)),
                    style = MaterialTheme.typography.bodyMedium, color = sig.muted,
                )
            }
            val c = statusColor(status)
            Box(Modifier.padding(top = 10.dp).clickable { nav.navigate("rhythm") }) {
                Pill("${status.headline} ${status.doneCount}/${status.gates.size}", c, dot = status.strict)
            }
        }

        // ---------- 该你处理的提示 ----------
        Guidance(status, day.date, now, nav)
        if (autoPassed.isNotEmpty()) {
            Panel(accent = sig.free, modifier = Modifier.enter(1)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Stamp("已验", sig.free, size = 56.dp)
                    Column(Modifier.weight(1f)) {
                        Text("自动验收通过", style = MaterialTheme.typography.titleSmall)
                        Hint(autoPassed.joinToString("、") + "：证据够了，不用你交。")
                    }
                }
            }
        }
        if (clipHandoff) {
            Panel(accent = sig.think, onClick = { clipHandoff = false; nav.navigate("import") }, modifier = Modifier.enter(1)) {
                Text("剪贴板里有 GPT 的交接块", style = MaterialTheme.typography.titleSmall)
                Hint("点这里导入：结论、下一步、行动会写进事项，并同步到 Notion。")
            }
        }

        // ---------- 本周计划（参照） ----------
        PlanStrip(plan, book, work, now, nav)

        // ---------- 现在这一步 ----------
        Box(Modifier.enter(2)) {
            AnimatedContent(
                targetState = cur,
                contentKey = { it.action?.key to it.why },
                transitionSpec = { (fadeIn() + slideInVertically { it / 8 }) togetherWith fadeOut() },
                label = "task",
            ) { c -> TaskCard(c, now, notionReady, nav, plan) }
        }

        // ---------- 四个部门 ----------
        SectionLabel("四个部门")
        val depts = listOf(Dept.INFO, Dept.THINK, Dept.PLAN, Dept.ACT)
        depts.chunked(2).forEachIndexed { row, pair ->
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                pair.forEachIndexed { i, dept ->
                    DeptTile(dept, status, work, now, yishou, Modifier.weight(1f).enter(3 + row * 2 + i)) { nav.navigate("dept/${dept.name}") }
                }
            }
        }

        // ---------- 快捷 ----------
        SectionLabel("随手")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.enter(7)) {
            QuickChip("收一个念头", sig.info) { nav.navigate("capture") }
            QuickChip("我卡住了", sig.strict) { nav.navigate("stuckpick") }
            QuickChip("问参谋", sig.ai) { nav.navigate("chat") }
            val n = work.changes.count { it.at > store.seenChanges }
            QuickChip(if (n > 0) "查看结果 · $n" else "查看结果", sig.think) { nav.navigate("results") }
            config.shortcuts.filter { it.dept == null }.forEach { s -> QuickChip(s.label, sig.ai) { runShortcut(s, app, nav) } }
        }

        // ---------- 等你处理 ----------
        if (attention.isNotEmpty() || unplanned.isNotEmpty()) {
            SectionLabel("等你处理")
            Panel(modifier = Modifier.enter(8)) {
                attention.take(4).forEach { a ->
                    val label = if (a.actionStatus == ActionStatus.CONFIRM) "待确认：结果够不够" else "卡住：${a[F.STUCK].ifBlank { "原因没写" }}"
                    MiniRow(a.title, label, actionColor(a.actionStatus)) { nav.navigate("action/${a.key}") }
                }
                unplanned.take(3).forEach { t ->
                    MiniRow("${t.title}：${t[F.NEXT]}", "下一步还没排进时间 · 点开排", sig.warn) { nav.navigate("thread/${t.key}") }
                }
            }
        }

        // ---------- 接下来 ----------
        if (upcoming.isNotEmpty()) {
            SectionLabel("接下来")
            Panel(modifier = Modifier.enter(9)) {
                upcoming.forEach { a -> TimelineRow(a, work, now, nav) }
            }
        }

        if (!PermissionStatus.accessibility(context)) {
            Panel(accent = sig.warn, onClick = { nav.navigate("settings/perm") }) {
                Text("总控现在没在盯", style = MaterialTheme.typography.titleSmall, color = sig.warn)
                Hint("无障碍没开：专注锁和严管都不会生效。点这里一步步打开。")
            }
        }
        SyncLine(work, notionReady) { scope.launch { app.actions.work.sync() } }
        Spacer(Modifier.height(8.dp))
    }
}

fun greeting(hour: Int): String = when (hour) {
    in 0..4 -> "夜深了。"
    in 5..8 -> "早上好。"
    in 9..11 -> "上午好。"
    in 12..13 -> "中午好。"
    in 14..17 -> "下午好。"
    in 18..22 -> "晚上好。"
    else -> "夜深了。"
}

@Composable
fun statusColor(status: Status): Color {
    val sig = LocalSignals.current
    return when {
        status.paused -> sig.muted
        status.emergencyUntil != null -> sig.warn
        status.strict -> sig.strict
        else -> sig.free
    }
}

/**
 * 轮椅式的提示：此刻你只需要看这一条。
 * 严管时说清楚怎么解除；早上没部署就带你去部署；晚上没复盘就带你去复盘。
 */
@Composable
private fun PlanStrip(plan: com.zongkong.core.plan.WeekPlan?, book: com.zongkong.core.plan.PlanBook, work: Work, now: Long, nav: NavHostController) {
    val sig = LocalSignals.current
    val zone = java.time.ZoneId.systemDefault()
    if (plan == null) {
        val draft = com.zongkong.core.plan.Plans.draft(book, now, zone)
        Panel(accent = if (draft != null) sig.ai else null, onClick = { nav.navigate("plan") }, modifier = Modifier.enter(2).testTag("plan-strip")) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                DeptSeal(Dept.PLAN, size = 40.dp)
                Column(Modifier.weight(1f)) {
                    Text(if (draft != null) "有一份计划草稿等你点头" else "这周还没有参照", style = MaterialTheme.typography.titleSmall)
                    Hint(if (draft != null) "${draft.by}起草的：${draft.goals.joinToString("、") { it.title }}" else "五分钟定个计划：几个方向、交出什么、每天多少时间。不想想就让参谋起草。")
                }
                Text("→", style = MaterialTheme.typography.titleMedium, color = sig.plan)
            }
        }
        return
    }
    val main = com.zongkong.core.plan.Plans.mainAction(book, work, now, zone)
    val pr = remember(plan, work, now) { com.zongkong.core.plan.Plans.progress(plan, work, now, zone) }
    Panel(onClick = { nav.navigate("plan") }, modifier = Modifier.enter(2).testTag("plan-strip")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            ProgressRing(if (plan.outcomes.isEmpty()) 0f else plan.doneCount.toFloat() / plan.outcomes.size, sig.plan, size = 44.dp, stroke = 3.5.dp) {
                Text("${plan.doneCount}/${plan.outcomes.size}", style = MaterialTheme.typography.labelMedium.merge(Mono), color = sig.plan)
            }
            Column(Modifier.weight(1f)) {
                Text(plan.goals.joinToString(" · ") { it.title }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    (main?.let { "主线：${it.title}" } ?: "今天的主线还没定 · 点这里挑") + " · 计划内 ${pr.todayPlanMinutes}/${plan.dailyMinutes} 分",
                    style = MaterialTheme.typography.bodySmall, color = if (main == null) sig.warn else sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun Guidance(status: Status, date: String, now: Long, nav: NavHostController) {
    val sig = LocalSignals.current
    val hour = Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).hour
    val reason = status.reasons.firstOrNull()
    when {
        status.strict && reason != null -> {
            val (title, how, route) = when (reason) {
                is Reason.GateDue -> Triple(
                    "「${reason.status.gate.title}」还没交",
                    if (reason.status.gate.id == "plan_morning") "点这里，跟着向导两分钟部署完就放行" else "交了就放行，点这里去交",
                    if (reason.status.gate.id == "plan_morning") "wizard/plan" else "gate/${reason.status.gate.id}",
                )
                is Reason.Silence -> Triple("太久没汇报了", "报个到：说一句现在在做什么", "capture")
                is Reason.Quota -> Triple("今天的娱乐额度用完了", "明天凌晨 4 点重置", "rhythm")
                is Reason.Focus -> Triple("专注锁开着", "做完「${reason.title}」就解开", "session")
            }
            Panel(accent = sig.strict, onClick = { nav.navigate(route) }, modifier = Modifier.enter(1)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Seal("严", sig.strict, size = 44.dp, filled = true)
                    Column(Modifier.weight(1f)) {
                        Text(title, style = MaterialTheme.typography.titleSmall, color = sig.strict)
                        Text(how, style = MaterialTheme.typography.bodyMedium)
                    }
                    Text("去 →", style = MaterialTheme.typography.labelLarge, color = sig.strict)
                }
                if (status.reasons.size > 1) Hint("另外还有 ${status.reasons.size - 1} 条，一条条来。")
            }
        }
        status.gates.any { it.gate.id == "plan_morning" && !it.done && it.phase != GatePhase.NOT_OPEN } -> {
            Panel(accent = sig.plan, onClick = { nav.navigate("wizard/plan") }, modifier = Modifier.enter(1).testTag("guide-plan")) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DeptSeal(Dept.PLAN, size = 44.dp, filled = true)
                    Column(Modifier.weight(1f)) {
                        Text("先花两分钟部署今天", style = MaterialTheme.typography.titleSmall)
                        Hint("一步一步问你：今天最要紧的一件、第一步、几点做、做到什么算完。")
                    }
                    Text("开始 →", style = MaterialTheme.typography.labelLarge, color = sig.plan)
                }
            }
        }
        hour >= 20 && status.gates.any { it.gate.id == "hq_review" && !it.done } -> {
            Panel(accent = sig.hq, onClick = { nav.navigate("wizard/review") }, modifier = Modifier.enter(1)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    DeptSeal(Dept.HQ, size = 44.dp, filled = true)
                    Column(Modifier.weight(1f)) {
                        Text("复盘今天，定好明天第一步", style = MaterialTheme.typography.titleSmall)
                        Hint("点一点就行：今天每一步怎么样、明天先做什么。")
                    }
                    Text("开始 →", style = MaterialTheme.typography.labelLarge, color = sig.hq)
                }
            }
        }
    }
}

@Composable
private fun DeptTile(dept: Dept, status: Status, work: Work, now: Long, yishou: YishouStatus?, modifier: Modifier, onClick: () -> Unit) {
    val sig = LocalSignals.current
    val color = sig.dept(dept)
    val zone = java.time.ZoneId.systemDefault()
    val gates = status.gates.filter { it.gate.dept == dept }
    val (line, progress) = when (dept) {
        Dept.INFO -> Focus.evidence(work, Focus.Evidence.LINKED_NOTES, now, zone).first.let { "今天挂上 $it 条" to null }
        Dept.THINK -> Focus.evidence(work, Focus.Evidence.CONCLUSIONS, now, zone).first.let { "今天 $it 个判断" to null }
        Dept.PLAN -> Focus.evidence(work, Focus.Evidence.PLANNED_TODAY, now, zone).first.let { "今天排了 $it 步" to null }
        Dept.ACT -> when {
            work.session?.let { it.lockUntil > now } == true -> "专注锁到 ${clock(work.session!!.lockUntil)}" to null
            yishou != null -> "一手 ${yishou.doneSets}/${yishou.targetSets} 组" to (if (yishou.targetSets > 0) yishou.doneSets.toFloat() / yishou.targetSets else null)
            else -> "一手没连上" to null
        }
        Dept.HQ -> "" to null
    }
    val ring = progress ?: if (gates.isEmpty()) 0f else gates.count { it.done }.toFloat() / gates.size
    val source = remember { MutableInteractionSource() }
    Surface(
        modifier = modifier
            .pressScale(source)
            .clickable(interactionSource = source, indication = ripple(), onClick = onClick)
            .testTag("dept-${dept.name}"),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(22.dp),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(ring, color, size = 46.dp, stroke = 3.5.dp) {
                    Text(dept.seal, color = color, fontWeight = FontWeight.Black, fontSize = 18.sp)
                }
                Spacer(Modifier.weight(1f))
                val overdue = gates.any { it.phase == GatePhase.OVERDUE }
                if (overdue) PulseDot(sig.strict) else if (gates.isNotEmpty() && gates.all { it.done }) Text("✓", color = sig.free, fontWeight = FontWeight.Black)
            }
            Column {
                Text(dept.label, style = MaterialTheme.typography.titleSmall)
                Text(line, style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
fun QuickChip(label: String, color: Color, onClick: () -> Unit) {
    val source = remember { MutableInteractionSource() }
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, color.copy(alpha = 0.35f)),
        modifier = Modifier.pressScale(source).clickable(interactionSource = source, indication = ripple(), onClick = onClick),
    ) {
        Row(Modifier.padding(horizontal = 14.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.width(6.dp).height(6.dp).background(color, RoundedCornerShape(50)))
            Text(label, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** 参谋加的快捷按钮：ask 发给参谋，open 打开页面。 */
fun runShortcut(s: Shortcut, app: com.zongkong.app.ZkApp, nav: NavHostController) {
    if (s.kind == Shortcut.ASK) {
        app.chatDraft = s.payload
        nav.navigate("chat") { launchSingleTop = true }
    } else {
        val (p, t) = s.payload.split(':', limit = 2).let { it[0] to it.getOrElse(1) { "" } }
        app.chat.agent.tools.route(p, t)?.let { r -> runCatching { nav.navigate(r) } }
    }
}

@Composable
private fun TimelineRow(a: com.zongkong.core.work.Rec, work: Work, now: Long, nav: NavHostController) {
    val sig = LocalSignals.current
    val zone = java.time.ZoneId.systemDefault()
    val start = a.plan?.let { TimeParse.startMillis(it, zone) }
    Row(
        Modifier.fillMaxWidth().clickable { nav.navigate("action/${a.key}") }.padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Column(Modifier.width(52.dp), horizontalAlignment = Alignment.End) {
            Text(start?.let { clock(it) } ?: "—", style = MaterialTheme.typography.titleSmall.merge(Mono), color = if (start != null) MaterialTheme.colorScheme.onSurface else sig.muted)
            a.plan?.let { p ->
                val label = TimeParse.label(p, now, zone)
                if (!label.startsWith("今天")) Text(label.substringBefore(' '), style = MaterialTheme.typography.labelSmall, color = sig.muted)
            }
        }
        Box(Modifier.width(3.dp).height(34.dp).background(actionColor(a.actionStatus), RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            work.rec(a.threadKey)?.let { Text(it.title, style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

@Composable
private fun TaskCard(cur: Focus.Current, now: Long, notionReady: Boolean, nav: NavHostController, plan: com.zongkong.core.plan.WeekPlan? = null) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val a = cur.action
    val t = cur.thread
    val accent = when (cur.why) {
        Focus.Why.RUNNING -> sig.warn
        Focus.Why.NOW -> MaterialTheme.colorScheme.primary
        Focus.Why.OVERDUE -> sig.strict
        Focus.Why.CONTINUE -> sig.think
        else -> sig.muted
    }
    Surface(
        shape = RoundedCornerShape(26.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 1.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (cur.why == Focus.Why.RUNNING || cur.why == Focus.Why.OVERDUE) PulseDot(accent)
                Text(
                    if (a == null) "现在" else cur.why.label, color = accent, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f).padding(start = if (cur.why == Focus.Why.RUNNING || cur.why == Focus.Why.OVERDUE) 8.dp else 0.dp),
                )
                cur.start?.let { s ->
                    val label = when {
                        cur.why == Focus.Why.RUNNING -> app.store.work.value.session?.let { "已做 ${span(now - it.startedAt)}" } ?: ""
                        s > now -> "${span(s - now)}后"
                        cur.end != null && cur.end > now -> "还剩 ${span(cur.end - now)}"
                        cur.end != null -> "超时 ${span(now - cur.end)}"
                        else -> ""
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge.merge(Mono), color = accent)
                }
            }
            if (a == null) {
                Text("没有排好的下一步", style = MaterialTheme.typography.headlineMedium)
                Text("不用想太多：让参谋陪你挑一件，或者跟着向导部署一下。", style = MaterialTheme.typography.bodyMedium, color = sig.muted)
                BigButton("跟着向导排一件", { nav.navigate("wizard/plan") })
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { nav.navigate("chat") }, modifier = Modifier.weight(1f)) { Text("问参谋") }
                    OutlinedButton(onClick = { nav.navigate("import") }, modifier = Modifier.weight(1f)) { Text("导入交接块") }
                }
                return@Column
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                t?.let { Text("${it.title} · ${it[F.CODE]}", style = MaterialTheme.typography.labelLarge, color = sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false)) }
                if (plan != null) {
                    if (com.zongkong.core.plan.Plans.inPlan(plan, a)) Tag("计划内", sig.plan) else Tag("计划外", sig.warn)
                }
            }
            Text(a.title, style = MaterialTheme.typography.headlineMedium, modifier = Modifier.testTag("task-title").clickable { nav.navigate("action/${a.key}") })
            a.plan?.let { Text(TimeParse.label(it, now, app.store.zone), style = MaterialTheme.typography.bodySmall.merge(Mono), color = sig.muted) }
            Column(
                Modifier.fillMaxWidth().background(sig.wash.copy(alpha = 0.55f), RoundedCornerShape(16.dp)).padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CardLine("为什么", t?.get(F.WHY).orEmpty(), placeholder = "")
                CardLine("停在", a[F.BREAK].ifBlank { t?.get(F.BREAK).orEmpty() })
                CardLine("做到", a[F.CRITERIA], placeholder = "还没写完成依据——点标题写一句：做到什么算完")
            }
            when (cur.why) {
                Focus.Why.RUNNING -> BigButton("回到正在做的", { nav.navigate("session") }, color = sig.warn)
                else -> BigButton(
                    if (cur.why == Focus.Why.CONTINUE) "从断点继续" else "开始",
                    { nav.navigate("start/${a.key}") },
                    Modifier.testTag("task-start"),
                    color = if (cur.why == Focus.Why.OVERDUE) sig.strict else MaterialTheme.colorScheme.primary,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    if (t != null) {
                        app.prompt = app.actions.work.gptPrompt(t.key)
                        app.promptTitle = t.title
                        app.store.awaitingGpt = t.key
                        nav.navigate("gpt")
                    } else {
                        nav.navigate("action/${a.key}")
                    }
                }, modifier = Modifier.testTag("task-gpt")) { Text("去 GPT") }
                TextButton(onClick = { nav.navigate("stuck/${a.key}") }) { Text("卡住了", color = sig.muted) }
                if (t != null && t.url.isNotBlank()) TextButton(onClick = { Gpt.openNotion(context, t.url) }) { Text("Notion", color = sig.muted) }
                Box(Modifier.weight(1f))
                SyncTag(a, notionReady)
            }
        }
    }
}

@Composable
private fun CardLine(label: String, value: String, placeholder: String = "") {
    if (value.isBlank() && placeholder.isBlank()) return
    Row {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalSignals.current.muted, modifier = Modifier.padding(top = 2.dp).width(44.dp))
        Text(
            value.ifBlank { placeholder }, style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) LocalSignals.current.warn else MaterialTheme.colorScheme.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun MiniRow(title: String, sub: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(Modifier.width(3.dp).height(34.dp).background(color, RoundedCornerShape(2.dp)))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
