package com.moling.app.ui

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.core.talk.Outbrain
import com.moling.core.talk.OutbrainEndpoint
import com.moling.core.talk.OutbrainSession
import com.moling.core.talk.OutbrainStatus
import com.moling.core.talk.OutbrainTransport
import com.moling.core.talk.OutbrainRoute
import com.moling.core.talk.OutbrainRouter
import com.moling.core.talk.Packs
import com.moling.core.talk.ReceiveOutcome
import com.moling.core.workspace.PortError
import com.moling.core.workspace.PortResult
import com.moling.core.workspace.WorkspaceArtifact
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/*
 * 第十二批最小 UI：对话页 / 案板 / 胶囊共用的「外脑」一条。
 * 只读 BrainHost.outbrainState 渲染，动作全部经 BrainHost；不自动采用、不自动交总控、不自动发。
 */
private val obScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

private fun statusText(s: OutbrainSession): String = when (s.status) {
    OutbrainStatus.PREPARED -> "准备好了，还没送出"
    OutbrainStatus.OPENED -> "已打开外脑"
    OutbrainStatus.WAITING_RESULT -> if (s.closedAt > 0) "退场了，还在等结果" else "等结果回来"
    OutbrainStatus.RESULT_RECEIVED -> "结论回来了"
    OutbrainStatus.REVIEWED -> "看过了"
    OutbrainStatus.CANCELLED -> "已取消"
    OutbrainStatus.EXPIRED -> "到点没回来"
}

