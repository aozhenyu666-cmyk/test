package com.zongkong.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.work.ActionStatus
import com.zongkong.core.work.F
import com.zongkong.core.work.Rec
import com.zongkong.core.work.ThreadStatus
import com.zongkong.core.work.TimeParse
import kotlinx.coroutines.launch

// ---------- 事项列表 ----------

@Composable
fun ThreadsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    var creating by remember { mutableStateOf(false) }
    val groups = remember(work) {
        work.threads().groupBy {
            when (it.threadStatus) {
                ThreadStatus.ACTIVE, ThreadStatus.READY, ThreadStatus.STUCK -> "推进中"
                ThreadStatus.COLLECTING, ThreadStatus.THINKING -> "收集与思考"
                else -> "已收尾"
            }
        }
    }
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("事项", style = MaterialTheme.typography.headlineSmall.merge(SerifTitle), modifier = Modifier.weight(1f))
            OutlinedButton(onClick = { nav.navigate("import") }) { Text("导入交接块") }
        }
        Hint("一件事一条：材料、讨论出的判断、行动和结果都挂在它上面。GPT 在 Notion 里写的也会出现在这里。")
        Button(onClick = { creating = true }, modifier = Modifier.fillMaxWidth()) { Text("新建一件事") }
        if (work.threads().isEmpty()) Hint("还没有事项。可以新建，或者去 GPT 讨论后导入交接块。")
        listOf("推进中", "收集与思考", "已收尾").forEach { g ->
            val list = groups[g].orEmpty().sortedByDescending { it.updatedAt }
            if (list.isEmpty()) return@forEach
            SectionLabel(g)
            list.forEach { t -> ThreadRow(t, work.actionsOf(t.key), config.notion.workReady) { nav.navigate("thread/${t.key}") } }
        }
        SyncLine(work, config.notion.workReady) { scope.launch { app.actions.work.sync() } }
    }
    if (creating) {
        EditDialog("新建一件事", "", hint = "写一句话：要推进的是什么事。比如“国考行测提到 75 分”“改简历并投 3 家”。", singleLine = true, onDismiss = { creating = false }) { title ->
            if (title.isNotBlank()) nav.navigate("thread/${app.actions.work.createThread(title)}")
        }
    }
}

