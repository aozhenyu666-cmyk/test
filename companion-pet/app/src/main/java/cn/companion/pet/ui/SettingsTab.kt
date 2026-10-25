package cn.companion.pet.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import cn.companion.pet.a11y.PerceptionHub
import cn.companion.pet.a11y.AndroidShizukuProbe
import cn.companion.pet.a11y.AndroidShizukuCommandPort
import cn.companion.pet.core.device.ShizukuCapabilityRunner
import cn.companion.pet.a11y.ScreenCaptureGateway
import cn.companion.pet.core.vision.CapturedStatus
import cn.companion.pet.core.vision.ObservationSource
import cn.companion.pet.core.vision.ScreenCaptureAttempt
import cn.companion.pet.core.vision.ScreenCapturePolicy
import cn.companion.pet.core.vision.ScreenObservation
import java.util.concurrent.Executors
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import cn.companion.pet.PetApp
import cn.companion.pet.core.agent.AgentTrace
import cn.companion.pet.core.goal.Goal
import cn.companion.pet.core.goal.GoalDecision
import cn.companion.pet.core.goal.GoalStatus
import cn.companion.pet.core.action.PendingAction
import cn.companion.pet.core.action.PendingStatus
import cn.companion.pet.core.diagnostics.DiagnosticInputs
import cn.companion.pet.core.diagnostics.DiagnosticReport
import android.content.ClipData
import android.content.ClipboardManager
import cn.companion.pet.data.SecretWriteResult
import cn.companion.pet.bridge.Assistants
import cn.companion.pet.core.session.AssistantApp
import cn.companion.pet.data.Stats
import cn.companion.pet.net.CallDiagnosis
import cn.companion.pet.net.ChatMessage
import cn.companion.pet.net.ChatRequest
import cn.companion.pet.net.ChatResult
import cn.companion.pet.net.ChatRole
import cn.companion.pet.net.EndpointPolicy
import cn.companion.pet.net.ModelProvider
import cn.companion.pet.ui.components.PageHeader
import cn.companion.pet.ui.theme.CompanionSpacing
import cn.companion.pet.ui.theme.CompanionShapes
import cn.companion.pet.ui.theme.LocalCompanionColors
import cn.companion.pet.ui.components.Panel
import cn.companion.pet.ui.components.GroupTitle
import cn.companion.pet.ui.components.ToggleRow
import cn.companion.pet.work.NightlyWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 设置（右上角进入，不占主导航）：权限、模型与看图、陪伴、设备、诊断。
 * 记忆/目标/技能/人设/统计这些“它自己的东西”在“我的”里。
 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsScreen(activity: cn.companion.pet.MainActivity, resumeTick: Int, group: Int, onGroup: (Int) -> Unit, onBack: () -> Unit) {
    val context: Context = activity
    Column(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = CompanionSpacing.page, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onBack, modifier = Modifier.testTag("settings_back")) { Text("‹ 返回") }
                Text("设置", style = MaterialTheme.typography.titleLarge, color = LocalCompanionColors.current.ink)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SETTINGS_GROUPS.forEachIndexed { i, label ->
                    val tag = Modifier.testTag("settings_group_" + i)
                    if (i == group) Button(onClick = {}, modifier = tag, shape = CompanionShapes.button) { Text(label) }
                    else OutlinedButton(onClick = { onGroup(i) }, modifier = tag, shape = CompanionShapes.button) { Text(label) }
                }
            }
        }
        HorizontalDivider()
        // 每个分区各自记住滚动位置。
        key(group) {
            Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = CompanionSpacing.page, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                when (group) {
                    SETTINGS_PERMISSIONS -> PermissionsSection(activity, resumeTick)
                    SETTINGS_MODELS -> { ModelSettings(context); ScreenDiagnosticSection(context) }
                    SETTINGS_COMPANION -> CompanionSettings(context)
                    SETTINGS_DEVICE -> DeviceSettings(context)
                    SETTINGS_GUARD -> GuardSection()
                    else -> {
                        CrashReportSection(activity)
                        FocusDiagnostics()
                        PresenceDiagnostics()
                        GroupTitle("决策轨迹")
                        AgentTracePreview()
                        DiagnosticSection(context)
                        DebugSettings(context)
                    }
                }
            }
        }
    }
}

/** 专注守护状态：只显示状态、剩余时间和最近一次结果。 */
@Composable
private fun FocusDiagnostics() {
    val app = PetApp.instance
    var tick by remember { mutableIntStateOf(0) }
    GroupTitle("专注守护")
    val st = remember(tick) { app.focusStatus?.invoke() }
    val lines = when {
        st == null -> listOf("桌宠没在运行。")
        !st.active -> listOf("现在没有守护。" + if (st.storeError.isNotBlank()) "（" + st.storeError + "）" else "")
        else -> buildList {
            add("守护 ${st.targetLabel}（" + (if (st.enforcement == cn.companion.pet.core.focus.FocusEnforcement.STRICT) "严格" else "提醒") + "）· 状态 ${st.state} · 还剩 ${st.secondsLeft / 60} 分 ${st.secondsLeft % 60} 秒")
            if (st.returnAttempts > 0) add("自动返回已尝试 ${st.returnAttempts} 次")
            if (st.lastResult.isNotBlank()) add("最近：" + st.lastResult)
            add("拦截层：" + if (st.overlayShown) "显示中" else if (st.overlayError.isNotBlank()) "没能显示（" + st.overlayError + "）" else "未显示")
            if (st.storeError.isNotBlank()) add("存储：" + st.storeError)
        }
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { tick++ }) { Text("刷新") }
}

/** C3 本机主动性的脱敏统计：只有计数和原因，没有任何屏幕内容。 */
@Composable
private fun PresenceDiagnostics() {
    val app = PetApp.instance
    var tick by remember { mutableIntStateOf(0) }
    GroupTitle("本机主动陪伴统计")
    if (!app.settings.presenceEnabled) {
        Text("没开（设置 → 陪伴 → 本机主动陪伴）。", style = MaterialTheme.typography.bodySmall)
        return
    }
    val st = remember(tick) { app.presenceEngine.stats }
    val policy = remember(tick) { app.presencePolicy.snapshot() }
    val now = System.currentTimeMillis()
    val lines = buildList {
        add("本次运行评估 ${st.evaluations} 次：小动作 ${st.micro}、本机提示 ${st.nudges}、调模型 ${st.modelCalls}")
        add("今天主动开口 ${app.presencePolicy.spokenToday(now)} / ${app.presencePolicy.config.dailyCap}；连续没回应 ${policy.consecutiveIgnores} 次")
        if (policy.ignoreCooldownUntil > now) add("没回应冷却还剩 ${(policy.ignoreCooldownUntil - now) / 60_000 + 1} 分钟")
        if (policy.dismissedUntil > now) add("你关掉后安静还剩 ${(policy.dismissedUntil - now) / 60_000 + 1} 分钟")
        if (policy.lastFeedback.isNotBlank()) add("上次反馈：" + when (policy.lastFeedback) {
            "INTERACTED" -> "回应了"; "IGNORED" -> "没理"; "DISMISSED" -> "关掉了"; "PAUSED" -> "暂停了"; else -> policy.lastFeedback
        })
        if (st.lastDecision.isNotBlank()) add("最近一次：" + st.lastDecision)
        if (st.silentReasons.isNotEmpty()) add("安静的原因：" + st.silentReasons.entries.joinToString("、") { it.key + " " + it.value })
    }
    lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    TextButton(onClick = { tick++ }) { Text("刷新") }
}

