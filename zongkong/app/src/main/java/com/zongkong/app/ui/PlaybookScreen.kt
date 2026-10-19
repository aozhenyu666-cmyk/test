package com.zongkong.app.ui

import androidx.compose.foundation.background
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.plan.Block
import com.zongkong.core.plan.BlockState
import com.zongkong.core.plan.Playbook

/**
 * 今天的预案（试验）：一天排成几段，每段带 B 方案。
 * 草稿随便改；定稿后到点会提醒（开始前、开始、过半速记、结束交结果），改要写为什么。
 * 一段没法按 A 做：走 B 方案，或者写一句为什么跳过。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PlaybookScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    val book by app.store.plans.collectAsStateWithLifecycle()
    val now = rememberNow(30_000)
    val script = remember(book, now) { Playbook.today(book, now, app.store.zone) }
    var editing by remember { mutableStateOf(false) }
    val showEditor = script == null || !script.locked || editing
    var text by remember(script?.date, editing) { mutableStateOf(script?.let { Playbook.format(it) } ?: "") }
    var why by remember(editing) { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        BackBar("今天的预案", { nav.popBackStack() }) { Tag("试验", sig.ai) }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            if (showEditor) {
                Hint(
                    "把今天排成几段，每段写一个 B 方案：下雨、起晚、临时有事的时候换成什么做——情况变了是换做法，不是不做了。" +
                        "一行一段：09:00-10:30 资料分析两套 | B: 在家做一套",
                )
                OutlinedTextField(
                    text, { text = it; err = null },
                    label = { Text("一行一段") },
                    placeholder = { Text("09:00-10:30 资料分析两套 | B: 在家做一套\n14:00-15:00 申论大作文 | B: 只写提纲") },
                    minLines = 5, modifier = Modifier.fillMaxWidth().testTag("script-text"),
                )
                if (script?.locked == true) {
                    OutlinedTextField(why, { why = it; err = null }, label = { Text("为什么改（定稿后改要写）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                }
                err?.let { Text(it, color = sig.strict, style = MaterialTheme.typography.bodyMedium) }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(onClick = {
                        err = app.actions.plan.writeScript(text, why)
                        if (err == null) editing = false
                    }, modifier = Modifier.weight(1f).testTag("script-save")) { Text(if (script?.locked == true) "保存修改" else "存草稿") }
                    if (script?.locked != true) {
                        BigButton("定稿", {
                            err = app.actions.plan.writeScript(text, "")
                            if (err == null) { app.actions.plan.lockScript(); editing = false }
                        }, Modifier.weight(1f).testTag("script-lock"), color = sig.plan)
                    }
                }
                TextButton(onClick = {
                    app.chatDraft = "帮我把今天排成预案：每段写清楚做什么，再写一个 B 方案（下雨、起晚、临时有事时换成什么做）。我今天的情况是："
                    nav.navigate("chat") { launchSingleTop = true }
                }) { Text("让总控起草", color = sig.ai) }
                if (script?.locked == true) TextButton(onClick = { editing = false }) { Text("不改了", color = sig.muted) }
            }

            if (script != null && script.locked && !editing) {
                val cur = Playbook.current(script, now, app.store.zone)
                Hint("定稿于 ${java.time.Instant.ofEpochMilli(script.lockedAt).atZone(app.store.zone).toLocalTime().toString().take(5)}。到点会提醒：开始前 5 分钟、开始、过半速记、结束交结果。")
                script.blocks.forEach { b -> BlockCard(b, b.id == cur?.id) }
                OutlinedButton(onClick = { editing = true }, modifier = Modifier.fillMaxWidth()) { Text("改预案（要写为什么）") }
            } else if (script != null && !script.locked && script.blocks.isNotEmpty()) {
                Text("草稿预览", style = MaterialTheme.typography.titleMedium)
                script.blocks.forEach { b -> BlockCard(b, false, preview = true) }
            }
            if (script != null && script.revisions.isNotEmpty()) {
                Text("改动留痕", style = MaterialTheme.typography.titleMedium)
                Panel {
                    script.revisions.takeLast(8).forEach { r ->
                        Text("${java.time.Instant.ofEpochMilli(r.at).atZone(app.store.zone).toLocalTime().toString().take(5)} ${r.what}：${r.why}", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockCard(b: Block, current: Boolean, preview: Boolean = false) {
    val app = LocalContext.current.zk
    val sig = LocalSignals.current
    // 正在填的：速记 / 结果 / B 的原因 / 跳过的原因
    var mode by remember(b.id) { mutableStateOf<String?>(null) }
    var input by remember(b.id, mode) { mutableStateOf("") }
    var err by remember(b.id) { mutableStateOf<String?>(null) }
    val color = when (b.state) {
        BlockState.DONE -> sig.free
        BlockState.PLAN_B -> sig.warn
        BlockState.SKIPPED -> sig.strict
        BlockState.ON -> sig.plan
        BlockState.WAIT -> if (current) sig.plan else sig.muted
    }
    Panel(accent = if (current || b.state == BlockState.ON) sig.plan else null, modifier = Modifier.testTag("block-${b.id}")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(b.span, style = MaterialTheme.typography.labelLarge.merge(Mono), color = color)
            Box(Modifier.width(8.dp).height(8.dp).background(color, RoundedCornerShape(50)))
            Text(b.state.label, style = MaterialTheme.typography.labelMedium, color = color)
        }
        Text(b.what, style = MaterialTheme.typography.titleMedium)
        if (b.planB.isNotBlank()) Text("B：${b.planB}", style = MaterialTheme.typography.bodySmall, color = sig.warn)
        b.notes.forEach { Text("· $it", style = MaterialTheme.typography.bodySmall, color = sig.muted) }
        if (b.result.isNotBlank()) Text(b.result, style = MaterialTheme.typography.bodyMedium)
        if (preview || b.state.closed) return@Panel

        if (mode == null) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (b.state == BlockState.WAIT) QuickChip("开始", sig.plan) { app.actions.plan.scriptBegin(b.id) }
                if (b.state == BlockState.ON) QuickChip("速记", sig.info) { mode = "速记" }
                QuickChip("做完", sig.free) { mode = "做完" }
                if (b.planB.isNotBlank()) QuickChip("走 B 方案", sig.warn) { mode = "B" }
                QuickChip("跳过", sig.strict) { mode = "跳过" }
            }
        } else {
            OutlinedTextField(
                input, { input = it; err = null },
                label = { Text(when (mode) { "速记" -> "做到哪了（一句）"; "做完" -> "结果（可不写）"; "B" -> "为什么换 B（可不写）"; else -> "为什么跳过（要写）" }) },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            err?.let { Text(it, color = sig.strict, style = MaterialTheme.typography.bodySmall) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickChip("确定", sig.plan) {
                    err = when (mode) {
                        "速记" -> { app.actions.plan.scriptNote(b.id, input); null }
                        "做完" -> { app.actions.plan.scriptFinish(b.id, input); null }
                        "B" -> app.actions.plan.scriptPlanB(b.id, input)
                        else -> app.actions.plan.scriptSkip(b.id, input)
                    }
                    if (err == null) mode = null
                }
                QuickChip("算了", sig.muted) { mode = null; err = null }
            }
        }
    }
}