@Composable
private fun ThreadRow(t: Rec, actions: List<Rec>, notionReady: Boolean, onClick: () -> Unit) {
    val sig = LocalSignals.current
    val open = actions.count { it.actionStatus.open }
    Panel(onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(t.title, style = MaterialTheme.typography.titleMedium.merge(SerifTitle), modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Tag(t.threadStatus.label, threadColor(t.threadStatus))
        }
        val line = t[F.NEXT].ifBlank { t[F.JUDGE] }.ifBlank { t[F.WHY] }
        if (line.isNotBlank()) Text(line, style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Row {
            Text("${t[F.CODE]} · 行动 ${actions.size}（待做 $open）", style = MaterialTheme.typography.labelSmall.merge(Mono), color = sig.muted, modifier = Modifier.weight(1f))
            SyncTag(t, notionReady)
        }
    }
}

// ---------- 事项详情 ----------

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ThreadScreen(key: String, nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val t = work.rec(key)
    val sig = LocalSignals.current
    var editing by remember { mutableStateOf<String?>(null) }
    var adding by remember { mutableStateOf(false) }
    var showAllNotes by remember { mutableStateOf(false) }
    val now = rememberNow(30_000)

    Column(Modifier.fillMaxSize()) {
        BackBar(t?.get(F.CODE) ?: "事项", { nav.popBackStack() })
        if (t == null) {
            Hint("找不到这件事。", Modifier.padding(16.dp))
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(t.title, style = MaterialTheme.typography.headlineSmall.merge(SerifTitle), modifier = Modifier.clickable { editing = F.TITLE })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                ThreadStatus.entries.forEach { s ->
                    FilterChip(selected = t.threadStatus == s, onClick = { app.actions.work.update(key, mapOf(F.STATUS to s.label)) }, label = { Text(s.label) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SyncTag(t, config.notion.workReady)
                if (t.syncError.isNotBlank()) Text("  ${t.syncError}", style = MaterialTheme.typography.labelSmall, color = sig.warn)
            }

            // 冲突：两边都改了同一处
            if (t.conflicts.isNotEmpty()) ConflictPanel(t)

            // 去 GPT / Notion
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    app.prompt = app.actions.work.gptPrompt(key)
                    app.promptTitle = t.title
                    app.store.awaitingGpt = key
                    nav.navigate("gpt")
                }, modifier = Modifier.weight(1f)) { Text("带着前情去 GPT") }
                if (t.url.isNotBlank()) OutlinedButton(onClick = { Gpt.openNotion(context, t.url) }) { Text("Notion") }
            }

            Panel {
                FieldRow("为什么做", t[F.WHY], "（写一句：为什么值得做）") { editing = F.WHY }
                FieldRow("完成依据", t[F.CRITERIA], "（整件事做到什么算完成）") { editing = F.CRITERIA }
                FieldRow("当前判断", t[F.JUDGE], "（和 GPT 讨论后形成的判断）") { editing = F.JUDGE }
                FieldRow("关键不确定", t[F.UNSURE], "（还缺什么信息、去哪查）") { editing = F.UNSURE }
                FieldRow("断点", t[F.BREAK], "（上次停在哪）") { editing = F.BREAK }
                FieldRow("下一步", t[F.NEXT], "（接下来做什么）") { editing = F.NEXT }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("行动")
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = { adding = true }) { Text("＋ 行动") }
            }
            val actions = work.actionsOf(key).sortedWith(compareBy({ !it.actionStatus.open }, { it.plan?.let { p -> TimeParse.startMillis(p, app.store.zone) } ?: Long.MAX_VALUE }))
            if (actions.isEmpty()) Hint(if (t[F.NEXT].isNotBlank()) "下一步“${t[F.NEXT]}”还没排成行动。点 ＋ 行动 排进时间。" else "还没有行动。")
            actions.forEach { a -> ActionRow(a, now) { nav.navigate("action/${a.key}") } }

            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel("记录")
                androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
                TextButton(onClick = { nav.navigate("capture/$key") }) { Text("＋ 记一条") }
            }
            val notes = work.notesOf(key).sortedByDescending { it.createdAt }
            if (notes.isEmpty()) Hint("材料、问题、判断、结果、断点都会记在这里。")
            (if (showAllNotes) notes else notes.take(6)).forEach { n -> NoteRow(n) }
            if (notes.size > 6 && !showAllNotes) TextButton(onClick = { showAllNotes = true }) { Text("看全部 ${notes.size} 条") }
        }
    }

    editing?.let { field ->
        EditDialog(field, t?.get(field).orEmpty(), singleLine = field == F.TITLE, onDismiss = { editing = null }) { v ->
            app.actions.work.update(key, mapOf(field to v.trim()))
        }
    }
    if (adding) AddActionDialog(key, t?.get(F.NEXT).orEmpty()) { adding = false }
}

@Composable
fun ConflictPanel(r: Rec) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    Panel(accent = sig.strict) {
        Text("两边都改了同一处", style = MaterialTheme.typography.titleSmall, color = sig.strict)
        Hint("手机和 Notion（可能是 GPT）同时改了下面的内容。现在显示的是后改的那份，另一份留在这里，选一个。")
        r.conflicts.forEach { (field, other) ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(field, style = MaterialTheme.typography.labelLarge)
                Text("现在：${r[field].ifBlank { "（空）" }}", style = MaterialTheme.typography.bodySmall)
                Text("另一份：${other.ifBlank { "（空）" }}", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.actions.work.pickConflict(r.key, field, false) }) { Text("保留现在的") }
                    OutlinedButton(onClick = { app.actions.work.pickConflict(r.key, field, true) }) { Text("用另一份") }
                }
            }
        }
    }
}