/** 崩溃/卡死/被系统杀的报告：真机出问题时一键复制发给开发者。 */
@Composable
private fun CrashReportSection(activity: cn.companion.pet.MainActivity) {
    val a11yConnected by cn.companion.pet.a11y.PerceptionHub.connected.collectAsState()
    GroupTitle("崩溃与退出记录")
    Text("包含崩溃堆栈、系统给出的退出原因、版本和机型；不含聊天、屏幕文字或 Key。", style = MaterialTheme.typography.bodySmall)
    Button(onClick = {
        val text = cn.companion.pet.data.Diagnostics.report(activity, a11yConnected)
        val cm = activity.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText("同桌诊断", text))
        Toast.makeText(activity, "诊断信息已复制，粘贴发给开发者即可", Toast.LENGTH_SHORT).show()
    }, shape = CompanionShapes.button) { Text("复制诊断信息") }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun ModelSettings(context: Context) {
    val app = PetApp.instance
    val s = app.settings
    val scope = rememberCoroutineScope()
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    Section("搭话用的模型")
    Text("桌宠主动开口、想一想、整理记忆都用这里的模型。点一下填好常用服务商，Key 要换成对应服务商的。", style = MaterialTheme.typography.bodySmall)
    var endpoint by remember { mutableStateOf(s.endpoint) }
    var model by remember { mutableStateOf(s.model) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        ModelProvider.entries.forEach { p ->
            OutlinedButton(onClick = { endpoint = p.endpoint; model = p.model }) { Text(p.shortLabel) }
        }
    }
    var key by remember { mutableStateOf("") }
    var limit by remember { mutableStateOf(s.dailyLimit.toString()) }
    OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("接口地址（OpenAI 兼容）") }, singleLine = true,
        isError = !EndpointPolicy.isValid(endpoint))
    OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名") }, singleLine = true)
    var chatModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var chatModelsNote by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        val k = key.ifBlank { app.apiKey().orEmpty() }
        if (k.isBlank()) { toast("先填 Key"); return@TextButton }
        chatModelsNote = "正在获取…"
        scope.launch {
            when (val r = app.modelLister.list(endpoint, k)) {
                is cn.companion.pet.net.ModelLister.Result.Success -> { chatModels = r.models; chatModelsNote = "点一个填进上面的模型名（共 ${r.models.size} 个）" }
                is cn.companion.pet.net.ModelLister.Result.Failure -> { chatModels = emptyList(); chatModelsNote = "没取到：" + r.reason }
            }
        }
    }) { Text("获取模型列表") }
    ModelChoices(chatModels, chatModelsNote, highlight = { false }) { model = it }
    OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (app.apiKey() != null) "API Key（已保存，留空不改）" else "API Key") },
        singleLine = true, visualTransformation = PasswordVisualTransformation())
    NumberField("每日调用上限", limit) { limit = it }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            if (!EndpointPolicy.isValid(endpoint)) { toast("接口地址要是 https 开头"); return@Button }
            if (endpoint.trim() != s.endpoint || model.trim() != s.model || key.isNotBlank()) app.callStatus.clear()
            s.endpoint = endpoint.trim(); s.model = model.trim()
            s.dailyLimit = limit.toIntOrNull()?.coerceIn(1, 2000) ?: s.dailyLimit
            if (key.isNotBlank()) {
                val k = key
                scope.launch {
                    val r = withContext(Dispatchers.IO) { app.secrets.save(k) }
                    app.invalidateKey()
                    toast(if (r == SecretWriteResult.Success) "已保存" else "Key 保存失败")
                    key = ""
                }
            } else toast("已保存")
        }) { Text("保存") }
        OutlinedButton(onClick = {
            scope.launch { withContext(Dispatchers.IO) { app.secrets.clear() }; app.invalidateKey(); app.callStatus.clear(); toast("已清除 Key") }
        }) { Text("清除 Key") }
        // 用已保存的地址/模型/Key 真发一次很短的请求，失败时直接告诉原因。
        OutlinedButton(onClick = {
            val savedKey = app.apiKey()
            if (savedKey == null) { toast("先填 Key 并点保存"); return@OutlinedButton }
            toast("正在测试 ${s.model}…")
            scope.launch {
                val today = java.time.LocalDate.now().toString()
                val reserved = withContext(Dispatchers.IO) { app.budget.reserve(s.dailyLimit, today) }
                if (!reserved) { toast("今天的调用额度用完了，明天再测"); return@launch }
                app.stats.add(Stats.Kind.API_CALLS)
                val result = app.client.complete(
                    ChatRequest(s.endpoint, s.model, savedKey, listOf(ChatMessage(ChatRole.USER, "只回复两个字：在呢")), maxTokens = 64, timeoutMillis = 20_000L),
                )
                val text = if (result is ChatResult.Success) "连接正常（${s.model}）" else "没连上：" + CallDiagnosis.describe(result)
                Toast.makeText(context, text, Toast.LENGTH_LONG).show()
            }
        }) { Text("测试连接") }
    }

}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun DeviceSettings(context: Context) {
    val s = PetApp.instance.settings
    val scope = rememberCoroutineScope()
    Section("能力")
    OutlinedButton(onClick = { context.startActivity(Intent(context, cn.companion.pet.CapabilityActivity::class.java)) }) {
        Text("打开能力面板")
    }
    Section("设备操作")
    val shizuku = remember { AndroidShizukuProbe(context) }
    val capability = remember { ShizukuCapabilityRunner(shizuku, AndroidShizukuCommandPort()) }
    var shizukuText by remember { mutableStateOf(shizuku.diagnostic().detail.ifBlank { shizuku.diagnostic().status.name }) }
    Text("Shizuku 只用于状态/授权，不执行任意 Shell。未安装/未运行时，Intent 和无障碍动作仍可用。", style = MaterialTheme.typography.bodySmall)
    Text("Shizuku：" + shizukuText, style = MaterialTheme.typography.bodySmall)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = { shizukuText = shizuku.diagnostic().detail }, enabled = !shizukuText.contains("请求")) {
            Text("刷新状态")
        }
        OutlinedButton(onClick = {
            shizuku.requestPermission()
            shizukuText = "已打开系统授权；请刷新状态"
        }) { Text("请求权限") }
        OutlinedButton(onClick = {
            scope.launch {
                val r = withContext(Dispatchers.IO) { capability.readSdkDiagnostic() }
                shizukuText = "固定 Binder 能力：" + r.status.name + (if (r.value.isBlank()) "" else " · 版本 " + r.value) + (if (r.reason.isBlank()) "" else " · " + r.reason)
            }
        }) { Text("读取 Shizuku 版本") }
    }
    AppListRow(context, "允许 Agent 操作的应用", "空名单时拒绝所有设备动作（这是有意的）；只打开/点击/输入这些应用。测试时放一个专用的测试 App，不要放系统设置、应用商店、银行或支付类 App。", s.deviceAllowedApps) {
        s.deviceAllowedApps = it
    }
    Text("技能、目标、待确认在“我的”里。", style = MaterialTheme.typography.bodySmall)
}

