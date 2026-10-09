package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.data.Actions
import com.zongkong.app.zk
import com.zongkong.core.DayClock
import com.zongkong.core.Defaults
import com.zongkong.core.Dept
import com.zongkong.core.GatePhase
import com.zongkong.core.TextCheck
import com.zongkong.core.Verdict
import com.zongkong.core.VerifyMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate

/** 交一道关卡：看要求、写汇报、提交验收、看反馈。 */
@Composable
fun GateScreen(gateId: String, nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    val day by store.today.collectAsStateWithLifecycle()
    val now = rememberNow()
    val status = remember(config, day, now) { store.status(now) }
    val gs = status.gates.firstOrNull { it.gate.id == gateId }
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()

    var text by rememberSaveable { mutableStateOf(store.draft(gateId) ?: "") }
    var busy by remember { mutableStateOf(false) }
    var verdict by remember { mutableStateOf<Verdict?>(null) }
    var refused by remember { mutableStateOf<String?>(null) }
    var unavailable by remember { mutableStateOf<Actions.SubmitResult.Unavailable?>(null) }

    // 草稿自动保存：退出页面、切去别的应用都不会丢
    LaunchedEffect(text) {
        delay(600)
        if (text.isNotBlank()) store.saveDraft(gateId, text)
    }

    fun handle(r: Actions.SubmitResult) {
        when (r) {
            is Actions.SubmitResult.Judged -> verdict = r.verdict
            is Actions.SubmitResult.Refused -> refused = r.message
            is Actions.SubmitResult.Unavailable -> unavailable = r
        }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar(gs?.gate?.title ?: "关卡", { nav.popBackStack() })
        if (gs == null) {
            Hint("今天没有这道关卡。", Modifier.padding(16.dp))
            return@Column
        }
        val gate = gs.gate
        val date = LocalDate.parse(status.date)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                DeptSeal(gate.dept, size = 44.dp)
                Column(Modifier.weight(1f)) {
                    Text(gate.dept.label, style = MaterialTheme.typography.labelLarge, color = sig.dept(gate.dept))
                    Text(
                        "${DayClock.hhmm(gate.openAt)}–${DayClock.hhmm(gate.deadline)} · ${gate.block.label} · ${gate.verify.label}",
                        style = MaterialTheme.typography.bodySmall, color = sig.muted,
                    )
                }
                Tag(gs.phase.label, phaseColor(gs.phase))
            }
            Text(gate.instruction, style = MaterialTheme.typography.bodyLarge)

            if (gate.dept == Dept.THINK) {
                val m = Defaults.methodOf(date)
                Panel(accent = sig.think) {
                    Text("今天的方法卡 · ${m.name}", style = MaterialTheme.typography.titleSmall, color = sig.think)
                    Text(m.how, style = MaterialTheme.typography.bodyMedium)
                }
            }
            if (gate.rubric.isNotBlank() && gate.verify != VerifyMode.TEXT && gate.verify != VerifyMode.NOTION) {
                Panel {
                    Text("验收标准", style = MaterialTheme.typography.titleSmall)
                    gate.rubric.lines().filter { it.isNotBlank() }.forEach { Text("· ${it.trim()}", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            if (gate.verify == VerifyMode.NOTION || gate.verify == VerifyMode.NOTION_AI) {
                Hint("验收时会去 Notion 数据库里数今天新建了几条（至少 ${gate.notionMinPages} 条）。" + if (gate.verify == VerifyMode.NOTION) "这里的文字可以不写。" else "")
            }

            if (gs.done) {
                gs.passed?.let { VerdictPanel(it.verdict) }
                Panel {
                    Text("交上去的内容", style = MaterialTheme.typography.titleSmall)
                    Text(gs.passed?.text.orEmpty().ifBlank { "（没有文字）" }, style = MaterialTheme.typography.bodyMedium)
                }
                return@Column
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (gate.template.isNotBlank()) {
                    OutlinedButton(onClick = {
                        val t = Defaults.fillTemplate(gate.template, date)
                        text = if (text.isBlank()) t else text + "\n" + t
                    }) { Text("填入格式") }
                }
                if (gate.launch.isNotBlank()) {
                    OutlinedButton(onClick = {
                        if (!launchTarget(context, gate.launch)) refused = "打不开「${gate.launch}」，检查一下包名或链接"
                    }) { Text("去做") }
                }
            }
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                minLines = 8,
                placeholder = { Text("在这里写汇报……") },
                supportingText = {
                    val n = TextCheck.effectiveChars(text, Defaults.fillTemplate(gate.template, date))
                    Text("有效字数 $n / ${gate.minChars}（模板标签不算）", style = MaterialTheme.typography.bodySmall.merge(Mono))
                },
            )
            Button(
                onClick = {
                    busy = true
                    verdict = null
                    scope.launch {
                        handle(context.zk.actions.submit(gateId, text))
                        busy = false
                    }
                },
                enabled = !busy && gs.phase != GatePhase.NOT_OPEN,
                colors = if (gs.blocking) ButtonDefaults.buttonColors(containerColor = sig.strict) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (busy) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                    Text("  验收中…")
                } else {
                    Text(if (gs.phase == GatePhase.NOT_OPEN) "${DayClock.hhmm(gate.openAt)} 开放" else "提交验收")
                }
            }
            verdict?.let { VerdictPanel(it) }
            val failed = day.submissions.filter { it.gateId == gateId && !it.verdict.pass }
            if (failed.isNotEmpty()) {
                SectionLabel("之前没通过的 ${failed.size} 次")
                failed.asReversed().take(3).forEach { s ->
                    Hint("${clock(s.at)} · ${s.verdict.by}：${s.verdict.feedback}")
                }
            }
        }
    }

    refused?.let { m ->
        AlertDialog(
            onDismissRequest = { refused = null },
            confirmButton = { TextButton(onClick = { refused = null }) { Text("知道了") } },
            text = { Text(m) },
        )
    }
    unavailable?.let { u ->
        AlertDialog(
            onDismissRequest = { unavailable = null },
            title = { Text("暂时没法验收") },
            text = {
                Text(
                    u.reason + "\n\n" + if (u.degradedLeft > 0) {
                        "可以用降级验收：去掉模板后字数翻倍就算过。今天还能用 ${u.degradedLeft} 次，会标记为“降级”。"
                    } else {
                        "今天的降级验收用完了。检查网络或设置后再试。"
                    },
                )
            },
            confirmButton = {
                if (u.degradedLeft > 0) {
                    TextButton(onClick = {
                        unavailable = null
                        busy = true
                        scope.launch {
                            handle(context.zk.actions.submitDegraded(gateId, text, u.reason.take(40)))
                            busy = false
                        }
                    }) { Text("降级验收") }
                }
            },
            dismissButton = { TextButton(onClick = { unavailable = null }) { Text("稍后再试") } },
        )
    }
}

@Composable
fun VerdictPanel(v: Verdict) {
    val sig = LocalSignals.current
    val c = if (v.pass) sig.free else sig.strict
    Panel(accent = c) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Seal(if (v.pass) "过" else "退", c, size = 40.dp, filled = true)
            Column(Modifier.weight(1f)) {
                Text(if (v.pass) "验收通过" else "打回重写", style = MaterialTheme.typography.titleMedium, color = c)
                Text(v.by + (v.score?.let { " · $it 分" } ?: "") + if (v.degraded) " · 降级" else "", style = MaterialTheme.typography.bodySmall, color = sig.muted)
            }
        }
        Text(v.feedback, style = MaterialTheme.typography.bodyMedium)
        if (v.missing.isNotEmpty()) {
            Text("没做到的：", style = MaterialTheme.typography.labelLarge, color = sig.muted)
            v.missing.forEach { Text("· $it", style = MaterialTheme.typography.bodyMedium) }
        }
    }
}
