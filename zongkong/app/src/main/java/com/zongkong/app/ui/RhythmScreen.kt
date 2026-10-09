package com.zongkong.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.GatePhase
import com.zongkong.core.GateStatus
import com.zongkong.core.Reason
import kotlinx.coroutines.launch

/** 节律：每日关卡、严管/放行、娱乐额度、紧急放行。首页只放一条摘要，细节在这里。 */
@Composable
fun RhythmScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    val now = rememberNow()
    val status = remember(config, day, now) { store.status(now) }
    val sig = LocalSignals.current
    var resumeKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { resumeKey++ }
    val guardOn = remember(resumeKey, now) { PermissionStatus.accessibility(context) }
    var showEmergency by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
    BackBar("每日节律", { nav.popBackStack() })
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // ---------- 状态 ----------
        val color = when {
            status.paused -> sig.muted
            status.emergencyUntil != null -> sig.warn
            status.strict -> sig.strict
            else -> sig.free
        }
        Panel(accent = color) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Seal(status.headline.take(1), color, size = 56.dp, filled = true)
                Column(Modifier.weight(1f)) {
                    Text(status.headline, style = MaterialTheme.typography.displaySmall, color = color)
                    Text(
                        "${status.date} · 已验收 ${status.doneCount}/${status.gates.size}",
                        style = MaterialTheme.typography.bodySmall.merge(Mono), color = sig.muted,
                    )
                }
            }
            if (status.reasons.isEmpty()) {
                Text(com.zongkong.core.Engine.summary(status), style = MaterialTheme.typography.bodyMedium)
            } else {
                status.reasons.forEach { r ->
                    Row(verticalAlignment = Alignment.Top) {
                        Text("■ ", color = color, style = MaterialTheme.typography.bodyMedium)
                        Text(r.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            status.emergencyUntil?.let { Text("紧急放行到 ${clock(it)}", color = sig.warn, style = MaterialTheme.typography.bodyMedium.merge(Mono)) }
            if (status.paused) Text("休假到 ${clock(config.pausedUntil)}（${java.time.Instant.ofEpochMilli(config.pausedUntil).atZone(store.zone).toLocalDate()}）", color = sig.muted)
            Meters(status.playMin, status.quotaMin, status.silenceDueAt, now)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { nav.navigate("capture") }, modifier = Modifier.weight(1f)) { Text("去报到") }
                if (status.strict && status.emergencyLeft > 0) {
                    OutlinedButton(onClick = { showEmergency = true }, modifier = Modifier.weight(1f)) { Text("紧急放行") }
                }
            }
        }

        // ---------- 掉线、未设置 ----------
        if (!guardOn) {
            Panel(accent = sig.strict, onClick = { nav.navigate("settings/perm") }) {
                Text("总控没有在盯：无障碍服务没开", style = MaterialTheme.typography.titleSmall, color = sig.strict)
                Hint("不开它，严管只是一句话。点这里去开启，顺便把 vivo 的后台设置做完。")
            }
        }
        val todo = buildList {
            if (config.blocked.isEmpty()) add("选拦截名单（抖音、B 站……）" to "settings/block")
            if (!config.ai.ready) add("配置 AI 接口（AI 验收要用）" to "settings/ai")
            if (!config.notion.ready) add("连接 Notion（可选：查账、写日志）" to "settings/notion")
        }
        if (todo.isNotEmpty()) {
            Panel {
                Text("还差几步", style = MaterialTheme.typography.titleSmall)
                todo.forEach { (t, r) ->
                    TextButton(onClick = { nav.navigate(r) }) { Text("→ $t") }
                }
            }
        }
        if (day.offline.isNotEmpty()) {
            Hint("今天失联 ${day.offline.sumOf { (it.to - it.from) / 60_000 }} 分钟（总控没在运行），会写进日终验收。")
        }

        // ---------- 今天的关卡 ----------
        SectionLabel("今天的关卡")
        status.gates.forEach { g -> GateRow(g, now) { nav.navigate("gate/${g.gate.id}") } }
        if (status.gates.isEmpty()) Hint("今天没有关卡。到 设置 → 关卡 添加。")

        if (config.pending.isNotEmpty()) {
            Panel(onClick = { nav.navigate("settings/pending") }) {
                Text("${config.pending.size} 项放宽改动等待生效", style = MaterialTheme.typography.titleSmall)
                Hint("最近的一项 ${clock(config.pending.minOf { it.effectiveAt })} 生效，点开可以撤回。")
            }
        }
        TextButton(onClick = { nav.navigate("history") }) { Text("历史记录 →") }
    }
    }

    if (showEmergency) EmergencyDialog(config.emergencyMinutes, status.emergencyLeft) { showEmergency = false }
}

