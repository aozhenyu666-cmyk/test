package com.zongkong.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import com.zongkong.app.zk
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Focus
import com.zongkong.core.work.Handoff
import com.zongkong.core.work.TimeParse
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * 首页：此刻最重要的一步。
 * 一张任务卡回答五个问题：做什么、为什么、上次停在哪、怎么继续、做到什么算完。
 * 下面是四个直接入口，再下面是等你处理的事、接下来的安排、今天的节律。
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
    val cur = remember(work, now) { Focus.current(work, now, store.zone) }
    val upcoming = remember(work, now, cur) { Focus.upcoming(work, cur.action?.key, now, store.zone, 3) }
    val attention = remember(work) { Focus.needsAttention(work) }
    val unplanned = remember(work) { Focus.unplannedThreads(work) }
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val notionReady = config.notion.workReady
    var resumeKey by remember { mutableIntStateOf(0) }
    var clipHandoff by remember { mutableStateOf(false) }

    // 回到前台：拉一次 Notion（GPT 可能刚写过）；刚从 ChatGPT 回来且剪贴板里有交接块，就提示导入
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeKey++ }
    LaunchedEffect(resumeKey) {
        if (notionReady && System.currentTimeMillis() - work.lastSyncAt > 60_000) scope.launch { app.actions.work.sync() }
        if (store.awaitingGpt.isNotBlank()) {
            delay(400) // 等窗口拿到焦点才能读剪贴板
            clipHandoff = Handoff.contains(Gpt.readClipboard(context))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        // ---------- 顶部：日期 + 节律状态 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                val d = Instant.ofEpochMilli(now).atZone(store.zone)
                Text("%d月%d日 %s".format(d.monthValue, d.dayOfMonth, "周" + "一二三四五六日"[d.dayOfWeek.value - 1]), style = MaterialTheme.typography.titleMedium.merge(SerifTitle))
                Text(clock(now), style = MaterialTheme.typography.bodySmall.merge(Mono), color = sig.muted)
            }
            val c = when {
                status.paused -> sig.muted
                status.emergencyUntil != null -> sig.warn
                status.strict -> sig.strict
                else -> sig.free
            }
            Surface(
                color = c.copy(alpha = 0.12f), shape = RoundedCornerShape(50),
                modifier = Modifier.clickable { nav.navigate("rhythm") },
            ) {
                Text(
                    "${status.headline} · 节律 ${status.doneCount}/${status.gates.size}",
                    color = c, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                )
            }
        }

        if (clipHandoff) {
            Panel(accent = sig.think, onClick = { clipHandoff = false; nav.navigate("import") }) {
                Text("剪贴板里有 GPT 的交接块", style = MaterialTheme.typography.titleSmall)
                Hint("点这里导入：结论、下一步、行动会写进事项，并同步到 Notion。")
            }
        }

        // ---------- 当前任务卡 ----------
        TaskCard(cur, now, notionReady, nav)

        // ---------- 四个入口 ----------
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val cont = work.session?.let { "session" } ?: cur.action?.let { if (cur.why == Focus.Why.CONTINUE) "start/${it.key}" else null }
                ?: work.threads().filter { it.threadStatus.open }.maxByOrNull { it.updatedAt }?.let { "thread/${it.key}" }
            Tile("继续上次", "续", Modifier.weight(1f), enabled = cont != null) { cont?.let { nav.navigate(it) } }
            Tile("收集念头", "收", Modifier.weight(1f)) { nav.navigate("capture") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Tile("我卡住了", "卡", Modifier.weight(1f), enabled = cur.action != null) { cur.action?.let { nav.navigate("stuck/${it.key}") } }
            val n = work.changes.count { it.at > store.seenChanges }
            Tile(if (n > 0) "查看结果 · $n" else "查看结果", "果", Modifier.weight(1f)) { nav.navigate("results") }
        }

        // ---------- 等你处理 ----------
        if (attention.isNotEmpty() || unplanned.isNotEmpty()) {
            SectionLabel("等你处理")
            attention.take(4).forEach { a ->
                val label = if (a.actionStatus == ActionStatus.CONFIRM) "待确认" else "卡住：${a[F.STUCK].ifBlank { "原因没写" }}"
                MiniRow(a.title, label, actionColor(a.actionStatus)) { nav.navigate("action/${a.key}") }
            }
            unplanned.take(3).forEach { t ->
                MiniRow("${t.title}：${t[F.NEXT]}", "下一步还没排进时间", sig.warn) { nav.navigate("thread/${t.key}") }
            }
        }

        // ---------- 接下来 ----------
        if (upcoming.isNotEmpty()) {
            SectionLabel("接下来")
            upcoming.forEach { a ->
                val time = a.plan?.let { TimeParse.label(it, now, store.zone) } ?: "未排时间"
                MiniRow(a.title, time + (work.rec(a.threadKey)?.let { " · ${it.title}" } ?: ""), actionColor(a.actionStatus)) { nav.navigate("action/${a.key}") }
            }
        }

        // ---------- 今天的节律 ----------
        SectionLabel("今天的节律")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            status.gates.forEach { g ->
                Surface(
                    color = phaseColor(g.phase).copy(alpha = 0.10f), shape = RoundedCornerShape(6.dp),
                    modifier = Modifier.clickable { nav.navigate("gate/${g.gate.id}") },
                ) {
                    Row(Modifier.padding(horizontal = 8.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(g.gate.dept.seal, color = sig.dept(g.gate.dept), fontWeight = FontWeight.Black, fontSize = 13.sp)
                        Text(" ${g.gate.title} · ${g.phase.label}", style = MaterialTheme.typography.labelMedium, color = phaseColor(g.phase))
                    }
                }
            }
        }
        if (!PermissionStatus.accessibility(context)) {
            Hint("总控没在盯（无障碍未开），专注锁和严管不会生效。设置 → 权限与运行状态。")
        }
        SyncLine(work, notionReady) { scope.launch { app.actions.work.sync() } }
    }
}

