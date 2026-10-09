package com.zongkong.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.zongkong.app.zk
import com.zongkong.core.AiConfig
import com.zongkong.core.NotionIds
import kotlinx.coroutines.launch

private data class Preset(val name: String, val url: String, val model: String)

private val PRESETS = listOf(
    Preset("DeepSeek", "https://api.deepseek.com", "deepseek-chat"),
    Preset("Kimi", "https://api.moonshot.cn/v1", "moonshot-v1-8k"),
    Preset("通义千问", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
    Preset("智谱", "https://open.bigmodel.cn/api/paas/v4", "glm-4-flash"),
    Preset("OpenAI", "https://api.openai.com/v1", "gpt-4o-mini"),
)

@OptIn(ExperimentalLayoutApi::class, androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AiScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    var url by remember { mutableStateOf(config.ai.baseUrl) }
    var key by remember { mutableStateOf(config.ai.apiKey) }
    var model by remember { mutableStateOf(config.ai.model) }
    var json by remember { mutableStateOf(config.ai.jsonMode) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        BackBar("AI 接口", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Hint("任何 OpenAI 兼容接口都行。验收一次大约一两千 token，一天十来次，DeepSeek 一个月几毛钱。密钥只存在这台手机上。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PRESETS.forEach { p ->
                    AssistChip(onClick = { url = p.url; model = p.model }, label = { Text(p.name) })
                }
            }
            OutlinedTextField(url, { url = it }, label = { Text("接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                key, { key = it }, label = { Text("API Key") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(model, { model = it }, label = { Text("模型名") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("JSON 模式")
                    Hint("DeepSeek、OpenAI、通义支持。测试报 400 时关掉试试。")
                }
                Switch(checked = json, onCheckedChange = { json = it })
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    store.updateConfig { it.copy(ai = AiConfig(url.trim(), key.trim(), model.trim(), json)) }
                    busy = true
                    result = "测试中…"
                    scope.launch {
                        result = context.zk.actions.testAi()
                        busy = false
                    }
                }, enabled = !busy) { Text("保存并测试") }
            }
            result?.let { Panel { Text(it) } }
        }
    }
}

@Composable
fun NotionScreen(nav: NavHostController) {
    val context = LocalContext.current
    val store = context.zk.store
    val config by store.config.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf(config.notion.token) }
    var parent by remember { mutableStateOf(config.notion.parentPage) }
    var inbox by remember { mutableStateOf(config.notion.inboxDb) }
    var think by remember { mutableStateOf(config.notion.thinkDb) }
    var log by remember { mutableStateOf(config.notion.logDb) }
    var result by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sig = LocalSignals.current

    fun save() = store.updateConfig {
        it.copy(notion = it.notion.copy(token = token.trim(), parentPage = parent.trim(), inboxDb = inbox.trim(), thinkDb = think.trim(), logDb = log.trim()))
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("Notion", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Panel {
                Text("三步连上", style = MaterialTheme.typography.titleSmall)
                Text("1. 电脑浏览器打开 notion.so/profile/integrations → 新建集成（类型选“内部”）→ 复制密钥，贴到下面。", style = MaterialTheme.typography.bodyMedium)
                Text("2. 在 Notion 里新建一个页面，叫“总控”。打开它 → 右上角 ⋯ → 连接（Connections）→ 选你的集成。", style = MaterialTheme.typography.bodyMedium)
                Text("3. 复制这个页面的链接贴到下面，点“一键搭建”。会在里面建好信息收集库、谋划库、总控日志。", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                token, { token = it }, label = { Text("集成密钥（ntn_ 或 secret_ 开头）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(parent, { parent = it }, label = { Text("“总控”页面链接") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    save()
                    busy = true
                    result = "测试中…"
                    scope.launch { result = context.zk.actions.testNotion(); busy = false }
                }, enabled = !busy) { Text("保存并测试") }
                OutlinedButton(onClick = {
                    if (NotionIds.parse(parent) == null) {
                        result = "先贴“总控”页面的链接"
                    } else {
                        save()
                        busy = true
                        result = "搭建中…"
                        scope.launch {
                            result = context.zk.actions.setupNotion(parent.trim())
                            val n = store.config.value.notion
                            inbox = n.inboxDb; think = n.thinkDb; log = n.logDb
                            busy = false
                        }
                    }
                }, enabled = !busy && config.notion.inboxDb.isBlank()) { Text("一键搭建") }
            }
            result?.let { Panel { Text(it) } }

            SectionLabel("数据库（一键搭建会自动填；也可以贴你自己的库）")
            OutlinedTextField(inbox, { inbox = it }, label = { Text("信息收集库：随手记写这里") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(think, { think = it }, label = { Text("谋划库：谋划单写这里") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(log, { log = it }, label = { Text("总控日志：每次验收写这里") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedButton(onClick = { save(); result = "已保存" }) { Text("保存数据库设置") }
            Hint("自己建的库也能用：标题列随意，其余列名对得上（类型、状态、日期、部门、结果、得分……）就会填进去，对不上的跳过。")
            if (store.syncNote.isNotBlank()) Hint(store.syncNote)

            SectionLabel("和 GPT 配合")
            Text(
                "ChatGPT 连上 Notion 之后，可以让它读“总控日志”帮你做周复盘、读“信息收集库”帮你挑问题。" +
                    "信息收集部的关卡改成“Notion 查账”后，不管你是用 GPT、Notion 还是这里的随手记存进去，只要当天建了足够条数就算过。",
                style = MaterialTheme.typography.bodyMedium, color = sig.muted,
            )
        }
    }
}