@Composable
private fun Meters(playMin: Int, quotaMin: Int, silenceDueAt: Long?, now: Long) {
    val sig = LocalSignals.current
    if (quotaMin > 0) {
        val frac = (playMin.toFloat() / quotaMin).coerceIn(0f, 1f)
        val c = when {
            frac >= 1f -> sig.strict
            frac >= 0.8f -> sig.warn
            else -> sig.free
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KeyValue("娱乐", "$playMin / $quotaMin 分钟", c)
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(sig.line),
            ) {
                Box(Modifier.fillMaxWidth(frac).fillMaxHeight().background(c))
            }
        }
    } else {
        KeyValue("娱乐", "$playMin 分钟（不限）")
    }
    silenceDueAt?.let { due ->
        val left = due - now
        KeyValue(
            "下次报到",
            if (left > 0) "${span(left)}内（${clock(due)} 前）" else "现在",
            if (left <= 15 * 60_000) sig.warn else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
fun GateRow(g: GateStatus, now: Long, onClick: () -> Unit) {
    val sig = LocalSignals.current
    val pc = phaseColor(g.phase)
    Panel(accent = if (g.blocking) sig.strict else null, onClick = onClick) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            DeptSeal(g.gate.dept, filled = g.done)
            Column(Modifier.weight(1f)) {
                Text(g.gate.title, style = MaterialTheme.typography.titleMedium)
                Text(
                    "${g.gate.dept.label} · ${DayClock.hhmm(g.gate.openAt)}–${DayClock.hhmm(g.gate.deadline)} · ${g.gate.verify.label}",
                    style = MaterialTheme.typography.bodySmall, color = sig.muted,
                )
            }
            Tag(g.phase.label, pc)
        }
        val line = when (g.phase) {
            GatePhase.NOT_OPEN -> "${clock(g.openAt)} 开放"
            GatePhase.OPEN, GatePhase.DUE_SOON -> "还有 ${span(g.deadlineAt - now)}" + if (g.blocking) " · 交了才放行" else ""
            GatePhase.OVERDUE -> "超时 ${span(now - g.deadlineAt)} · 正在拦截"
            GatePhase.DONE, GatePhase.DONE_LATE -> g.passed?.let { "${clock(it.at)} 验收 · ${it.verdict.by}" + (it.verdict.score?.let { s -> " $s 分" } ?: "") } ?: ""
        }
        Text(line, style = MaterialTheme.typography.bodySmall.merge(Mono), color = pc)
        if (!g.done && g.attempts > 0) Hint("交过 ${g.attempts} 次没通过")
    }
}

@Composable
fun EmergencyDialog(minutes: Int, left: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    var reason by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("紧急放行 $minutes 分钟") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Hint("今天还剩 $left 次。写清楚为什么必须现在用（至少 20 字），会写进日终验收。")
                OutlinedTextField(value = reason, onValueChange = { reason = it }, minLines = 3, modifier = Modifier.fillMaxWidth())
                msg?.let { Text(it, color = LocalSignals.current.warn, fontWeight = FontWeight.Bold) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val m = context.zk.actions.emergency(reason)
                msg = m
                if (m.startsWith("已放行")) onDismiss()
            }) { Text("申请") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("算了") } },
    )
}