@Composable
private fun TaskCard(cur: Focus.Current, now: Long, notionReady: Boolean, nav: NavHostController) {
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
        else -> sig.line
    }
    // 边框可以很淡，文字不行：普通状态的标签用灰色
    val labelColor = if (accent == sig.line) sig.muted else accent
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.5.dp, accent),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cur.why.label, color = labelColor, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                cur.start?.let { s ->
                    val label = when {
                        cur.why == Focus.Why.RUNNING -> app.store.work.value.session?.let { "已做 ${span(now - it.startedAt)}" } ?: ""
                        s > now -> "${span(s - now)}后"
                        cur.end != null && cur.end > now -> "还剩 ${span(cur.end - now)}"
                        cur.end != null -> "超时 ${span(now - cur.end)}"
                        else -> ""
                    }
                    Text(label, style = MaterialTheme.typography.labelLarge.merge(Mono), color = labelColor)
                }
            }
            if (a == null) {
                Text("现在没有排好的下一步", style = MaterialTheme.typography.headlineSmall.merge(SerifTitle))
                Hint("把一件事拿去和 GPT 讨论出下一步，或者先收一个念头。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { nav.navigate("threads") }) { Text("挑一件事") }
                    OutlinedButton(onClick = { nav.navigate("import") }) { Text("导入交接块") }
                }
                return@Column
            }
            t?.let { Text("${it.title} · ${it[F.CODE]}", style = MaterialTheme.typography.labelLarge, color = sig.muted, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            Text(a.title, style = MaterialTheme.typography.headlineSmall.merge(SerifTitle), modifier = Modifier.testTag("task-title").clickable { nav.navigate("action/${a.key}") })
            a.plan?.let { Text(TimeParse.label(it, now, app.store.zone), style = MaterialTheme.typography.bodySmall.merge(Mono), color = sig.muted) }
            CardLine("为什么", t?.get(F.WHY).orEmpty())
            CardLine("停在", a[F.BREAK].ifBlank { t?.get(F.BREAK).orEmpty() })
            CardLine("做到", a[F.CRITERIA], placeholder = "还没写完成依据——点开写一句：做到什么算完")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                when (cur.why) {
                    Focus.Why.RUNNING -> Button(onClick = { nav.navigate("session") }, modifier = Modifier.weight(1f)) { Text("回到正在做的") }
                    else -> Button(
                        onClick = { nav.navigate("start/${a.key}") },
                        colors = if (cur.why == Focus.Why.OVERDUE) ButtonDefaults.buttonColors(containerColor = sig.strict) else ButtonDefaults.buttonColors(),
                        modifier = Modifier.weight(1f).testTag("task-start"),
                    ) { Text(if (cur.why == Focus.Why.CONTINUE) "从断点继续" else "开始") }
                }
                OutlinedButton(onClick = {
                    if (t != null) {
                        app.prompt = app.actions.work.gptPrompt(t.key)
                        app.promptTitle = t.title
                        app.store.awaitingGpt = t.key
                        nav.navigate("gpt")
                    } else {
                        nav.navigate("action/${a.key}")
                    }
                }, modifier = Modifier.testTag("task-gpt")) { Text("去 GPT") }
            }
            Row {
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
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalSignals.current.muted, modifier = Modifier.padding(top = 2.dp).fillMaxWidth(0.16f))
        Text(
            value.ifBlank { placeholder }, style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) LocalSignals.current.warn else MaterialTheme.colorScheme.onSurface,
            maxLines = 3, overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 黑白方块入口（stoic 式）。 */
@Composable
private fun Tile(label: String, seal: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val ink = MaterialTheme.colorScheme.onSurface
    Surface(
        modifier = modifier.height(76.dp).then(if (enabled) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(12.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, LocalSignals.current.line),
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.background(if (enabled) ink else LocalSignals.current.line, RoundedCornerShape(8.dp)).padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(seal, color = MaterialTheme.colorScheme.surface, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
            Text(label, style = MaterialTheme.typography.titleSmall, color = if (enabled) ink else LocalSignals.current.muted)
        }
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
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.background(color, RoundedCornerShape(2.dp)).padding(horizontal = 2.dp, vertical = 14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(sub, style = MaterialTheme.typography.bodySmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