@Composable
fun ActionRow(a: Rec, now: Long, onClick: () -> Unit) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    Panel(onClick = onClick, accent = if (a.actionStatus == ActionStatus.STUCK) sig.strict else null) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(a.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            Tag(a.actionStatus.label, actionColor(a.actionStatus))
        }
        val bits = listOfNotNull(
            a.plan?.let { TimeParse.label(it, now, app.store.zone) },
            a[F.CRITERIA].takeIf { it.isNotBlank() }?.let { "做到：$it" },
        )
        if (bits.isNotEmpty()) Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = sig.muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (a[F.RESULT].isNotBlank()) Text("结果：${a[F.RESULT]}", style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
fun NoteRow(n: Rec) {
    val context = LocalContext.current
    val sig = LocalSignals.current
    var open by remember { mutableStateOf(false) }
    Column(
        Modifier
            .fillMaxWidth()
            .clickable { open = !open }
            .padding(vertical = 4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tag(n[F.TYPE].ifBlank { "记录" }, sig.info)
            Text(n.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = if (open) 4 else 1, overflow = TextOverflow.Ellipsis)
            Text(clock(n.createdAt), style = MaterialTheme.typography.labelSmall.merge(Mono), color = sig.muted)
        }
        if (open) {
            if (n[F.RAW].isNotBlank()) Text(n[F.RAW], style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            if (n[F.URL].isNotBlank()) TextButton(onClick = { Gpt.openNotion(context, n[F.URL]) }) { Text("打开链接") }
        }
    }
}

/** 新增行动：标题、时间（随便写，下面实时显示认出来的时间）、完成依据。 */
@Composable
fun AddActionDialog(threadKey: String, suggested: String, onDismiss: () -> Unit) {
    val app = LocalContext.current.zk
    var title by remember { mutableStateOf(suggested) }
    var time by remember { mutableStateOf("") }
    var criteria by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val parsed = remember(time) { TimeParse.parse(time, now, app.store.zone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("新增行动") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("做什么（小到 30 分钟内能开始）") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(time, { time = it }, label = { Text("什么时候") }, placeholder = { Text("明天 20:00-21:00 / 周六 10:00") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Hint(
                    when {
                        time.isBlank() -> "可以先不排时间"
                        parsed == null -> "没认出来"
                        else -> "→ ${TimeParse.label(parsed, now, app.store.zone)}"
                    },
                )
                OutlinedTextField(criteria, { criteria = it }, label = { Text("完成依据：做到什么算完") }, placeholder = { Text("写完 300 字并发给朋友 / 正确率记录") }, modifier = Modifier.fillMaxWidth())
                err?.let { Text(it, color = LocalSignals.current.strict) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (title.isBlank()) {
                    err = "写一下做什么"
                } else {
                    val (_, e) = app.actions.work.addAction(threadKey, title, time, criteria)
                    if (e != null) err = e else onDismiss()
                }
            }) { Text("添加") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------- 行动详情 ----------

@Composable
fun ActionScreen(key: String, nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val a = work.rec(key)
    val sig = LocalSignals.current
    val now = rememberNow(30_000)
    var editing by remember { mutableStateOf<String?>(null) }
    var resched by remember { mutableStateOf(false) }
    var cancel by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar("行动", { nav.popBackStack() })
        if (a == null) {
            Hint("找不到这一步。", Modifier.padding(16.dp))
            return@Column
        }
        val t = work.rec(a.threadKey)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            t?.let { Text("${it.title} · ${it[F.CODE]}", style = MaterialTheme.typography.labelLarge, color = sig.muted, modifier = Modifier.clickable { nav.navigate("thread/${it.key}") }) }
            Text(a.title, style = MaterialTheme.typography.headlineSmall.merge(SerifTitle), modifier = Modifier.clickable { editing = F.TITLE })
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Tag(a.actionStatus.label, actionColor(a.actionStatus))
                SyncTag(a, config.notion.workReady)
            }
            if (a.conflicts.isNotEmpty()) ConflictPanel(a)
            Panel {
                FieldRow("时间", a.plan?.let { TimeParse.label(it, now, app.store.zone) }.orEmpty(), "（没排时间）") { resched = true }
                FieldRow("完成依据", a[F.CRITERIA], "（做到什么算完——写一句）") { editing = F.CRITERIA }
                FieldRow("断点", a[F.BREAK], "（做到一半离开时记在这里）") { editing = F.BREAK }
                FieldRow("结果", a[F.RESULT], "（做完后写）") { editing = F.RESULT }
                if (a[F.STUCK].isNotBlank()) FieldRow("卡点", a[F.STUCK])
            }
            when (a.actionStatus) {
                ActionStatus.DONE, ActionStatus.CANCELLED -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { app.actions.work.update(key, mapOf(F.STATUS to ActionStatus.TODO.label)) }) { Text("重新打开") }
                }
                ActionStatus.CONFIRM -> {
                    Hint("结果记了，但没完全达到完成依据。可以带去 GPT 判断，或者你自己确认。")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { app.actions.work.update(key, mapOf(F.STATUS to ActionStatus.DONE.label)) }) { Text("确认完成") }
                        OutlinedButton(onClick = { app.actions.work.update(key, mapOf(F.STATUS to ActionStatus.TODO.label)) }) { Text("还没完成") }
                    }
                }
                else -> {
                    Button(onClick = { nav.navigate("start/$key") }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (work.session?.actionKey == key) "回到正在做的" else if (a[F.BREAK].isNotBlank()) "从断点继续" else "开始")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { nav.navigate("complete/$key") }, modifier = Modifier.weight(1f)) { Text("已经做完") }
                        OutlinedButton(onClick = { nav.navigate("stuck/$key") }, modifier = Modifier.weight(1f)) { Text("卡住了") }
                    }
                }
            }
            Row {
                TextButton(onClick = { resched = true }) { Text("改期") }
                TextButton(onClick = { cancel = true }) { Text("取消这一步", color = sig.muted) }
                if (t != null) TextButton(onClick = {
                    app.prompt = app.actions.work.gptPrompt(t.key, "这一步是「${a.title}」。")
                    app.promptTitle = t.title
                    app.store.awaitingGpt = t.key
                    nav.navigate("gpt")
                }) { Text("去 GPT") }
            }
            val events = work.events.filter { it.actionKey == key }.takeLast(8).asReversed()
            if (events.isNotEmpty()) {
                SectionLabel("经过")
                events.forEach { e -> Hint("${clock(e.at)} ${eventLabel(e.type)}${if (e.detail.isNotBlank()) "：${e.detail}" else ""}") }
            }
        }
    }
    editing?.let { field ->
        EditDialog(field, a?.get(field).orEmpty(), singleLine = field == F.TITLE, onDismiss = { editing = null }) { v -> app.actions.work.update(key, mapOf(field to v.trim())) }
    }
    if (resched) RescheduleDialog(key) { resched = false }
    if (cancel) EditDialog("取消这一步", "", hint = "为什么不做了？（会记在结果里）", onDismiss = { cancel = false }) { r -> app.actions.work.cancel(key, r) }
}

