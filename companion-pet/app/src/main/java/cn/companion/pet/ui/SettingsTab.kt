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
import cn.companion.pet.core.action.ActionRequest
import cn.companion.pet.data.SecretWriteResult
import cn.companion.pet.bridge.Assistants
import cn.companion.pet.core.session.AssistantApp
import cn.companion.pet.net.EndpointPolicy
import cn.companion.pet.net.ModelProvider
import cn.companion.pet.ui.components.PageHeader
import cn.companion.pet.ui.theme.CompanionSpacing
import cn.companion.pet.ui.components.GroupTitle
import cn.companion.pet.ui.components.ToggleRow
import cn.companion.pet.work.NightlyWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
@OptIn(ExperimentalLayoutApi::class)
fun SettingsTab(context: Context) {
    val app = PetApp.instance
    val s = app.settings
    val scope = rememberCoroutineScope()
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = CompanionSpacing.page, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        PageHeader("设置", "连接、陪伴、感知、深聊、表现。改动保存后立即生效。")
        Section("能力")
        OutlinedButton(onClick = { context.startActivity(Intent(context, cn.companion.pet.CapabilityActivity::class.java)) }) {
            Text("打开能力面板")
        }
        Section("自主模式（实验）")
        var agentOn by remember { mutableStateOf(s.agentAutonomy) }
        Toggle(
            "让模型自己决定用一次工具",
            "开启后，新素材或用户念头会触发一次模型决策。只自动执行读取记忆/素材；保存念头、打开 AI 应用、朗读只记为需要确认，不会自动做。每次决策都记入下方轨迹。默认关，会消耗一次每日模型额度。",
            agentOn,
        ) { agentOn = it; s.agentAutonomy = it }
        AgentTracePreview()
        ScreenDiagnosticSection(context)
        Section("设备操作（A3）")
        val shizuku = remember { AndroidShizukuProbe(context) }
        val capability = remember { ShizukuCapabilityRunner(shizuku, AndroidShizukuCommandPort()) }
        var shizukuText by remember { mutableStateOf(shizuku.diagnostic().detail.ifBlank { shizuku.diagnostic().status.name }) }
        Text("Shizuku 只用于状态/授权，不执行任意 Shell。未安装/未运行时，Intent 和无障碍动作仍可用。", style = MaterialTheme.typography.bodySmall)
        Text("Shizuku：" + shizukuText, style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
        AppListRow(context, "允许 Agent 操作的应用", "空名单时拒绝所有设备动作；只打开/点击/输入这些应用。", s.deviceAllowedApps) {
            s.deviceAllowedApps = it
        }
        GoalSection()
        PendingActionSection()
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
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (app.apiKey() != null) "API Key（已保存，留空不改）" else "API Key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        NumberField("每日调用上限", limit) { limit = it }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                if (!EndpointPolicy.isValid(endpoint)) { toast("接口地址要是 https 开头"); return@Button }
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
                scope.launch { withContext(Dispatchers.IO) { app.secrets.clear() }; app.invalidateKey(); toast("已清除 Key") }
            }) { Text("清除 Key") }
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

        Section("连续性")
        var continuity by remember { mutableStateOf(s.continuityEnabled) }
        Toggle(
            "按相关召回 + 结构化记忆",
            "打开后，上下文只带与当前话题相关的记忆和最近交流，不再把整份档案塞进去；关掉则回到旧路径。默认关，先在调试里验证。",
            continuity,
        ) { continuity = it; s.continuityEnabled = it }

        Section("人设")
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

        Section("调试")
        OutlinedButton(onClick = { NightlyWorker.runNow(context); toast("已排队，联网时会整理一次") }) { Text("现在整理一次记忆") }
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
    GroupTitle("看一眼（诊断）")
    Text("只在点击时向系统请求一张原生截图，默认只在本机显示；不上传、不落盘。", style = MaterialTheme.typography.bodySmall)
    Toggle("允许把截图发给视觉模型", "默认关。打开后，仅在点击“看一眼”并成功截图后，按一次全局预算发送给视觉模型；排除/敏感窗口不上传。", upload) {
        upload = it; app.settings.visionUploadEnabled = it
    }
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
                    packageName = PerceptionHub.trail.foreground ?: "unknown", source = ObservationSource.SCREENSHOT,
                    screenshotAvailable = true, nodesAvailable = false, visionAvailable = upload && visionText.isNotBlank(),
                    status = CapturedStatus.OK, summary = visionText.ifBlank { "用户点击诊断截图" },
                    expiresAt = System.currentTimeMillis() + 60_000,
                ))
            } else {
                status = "截图未完成：" + attempt.status.name + (if (attempt.error.isBlank()) "" else " · " + attempt.error)
            }
            busy = false
        }
    }) { Text("看一眼（原生截图）") }
    Text(status, style = MaterialTheme.typography.bodySmall)
    preview?.let { bmp ->
        Image(bmp.asImageBitmap(), contentDescription = "本机截图预览", modifier = Modifier.fillMaxWidth().heightIn(max = 280.dp), contentScale = ContentScale.Fit)
        TextButton(onClick = { preview?.recycle(); preview = null }) { Text("关闭预览") }
    }
    visionSummary?.let { Text("视觉摘要：" + it, style = MaterialTheme.typography.bodySmall) }
    Text("截图成功 ≠ 模型已理解；上传开关关闭时不会请求视觉模型。", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun GoalSection() {
    val app = PetApp.instance
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var goals by remember { mutableStateOf<List<Goal>>(emptyList()) }
    var text by remember { mutableStateOf("") }
    var plan by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) {
        goals = withContext(Dispatchers.IO) { app.goalStore.state().goals }
        plan = withContext(Dispatchers.IO) {
            val p = app.goalSupervisor.decide(System.currentTimeMillis(),
                app.budget.remaining(app.settings.dailyLimit, java.time.LocalDate.now().toString()))
            p.decision.name + " · " + p.reason
        }
    }
    GroupTitle("长期目标（实验）")
    Text("目标只保存计划和状态，不会自动执行高风险操作；关闭/删除后不再被旧调用复活。", style = MaterialTheme.typography.bodySmall)
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
    goals.forEach { goal ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text(goal.text, style = MaterialTheme.typography.bodyMedium)
                Text(goal.status.name, style = MaterialTheme.typography.bodySmall)
            }
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) {
                        app.goalStore.setGoalStatus(goal.id,
                            if (goal.status == GoalStatus.ACTIVE) GoalStatus.PAUSED else GoalStatus.ACTIVE,
                            System.currentTimeMillis())
                    }
                    tick++
                }
            }) { Text(if (goal.status == GoalStatus.ACTIVE) "暂停" else "恢复") }
            TextButton(onClick = {
                scope.launch {
                    withContext(Dispatchers.IO) { app.goalStore.deleteGoal(goal.id, System.currentTimeMillis()) }
                    tick++
                }
            }) { Text("删除") }
        }
    }
}

