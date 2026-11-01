package com.zongkong.app.ui

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.buddy.Buddy
import com.zongkong.core.buddy.BuddyMsg
import kotlinx.coroutines.launch

/**
 * 伙伴页：和伙伴的聊天（和总控的对话分开），外加它的设置——叫什么、多常来找你、几点别打扰、它记得你什么。
 * 它平时主要从通知里找你；这一页是翻聊天记录、改设置的地方。
 */
@Composable
fun BuddyScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val book by app.buddy.book.collectAsStateWithLifecycle()
    val busy by app.buddy.busy.collectAsStateWithLifecycle()
    val cfg = book.config
    var input by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    val list = rememberLazyListState()
    val shown = book.messages
    LaunchedEffect(shown.size) { if (shown.isNotEmpty()) list.animateScrollToItem(shown.size - 1) }

    Column(Modifier.fillMaxSize()) {
        BackBar(cfg.name, { nav.popBackStack() }) {
            TextButton(onClick = { settings = !settings }, modifier = Modifier.testTag("buddy-settings")) {
                Text(if (settings) "聊天" else "设置", color = sig.ai)
            }
        }
        if (settings) {
            BuddySettings(nav)
            return@Column
        }
        // 它现在为什么安静（让你知道它没坏）
        val quiet = remember(book, busy) { runCatching { Buddy.blocked(book, app.buddy.signals()) }.getOrNull() }
        Text(
            when {
                busy -> "在想…"
                !app.store.config.value.ai.ready -> "还没配置 AI 接口：设置 → AI 接口（和总控共用）"
                !cfg.enabled -> "主动找你：关着"
                quiet != null -> "现在不会主动找你：$quiet"
                else -> "会主动找你：每小时最多 ${cfg.perHour} 次，你不回会越来越少打扰"
            },
            style = MaterialTheme.typography.labelMedium, color = sig.muted,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
        )
        LazyColumn(
            state = list,
            modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 16.dp).testTag("buddy-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (shown.isEmpty()) {
                item {
                    Hint(
                        "${cfg.name}是你的伙伴：住在总控里，但不是总控——不管规则、不锁手机、不打分。" +
                            "它会隔一会儿从通知里找你说两句（刚解锁、刷得有点久、一手有题没接住、早上、睡前），你在通知里直接回就行。",
                        Modifier.padding(vertical = 12.dp),
                    )
                }
            }
            items(shown) { m -> Bubble(m) }
        }
        note?.let { Text(it, color = sig.warn, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(horizontal = 20.dp)) }
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                input, { input = it },
                placeholder = { Text("跟${cfg.name}说…") },
                modifier = Modifier.weight(1f).testTag("buddy-input"),
                maxLines = 4,
            )
            Button(
                onClick = { app.buddy.send(input); input = "" },
                enabled = input.isNotBlank() && !busy,
                modifier = Modifier.testTag("buddy-send"),
            ) { Text("发") }
        }
        // 没聊过、或者上一句是它说的：可以叫它再说一句
        if (shown.isEmpty() || shown.last().role != BuddyMsg.USER) TextButton(
            onClick = {
                scope.launch {
                    val said = runCatching { app.buddy.nudge(app.buddy.signals()) }.getOrNull()
                    note = if (said == null) "它没说出来：" + app.buddy.lastWhyQuiet.ifBlank { "看看 AI 接口" } else null
                }
            },
            enabled = !busy,
            modifier = Modifier.padding(start = 12.dp, bottom = 8.dp).testTag("buddy-nudge"),
        ) { Text("让${cfg.name}先说一句") }
    }
}

@Composable
private fun Bubble(m: BuddyMsg) {
    val sig = LocalSignals.current
    val mine = m.role == BuddyMsg.USER
    Box(Modifier.fillMaxWidth(), contentAlignment = if (mine) Alignment.CenterEnd else Alignment.CenterStart) {
        Column(
            Modifier.widthIn(max = 300.dp)
                .background(if (mine) sig.ai.copy(alpha = 0.14f) else sig.wash, RoundedCornerShape(16.dp))
                .padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Text(m.text, style = MaterialTheme.typography.bodyLarge)
            Text(
                java.time.Instant.ofEpochMilli(m.at).atZone(com.zongkong.core.DayClock.BEIJING).let { String.format("%02d:%02d", it.hour, it.minute) } +
                    if (m.origin.startsWith("speakup")) " · 它主动说的" else "",
                style = MaterialTheme.typography.labelSmall, color = sig.muted,
            )
        }
    }
}

