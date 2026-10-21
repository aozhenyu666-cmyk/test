package com.moling.app.ui

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.moling.app.BrainHost
import com.moling.app.data.SettingsStore
import com.moling.core.mirror.Mirror
import kotlinx.coroutines.launch

/**
 * 贯通（v6）：让墨灵和 GPT / Notion AI 互相看得见。
 *  - Notion：她把“黑板”（她知道的、她想问的）写进 Notion，GPT / Notion AI 读了把回答写进“收件箱”，她每 15 分钟来收；
 *  - 她想问 GPT 的问题：她自己会想（夜里、聊天里），你也可以加；
 *  - ChatGPT 的导出记录：一次性把你和 GPT 聊过的收进来；
 *  - 深想模型：想得深的事换一个更强的模型（可以就是 GPT 的接口）。
 */
@Composable
fun BridgeScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val state by BrainHost.state.collectAsState()
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val saved = remember { store.load() }
    val notion = remember { store.notion() }

    var token by remember { mutableStateOf(notion.token) }
    var root by remember { mutableStateOf(notion.root) }
    var status by remember { mutableStateOf(store.notionStatus()) }
    var busy by remember { mutableStateOf(false) }
    var newQ by remember { mutableStateOf("") }
    var importText by remember { mutableStateOf("") }
    var deepUrl by remember { mutableStateOf(saved.deepBaseUrl) }
    var deepKey by remember { mutableStateOf(saved.deepApiKey) }
    var deepModel by remember { mutableStateOf(saved.deepModel) }

    fun toast(msg: String) = Toast.makeText(context.applicationContext, msg, Toast.LENGTH_LONG).show()

    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            busy = true
            importText = "在读…（大文件要一会儿，她要一段段看）"
            scope.launch {
                importText = runCatching { BrainHost.importGpt(uri) }.getOrElse { "没读成：${it.message}" }
                busy = false
            }
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onDone) { Text("← 返回") }
            Text("贯通：Notion · GPT", style = MaterialTheme.typography.titleMedium)
        }
        Text(
            "ChatGPT 和 Notion AI 都不让别的程序直接找它们聊，也读不到它们的聊天记录；但它们都能读写 Notion。" +
                "所以墨灵在 Notion 里放一块「${Mirror.BOARD_TITLE}」（她知道的、这几天的判断、想问的问题），" +
                "GPT / Notion AI 读完把回答写进「${Mirror.INBOX_TITLE}」，她每 15 分钟来收一次。" +
                "别人写来的她先把关：论断进体系图，要改“对你的了解”的变成提议，你点“好”才算数。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // ---------------------------------------------------------------- Notion
        Text("Notion", style = MaterialTheme.typography.titleSmall)
        Text(
            "1. 电脑或手机浏览器打开 notion.so/my-integrations，新建一个“内部集成”，复制它的密钥。\n" +
                "2. 在 Notion 里建一个页面（比如叫“墨灵”），右上角 ··· → 连接 → 选刚才的集成。\n" +
                "3. 复制这个页面的链接，和密钥一起填在下面。她会在这个页面下面建黑板和收件箱；这个页面下你自己的其他笔记页，她也会读（改过的才读）。",
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = token, onValueChange = { token = it.trim() }, label = { Text("集成密钥") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            supportingText = { Text("只存在这台手机上（加密）。") },
        )
        OutlinedTextField(
            value = root, onValueChange = { root = it.trim() }, label = { Text("页面链接") }, singleLine = true,
            modifier = Modifier.fillMaxWidth(),
            isError = root.isNotBlank() && Mirror.pageId(root) == null,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !busy && token.isNotBlank() && Mirror.pageId(root) != null, onClick = {
                store.saveNotion(token, root)
                busy = true
                status = "在同步…"
                scope.launch {
                    status = runCatching { BrainHost.mirror(force = true) }.getOrNull() ?: store.notionStatus().ifBlank { "没同步成" }
                    busy = false
                }
            }) { Text(if (busy) "在同步…" else "保存并现在同步") }
            if (notion.ready()) OutlinedButton(enabled = !busy, onClick = {
                store.saveNotion("", "")
                token = ""; root = ""; status = "断开了（Notion 里的页面没动）"
            }) { Text("断开") }
        }
        if (status.isNotBlank()) Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

        Surface(tonalElevation = 1.dp, shape = MaterialTheme.shapes.medium) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                val line = Mirror.askLine(saved.callUser)
                Text("在 ChatGPT 或 Notion AI 里说这句：", style = MaterialTheme.typography.labelLarge)
                Text(line, style = MaterialTheme.typography.bodyMedium)
                Text(
                    "ChatGPT 要先在 设置 → 应用 / 连接器 里连上 Notion。它要是只能读、写不进去，就让它把回答发给你，你复制后从分享菜单发给墨灵，或贴进收件箱。Notion AI 读写都行。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(onClick = { clipboard.setText(AnnotatedString(line)); toast("复制好了") }) { Text("复制这句") }
            }
        }

        // ---------------------------------------------------------------- 她想问 GPT 的
        Text("她想问 GPT 的", style = MaterialTheme.typography.titleSmall)
        Text(
            "她夜里整理完、或者聊天里拿不准时，会自己记下要问的（最多同时 5 个），下次同步写上黑板。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        state.questions.reversed().take(12).forEach { q ->
            Surface(tonalElevation = if (q.answer.isBlank()) 2.dp else 0.dp, shape = MaterialTheme.shapes.medium) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${q.id} · ${q.by}问的：${q.text}", style = MaterialTheme.typography.bodyMedium)
                    if (q.why.isNotBlank()) Text("为什么问：${q.why}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (q.answer.isNotBlank()) Text("回答：${q.answer}", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = { scope.launch { BrainHost.dropQuestion(q.id) } }) { Text(if (q.answer.isBlank()) "不问了" else "删掉") }
                }
            }
        }
        OutlinedTextField(
            value = newQ, onValueChange = { newQ = it.take(120) }, modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("你也可以替她问一句") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = newQ.trim().length >= 4, onClick = {
                val t = newQ.trim()
                newQ = ""
                scope.launch { if (BrainHost.askGpt(t) == null) toast("这个已经在问了，或者问题太多了") }
            }) { Text("加上") }
            OutlinedButton(enabled = !busy, onClick = {
                busy = true
                scope.launch {
                    val made = runCatching { BrainHost.prepareQuestions() }.getOrDefault(emptyList())
                    busy = false
                    toast(if (made.isEmpty()) "她现在没想到要问的（或者模型没接上）" else "她想了 ${made.size} 个")
                }
            }) { Text("让她想想要问什么") }
        }

        // ---------------------------------------------------------------- ChatGPT 的记录
        Text("你和 GPT 聊过的", style = MaterialTheme.typography.titleSmall)
        Text(
            "ChatGPT 设置 → 数据控制 → 导出数据，邮件里会收到一个压缩包。下载到手机后在这里选它（或解压出来的 conversations.json）。" +
                "她只收上次导入之后更新过的、最新的 20 段，一段段把关后收进体系图。文件只在手机上读，不上传。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(enabled = !busy, onClick = {
            runCatching { pick.launch(arrayOf("application/zip", "application/json", "application/octet-stream", "*/*")) }
        }) { Text("选 ChatGPT 的导出文件") }
        if (importText.isNotBlank()) Text(importText, style = MaterialTheme.typography.bodySmall)

        // ---------------------------------------------------------------- 深想模型
        Text("深想模型", style = MaterialTheme.typography.titleSmall)
        Text(
            "夜里整理、收收件箱、想问 GPT 的、归纳体系、周报这些要想得深的事，换一个更强的模型；平时聊天还用上面那个（便宜、快）。" +
                "DeepSeek 填 deepseek-reasoner 就行（地址和密钥留空=沿用）。想让 GPT 直接参与她的思考：地址填 https://api.openai.com/v1、" +
                "填你的 OpenAI 接口密钥和模型名——这是按量付费的接口，和 ChatGPT 会员是两回事，也看不到你在 ChatGPT 里的记录。不通会自动退回平时的模型。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(value = deepModel, onValueChange = { deepModel = it.trim() }, label = { Text("模型名（空 = 不用）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(value = deepUrl, onValueChange = { deepUrl = it.trim() }, label = { Text("接口地址（空 = 沿用）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(
            value = deepKey, onValueChange = { deepKey = it.trim() }, label = { Text("密钥（空 = 沿用）") }, singleLine = true,
            visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
        )
        Button(onClick = {
            store.saveDeep(deepUrl, deepKey, deepModel)
            toast(if (deepModel.isBlank()) "不用深想模型了" else "好了，想得深的事用 $deepModel")
        }) { Text("保存深想模型") }
    }
}
