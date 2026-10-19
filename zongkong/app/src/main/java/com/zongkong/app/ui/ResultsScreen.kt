package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.zongkong.core.work.Focus
import kotlinx.coroutines.launch

/**
 * 查看结果：Notion 那边（GPT、你自己）改了什么；哪些行动等你确认、哪些卡住了；
 * 以及从你的记录里看出来的规律——什么时候的提醒管用、最常卡在哪。
 */
@Composable
fun ResultsScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val work by app.store.work.collectAsStateWithLifecycle()
    val config by app.store.config.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val seen = remember { app.store.seenChanges }
    var msg by remember { mutableStateOf<String?>(null) }
    DisposableEffect(Unit) { onDispose { app.store.seenChanges = System.currentTimeMillis() } }

    Column(Modifier.fillMaxSize()) {
        BackBar("查看结果", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            SyncLine(work, config.notion.workReady) { scope.launch { msg = app.actions.work.sync() } }
            msg?.let { Hint(it) }

            val attention = Focus.needsAttention(work)
            if (attention.isNotEmpty()) {
                SectionLabel("等你确认 / 卡住的")
                attention.forEach { a ->
                    Panel(onClick = { nav.navigate("action/${a.key}") }, accent = actionColor(a.actionStatus)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(a.title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Tag(a.actionStatus.label, actionColor(a.actionStatus))
                        }
                        if (a[F.RESULT].isNotBlank()) Hint("结果：${a[F.RESULT]}")
                        if (a.actionStatus == ActionStatus.CONFIRM) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = { app.actions.work.update(a.key, mapOf(F.STATUS to ActionStatus.DONE.label)) }) { Text("确认完成") }
                            OutlinedButton(onClick = { app.actions.work.update(a.key, mapOf(F.STATUS to ActionStatus.TODO.label)) }) { Text("还没完成") }
                        }
                    }
                }
            }

            SectionLabel("最近的变化（来自 Notion / GPT）")
            if (work.changes.isEmpty()) Hint(if (config.notion.workReady) "还没有。GPT 在 Notion 里写了东西，同步后会出现在这里。" else "Notion 还没连接。")
            work.changes.asReversed().take(40).forEach { c ->
                Row(
                    Modifier.padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top,
                ) {
                    Text(clock(c.at), style = MaterialTheme.typography.labelSmall.merge(Mono), color = if (c.at > seen) sig.warn else sig.muted, modifier = Modifier.width(40.dp))
                    Column(Modifier.weight(1f)) {
                        Text("${c.kind.label}「${c.title}」", style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(c.what, style = MaterialTheme.typography.bodySmall, color = if (c.what.startsWith("冲突")) sig.strict else sig.muted)
                    }
                    Tag(c.by, if (c.by == "GPT") sig.think else sig.info)
                }
            }

            val failed = work.recs.filter { it.sync == com.zongkong.core.work.SyncState.FAILED }
            if (failed.isNotEmpty()) {
                SectionLabel("同步失败的")
                failed.forEach { r -> Hint("${r.kind.label}「${r.title}」：${r.syncError}") }
            }

            val ins = Focus.insight(work, app.store.zone)
            SectionLabel("什么对你有效")
            if (ins.reminders == 0 && ins.stuck.isEmpty()) {
                Hint("用几天以后，这里会显示：提醒后多快开始、哪个时段的提醒最管用、最常卡在哪。")
            } else {
                Panel {
                    KeyValue("提醒后 30 分钟内开始", "${ins.startedAfterReminder}/${ins.reminders}")
                    ins.byPeriod.forEach { (p, v) -> KeyValue("  $p", "${v.first}/${v.second}") }
                    if (ins.reschedules > 0) KeyValue("改期", "${ins.reschedules} 次")
                    if (ins.stuck.isNotEmpty()) KeyValue("卡点", ins.stuck.entries.sortedByDescending { it.value }.joinToString("、") { "${it.key} ${it.value}" })
                    val best = ins.byPeriod.filter { it.value.second >= 3 }.maxByOrNull { it.value.first.toDouble() / it.value.second }
                    best?.let { Hint("${it.key}的提醒最容易让你开始。重要的事可以优先排在${it.key}。") }
                    if ((ins.stuck["不清楚"] ?: 0) + (ins.stuck["不会做"] ?: 0) >= 3) Hint("“不清楚 / 不会做”出现得多：和 GPT 讨论时让它把第一步拆得更小、完成依据写得更具体。")
                    if ((ins.stuck["去娱乐了"] ?: 0) >= 3) Hint("“去娱乐了”出现得多：开始时打开专注锁。")
                }
            }
        }
    }
}