@Composable
private fun BuddySettings(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val book by app.buddy.book.collectAsStateWithLifecycle()
    val cfg = book.config
    var name by remember(cfg.name) { mutableStateOf(cfg.name) }
    var noteText by remember(cfg.note) { mutableStateOf(cfg.note) }
    var qs by remember(cfg.quietStart) { mutableStateOf(cfg.quietStart) }
    var qe by remember(cfg.quietEnd) { mutableStateOf(cfg.quietEnd) }
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Panel {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("主动找你", style = MaterialTheme.typography.titleSmall)
                        Hint("关了它就只在你找它时说话。")
                    }
                    Switch(checked = cfg.enabled, onCheckedChange = { on -> app.buddy.setConfig { it.copy(enabled = on) } }, modifier = Modifier.testTag("buddy-enabled"))
                }
                Text("每小时最多 ${cfg.perHour} 次", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = cfg.perHour.toFloat(), onValueChange = { v -> app.buddy.setConfig { it.copy(perHour = v.toInt().coerceIn(1, 12)) } },
                    valueRange = 1f..12f, steps = 10,
                )
                Text("两次之间至少 ${cfg.minGapMin} 分钟（你不回，下一次会隔 2、4、8 倍）", style = MaterialTheme.typography.bodyMedium)
                Slider(
                    value = cfg.minGapMin.toFloat(), onValueChange = { v -> app.buddy.setConfig { it.copy(minGapMin = v.toInt().coerceIn(3, 60)) } },
                    valueRange = 3f..60f,
                )
                Hint("你正在和总控说话、在总控时间里、屏幕灭着时，它都不开口。")
            }
        }
        item {
            Panel {
                Text("叫什么、怎么说话", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(name, { name = it.take(12) }, label = { Text("名字") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(noteText, { noteText = it.take(300) }, label = { Text("想让它怎么跟你说话（可空）") }, minLines = 2, modifier = Modifier.fillMaxWidth())
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(qs, { qs = it.take(5) }, label = { Text("安静从") }, singleLine = true, modifier = Modifier.weight(1f))
                    OutlinedTextField(qe, { qe = it.take(5) }, label = { Text("到") }, singleLine = true, modifier = Modifier.weight(1f))
                }
                Button(onClick = {
                    app.buddy.setConfig { it.copy(name = name.trim().ifBlank { "小鲸" }, note = noteText.trim(), quietStart = qs.trim(), quietEnd = qe.trim()) }
                }, modifier = Modifier.fillMaxWidth()) { Text("保存") }
            }
        }
        item { BridgePanel() }
        item {
            Panel {
                Text("它记得你的事（${book.facts.size}）", style = MaterialTheme.typography.titleSmall)
                if (book.facts.isEmpty()) Hint("聊天里它觉得值得记的，会记在这里。")
                book.facts.forEach { f ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(f, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        TextButton(onClick = { app.buddy.forget(f) }) { Text("忘掉", color = sig.muted) }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 24.dp)) {
                OutlinedButton(onClick = { app.buddy.clear() }) { Text("清空聊天记录") }
                OutlinedButton(onClick = { nav.navigate("settings") }) { Text("AI 接口") }
            }
        }
    }
}

/** 外接大脑：把「此刻发生了什么」推给电脑上 DeepSeek Harness 里的伙伴（大肥鱼那一套），它通过 QQ 找你。 */
@Composable
private fun BridgePanel() {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val book by app.buddy.book.collectAsStateWithLifecycle()
    val b = book.bridge
    var url by remember(b.url) { mutableStateOf(b.url) }
    var token by remember(b.token) { mutableStateOf(b.token) }
    // 推送结果每秒刷新一次（重试、送达都会更新），不用再点「看结果」
    val log by androidx.compose.runtime.produceState(app.buddy.bridgeLog) {
        while (true) { value = app.buddy.bridgeLog; kotlinx.coroutines.delay(1_000) }
    }
    val check = remember(url) { com.zongkong.core.buddy.BridgeNet.check(url) }
    Panel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("外接大脑（DeepSeek Harness）", style = MaterialTheme.typography.titleSmall)
                Hint("开着时，手机把「刚解锁、刚打开抖音、刷了多久、一手哪题没接住」推给 DSH 里的伙伴，由它在当下找你聊。")
            }
            Switch(checked = b.enabled, onCheckedChange = { on -> app.buddy.setBridge { it.copy(enabled = on) } }, modifier = Modifier.testTag("bridge-enabled"))
        }
        OutlinedTextField(
            url, { url = it.trim() },
            label = { Text("地址") },
            placeholder = { Text(com.zongkong.core.buddy.BridgeNet.LOCAL_DEFAULT) },
            singleLine = true, modifier = Modifier.fillMaxWidth().testTag("bridge-url"),
            isError = url.isNotBlank() && check is com.zongkong.core.buddy.BridgeNet.Check.Bad,
        )
        Hint(
            when {
                url.isBlank() -> "DSH 就在这台手机上：填 ${com.zongkong.core.buddy.BridgeNet.LOCAL_DEFAULT}（127.0.0.1 就是这台手机自己，切 Wi-Fi、用流量、出门都不用改）。DSH 在别的电脑上：用 https:// 地址。"
                check is com.zongkong.core.buddy.BridgeNet.Check.Bad -> check.why
                (check as com.zongkong.core.buddy.BridgeNet.Check.Ok).scheme == "http" -> "同机直连：只连这台手机上的 DSH，不经过网络。"
                else -> "远程 https 连接：需要网络，DSH 那台电脑和隧道要开着。"
            },
        )
        TextButton(onClick = { url = com.zongkong.core.buddy.BridgeNet.LOCAL_DEFAULT }, modifier = Modifier.testTag("bridge-local")) { Text("填本机地址") }
        OutlinedTextField(
            token, { token = it.trim() }, label = { Text("口令") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("开着时手机里的${book.config.name}不再自己开口", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Switch(checked = b.muteLocal, onCheckedChange = { on -> app.buddy.setBridge { it.copy(muteLocal = on) } })
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { app.buddy.setBridge { it.copy(url = url, token = token) } }) { Text("保存") }
            OutlinedButton(onClick = {
                app.buddy.setBridge { it.copy(url = url, token = token) }
                app.buddy.testBridge()
            }, modifier = Modifier.testTag("bridge-test")) { Text("发一条测试") }
        }
        Hint("「已送达（HTTP 200）」只说明 DSH 收到了事件；伙伴有没有回、回了什么，要在 QQ 或 DSH 里看。")
        log.takeLast(5).asReversed().forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = sig.muted) }
    }
}
