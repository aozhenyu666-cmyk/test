package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.plan.Directive
import com.zongkong.core.plan.DirectiveItem
import com.zongkong.core.plan.Directives
import kotlin.random.Random

/**
 * 外接谋划：用户在外面（Gemini / GPT / Notion）聊出来的报告和计划。
 *
 * 总控这里只做三件事：收进来、拆成能检查的条目、盯落实。它不替用户重做专业谋划——
 * 那是外部谋划者的事；总控要做的是“管理专业能力”，不是“再长一个专业能力”。
 */

@Composable
fun DirectiveListScreen(nav: NavHostController) {
    val context = LocalContext.current
    val config by context.zk.store.config.collectAsStateWithLifecycle()
    val list = config.directives
    val active = list.filter { it.status == Directive.STATUS_ACTIVE }
    val others = list.filter { it.status != Directive.STATUS_ACTIVE }

    Column(Modifier.fillMaxSize()) {
        BackBar("外接谋划", { nav.popBackStack() }) {
            TextButton(onClick = { nav.navigate("directives/add") }) { Text("收一份") }
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenTitle(
                "外接的谋划",
                "Gemini / GPT / Notion 里聊出来的报告、计划、问题清单。收进来，总控负责盯落实：拆条目、挂事项、要证据，而不是替你重想一遍。",
                Modifier.enter(0),
            )
            if (list.isEmpty()) {
                Panel(Modifier.enter(1)) {
                    Text("还没有外接谋划单。", style = MaterialTheme.typography.titleSmall)
                    Hint("在 Gemini / GPT 里聊完，把报告整段复制进来（「收一份」），总控会把它拆成一条条能检查的事，之后按时对账。")
                }
            }
            if (active.isNotEmpty()) {
                SectionLabel("在办 · ${active.size} 份")
                active.forEachIndexed { i, d -> DirectiveCard(d, "第 ${i + 1} 份", Modifier.enter(i + 1)) { nav.navigate("directive/${d.id}") } }
            }
            if (others.isNotEmpty()) {
                SectionLabel("已收起 · ${others.size} 份")
                others.forEach { d -> DirectiveCard(d, "", Modifier) { nav.navigate("directive/${d.id}") } }
            }
            Hint("总控不替你做专业谋划；它只监督这些谋划有没有落到事项、行动和约定上。", Modifier.padding(top = 8.dp, bottom = 12.dp))
        }
    }
}

@Composable
private fun DirectiveCard(d: Directive, tag: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Panel(modifier = modifier, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    listOfNotNull(tag.takeIf { it.isNotBlank() }, d.title).joinToString(" · "),
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    "${d.source} · ${d.doneCount}/${d.items.size} 条落实" +
                        if (d.status != Directive.STATUS_ACTIVE) " · ${if (d.status == Directive.STATUS_DONE) "已收掉" else "已归档"}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = LocalSignals.current.muted,
                )
            }
            Text("›", style = MaterialTheme.typography.titleLarge, color = LocalSignals.current.muted)
        }
        if (d.summary.isNotBlank()) Text(d.summary, style = MaterialTheme.typography.bodyMedium)
        val open = d.open.take(3)
        if (open.isNotEmpty()) {
            Hint(open.joinToString("\n") { "· [${DirectiveItem.kindLabel(it.kind)}] ${it.text}" })
        }
    }
}