@Composable
private fun PendingActionSection() {
    val app = PetApp.instance
    val scope = rememberCoroutineScope()
    var tick by remember { mutableIntStateOf(0) }
    var items by remember { mutableStateOf<List<PendingAction>>(emptyList()) }
    var message by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(tick) { items = withContext(Dispatchers.IO) { app.pendingActions.list(System.currentTimeMillis()) } }
    GroupTitle("待确认动作")
    Text("模型提议写动作/打开/朗读时，先停在这里；默认不执行。允许后仍会重新检查前台、白名单和观察时效。", style = MaterialTheme.typography.bodySmall)
    message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
    items.forEach { item ->
        Column(Modifier.fillMaxWidth()) {
            Text(item.kind.name + " · " + item.status.name, style = MaterialTheme.typography.bodyMedium)
            Text(item.preview, style = MaterialTheme.typography.bodySmall)
            if (item.status == PendingStatus.APPROVED) {
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
                            val approved = withContext(Dispatchers.IO) {
                                app.pendingActions.approve(item.id, System.currentTimeMillis(),
                                    cn.companion.pet.a11y.PerceptionHub.trail.foreground)
                            }
                            if (approved == null) { message = "未批准：已过期或前台已切换"; tick++ } else {
                                val result = withContext(Dispatchers.IO) {
                                    app.actions.execute(ActionRequest("pending-" + approved.id, approved.kind, approved.args))
                                }
                                withContext(Dispatchers.IO) { app.pendingActions.finish(approved.id, result) }
                                message = result.kind.name + " · " + result.status.name + " · " + result.reason
                                tick++
                            }
                        }
                    }) { Text("允许本次") }
                    OutlinedButton(onClick = {
                        scope.launch {
                            withContext(Dispatchers.IO) { app.pendingActions.rejectOrCancel(item.id) }
                            message = "已拒绝"; tick++
                        }
                    }) { Text("拒绝") }
                }
            }
        }
        HorizontalDivider()
    }
}