@Composable
internal fun AutonomySettings() {
    val s = PetApp.instance.settings
    Section("让它自己动手（实验）")
    var agentOn by remember { mutableStateOf(s.agentAutonomy) }
    Toggle(
        "让模型自己决定用一次工具",
        "开启后，新素材或用户念头会触发一次模型决策。只自动执行读取记忆/素材；保存念头、打开 AI 应用、朗读只记为需要确认，不会自动做。每次决策都记在 设置 → 诊断 → 决策轨迹。默认关，会消耗一次每日模型额度。",
        agentOn,
    ) { agentOn = it; s.agentAutonomy = it }
}

@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun CompanionSettings(context: Context) {
    val app = PetApp.instance
    val s = app.settings
    val scope = rememberCoroutineScope()
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    Section("本机主动陪伴（实验）")
    var presenceOn by remember { mutableStateOf(s.presenceEnabled) }
    Toggle("本机主动陪伴",
        "打开后它一直在场：看你切换、停留、卡壳这些真实信号，先做小动作（不花额度），有依据时说一句简短提示（不调模型），复杂情境才调模型。" +
            "连续 3 次没回应会安静 30 分钟；点 × 收起也会安静 30 分钟。关掉就完全回到原来的开口方式。",
        presenceOn) { presenceOn = it; s.presenceEnabled = it }
    if (presenceOn) {
        var cap by remember { mutableIntStateOf(s.presenceDailyCap) }
        Text("每天主动开口最多（你主动找它、纯动画不算）：", style = MaterialTheme.typography.bodySmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            cn.companion.pet.core.presence.PresenceConfig.CAP_CHOICES.forEach { c ->
                if (c == cap) Button(onClick = {}, shape = CompanionShapes.button) { Text("$c 次") }
                else OutlinedButton(onClick = { cap = c; s.presenceDailyCap = c }, shape = CompanionShapes.button) { Text("$c 次") }
            }
        }
    }
    Section("开口节奏")
    var minI by remember { mutableStateOf(s.minIntervalMin.toString()) }
    var maxI by remember { mutableStateOf(s.maxIntervalMin.toString()) }
    var qs by remember { mutableStateOf((s.quietStartMin / 60).toString()) }
    var qe by remember { mutableStateOf((s.quietEndMin / 60).toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("最短间隔（分钟）", minI, Modifier.weight(1f)) { minI = it }
        NumberField("最长间隔（分钟）", maxI, Modifier.weight(1f)) { maxI = it }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("安静开始（点）", qs, Modifier.weight(1f)) { qs = it }
        NumberField("安静结束（点）", qe, Modifier.weight(1f)) { qe = it }
    }
    Button(onClick = {
        s.minIntervalMin = minI.toIntOrNull()?.coerceIn(1, 120) ?: s.minIntervalMin
        s.maxIntervalMin = maxI.toIntOrNull()?.coerceIn(s.minIntervalMin, 240) ?: s.maxIntervalMin
        s.quietStartMin = (qs.toIntOrNull()?.coerceIn(0, 23) ?: 0) * 60
        s.quietEndMin = (qe.toIntOrNull()?.coerceIn(0, 23) ?: 7) * 60
        toast("已保存")
    }) { Text("保存节奏") }

    var heartbeat by remember { mutableStateOf(s.heartbeatEnabled) }
    var hbInterval by remember { mutableStateOf(s.heartbeatIntervalMin.toString()) }
    Toggle("防跑马模式（锁屏心跳）", "锁屏期间定时轻轻震一下，亮屏后它问你刚才在想什么。默认关。", heartbeat) {
        heartbeat = it; s.heartbeatEnabled = it
    }
    if (heartbeat) NumberField("心跳间隔（分钟）", hbInterval) {
        hbInterval = it; it.toIntOrNull()?.let { v -> s.heartbeatIntervalMin = v.coerceIn(5, 120) }
    }

    Section("App 名单")
    Text("名单留空也能用：游荡靠切换频率判断，学习靠屏幕上有没有大段正文。", style = MaterialTheme.typography.bodySmall)
    AppListRow(context, "学习 App", "前台是这些时按学习场景聊屏幕内容", s.learningApps) { s.learningApps = it }
    AppListRow(context, "信息流 App", "前台是这些时只聊状态，不聊内容", s.feedApps) { s.feedApps = it }
    AppListRow(context, "专注 App", "连续用 15 分钟以上，开口间隔翻倍", s.focusApps) { s.focusApps = it }
    AppListRow(context, "排除 App", "前台是这些时不开口、不读屏", s.excludeApps) { s.excludeApps = it }

    Section("深聊用哪个 AI")
    val assistants = remember { Assistants(context, s) }
    var chosen by remember { mutableStateOf(assistants.current) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AssistantApp.entries.forEach { a ->
            val selected = a == chosen
            if (selected) Button(onClick = {}) { Text(a.label) }
            else OutlinedButton(onClick = { chosen = a; s.assistant = a.name }) { Text(a.label) }
        }
    }
    val found = remember(chosen) { chosen.resolve(assistants.installed(), assistants.override(chosen)) }
    Text(
        if (found != null) "已找到 ${chosen.label}：$found" else "没在手机上找到 ${chosen.label}。装了的话，在下面填它的包名。",
        style = MaterialTheme.typography.bodySmall,
    )
    var pkg by remember(chosen) { mutableStateOf(assistants.override(chosen)) }
    OutlinedTextField(pkg, { pkg = it; assistants.setOverride(chosen, it.trim()) }, Modifier.fillMaxWidth(),
        label = { Text("${chosen.label} 包名（留空自动找）") }, singleLine = true)
    var auto by remember { mutableStateOf(s.geminiAutoSend) }
    Toggle("自动发送（实验）", "打开 AI App 后用无障碍自动粘贴并点发送；3 秒找不到输入框就退回手动粘贴。", auto) {
        auto = it; s.geminiAutoSend = it
    }

    Section("AI 会话")
    Text("和 Gemini、ChatGPT 或千问聊天时，桌宠头上会出现蓝色的 G。文字深聊中切到别的 App，它先倒计时提醒，到点把你拉回原来的对话；“一起看”本来就要切出去看东西，只记录不拉回。点桌宠可以随时结束会话。",
        style = MaterialTheme.typography.bodySmall)
    Section("专注守护")
    Text("点桌宠 →“守护着去 …”：先建立守护再打开 AI App，守护期间离开会被管。时长到了自动结束；随时可以暂停 5 分钟或结束守护（紧急退出）。", style = MaterialTheme.typography.bodySmall)
    var strict by remember { mutableStateOf(s.focusEnforcement == "STRICT") }
    Toggle("严格守护（STRICT）", "默认关（只用气泡提醒）。打开后离开超过宽限就显示全屏拦截层，并自动尝试把你带回去（最多 3 次，每次都核验是否真的回去了）；拦截层上始终有暂停和紧急退出。", strict) {
        strict = it; s.focusEnforcement = if (it) "STRICT" else "SOFT"
    }
    var focusMinutesText by remember { mutableStateOf(s.focusMinutes.toString()) }
    NumberField("守护时长（分钟，5–180）", focusMinutesText) {
        focusMinutesText = it; it.toIntOrNull()?.let { v -> s.focusMinutes = v.coerceIn(5, 180) }
    }
    var autoStart by remember { mutableStateOf(s.sessionAutoStart) }
    Toggle("打开 AI App 自动开始", "自己打开 Gemini / ChatGPT / 千问 停留 ${s.sessionAutoStartSec} 秒，就算开始一次会话。从桌宠点“去 …”会直接开始。", autoStart) {
        autoStart = it; s.sessionAutoStart = it
    }
    var pull by remember { mutableStateOf(s.sessionPullBack) }
    Toggle("切出去时拉回", "关掉后只记录离开次数，不倒计时、不拉回。", pull) { pull = it; s.sessionPullBack = it }
    var grace by remember { mutableStateOf(s.sessionGraceSec.toString()) }
    var allowedGrace by remember { mutableStateOf(s.sessionAllowedGraceSec.toString()) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField("宽限（秒）", grace, Modifier.weight(1f)) {
            grace = it; it.toIntOrNull()?.let { v -> s.sessionGraceSec = v.coerceIn(3, 600) }
        }
        NumberField("允许的 App 宽限（秒）", allowedGrace, Modifier.weight(1f)) {
            allowedGrace = it; it.toIntOrNull()?.let { v -> s.sessionAllowedGraceSec = v.coerceIn(3, 3600) }
        }
    }
    AppListRow(context, "会话中允许的 App", "切到这些 App（如笔记、词典、要复制资料的阅读器）宽限更长；学习 App 也算", s.sessionAllowedApps) {
        s.sessionAllowedApps = it
    }

    Section("想一想")
    var skimOn by remember { mutableStateOf(s.skimEnabled) }
    Toggle("划过留痕", "滑动时把经过的段落记在内存里，点桌宠 → 想一想 → 划过了啥，就能回看。只留 10 分钟、熄屏就清空，不存文件；信息流 App、排除 App 和 AI App 里不记。", skimOn) {
        skimOn = it; s.skimEnabled = it
    }

    Section("其他")
    var tts by remember { mutableStateOf(s.ttsEnabled) }
    Toggle("语音播报", "用系统 TTS 念出它说的话。默认关，机械音会破坏伴生感。", tts) {
        tts = it; s.ttsEnabled = it
        if (!it) app.stopSpeech()
    }
    var sysVoice by remember { mutableStateOf(s.systemVoice) }
    Toggle("用系统语音识别（实验）",
        "默认关：按住角色时弹出输入框，点输入法上的话筒说话（比如豆包输入法），更稳。打开后改用系统语音识别；在后台或没有识别服务时会失败，失败时会说明原因。",
        sysVoice) { sysVoice = it; s.systemVoice = it }

    Section("连续性")
    var continuity by remember { mutableStateOf(s.continuityEnabled) }
    Toggle(
        "按相关召回 + 结构化记忆",
        "打开后，上下文只带与当前话题相关的记忆和最近交流，不再把整份档案塞进去；关掉则回到旧路径。默认关，先在调试里验证。",
        continuity,
    ) { continuity = it; s.continuityEnabled = it }
}

/** 人设（persona.md）：在“我的 → 人设”里编辑。 */
@Composable
@OptIn(ExperimentalLayoutApi::class)
internal fun PersonaSection(context: Context) {
    val app = PetApp.instance
    val scope = rememberCoroutineScope()
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    Text("它说话的口气、关心什么、不碰什么，都写在这里。留空恢复默认。", style = MaterialTheme.typography.bodySmall)
    var persona by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { persona = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { app.memory.persona() } } }
    OutlinedTextField(persona, { persona = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text("persona.md") })
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val text = persona
            scope.launch { withContext(Dispatchers.IO) { synchronized(app.memoryLock) { app.memory.savePersona(text) } }; toast("人设已保存") }
        }) { Text("保存人设") }
        OutlinedButton(onClick = {
            scope.launch {
                persona = withContext(Dispatchers.IO) {
                    synchronized(app.memoryLock) { app.memory.savePersona(""); app.memory.persona() }
                }
            }
        }) { Text("恢复默认") }
    }

}

