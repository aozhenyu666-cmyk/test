package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.data.Actions
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.GatePhase
import com.zongkong.core.Policy
import com.zongkong.core.control.Control
import com.zongkong.core.control.DeferralKind
import com.zongkong.core.control.TakeoverConfig
import kotlinx.coroutines.launch
import java.time.Instant

private fun controlTime(at: Long): String = Instant.ofEpochMilli(at).atZone(DayClock.BEIJING).let { "%d/%d %02d:%02d".format(it.monthValue, it.dayOfMonth, it.hour, it.minute) }

@Composable
fun DeferButton(kind: DeferralKind, target: String) {
    val app = LocalContext.current.zk
    val book by app.store.control.collectAsStateWithLifecycle()
    val now = rememberNow()
    val left = Control.passesLeft(book, DayClock.dateKey(now, app.store.zone))
    var confirm by remember { mutableStateOf(false) }
    var reason by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = { confirm = true }, enabled = left > 0) { Text(if (left > 0) "用今日一次缓行 · 延后 30 分钟" else "今日缓行已用完") }
    result?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (confirm) AlertDialog(
        onDismissRequest = { confirm = false }, title = { Text("这项先缓一缓") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("每天共一次，30 分钟后恢复。只延后这项，不算完成，其他限制继续生效；会列入补交清单。")
            OutlinedTextField(reason, { reason = it }, label = { Text("原因（可不写）") })
        } },
        confirmButton = { TextButton(onClick = {
            val error = when (kind) {
                DeferralKind.GATE -> app.actions.control.deferGate(target, reason)
                DeferralKind.HQ -> app.actions.control.deferHq(reason)
                DeferralKind.ACTION -> app.actions.control.deferAction(target, reason)
            }
            result = error ?: "已延后 30 分钟，稍后仍需补交"
            confirm = false
        }) { Text("使用一次缓行") } },
        dismissButton = { TextButton(onClick = { confirm = false }) { Text("继续做") } },
    )
}

@Composable
fun ControlHomeCard(nav: NavHostController) {
    val store = LocalContext.current.zk.store
    val book by store.control.collectAsStateWithLifecycle()
    val cfg by store.config.collectAsStateWithLifecycle()
    val now = rememberNow()
    Panel {
        Text("强督导与补交", style = MaterialTheme.typography.titleSmall)
        Text("强督导${if (cfg.takeover.enabled) "已开启" else "待开启"} · 待补交 ${book.deferrals.count { it.resolvedAt == 0L }} 项 · 今日缓行 ${Control.passesLeft(book, DayClock.dateKey(now, store.zone))} 次")
        TextButton(onClick = { nav.navigate("control") }) { Text("设置范围 / 查看补交") }
    }
}