@Composable
fun OutbrainCard(focusId: String? = null, allowSend: Boolean = false, onOpenBench: () -> Unit = {}) {
    val context = LocalContext.current
    val clip = LocalClipboardManager.current
    val st by BrainHost.outbrainState.collectAsState()
    val ws by BrainHost.workbench.collectAsState()
    val state = st
    val active = Outbrain.active(state)
    val collect = Outbrain.needCollect(state).lastOrNull()
    val landed = state.sessions.lastOrNull { it.status == OutbrainStatus.RESULT_RECEIVED && !it.artifactId.isNullOrBlank() }
    var sendOpen by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<OutbrainSession?>(null) }
    var collectOpen by remember { mutableStateOf(false) }
    var resultOpen by remember { mutableStateOf<OutbrainSession?>(null) }

    fun toast(msg: String) = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()

    fun prepared(r: PortResult<OutbrainSession>) {
        when (r) {
            is PortResult.Ok -> preview = r.value
            is PortResult.Failed -> toast(r.error.message)
        }
    }

    fun openAndCopy(session: OutbrainSession) {
        val text = BrainHost.outbrainPackText(session.id)
        if (text.isBlank()) {
            toast("进场包是空的")
            return
        }
        clip.setText(AnnotatedString(text))
        obScope.launch {
            BrainHost.outbrainOpen(session.id)
            if (session.transport == OutbrainTransport.API) {
                toast(BrainHost.outbrainAskApi(session.id))
            } else {
                val launch = runCatching { context.packageManager.getLaunchIntentForPackage(session.actor.pkg) }.getOrNull()
                if (launch != null) {
                    runCatching { context.startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                        .onFailure { toast("外脑没打开，进场包已经复制") }
                } else {
                    toast("进场包已复制（没装 " + session.actor.label + "，粘到你常用的外脑里）")
                }
                toast("送出去了，等结论回来收线")
            }
            preview = null
        }
    }

    if (active == null && collect == null && landed == null && !allowSend) return

    Surface(
        shape = MaterialTheme.shapes.medium,
        border = BorderStroke(0.7.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.25f)),
        color = MaterialTheme.colorScheme.surface,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("外脑", style = MaterialTheme.typography.labelLarge)
                Text(
                    "  " + (active?.let { it.actor.label + " · " + statusText(it) } ?: if (collect != null) "还没收线" else "结论回来了"),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val shown = active ?: collect ?: landed
            if (shown != null) {
                Text(
                    (shown.pack.question.take(30)) + " · 材料 " + shown.pack.materials.size + " 条",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                shown.timeline.lastOrNull()?.let { Text(it.note.ifBlank { statusText(shown) }, style = MaterialTheme.typography.bodySmall) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (active != null) {
                    TextButton(onClick = {
                        clip.setText(AnnotatedString(BrainHost.outbrainPackText(active.id)))
                        toast("进场包已复制")
                    }) { Text("复制进场包") }
                    TextButton(onClick = { openAndCopy(active) }) { Text(if (active.transport == OutbrainTransport.API) "用 API 问" else "送到外脑") }
                    TextButton(onClick = { collectOpen = true }) { Text("收线") }
                }
                if (collect != null) {
                    TextButton(onClick = { collectOpen = true }) { Text("粘贴一次") }
                    TextButton(onClick = {
                        obScope.launch {
                            toast("先留着，等你想说再收")
                            BrainHost.outbrainSweep(System.currentTimeMillis())
                        }
                    }) { Text("稍后") }
                }
                if (landed != null && active == null) {
                    TextButton(onClick = { resultOpen = landed }) { Text("看看结论") }
                    TextButton(onClick = onOpenBench) { Text("看案板") }
                }
                if (allowSend && active == null) {
                    TextButton(onClick = { sendOpen = true }) { Text("发到外脑") }
                }
            }
        }
    }

    if (sendOpen) {
        val focus = (focusId?.let { ws.focus(it) } ?: ws.active())
        if (focus == null) {
            toast("先在案板上开一件事")
            sendOpen = false
            return
        }
        val materials = ws.materialsOf(focus)
        var excluded by remember(focus.id) { mutableStateOf(materials.filter { Packs.isSensitive(it) }.map { it.id }.toSet()) }
        var endpoint by remember { mutableStateOf(BrainHost.outbrainEndpoints().firstOrNull { it.configured }?.id) }
        SendOutbrainDialog(
            endpoints = BrainHost.outbrainEndpoints(),
            endpoint = endpoint,
            onEndpoint = { endpoint = it },
            materials = materials,
            excluded = excluded,
            onToggle = { id -> excluded = if (id in excluded) excluded - id else excluded + id },
            onClose = { sendOpen = false },
            onPrepare = {
                val ep = endpoint ?: return@SendOutbrainDialog
                sendOpen = false
                obScope.launch {
                    prepared(BrainHost.outbrainPrepare(ep, focus.id, excluded))
                }
            },
        )
    }

    preview?.let { s ->
        PreviewDialog(
            session = s,
            text = BrainHost.outbrainPackText(s.id),
            onCopy = {
                clip.setText(AnnotatedString(BrainHost.outbrainPackText(s.id)))
                toast("进场包已复制")
            },
            onSend = { openAndCopy(s) },
            onClose = { preview = null },
        )
    }

    if (collectOpen) {
        CollectDialog(
            sessions = Outbrain.waiting(state),
            onClose = { collectOpen = false },
            onSubmit = { text, sid ->
                collectOpen = false
                obScope.launch {
                    when (val r = BrainHost.outbrainReceive(text, sid, source = "paste")) {
                        is PortResult.Ok -> {
                            val (outcome, artifact) = r.value
                            toast(
                                when {
                                    outcome.duplicate -> "这段刚才已经收过了"
                                    artifact != null -> "放上案板材料了（未采用）"
                                    outcome.history -> "会话已经结束，这条只记进历史"
                                    else -> "收下了"
                                },
                            )
                        }
                        is PortResult.Failed -> toast(r.error.message)
                    }
                }
            },
        )
    }

    resultOpen?.let { s ->
        ResultDialog(
            session = s,
            results = Outbrain.finalResults(state, s.id),
            onKeep = {
                obScope.launch { BrainHost.outbrainReviewed(s.id) }
                resultOpen = null
            },
            onBench = {
                obScope.launch { BrainHost.outbrainReviewed(s.id) }
                resultOpen = null
                onOpenBench()
            },
        )
    }
}

@Composable
private fun SendOutbrainDialog(
    endpoints: List<OutbrainEndpoint>,
    endpoint: String?,
    onEndpoint: (String) -> Unit,
    materials: List<com.moling.core.workspace.Material>,
    excluded: Set<String>,
    onToggle: (String) -> Unit,
    onClose: () -> Unit,
    onPrepare: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("发到外脑") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("走哪条路", style = MaterialTheme.typography.labelLarge)
                endpoints.forEach { ep ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(enabled = ep.configured, value = endpoint == ep.id, onValueChange = { onEndpoint(ep.id) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = endpoint == ep.id, enabled = ep.configured, onClick = { onEndpoint(ep.id) })
                        Text(
                            ep.label + if (ep.configured) "" else "（未配置）",
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (ep.configured) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text("去掉不想给的材料（敏感材料默认不去）", style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 6.dp))
                if (materials.isEmpty()) Text("这件事还没有材料", style = MaterialTheme.typography.bodySmall)
                materials.forEach { m ->
                    Row(
                        Modifier.fillMaxWidth().toggleable(value = m.id !in excluded, onValueChange = { onToggle(m.id) })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = m.id !in excluded, onCheckedChange = { onToggle(m.id) })
                        Column(Modifier.padding(start = 4.dp)) {
                            Text(m.title.take(24), style = MaterialTheme.typography.bodySmall)
                            Text(
                                (if (Packs.isSensitive(m)) "敏感：默认不放 · " else "") + m.text.take(40),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Text("只发摘要，不把整库给外部服务。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(enabled = endpoint != null, onClick = onPrepare) { Text("生成进场包") } },
        dismissButton = { TextButton(onClick = onClose) { Text("算了") } },
    )
}

@Composable
private fun PreviewDialog(
    session: OutbrainSession,
    text: String,
    onCopy: () -> Unit,
    onSend: () -> Unit,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("进场包（发给 " + session.actor.label + "）") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("先看一眼；不想发的材料回上一步去掉。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text, style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (session.transport == OutbrainTransport.API) {
                    onSend()
                } else {
                    onCopy()
                    onSend()
                }
            }) {
                Text(if (session.transport == OutbrainTransport.API) "用 API 问" else "复制并送到外脑")
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onCopy) { Text("只复制") }
                TextButton(onClick = onClose) { Text("算了") }
            }
        },
    )
}

@Composable
private fun CollectDialog(
    sessions: List<OutbrainSession>,
    onClose: () -> Unit,
    onSubmit: (String, String?) -> Unit,
) {
    var text by remember { mutableStateOf("") }
    // 只在一个会话在等时默认选它；两个以上默认不选，不选不能提交。
    var pick by remember { mutableStateOf(sessions.singleOrNull()?.id) }
    val tagged = Outbrain.taggedSessionId(text)
    val canSubmit = text.isNotBlank() && (sessions.size <= 1 || tagged != null || pick != null)
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("收线：把结论带回来") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (sessions.size > 1) {
                    Text(
                        if (tagged != null) "文本里有标记：按 [[外脑:" + tagged + "]] 认，手选不算" else "有两个以上会话在等：这条属于哪一个？（默认不选，不按时间猜）",
                        style = MaterialTheme.typography.labelSmall,
                    )
                    sessions.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().toggleable(value = pick == s.id, onValueChange = { pick = s.id }).padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = pick == s.id, onClick = { pick = s.id })
                            Text(s.id + " · " + s.pack.question.take(16), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("外脑怎么说（整段粘进来）") },
                    minLines = 4,
                )
                Text(
                    "只作为材料放上案板，未采用；不自动交总控。带 [[外脑:ob-…]] 标记也能自动对上。",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(enabled = canSubmit, onClick = { onSubmit(text, pick) }) { Text("接回案板") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("稍后") } },
    )
}

@Composable
private fun ResultDialog(
    session: OutbrainSession,
    results: List<com.moling.core.talk.ActorResult>,
    onKeep: () -> Unit,
    onBench: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onKeep,
        title = { Text("外脑带回来的（原文）") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("已经放上案板材料；还没采用，也没交总控。", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                results.forEach { r -> Text(r.text, style = MaterialTheme.typography.bodySmall) }
                if (results.isEmpty()) Text("没有最终结论（只收到上屏一半）")
            }
        },
        confirmButton = { TextButton(onClick = onBench) { Text("看案板") } },
        dismissButton = { TextButton(onClick = onKeep) { Text("知道了") } },
    )
}