fun eventLabel(type: String): String = when {
    type == "start" -> "开始"
    type == "pause" -> "离开，记了断点"
    type == "remind" -> "到点提醒"
    type == "followup" -> "追问还没开始"
    type == "snooze" -> "推迟 30 分钟"
    type == "reschedule" -> "改期"
    type == "cancel" -> "取消"
    type == "import" -> "从 GPT 交接导入"
    type.startsWith("done:") -> "记录结果"
    type.startsWith("stuck:") -> "卡住：" + (runCatching { com.zongkong.core.work.StuckReason.valueOf(type.removePrefix("stuck:")).label }.getOrDefault(""))
    else -> type
}

@Composable
fun RescheduleDialog(actionKey: String, onDismiss: () -> Unit) {
    val app = LocalContext.current.zk
    var time by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    val now = System.currentTimeMillis()
    val parsed = remember(time) { TimeParse.parse(time, now, app.store.zone) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("改到什么时候") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf("今晚 20:00", "明天 9:00", "明晚 20:00").forEach { q -> OutlinedButton(onClick = { time = q }) { Text(q, style = MaterialTheme.typography.labelSmall) } }
                }
                OutlinedTextField(time, { time = it }, placeholder = { Text("周六 10:00-11:00") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Hint(if (time.isBlank()) "" else parsed?.let { "→ ${TimeParse.label(it, now, app.store.zone)}" } ?: "没认出来")
                err?.let { Text(it, color = LocalSignals.current.strict) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val e = app.actions.work.reschedule(actionKey, time)
                if (e != null) err = e else onDismiss()
            }) { Text("改期") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
