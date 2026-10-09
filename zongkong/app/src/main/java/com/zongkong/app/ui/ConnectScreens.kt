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
import androidx.compose.material3.TextButton
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
    val app = context.zk
    val store = app.store
    val config by store.config.collectAsStateWithLifecycle()
    val work by store.work.collectAsStateWithLifecycle()
    var token by remember { mutableStateOf(config.notion.token) }
    var parent by remember { mutableStateOf(config.notion.parentPage) }
    var threads by remember(config.notion.threadsDb) { mutableStateOf(config.notion.threadsDb) }
    var actions by remember(config.notion.actionsDb) { mutableStateOf(config.notion.actionsDb) }
    var notes by remember(config.notion.notesDb) { mutableStateOf(config.notion.notesDb) }
    var result by remember { mutableStateOf<String?>(null) }
    var steps by remember { mutableStateOf<List<com.zongkong.core.work.SelfTest.Step>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val sig = LocalSignals.current

    fun save() = store.updateConfig {
        it.copy(notion = it.notion.copy(
            token = token.trim(), parentPage = parent.trim(),
            threadsDb = threads.trim(), actionsDb = actions.trim(), notesDb = notes.trim(),
        ))
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
                Text("1. 电脑浏览器打开 notion.so/profile/integrations → 新建集成（内部）→ 复制密钥，贴到下面。", style = MaterialTheme.typography.bodyMedium)
                Text("2. 在 Notion 里新建一个页面叫“总控”。打开它 → 右上角 ⋯ → 连接 → 选你的集成。", style = MaterialTheme.typography.bodyMedium)
                Text("3. 复制这个页面的链接贴到下面，点“一键建库”：会建好 总控·事项、总控·行动、总控·记录 三个库。", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                token, { token = it }, label = { Text("集成密钥（ntn_ 或 secret_ 开头）") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(parent, { parent = it }, label = { Text("“总控”页面链接") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    if (NotionIds.parse(parent) == null) {
                        result = "先贴“总控”页面的链接"
                    } else {
                        save(); busy = true; result = "建库中…"
                        scope.launch { result = app.actions.work.setup(parent.trim()); busy = false }
                    }
                }, enabled = !busy && token.isNotBlank()) { Text(if (config.notion.workReady) "再建一套" else "一键建库") }
                OutlinedButton(onClick = {
                    save(); busy = true; result = "自检中…"; steps = emptyList()
                    scope.launch {
                        steps = app.actions.work.selfTest()
                        result = if (steps.all { it.ok }) "自检全部通过：写入、读回、修改、增量查询、关联都正常。" else "自检有问题，看下面哪一步没过。"
                        busy = false
                    }
                }, enabled = !busy && config.notion.workReady) { Text("连接自检") }
            }
            result?.let { Panel { Text(it) } }
            steps.forEach { st ->
                Row(verticalAlignment = Alignment.Top) {
                    Text(if (st.ok) "✓ " else "✗ ", color = if (st.ok) sig.free else sig.strict)
                    Column {
                        Text(st.name, style = MaterialTheme.typography.bodyMedium)
                        Hint(st.detail)
                    }
                }
            }

            SectionLabel("三个库（一键建库会自动填；也可以贴你自己的库链接）")
            OutlinedTextField(threads, { threads = it }, label = { Text("事项库") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(actions, { actions = it }, label = { Text("行动库") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(notes, { notes = it }, label = { Text("记录库") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { save(); result = "已保存" }) { Text("保存") }
                OutlinedButton(onClick = { save(); scope.launch { result = app.actions.work.sync() } }, enabled = config.notion.workReady) { Text("立即同步") }
            }
            SyncLine(work, config.notion.workReady, null)
            Hint("自己建的库也能用：列名对得上（标题、状态、事项、计划时间、完成依据……）就同步，缺的列会跳过并提示。数据库不能有多个数据源（Notion 新功能），否则读不了。")

            SectionLabel("同步规则")
            Text(
                "· 事项、行动、记录的内容以 Notion 为准；手机上改的先存本机，联网后按字段写回。\n" +
                    "· 两边都改了同一处：后改的为准，另一份留着给你选。\n" +
                    "· 打开总控、回到前台、每 15 分钟（总控在运行时）、你改了东西 2 秒后，各同步一次。\n" +
                    "· 提醒时间、专注锁、娱乐限制只在手机上算，不依赖网络。\n" +
                    "· 在 Notion 里删掉的页面不会自动从手机删除；要取消请把状态改成“取消”。",
                style = MaterialTheme.typography.bodySmall, color = sig.muted,
            )
            TextButton(onClick = { nav.navigate("settings/gptguide") }) { Text("怎么让 ChatGPT 配合 →") }
        }
    }
}

/** GPT 配合说明：把说明放进 ChatGPT 项目；连接现状如实列出来。 */
@Composable
fun GptGuideScreen(nav: NavHostController) {
    val context = LocalContext.current
    val sig = LocalSignals.current
    var copied by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        BackBar("ChatGPT 配合", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Panel {
                Text("设置一次", style = MaterialTheme.typography.titleSmall)
                Text("1. 在 ChatGPT 里新建一个“项目”，叫“总控”。", style = MaterialTheme.typography.bodyMedium)
                Text("2. 把下面的说明粘贴到项目的“说明 / Instructions”里。以后在这个项目里聊，GPT 就知道怎么配合。", style = MaterialTheme.typography.bodyMedium)
                Text("3. 可选：在 ChatGPT 设置里连接 Notion。只能读的话，GPT 能看你的记录；要让它直接写，需要连 Notion 的 MCP（看你的 ChatGPT 套餐和版本是否支持）。写不了也没关系，用交接块。", style = MaterialTheme.typography.bodyMedium)
            }
            Button(onClick = { Gpt.copy(context, com.zongkong.core.work.Handoff.GPT_INSTRUCTIONS); copied = true }, modifier = Modifier.fillMaxWidth()) {
                Text(if (copied) "已复制说明" else "复制给 ChatGPT 的说明")
            }
            Panel {
                Text(com.zongkong.core.work.Handoff.GPT_INSTRUCTIONS, style = MaterialTheme.typography.bodySmall)
            }
            SectionLabel("各条连接的现状")
            CONNECTIONS.forEach { (what, state, how) ->
                Panel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(what, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                        Tag(state, when (state) {
                            "已验证" -> sig.free
                            "已实现·待你确认" -> sig.warn
                            "需要你配置" -> sig.info
                            else -> sig.muted
                        })
                    }
                    Hint(how)
                }
            }
        }
    }
}

private val CONNECTIONS = listOf(
    Triple("总控 → Notion 写入", "已实现·待你确认", "新建、按字段更新、断网重试、防重复都在模拟的 Notion 上测过。真实 Notion 请在“Notion → 连接自检”走一遍。"),
    Triple("Notion → 总控 读取", "已实现·待你确认", "按修改时间增量拉取，GPT 改了状态、判断、新建行动，总控能识别并在“查看结果”显示。同上，用连接自检确认。"),
    Triple("GPT → Notion 写入", "需要你配置", "取决于你的 ChatGPT：内置 Notion 连接一般只能读；接上 Notion MCP 才能写。用“交接块”可以绕过：GPT 输出、你复制、总控写入。"),
    Triple("GPT 读 Notion 里的结果", "需要你配置", "ChatGPT 连上 Notion（只读即可）就能读到事项、行动和结果。没连的话，“去 GPT”时总控会把前情和结果整理成一段话带过去。"),
    Triple("总控 → ChatGPT 带上下文", "已实现·待你确认", "ChatGPT App 不接受外部直接开新对话。总控把前情复制好并打开 ChatGPT，你粘贴；也提供“分享给 ChatGPT”和网页版预填。都不会自动发送。"),
    Triple("ChatGPT → 总控 交回结果", "已实现·待你确认", "复制或分享【总控交接】块到总控，先预览再写入。从 ChatGPT 回来时首页会检测剪贴板。"),
    Triple("Notion 变化实时推到手机", "当前做不到", "Notion 的 Webhook 需要一台公网服务器转发，现在用“回到前台 + 每 15 分钟”拉取代替。Notion 的变化也不能唤醒一个 ChatGPT 对话。"),
)