@Composable
fun ControlScreen(nav: NavHostController) {
    val app = LocalContext.current.zk
    val cfg by app.store.config.collectAsStateWithLifecycle()
    val book by app.store.control.collectAsStateWithLifecycle()
    val work by app.store.work.collectAsStateWithLifecycle()
    val hq by app.store.hq.collectAsStateWithLifecycle()
    val day by app.store.today.collectAsStateWithLifecycle()
    val now = rememberNow()
    val scope = rememberCoroutineScope()
    var enabled by remember(cfg.takeover) { mutableStateOf(cfg.takeover.enabled) }
    var waitMin by remember(cfg.takeover) { mutableStateOf(cfg.takeover.waitMin.toString()) }
    var waitMax by remember(cfg.takeover) { mutableStateOf(cfg.takeover.waitMax.toString()) }
    var durationMin by remember(cfg.takeover) { mutableStateOf(cfg.takeover.durationMin.toString()) }
    var durationMax by remember(cfg.takeover) { mutableStateOf(cfg.takeover.durationMax.toString()) }
    var result by remember { mutableStateOf<String?>(null) }
    var submitting by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf<String?>(null) }
    var text by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize()) {
        BackBar("强督导与一次缓行", { nav.popBackStack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("当前：强督导${if (cfg.takeover.enabled) "已开启" else "未开启"}；今日可缓行 ${Control.passesLeft(book, DayClock.dateKey(now, app.store.zone))} 次")
            Hint("询问后长期不回应，先预告，再进入一轮总控时间。仅无障碍真正在线时执行；否则说明无法接管。每天最多两轮，每轮后冷却一小时。")
            Text("启用无回应强督导", style = MaterialTheme.typography.titleSmall)
            Switch(enabled, { enabled = it })
            OutlinedTextField(waitMin, { waitMin = it }, label = { Text("最少等待（15–120 分钟）") }, singleLine = true)
            OutlinedTextField(waitMax, { waitMax = it }, label = { Text("最多等待（不小于最少等待）") }, singleLine = true)
            OutlinedTextField(durationMin, { durationMin = it }, label = { Text("接管最短（15–30 分钟）") }, singleLine = true)
            OutlinedTextField(durationMax, { durationMax = it }, label = { Text("接管最长（不超过 30 分钟）") }, singleLine = true)
            BigButton("保存强督导设置", {
                val next = TakeoverConfig(enabled, waitMin.toIntOrNull() ?: -1, waitMax.toIntOrNull() ?: -1, durationMin.toIntOrNull() ?: -1, durationMax.toIntOrNull() ?: -1)
                if (!next.valid()) result = "请检查范围：等待 15–120 分钟，接管 15–30 分钟，上限不能小于下限"
                else app.store.updateConfig { c -> Policy.setTakeover(c, next, now).also { result = it.message }.config }
            })
            Hint("开启或收紧立即生效；关闭或放宽沿用 24 小时生效规则。每日一次缓行立即生效，但只作用于选中的任务。")
            book.episode?.let { Text("正在等待回应：${it.reason}；接管时刻 ${controlTime(it.dueAt)}，持续 ${it.minutes} 分钟。") }
            result?.let { Text(it) }
            Text("用一次缓行延后当前任务", style = MaterialTheme.typography.titleMedium)
            hq.active?.let { Text("总控时间"); DeferButton(DeferralKind.HQ, it.id) }
            val gates = remember(cfg, day, book, now) { app.store.status(now).gates }
            gates.filter { !it.done && it.phase != GatePhase.NOT_OPEN }.forEach { g ->
                Panel { Text(g.gate.title); DeferButton(DeferralKind.GATE, g.gate.id) }
            }
            work.actions().filter { it.actionStatus.open }.take(8).forEach { a ->
                Panel { Text(a.title); DeferButton(DeferralKind.ACTION, a.key) }
            }
            Text("缓行与补交记录", style = MaterialTheme.typography.titleMedium)
            if (book.deferrals.isEmpty()) Hint("还没有使用缓行。使用后即使跨日也会保留未补交项。")
            book.deferrals.sortedByDescending { it.at }.forEach { d ->
                Panel {
                    Text("${d.date} · ${d.title}", style = MaterialTheme.typography.titleSmall)
                    Text(when {
                        d.resolvedAt > 0 -> "已补交 · ${controlTime(d.resolvedAt)}"
                        d.until > now -> "缓行至 ${controlTime(d.until)}，未完成"
                        else -> "缓行已到期，待补交"
                    })
                    Text(d.reason, style = MaterialTheme.typography.bodySmall)
                    if (d.resolvedAt == 0L) {
                        when (d.kind) {
                            DeferralKind.GATE -> TextButton(onClick = { selected = d.id; text = "" }) { Text("补交原日期的关卡") }
                            DeferralKind.HQ -> TextButton(onClick = { nav.navigate("hq") }) { Text("回总控时间继续 / 重新进入") }
                            DeferralKind.ACTION -> TextButton(onClick = { nav.navigate("action/${d.target}") }) { Text("回到这一步行动") }
                        }
                        if (selected == d.id) {
                            OutlinedTextField(text, { text = it }, label = { Text("补交内容（原验收标准仍生效）") }, minLines = 4, modifier = Modifier.fillMaxWidth())
                            TextButton(enabled = !submitting, onClick = {
                                submitting = true
                                scope.launch {
                                    result = when (val r = app.actions.submitDeferred(d.id, text)) {
                                        is Actions.SubmitResult.Judged -> if (r.verdict.pass) "已补交原日期任务，今天同名关卡不受影响" else r.verdict.feedback
                                        is Actions.SubmitResult.Unavailable -> r.reason
                                        is Actions.SubmitResult.Refused -> r.message
                                    }
                                    submitting = false
                                }
                            }) { Text(if (submitting) "验收中…" else "提交验收") }
                        }
                    }
                }
            }
        }
    }
}