@Composable
fun DirectiveAddScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    var title by remember { mutableStateOf("") }
    var source by remember { mutableStateOf("粘贴") }
    var text by remember { mutableStateOf("") }
    var items by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize()) {
        BackBar("收一份谋划", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            ScreenTitle("把外面的谋划交进来", "整段贴进来就行。拆不开也没关系：存下后可以直接让总控拆。")
            OutlinedTextField(
                value = title, onValueChange = { title = it },
                label = { Text("这份谋划叫什么（一句话）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = source, onValueChange = { source = it },
                label = { Text("从哪来：Gemini / GPT / Notion / 粘贴") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = summary, onValueChange = { summary = it },
                label = { Text("结论（可空）：这份文档要你做到什么") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                label = { Text("原文：整段贴进来") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8, maxLines = 20,
            )
            OutlinedTextField(
                value = items, onValueChange = { items = it },
                label = { Text("手工拆条目（可空）：每行 类型｜一句话｜时间") },
                placeholder = { Text("要做｜把国考时间表查清楚｜本周\n风险｜资料分析做题速度不够\n待决｜要不要报冲刺班") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 4, maxLines = 10,
            )
            val canSave = title.isNotBlank() && text.isNotBlank()
            BigButton("收下", {
                val now = System.currentTimeMillis()
                val d = Directive(
                    id = "D" + (now / 1000).toString(36).takeLast(4) + Random.nextInt(36 * 36).toString(36).padStart(2, '0'),
                    title = title.trim().take(60),
                    source = source.trim().take(20).ifBlank { "粘贴" },
                    at = now,
                    raw = text.trim().take(20000),
                    summary = summary.trim().take(300),
                    items = parseItems(items),
                )
                store.updateConfig { it.copy(directives = (it.directives + d).takeLast(30)) }
                // 没手拆就把原文交给总控，让它按“拆条目”来做（不另做谋划）。
                if (d.items.isEmpty() && store.config.value.ai.ready) {
                    context.zk.chat.send(
                        "我刚收了一份外接谋划「${d.title}」，还没拆条目。先用 directive_check 看看它，然后把原文拆成能检查的条目（directive_update 加条目），" +
                            "只拆不重写：不要替我重新谋划。",
                    )
                }
                nav.popBackStack()
            }, Modifier, enabled = canSave)
            Hint("存下后：总控会在系统提示里带着这份谋划单；你随时可以问“外接的谋划现在到哪了”。", Modifier.padding(bottom = 12.dp))
        }
    }
}

@Composable
fun DirectiveDetailScreen(id: String, nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val d = config.directives.firstOrNull { it.id == id }
    var newItem by remember { mutableStateOf("") }
    var showRaw by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        BackBar("谋划单", { nav.popBackStack() })
        if (d == null) {
            Column(Modifier.padding(20.dp)) { Hint("这份谋划单已经没了。") }
            return@Column
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            ScreenTitle(d.title, "${d.source} · 收于 ${clock(d.at)}")
            if (d.summary.isNotBlank()) Panel { Text(d.summary, style = MaterialTheme.typography.bodyLarge) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Pill("${d.doneCount}/${d.items.size} 条落实", LocalSignals.current.ai)
            }
            SectionLabel("条目")
            val items = d.items
            if (items.isEmpty()) {
                Hint("还没拆条目。把它拆成一条条能检查的事，才能盯落实。")
            } else {
                items.forEach { i ->
                    Panel {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = i.done, onCheckedChange = { v -> setItem(store, d.id, i.id, v) })
                            Column(Modifier.weight(1f)) {
                                Text(i.text, style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    listOfNotNull(
                                        DirectiveItem.kindLabel(i.kind),
                                        i.due.takeIf { it.isNotBlank() },
                                        i.threadKey.takeIf { it.isNotBlank() }?.let { "事项 $it" },
                                    ).joinToString(" · "),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = LocalSignals.current.muted,
                                )
                            }
                        }
                    }
                }
            }
            SectionLabel("加一条")
            OutlinedTextField(
                value = newItem, onValueChange = { newItem = it },
                label = { Text("类型｜一句话｜时间（类型可省）") },
                modifier = Modifier.fillMaxWidth(), singleLine = true,
            )
            TextButton(onClick = {
                val parsed = parseItems(newItem)
                if (parsed.isNotEmpty()) {
                    store.updateConfig { c -> c.copy(directives = c.directives.map { x -> if (x.id == d.id) x.copy(items = x.items + parsed) else x }) }
                    newItem = ""
                }
            }) { Text("加上") }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { showRaw = !showRaw }) { Text(if (showRaw) "收起原文" else "看原文") }
                TextButton(onClick = {
                    store.updateConfig { c -> c.copy(directives = c.directives.map { x -> if (x.id == d.id) x.copy(status = Directive.STATUS_DONE) else x }) }
                    nav.popBackStack()
                }) { Text("收掉这单") }
                TextButton(onClick = {
                    store.updateConfig { c -> c.copy(directives = c.directives.filterNot { x -> x.id == d.id }) }
                    nav.popBackStack()
                }) { Text("删掉", color = LocalSignals.current.strict) }
            }
            if (showRaw) {
                Panel { Text(d.raw, style = MaterialTheme.typography.bodySmall) }
            }
            Hint("总控是监督者：它只核对条目有没有落到事项、行动和约定上，不替你重写这份谋划。", Modifier.padding(bottom = 16.dp))
        }
    }
}

private fun setItem(store: com.zongkong.app.data.Store, directiveId: String, itemId: String, done: Boolean) {
    store.updateConfig { c ->
        c.copy(directives = c.directives.map { d ->
            if (d.id != directiveId) d
            else d.copy(items = d.items.map { if (it.id == itemId) it.copy(done = done) else it })
        })
    }
}

/** 解析“类型｜一句话｜时间”行；类型认不出就把第一列当内容。 */
private fun parseItems(raw: String): List<DirectiveItem> =
    raw.lines().map { it.trim() }.filter { it.isNotBlank() }.take(60).mapIndexed { idx, line ->
        val parts = line.split('｜', '|').map { p -> p.trim() }
        val kind = when (parts.getOrNull(0).orEmpty()) {
            "风险", "risk" -> DirectiveItem.KIND_RISK
            "待决", "question" -> DirectiveItem.KIND_QUESTION
            "事实", "fact" -> DirectiveItem.KIND_FACT
            "要做", "task" -> DirectiveItem.KIND_TASK
            else -> null
        }
        val body = if (kind == null) parts.getOrNull(0).orEmpty() else parts.getOrNull(1).orEmpty()
        val due = if (kind == null) parts.getOrNull(1).orEmpty() else parts.getOrNull(2).orEmpty()
        DirectiveItem(
            id = "i${idx}_" + Random.nextInt(1296).toString(36).padStart(2, '0'),
            text = body.take(200),
            kind = kind ?: DirectiveItem.KIND_TASK,
            due = due.take(40),
        )
    }.filter { it.text.isNotBlank() }
