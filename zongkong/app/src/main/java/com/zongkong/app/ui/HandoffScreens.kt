package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.work.Handoff
import kotlinx.coroutines.launch

/**
 * 去 GPT：把前情整理好的一段话。
 * ChatGPT App 不接受外部直接塞入对话，所以这里如实给三种办法，没有一种会替你自动发送。
 */
@Composable
fun GptScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    var text by remember { mutableStateOf(app.prompt) }
    var note by remember { mutableStateOf<String?>(null) }
    val installed = remember { Gpt.installed(context) }
    val canShare = remember { Gpt.canShareTo(context) }
    Column(Modifier.fillMaxSize()) {
        BackBar("带着前情去 GPT", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (app.promptTitle.isNotBlank()) Text(app.promptTitle, style = MaterialTheme.typography.titleLarge.merge(SerifTitle))
            Hint("下面这段话交代了前情：为什么做、当前判断、停在哪、做过什么、结果如何。可以改。GPT 讨论完会给交接块，复制它回到总控就能导入。")
            OutlinedTextField(text, { text = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp))
            Button(onClick = {
                Gpt.copy(context, text)
                if (installed && Gpt.openApp(context)) note = "已复制。在 ChatGPT 里长按输入框粘贴。" else note = "已复制。没找到 ChatGPT App，可以用网页版。"
            }, modifier = Modifier.fillMaxWidth()) { Text(if (installed) "复制并打开 ChatGPT" else "复制") }
            if (installed && canShare) {
                OutlinedButton(onClick = { if (!Gpt.shareTo(context, text)) note = "分享失败，用上面的复制。" }, modifier = Modifier.fillMaxWidth()) {
                    Text("分享给 ChatGPT（文字直接进输入框）")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { Gpt.openWeb(context, text) }, modifier = Modifier.weight(1f)) { Text("网页版打开") }
                OutlinedButton(onClick = { Gpt.shareAny(context, text) }, modifier = Modifier.weight(1f)) { Text("发给别的 App") }
            }
            note?.let { Text(it, color = sig.free, style = MaterialTheme.typography.bodyMedium) }
            Panel {
                Text("回来的时候", style = MaterialTheme.typography.titleSmall)
                Hint("· GPT 能写 Notion：它写完会说“已写入”，回到总控会自动拉取。\n· GPT 不能写 Notion：复制它给的【总控交接】块，回到总控首页会提示导入；或者在 ChatGPT 里长按回答 → 分享 → 总控。")
                TextButton(onClick = { nav.navigate("import") }) { Text("我已经复制了交接块 → 导入") }
            }
            if (!installed) Hint("没检测到 ChatGPT App（包名 ${Gpt.PKG}）。")
        }
    }
}

/** 导入 GPT 的交接块：先给你看会改什么，确认后写入并同步。 */
@Composable
fun ImportScreen(nav: NavHostController) {
    val context = LocalContext.current
    val app = context.zk
    val sig = LocalSignals.current
    val scope = rememberCoroutineScope()
    val shared by app.shared.collectAsStateWithLifecycle()
    var text by remember { mutableStateOf("") }
    var msg by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(shared) {
        shared?.let { if (Handoff.contains(it.text)) { text = it.text; app.shared.value = null } }
    }
    LaunchedEffect(Unit) {
        if (text.isBlank()) {
            kotlinx.coroutines.delay(300)
            Gpt.readClipboard(context).takeIf { Handoff.contains(it) }?.let { text = it }
        }
    }
    val plan = remember(text, app.store.work.value) { if (text.isBlank()) null else app.actions.work.planImport(text) }
    val block = remember(text) { Handoff.parse(text) }

    Column(Modifier.fillMaxSize()) {
        BackBar("导入 GPT 交接", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (text.isBlank()) {
                Hint("剪贴板里没有【总控交接】块。在 ChatGPT 里复制它的回答，或者长按回答 → 分享 → 总控。")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { text = Gpt.readClipboard(context) }) { Text("从剪贴板读") }
                    OutlinedButton(onClick = { nav.navigate("settings/gptguide") }) { Text("交接块是什么") }
                }
            }
            OutlinedTextField(text, { text = it }, label = { Text("交接内容") }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth())
            when {
                text.isBlank() -> {}
                block?.alreadyWritten == true && (plan == null || plan.newActions.isEmpty() && plan.threadChanges.size <= 1) -> Panel(accent = sig.free) {
                    val code = block?.code.orEmpty()
                    Text("GPT 说已经写进 Notion 了${if (code.isNotBlank()) "（$code）" else ""}", style = MaterialTheme.typography.titleSmall)
                    Button(onClick = {
                        scope.launch {
                            msg = app.actions.work.sync()
                            app.store.awaitingGpt = ""
                            app.store.work.value.threadByCode(code)?.let { nav.navigate("thread/${it.key}") { popUpTo("home") } }
                        }
                    }) { Text("从 Notion 拉取") }
                }
                plan == null -> Text("没读出交接内容。要包含“【总控交接】”和“事项：”“行动：”这些行。", color = sig.strict)
                else -> {
                    Panel(accent = sig.think) {
                        Text("会写入这些", style = MaterialTheme.typography.titleSmall)
                        plan.lines.forEach { Text(it, style = MaterialTheme.typography.bodyMedium, color = if (it.startsWith("⚠")) sig.warn else MaterialTheme.colorScheme.onSurface) }
                    }
                    Button(onClick = {
                        val key = app.actions.work.applyImport(plan)
                        Gpt.copy(context, "")
                        nav.navigate("thread/$key") { popUpTo("home") }
                    }, modifier = Modifier.fillMaxWidth().testTag("import-confirm")) { Text("确认导入") }
                    Hint(if (app.store.config.value.notion.workReady) "导入后会同步到 Notion，GPT 下次读这件事能看到。" else "Notion 还没连接：先存在手机上，连上后自动补同步。")
                }
            }
            msg?.let { Hint(it) }
        }
    }
}
