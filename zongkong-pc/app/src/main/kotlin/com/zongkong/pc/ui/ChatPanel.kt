package com.zongkong.pc.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.dp
import com.zongkong.core.agent.Agent
import com.zongkong.core.agent.ChatMsg
import com.zongkong.pc.Confirm
import com.zongkong.pc.Controller

/**
 * 和总控说话。主窗口、锁屏、拦截页都用这一块：申请放行、说明情况、问它为什么。
 * [starter]：输入框里预先写好的话（例如“我想用 B站 做……”）。
 */
@Composable
fun ChatPanel(c: Controller, modifier: Modifier = Modifier, starter: String = "", compact: Boolean = false) {
    val sig = LocalSig.current
    val log by c.chat.collectAsState()
    val busy by c.busy.collectAsState()
    val config by c.config.collectAsState()
    val confirm by c.confirm.collectAsState()
    var input by remember(starter) { mutableStateOf(starter) }
    val shown = log.filter { !it.hidden && !(it.role == ChatMsg.ASSISTANT && it.content.isBlank() && it.calls.isEmpty()) }.let { if (compact) it.takeLast(8) else it }
    val list = rememberLazyListState()
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.animateScrollToItem(shown.size - 1) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (!config.ai.ready) {
            Panel(accent = sig.warn) {
                Text("还没接 AI", style = MaterialTheme.typography.titleSmall, color = sig.warn)
                Hint("在“连接”里填 AI 接口，或者粘贴手机上的配对码（会带上手机那份 AI 接口）。没接 AI 时，额度、会话、夜里锁机照样管，只是没法申请。")
            }
        }
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown) { m -> Bubble(m) { c.send(it) } }
            if (busy) item { Hint("总控在想…") }
        }
        confirm?.let { ConfirmCard(it, c) }
        // 锁屏、拦截页上：打不了字也能申请
        if (compact && config.ai.ready && !busy) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf("我要学习 30 分钟", "查资料 15 分钟", "有急事要处理", "不用了").forEach { q -> Chip(q, sig.ai) { c.send(q) } }
            }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input, { input = it },
                placeholder = { Text(if (compact) "说清楚：做什么、多久、用完回去做什么" else "跟总控说…（Enter 发送，Shift+Enter 换行）") },
                modifier = Modifier.weight(1f).onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Enter && !e.isShiftPressed) {
                        if (input.isNotBlank() && !busy) { c.send(input); input = "" }
                        true
                    } else false
                },
                maxLines = 4,
            )
            Button(onClick = { c.send(input); input = "" }, enabled = input.isNotBlank() && !busy && config.ai.ready, colors = ButtonDefaults.buttonColors(containerColor = sig.ai)) { Text("发送") }
        }
    }
}

@Composable
private fun Bubble(m: ChatMsg, onOption: (String) -> Unit) {
    val sig = LocalSig.current
    when (m.role) {
        ChatMsg.USER -> Row(Modifier.fillMaxWidth()) {
            Box(Modifier.weight(1f))
            Box(Modifier.widthIn(max = 520.dp).background(sig.plan.copy(alpha = 0.1f), RoundedCornerShape(14.dp)).padding(12.dp)) {
                Text(m.content, style = MaterialTheme.typography.bodyMedium)
            }
        }
        ChatMsg.TOOL -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Dot(if (m.ok) sig.free else sig.strict)
            Text(m.summary.ifBlank { m.content.take(60) }, style = MaterialTheme.typography.labelMedium, color = sig.muted)
        }
        else -> {
            if (m.content.isBlank()) return
            val (body, options) = Agent.options(m.content)
            Column(Modifier.widthIn(max = 620.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(body, style = MaterialTheme.typography.bodyMedium, color = if (m.error) sig.strict else sig.ink)
                if (options.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { options.forEach { o -> Chip(o, sig.ai) { onOption(o) } } }
            }
        }
    }
}

/** 总控要执行一条会改东西的命令：你点了才执行。 */
@Composable
fun ConfirmCard(x: Confirm, c: Controller) {
    val sig = LocalSig.current
    Panel(accent = sig.warn) {
        Text("总控想在这台电脑上执行：", style = MaterialTheme.typography.titleSmall)
        Box(Modifier.fillMaxWidth().background(sig.night, RoundedCornerShape(10.dp)).padding(12.dp)) {
            Text(x.command, style = MaterialTheme.typography.bodySmall.merge(Mono), color = androidx.compose.ui.graphics.Color(0xFFE8E6E1))
        }
        if (x.why.isNotBlank()) Hint("为什么：${x.why}")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { c.answerConfirm(true) }, colors = ButtonDefaults.buttonColors(containerColor = sig.warn)) { Text("执行") }
            OutlinedButton(onClick = { c.answerConfirm(false) }) { Text("不执行") }
        }
    }
}

@Composable
fun FullChat(c: Controller) {
    Box(Modifier.fillMaxSize().padding(20.dp)) { ChatPanel(c, Modifier.fillMaxSize()) }
}