@Composable
private fun DebugSettings(context: Context) {
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()
    Section("调试")
    OutlinedButton(onClick = { NightlyWorker.runNow(context); toast("已排队，联网时会整理一次") }) { Text("现在整理一次记忆") }
}

/** 模型列表：最多显示 40 个，点一下填进模型名。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelChoices(models: List<String>, note: String?, highlight: (String) -> Boolean, onPick: (String) -> Unit) {
    note?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    if (models.isEmpty()) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        models.take(40).forEach { m ->
            TextButton(onClick = { onPick(m) }) {
                Text(m, style = if (highlight(m)) MaterialTheme.typography.labelLarge.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold)
                else MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun Section(title: String) {
    GroupTitle(title)
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit)) }, modifier.fillMaxWidth(), label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun Toggle(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    ToggleRow(title = title, description = desc, checked = value, onChange = onChange)
}

@Composable
private fun AppListRow(context: Context, title: String, desc: String, current: Set<String>, onSave: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(current) }
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().clickable { open = true }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("$title（${selected.size}）", style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        TextButton(onClick = { open = true }) { Text("选择") }
    }
    if (open) AppPicker(context, title, selected, onDismiss = { open = false }) {
        selected = it; onSave(it); open = false
    }
}

@Composable
private fun AppPicker(context: Context, title: String, initial: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    var apps by remember { mutableStateOf<List<Pair<String, String>>>(emptyList()) }
    var chosen by remember { mutableStateOf(initial) }
    var query by remember { mutableStateOf("") }
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
            pm.queryIntentActivities(intent, 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .filter { it.first != context.packageName }
                .distinctBy { it.first }
                .sortedWith(compareByDescending<Pair<String, String>> { it.first in initial }.thenBy { it.second })
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth(), label = { Text("搜索") }, singleLine = true)
                LazyColumn(Modifier.heightIn(max = 380.dp)) {
                    items(apps.filter { query.isBlank() || it.second.contains(query, true) || it.first.contains(query, true) }, key = { it.first }) { (pkg, label) ->
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (pkg in chosen) chosen - pkg else chosen + pkg },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(pkg in chosen, { chosen = if (it) chosen + pkg else chosen - pkg })
                            Text(label)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDone(chosen) }) { Text("完成") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun AgentTracePreview() {
    val app = PetApp.instance
    var tick by remember { mutableIntStateOf(0) }
    var traces by remember { mutableStateOf<List<AgentTrace>>(emptyList()) }
    LaunchedEffect(tick) { traces = withContext(Dispatchers.IO) { app.agentTrace.recent(5) } }
    if (traces.isEmpty()) {
        Text("还没有决策轨迹。开启自主模式并保存一条素材/念头后，这里会出现摘要。", style = MaterialTheme.typography.bodySmall)
    } else {
        traces.forEach { t ->
            Text(t.status + " · " + (t.tool ?: "no_action") + " · " + (t.resultReason.ifBlank { t.decisionReason }).take(100), style = MaterialTheme.typography.bodySmall)
        }
    }
    TextButton(onClick = { tick++ }) { Text("刷新轨迹") }
}

@Composable
private fun ScreenDiagnosticSection(context: Context) {
    val app = PetApp.instance
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf("尚未请求截图") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var visionSummary by remember { mutableStateOf<String?>(null) }
    var upload by remember { mutableStateOf(app.settings.visionUploadEnabled) }
    var setupTick by remember { mutableIntStateOf(0) }
    var setupLabel by remember { mutableStateOf("读取中…") }
    var lastLook by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(setupTick) {
        setupLabel = withContext(Dispatchers.IO) { app.visionSetup().label() }
        lastLook = withContext(Dispatchers.IO) { app.visionEvidence.last() }?.let { e ->
            java.time.Instant.ofEpochMilli(e.at).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0).toString() + " " + e.label()
        }
    }
    GroupTitle("看图（看一眼）")
    Text("用法：在要看的 App 里点一下桌宠 → “看一眼”。它截一张图发给下面的视觉模型，回一句“我看了一眼：…”。", style = MaterialTheme.typography.bodySmall)
    Panel {
        Text("看图状态：" + setupLabel, style = MaterialTheme.typography.bodyMedium, color = LocalCompanionColors.current.ink,
            modifier = Modifier.testTag("vision_status"))
        Text("上传截图：" + (if (upload) "已允许" else "未允许（下面的开关）"), style = MaterialTheme.typography.bodySmall,
            color = LocalCompanionColors.current.ink2)
        Text("上次看一眼：" + (lastLook ?: "还没有记录"), style = MaterialTheme.typography.bodySmall,
            color = LocalCompanionColors.current.ink2, modifier = Modifier.testTag("vision_last"))
        TextButton(onClick = { setupTick++ }) { Text("刷新状态") }
    }
    // 视觉服务可以和搭话分开：搭话用 DeepSeek、看图用千问。地址留空沿用搭话的；换了服务商要填它自己的 Key。
    var visionEndpoint by remember { mutableStateOf(app.settings.visionEndpoint) }
    var visionModel by remember { mutableStateOf(app.settings.visionModel) }
    var visionKey by remember { mutableStateOf("") }
    Text("看图的模型：搭话模型（比如 DeepSeek）多半不能看图，要另选一个。点一下填好，再填对应服务商的 Key。",
        style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedButton(onClick = { visionEndpoint = "https://dashscope.aliyuncs.com/compatible-mode/v1"; visionModel = "qwen-vl-plus" }) { Text("千问看图") }
        OutlinedButton(onClick = { visionEndpoint = "https://api.openai.com/v1"; visionModel = "gpt-4.1-mini" }) { Text("GPT 看图") }
    }
    OutlinedTextField(visionEndpoint, { visionEndpoint = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("视觉接口地址（留空 = 和搭话相同）") },
        isError = visionEndpoint.isNotBlank() && !EndpointPolicy.isValid(visionEndpoint))
    OutlinedTextField(visionModel, { visionModel = it }, Modifier.fillMaxWidth(), singleLine = true,
        label = { Text("视觉模型名（必填，例如 qwen-vl-plus）") })
    var visionModels by remember { mutableStateOf<List<String>>(emptyList()) }
    var visionModelsNote by remember { mutableStateOf<String?>(null) }
    TextButton(onClick = {
        visionModelsNote = "正在获取…"
        scope.launch {
            val k = visionKey.ifBlank {
                withContext(Dispatchers.IO) {
                    val saved = (app.visionSecrets.read() as? cn.companion.pet.data.SecretReadResult.Available)?.value
                    cn.companion.pet.core.vision.VisionSetupResolver.resolve("probe", visionEndpoint, app.settings.endpoint, saved, app.apiKey())
                        .config?.apiKey.orEmpty()
                }
            }
            if (k.isBlank()) { visionModelsNote = "先填这个服务商的视觉 Key"; return@launch }
            when (val r = app.modelLister.list(visionEndpoint.ifBlank { app.settings.endpoint }, k)) {
                is cn.companion.pet.net.ModelLister.Result.Success -> {
                    visionModels = r.models.sortedByDescending { cn.companion.pet.net.ModelLister.looksVisual(it) }
                    visionModelsNote = "加粗的名字看起来能看图（只是按名字猜）。点一个填进上面。"
                }
                is cn.companion.pet.net.ModelLister.Result.Failure -> { visionModels = emptyList(); visionModelsNote = "没取到：" + r.reason }
            }
        }
    }) { Text("获取模型列表") }
    ModelChoices(visionModels, visionModelsNote, highlight = { cn.companion.pet.net.ModelLister.looksVisual(it) }) { visionModel = it }
    OutlinedTextField(visionKey, { visionKey = it }, Modifier.fillMaxWidth(), singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        label = { Text("视觉 Key（看图和搭话不是同一家时必填；已保存的不显示）") })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = {
            if (visionEndpoint.isNotBlank() && !EndpointPolicy.isValid(visionEndpoint)) {
                android.widget.Toast.makeText(context, "视觉接口地址要是 https 开头", android.widget.Toast.LENGTH_SHORT).show()
                return@OutlinedButton
            }
            app.settings.visionEndpoint = visionEndpoint.trim()
            app.settings.visionModel = visionModel.trim()
            val k = visionKey
            scope.launch {
                if (k.isNotBlank()) withContext(Dispatchers.IO) { app.visionSecrets.save(k) }
                visionKey = ""
                setupTick++
                android.widget.Toast.makeText(context, "已保存看图设置", android.widget.Toast.LENGTH_SHORT).show()
            }
        }) { Text("保存看图设置") }
        TextButton(onClick = {
            scope.launch {
                withContext(Dispatchers.IO) { app.visionSecrets.clear() }
                setupTick++
                android.widget.Toast.makeText(context, "已清除视觉 Key", android.widget.Toast.LENGTH_SHORT).show()
            }
        }) { Text("清除视觉 Key") }
    }
    Toggle("允许把截图发给视觉模型", "默认关。打开后，仅在点击“看一眼”并成功截图后，按一次全局预算发送给视觉模型；排除/敏感窗口不上传。", upload) {
        upload = it; app.settings.visionUploadEnabled = it
    }
    var marksOn by remember { mutableStateOf(app.settings.visionMarks) }
    Toggle("截图上给按钮标号", "看一眼时在截图上用红框和数字标出能点/能写的元素，并把同样的编号表发给视觉模型，它说“[3] 发送”就能对上是哪个按钮。只用屏幕上本来就显示的文字。", marksOn) {
        marksOn = it; app.settings.visionMarks = it
    }
    GroupTitle("截图权限检查")
    Text("只检查本机能不能截图，不看别的 App：此刻前台是桌宠自己的设置页，会被拒绝（NO_ACTIVE_WINDOW），这是正常的。截图只在本机显示，不落盘。", style = MaterialTheme.typography.bodySmall)
    Button(enabled = !busy, onClick = {
        if (busy) return@Button
        busy = true
        status = "正在请求系统截图…"
        scope.launch {
            val attempt = withContext(Dispatchers.IO) {
                val now = System.currentTimeMillis()
                val pkg = PerceptionHub.trail.foreground
                val policy = ScreenCapturePolicy.canCapture(pkg, context.packageName, app.settings.excludeApps, now, 0L, false)
                if (policy != CapturedStatus.OK) ScreenCaptureAttempt(policy, error = "当前窗口不允许截图")
                else {
                    val executor = Executors.newSingleThreadExecutor()
                    try {
                        ScreenCaptureGateway.captureBlocking(PerceptionHub.service, executor, 5_000)
                    } finally {
                        executor.shutdown()
                    }
                }
            }
            val jpeg = attempt.jpeg
            if (attempt.status == CapturedStatus.OK && jpeg != null) {
                val bitmap = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                preview = bitmap
                status = "原生截图成功：" + attempt.width + "×" + attempt.height + "，" + jpeg.size + " bytes"
                var visionText = ""
                if (upload) {
                    val day = java.time.LocalDate.now().toString()
                    if (app.budget.reserve(app.settings.dailyLimit, day)) {
                        cn.companion.pet.data.Stats.Kind.API_CALLS.let { app.stats.add(it) }
                        visionSummary = withContext(Dispatchers.IO) { app.visionAnalyzer.analyze(jpeg) }?.subject
                        visionText = visionSummary ?: "视觉模型未返回可解析摘要"
                    } else {
                        visionText = "今日模型额度不足，未上传"
                    }
                }
                app.screenObservations.publish(ScreenObservation(
                    id = java.util.UUID.randomUUID().toString(), capturedAt = System.currentTimeMillis(),
                    packageName = attempt.packageName.ifBlank { "unknown" }, source = ObservationSource.SCREENSHOT,
                    screenshotAvailable = true, nodesAvailable = false, visionAvailable = upload && visionText.isNotBlank(),
                    status = CapturedStatus.OK, summary = visionText.ifBlank { "用户点击诊断截图" },
                    expiresAt = System.currentTimeMillis() + 60_000,
                ))
            } else {
                status = "截图未完成：" + attempt.status.name + (if (attempt.error.isBlank()) "" else " · " + attempt.error)
            }
            busy = false
        }
    }) { Text("检查截图权限") }
    Text(status, style = MaterialTheme.typography.bodySmall)
    preview?.let { bmp ->
        Image(bmp.asImageBitmap(), contentDescription = "本机截图预览", modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
        TextButton(onClick = { preview?.recycle(); preview = null }) { Text("关闭预览") }
    }
    visionSummary?.let { Text("视觉摘要：" + it, style = MaterialTheme.typography.bodySmall) }
    Text("截图成功 ≠ 模型已理解；上传开关关闭时不会请求视觉模型。", style = MaterialTheme.typography.bodySmall)
}

/** 技能：固定步骤，按元素回放（参考 JARVIS Skills v3）。点/写的步骤在桌宠上挑元素录，这里管理和补简单步骤。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SkillsSection(context: Context) {
    val app = PetApp.instance
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var skills by remember { mutableStateOf<List<cn.companion.pet.core.skill.Skill>>(emptyList()) }
    var name by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { skills = withContext(Dispatchers.IO) { app.skillStore.list() } }
    fun io(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { block() }; tick++ }
    GroupTitle("技能")
    Text("把常用操作存成固定步骤，一键重放。录法：在要用的 App 里点桌宠 → 技能 → 录新技能，再一步步点桌宠挑要点的按钮/要写的输入框（会边录边帮你做）。" +
        "回放时按元素找，找不到就停下并告诉你哪一步，不会乱点。只能操作 设置 → 设备 →“允许 Agent 操作的应用”里的 App。", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(name, { name = it }, Modifier.weight(1f), label = { Text("新技能名字") }, singleLine = true)
        Button(onClick = {
            val n = name.trim(); if (n.isEmpty()) return@Button
            io { app.skillStore.create(n, System.currentTimeMillis()) }; name = ""
        }) { Text("新建") }
    }
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    app.skillStore.lastError?.let { Text("技能存储：" + it, style = MaterialTheme.typography.bodySmall) }
    if (skills.isEmpty()) Text("还没有技能。", style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("skills_empty"))
    skills.forEach { skill ->
        var manual by remember(skill.id) { mutableStateOf("") }
        var pickApp by remember(skill.id) { mutableStateOf(false) }
        Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
            Text(skill.name, style = MaterialTheme.typography.titleSmall)
            val last = if (skill.lastRunAt == 0L) "还没运行过" else
                "上次" + (if (skill.lastRunOk == true) "成功" else "没成功") + " · " +
                    java.time.Instant.ofEpochMilli(skill.lastRunAt).atZone(java.time.ZoneId.systemDefault()).toLocalTime().withNano(0)
            Text(skill.steps.size.toString() + " 步 · " + last, style = MaterialTheme.typography.bodySmall)
            skill.steps.forEachIndexed { i, step ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text((i + 1).toString() + ". " + step.describe(), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = { io { app.skillStore.moveStep(skill.id, step.id, -1, System.currentTimeMillis()) } }, enabled = i > 0) { Text("▲") }
                    TextButton(onClick = { io { app.skillStore.moveStep(skill.id, step.id, 1, System.currentTimeMillis()) } }, enabled = i < skill.steps.size - 1) { Text("▼") }
                    TextButton(onClick = { io { app.skillStore.removeStep(skill.id, step.id, System.currentTimeMillis()) } }) { Text("删") }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                fun add(step: cn.companion.pet.core.skill.SkillStep) = io { app.skillStore.addStep(skill.id, step, System.currentTimeMillis()) }
                fun id() = java.util.UUID.randomUUID().toString()
                OutlinedButton(onClick = { pickApp = true }) { Text("+打开应用") }
                OutlinedButton(onClick = { add(cn.companion.pet.core.skill.SkillStep(id(), cn.companion.pet.core.skill.SkillStepType.BACK)) }) { Text("+返回") }
                OutlinedButton(onClick = { add(cn.companion.pet.core.skill.SkillStep(id(), cn.companion.pet.core.skill.SkillStepType.SCROLL, forward = true)) }) { Text("+往下滚") }
                OutlinedButton(onClick = { add(cn.companion.pet.core.skill.SkillStep(id(), cn.companion.pet.core.skill.SkillStepType.SCROLL, forward = false)) }) { Text("+往上滚") }
                OutlinedButton(onClick = { add(cn.companion.pet.core.skill.SkillStep(id(), cn.companion.pet.core.skill.SkillStepType.WAIT, waitMs = 2_000)) }) { Text("+等2秒") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(manual, { manual = it }, Modifier.weight(1f), singleLine = true,
                    label = { Text("按文字点（例如“发送”）") })
                OutlinedButton(onClick = {
                    val t = manual.trim(); if (t.isEmpty()) return@OutlinedButton
                    io { app.skillStore.addStep(skill.id, cn.companion.pet.core.skill.SkillStep(java.util.UUID.randomUUID().toString(),
                        cn.companion.pet.core.skill.SkillStepType.TAP, target = cn.companion.pet.core.skill.ElementTarget(text = t)), System.currentTimeMillis()) }
                    manual = ""
                }) { Text("+点") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = {
                    val launcher = app.skillLauncher
                    message = if (launcher == null) "先在“今天”页启动桌宠，技能由桌宠来跑。" else {
                        launcher(skill.id); "已交给桌宠运行「${skill.name}」：进度看桌宠气泡，点桌宠可以停。"
                    }
                }, enabled = skill.steps.isNotEmpty()) { Text("▶ 运行") }
                TextButton(onClick = { io { app.skillStore.delete(skill.id) } }) { Text("删除技能") }
            }
            if (skill.lastRunLog.isNotEmpty()) {
                Text("上次运行：\n" + skill.lastRunLog.joinToString("\n"), style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
        if (pickApp) AppPicker(context, "要打开的应用（选一个）", emptySet(), onDismiss = { pickApp = false }) { chosen ->
            pickApp = false
            chosen.firstOrNull()?.let { pkg ->
                val label = runCatching { context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                io { app.skillStore.addStep(skill.id, cn.companion.pet.core.skill.SkillStep(java.util.UUID.randomUUID().toString(),
                    cn.companion.pet.core.skill.SkillStepType.OPEN_APP, appPackage = pkg, appLabel = label), System.currentTimeMillis()) }
            }
        }
    }
}

@Composable
internal fun GoalSection() {
    val app = PetApp.instance
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var goals by remember { mutableStateOf<List<Goal>>(emptyList()) }
    var tasks by remember { mutableStateOf<List<cn.companion.pet.core.goal.Task>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        withContext(Dispatchers.IO) { app.goalStore.state() }.let { goals = it.goals; tasks = it.tasks }
        plan = withContext(Dispatchers.IO) {
            val p = app.goalSupervisor.decide(System.currentTimeMillis(),
                app.budget.remaining(app.settings.dailyLimit, java.time.LocalDate.now().toString()))
            p.decision.name + " · " + p.reason
        }
    }
    GroupTitle("长期目标（实验）")
    Text("给目标加上具体的一步，它才会在你记念头/存素材时按这一步去做；只读步骤可自动执行，写步骤会先问你。目标完成要你自己确认。", style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(text, { text = it }, Modifier.fillMaxWidth(), label = { Text("想让它长期关注什么") })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(onClick = {
            val value = text.trim(); if (value.isBlank()) return@Button
            scope.launch {
                withContext(Dispatchers.IO) { app.goalStore.addGoal(value, now = System.currentTimeMillis()) }
                text = ""; tick++
            }
        }) { Text("添加目标") }
        TextButton(onClick = { tick++ }) { Text("刷新") }
    }
    plan?.let { Text("下一步：" + it, style = MaterialTheme.typography.bodySmall) }
    app.goalStore.lastError?.let { Text("目标存储：" + it, style = MaterialTheme.typography.bodySmall) }
    if (app.goalStore.status == cn.companion.pet.core.goal.GoalStoreStatus.CORRUPT) {
        Text("目标文件损坏，已停止写入；不会把损坏当成空目标。", style = MaterialTheme.typography.bodySmall)
    }
    if (goals.isEmpty()) {
        Text("还没有目标。在上面写一个并点“添加目标”，再给它加一步，它才会开始跟进。", style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("goals_empty"))
    }
    goals.forEach { goal ->
        GoalCard(goal, tasks.filter { it.goalId == goal.id }) { tick++ }
    }
}

/** 一个目标：下一步列表 + 加步骤 + 重试/标记完成；目标完成要用户自己点。 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun GoalCard(goal: Goal, tasks: List<cn.companion.pet.core.goal.Task>, changed: () -> Unit) {
    val app = PetApp.instance
    val scope = rememberCoroutineScope()
    var step by remember(goal.id) { mutableStateOf("") }
    fun io(block: () -> Unit) = scope.launch { withContext(Dispatchers.IO) { block() }; changed() }
    Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(goal.text, style = MaterialTheme.typography.bodyMedium)
                Text(goal.status.name, style = MaterialTheme.typography.bodySmall)
            }
            if (goal.status == GoalStatus.ACTIVE || goal.status == GoalStatus.PAUSED) {
                TextButton(onClick = {
                    io { app.goalStore.setGoalStatus(goal.id,
                        if (goal.status == GoalStatus.ACTIVE) GoalStatus.PAUSED else GoalStatus.ACTIVE, System.currentTimeMillis()) }
                }) { Text(if (goal.status == GoalStatus.ACTIVE) "暂停" else "恢复") }
            }
            TextButton(onClick = { io { app.goalStore.deleteGoal(goal.id, System.currentTimeMillis()) } }) { Text("删除") }
        }
        tasks.forEach { task ->
            Text("· " + task.nextStep + "（" + task.status.name + "）" + (if (task.evidence.isBlank()) "" else " " + task.evidence),
                style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (task.status == cn.companion.pet.core.goal.TaskStatus.BLOCKED) {
                    TextButton(onClick = { io { app.goalStore.setTaskStatus(task.id, cn.companion.pet.core.goal.TaskStatus.PENDING,
                        "你点了重试", System.currentTimeMillis(), wakeCondition = "") } }) { Text("重试") }
                }
                if (task.status != cn.companion.pet.core.goal.TaskStatus.DONE) {
                    TextButton(onClick = { io { app.goalStore.setTaskStatus(task.id, cn.companion.pet.core.goal.TaskStatus.DONE,
                        "你确认这一步已完成", System.currentTimeMillis(), wakeCondition = "") } }) { Text("这步已完成") }
                }
            }
        }
        if (goal.status == GoalStatus.ACTIVE) {
            if (tasks.isNotEmpty() && tasks.all { it.status == cn.companion.pet.core.goal.TaskStatus.DONE }) {
                Button(onClick = { io { app.goalStore.setGoalStatus(goal.id, GoalStatus.COMPLETED, System.currentTimeMillis()) } }) {
                    Text("步骤都完成了，确认目标完成")
                }
            }
            OutlinedTextField(step, { step = it }, Modifier.fillMaxWidth(), label = { Text("加一步（它只会用你选的那一种工具）") })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple("查记忆", "READ_MEMORY", "read"),
                    Triple("看素材", "READ_MATERIALS", "read"),
                    Triple("记念头（要你确认）", "SAVE_NOTE", "write"),
                ).forEach { (label, kind, risk) ->
                    OutlinedButton(onClick = {
                        val value = step.trim(); if (value.isBlank()) return@OutlinedButton
                        io { app.goalStore.addTask(goal.id, value, kind, risk, now = System.currentTimeMillis()) }
                        step = ""
                    }) { Text(label) }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
internal fun PendingActionSection() {
    val app = PetApp.instance
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<PendingAction>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { items = withContext(Dispatchers.IO) { app.pendingActions.list(System.currentTimeMillis()) } }
    GroupTitle("待确认动作")
    Text("模型提议写动作/打开/朗读时，会先在桌宠气泡上问你；没来得及点的也留在这里。允许后仍会重新检查前台、白名单和观察时效。" +
        "点击、输入这类动作最好在气泡上确认：在这里确认时前台已经不是原来的 App，多半会被拒绝。", style = MaterialTheme.typography.bodySmall)
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    app.pendingActions.lastError?.let { Text("待确认存储：" + it, style = MaterialTheme.typography.bodySmall) }
    if (items.none { it.status == PendingStatus.PENDING }) {
        Text("现在没有要你确认的动作。打开“自主模式”并给目标加一个写步骤（比如“记念头”），它提议时会出现在这里。",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.testTag("pending_empty"))
    }
    items.forEach { item ->
        Column(Modifier.fillMaxWidth()) {
            val label = when (item.status) {
                PendingStatus.INTERRUPTED -> "批准后程序中断，结果未知（不会自动重做，请自己核对）"
                else -> item.status.name
            }
            Text(item.kind.name + " · " + label, style = MaterialTheme.typography.bodyMedium)
            Text(item.preview, style = MaterialTheme.typography.bodySmall)
            item.result?.let { Text("回执：" + it.status.name + " · " + it.reason, style = MaterialTheme.typography.bodySmall) }
            if (item.status == PendingStatus.APPROVED || item.status == PendingStatus.INTERRUPTED) {
                OutlinedButton(onClick = {
                    scope.launch {
                        val reopened = withContext(Dispatchers.IO) {
                            app.pendingActions.reopen(item.id, System.currentTimeMillis())
                        }
                        message = if (reopened == null) "无法重开：状态已变化" else "已重开，需要重新确认"
                        tick++
                    }
                }) { Text("重新确认") }
            }
            if (item.status == PendingStatus.PENDING) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = {
                        scope.launch {
                            val outcome = withContext(Dispatchers.IO) {
                                app.pendingExecutor.approveAndRun(item.id, System.currentTimeMillis(),
                                    cn.companion.pet.a11y.PerceptionHub.trail.foreground, item.nonce)
                            }
                            message = when (outcome) {
                                is cn.companion.pet.core.action.PendingExecutor.Outcome.Rejected -> "未执行：" + outcome.reason
                                is cn.companion.pet.core.action.PendingExecutor.Outcome.Executed ->
                                    outcome.result.kind.name + " · " + outcome.result.status.name + " · " + outcome.result.reason
                            }
                            tick++
                        }
                    }) { Text("允许本次") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { app.pendingExecutor.reject(item.id) }
                            message = "已拒绝"; tick++
                        }
                    }) { Text("拒绝") }
                }
            }
        }
        HorizontalDivider()
    }
}

@Composable
private fun DiagnosticSection(context: Context) {
    val app = PetApp.instance
    val scope = rememberCoroutineScope()
    var report by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    GroupTitle("设备诊断 / 验收")
    Text("生成脱敏报告；不包含 Key、截图/Base64、聊天原文、输入文本。", style = MaterialTheme.typography.bodySmall)
    Button(enabled = !busy, onClick = {
        busy = true
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                val info = context.packageManager.getPackageInfo(context.packageName, 0)
                val obs = app.screenObservations.latest()
                val pkg = cn.companion.pet.a11y.PerceptionHub.trail.foreground
                val capability = cn.companion.pet.core.device.ShizukuCapabilityRunner(
                    AndroidShizukuProbe(context), AndroidShizukuCommandPort()).readSdkDiagnostic()
                val history = app.actions.history(1).firstOrNull()
                DiagnosticReport.render(DiagnosticInputs(
                    versionName = info.versionName ?: "?",
                    versionCode = if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
                    now = System.currentTimeMillis(),
                    accessibilityConnected = cn.companion.pet.a11y.PerceptionHub.service != null,
                    screenshotStatus = obs?.status?.name ?: "NOT_CAPTURED",
                    screenshotReason = obs?.summary.orEmpty().take(80),
                    visionConfigured = app.visionConfig() != null,
                    visionUploadEnabled = app.settings.visionUploadEnabled,
                    budgetRemaining = app.budget.remaining(app.settings.dailyLimit, java.time.LocalDate.now().toString()),
                    foregroundPackage = pkg,
                    foregroundAllowed = pkg != null && pkg in app.settings.deviceAllowedApps,
                    shizukuStatus = AndroidShizukuProbe(context).diagnostic().status.name,
                    capabilityStatus = capability.status.name,
                    capabilityReason = capability.reason.take(80),
                    autonomyEnabled = app.settings.agentAutonomy,
                    goalStatus = app.goalStore.status.name,
                    pendingCount = app.pendingActions.list(System.currentTimeMillis()).count { it.status == PendingStatus.PENDING },
                    lastAction = history?.let { it.kind.name + " " + (it.status?.name ?: "已准备") } ?: "无",
                    visionSetup = app.visionSetup().label(),
                    lastVision = app.visionEvidence.last()?.label().orEmpty(),
                    batteryUnrestricted = runCatching { Permissions.batteryUnrestricted(context) }.getOrNull(),
                ))
            }
            report = text
            busy = false
        }
    }) { Text("生成脱敏诊断") }
    report?.let { text ->
        Text(text, style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = {
            val cm = context.getSystemService(ClipboardManager::class.java)
            cm?.setPrimaryClip(ClipData.newPlainText("Companion Pet diagnostic", text))
        }) { Text("复制报告") }
    }
}