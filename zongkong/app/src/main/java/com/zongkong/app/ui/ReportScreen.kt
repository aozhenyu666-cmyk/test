package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.zongkong.app.zk
import com.zongkong.core.ReportKind
import com.zongkong.core.Workspace
import kotlinx.coroutines.launch

/** 随时汇报：报到（重置沉默计时）和随手记（存进信息收集库）。下面是今天的时间线。 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun ReportScreen() {
    val context = LocalContext.current
    val store = context.zk.store
    val actions = context.zk.actions
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()

    var checkin by rememberSaveable { mutableStateOf("") }
    var checkinMsg by remember { mutableStateOf<String?>(null) }
    var capture by rememberSaveable { mutableStateOf("") }
    var tag by rememberSaveable { mutableStateOf("问题") }
    var captureMsg by remember { mutableStateOf<String?>(null) }
    var syncMsg by remember { mutableStateOf<String?>(null) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("汇报", style = MaterialTheme.typography.headlineSmall)

        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Seal("到", MaterialTheme.colorScheme.primary, size = 28.dp)
                Text("报到", style = MaterialTheme.typography.titleMedium)
            }
            Hint(
                if (config.silenceHours > 0) "每 ${config.silenceHours} 小时至少汇报一次，不然进入严管。说清楚现在在做什么、接下来做什么。"
                else "沉默检查已关闭。报到仍会记进日志。",
            )
            OutlinedTextField(
                value = checkin, onValueChange = { checkin = it },
                placeholder = { Text("例：10:20 在做行测言语，做完这套去图书馆") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                scope.launch {
                    val err = actions.checkin(checkin)
                    checkinMsg = err ?: "已报到"
                    if (err == null) checkin = ""
                }
            }) { Text("报到") }
            checkinMsg?.let { Text(it, color = sig.muted, style = MaterialTheme.typography.bodySmall) }
        }

        Panel {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                DeptSeal(com.zongkong.core.Dept.INFO, size = 28.dp)
                Text("随手记 → 信息收集部", style = MaterialTheme.typography.titleMedium)
            }
            Hint("看到的、想到的、卡住的，先收进来。第一行当标题。“问题”类会流到谋划思考部。")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Workspace.CAPTURE_TAGS.forEach { t ->
                    FilterChip(selected = tag == t, onClick = { tag = t }, label = { Text(t) })
                }
            }
            OutlinedTextField(
                value = capture, onValueChange = { capture = it },
                placeholder = { Text("为什么我总是晚上才开始学？") },
                minLines = 2, modifier = Modifier.fillMaxWidth(),
            )
            Button(onClick = {
                val t = capture
                scope.launch {
                    captureMsg = actions.capture(t, tag)
                    if (t.isNotBlank()) capture = ""
                }
            }) { Text("收进来") }
            captureMsg?.let { Text(it, color = sig.muted, style = MaterialTheme.typography.bodySmall) }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("今天的时间线")
            if (config.notion.ready) {
                OutlinedButton(onClick = { scope.launch { syncMsg = actions.sync() } }) { Text("同步 Notion") }
            }
        }
        syncMsg?.let { Hint(it) }
        if (day.outbox.isNotEmpty() && config.notion.ready) Hint("${day.outbox.size} 条日志等待写入 Notion")

        data class Line(val at: Long, val tag: String, val color: Color, val text: String)
        val lines = buildList {
            day.submissions.forEach { s ->
                val g = config.gates.firstOrNull { it.id == s.gateId }
                add(Line(s.at, if (s.verdict.pass) "验收" else "打回", if (s.verdict.pass) sig.free else sig.strict, "${g?.title ?: s.gateId}：${s.verdict.feedback}"))
            }
            day.reports.forEach { r ->
                val label = if (r.kind == ReportKind.CHECKIN) "报到" else "随手记·${r.tag}"
                val tail = if (r.kind == ReportKind.CAPTURE && !r.synced && config.notion.inboxDb.isNotBlank()) "（未同步）" else ""
                add(Line(r.at, label, if (r.kind == ReportKind.CHECKIN) MaterialTheme.colorScheme.primary else sig.info, r.text.take(80) + tail))
            }
            day.emergencies.forEach { e -> add(Line(e.at, "紧急放行", sig.warn, "到 ${clock(e.until)}：${e.reason.take(60)}")) }
            day.offline.forEach { o -> add(Line(o.from, "失联", sig.strict, "到 ${clock(o.to)}，${span(o.to - o.from)}")) }
        }.sortedByDescending { it.at }
        if (lines.isEmpty()) Hint("今天还没有任何汇报。")
        lines.forEach { l ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(clock(l.at), style = MaterialTheme.typography.bodySmall.merge(Mono), color = sig.muted, modifier = Modifier.width(44.dp))
                Column(Modifier.weight(1f)) {
                    Text(l.tag, style = MaterialTheme.typography.labelMedium, color = l.color)
                    Text(l.text, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (day.bounces > 0) Hint("今天被弹回总控 ${day.bounces} 次。")
    }
}
