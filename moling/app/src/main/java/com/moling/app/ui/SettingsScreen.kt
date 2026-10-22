package com.moling.app.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.clickable
import com.moling.app.ui.art.inkEnter
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
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
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moling.app.BrainHost
import com.moling.app.HeartService
import com.moling.app.Usage
import com.moling.app.data.LlmResult
import com.moling.app.data.ModelSettings
import com.moling.app.data.MolingAsk
import com.moling.app.data.SettingsStore
import com.moling.core.link.ZkStatus
import com.moling.core.mind.BEIJING
import com.moling.core.mind.DEFAULT_PERSONA
import com.moling.core.power.Charter
import com.moling.core.power.Tier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * 设置页：接口、密钥、模型、人格、称呼、朗读。
 * “测试连接”只发一句“你好”，失败把原因写在按钮下面，界面不会停。
 * 权柄说明只读：等级怎么算是核心库 Charter 的事，这里只展示。
 */
@Composable
fun SettingsScreen(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { SettingsStore(context) }
    val saved = remember { store.load() }
    // 总控状态由大脑的心跳（MainActivity 里每分钟一次 tick）更新到这里。
    val brainState by BrainHost.state.collectAsState()
    var zkBusy by remember { mutableStateOf(false) }
    var debugOpen by remember { mutableStateOf(false) }
    var aboutOpen by remember { mutableStateOf(false) }
    var bridgeOpen by remember { mutableStateOf(false) }
    // 设置首页（null）还是某一节
    var section by remember { mutableStateOf<String?>(null) }
    var backgroundOn by remember { mutableStateOf(store.backgroundHeart()) }
    var autoPlanOn by remember { mutableStateOf(store.autoPlan()) }
    var planRemindOn by remember { mutableStateOf(store.planRemindOn()) }
    var planRemindMin by remember { mutableStateOf(store.planRemindMin()) }
    var liveOn by remember { mutableStateOf(store.liveOn()) }
    var gateOn by remember { mutableStateOf(store.gateOn()) }
    var whisperOn by remember { mutableStateOf(store.whisperOn()) }
    var fadeOn by remember { mutableStateOf(store.fadeOn()) }
    var disabled by remember { mutableStateOf(store.disabledModalities()) }
    var eager by remember { mutableStateOf(store.eagerness()) }
    var faceMood by remember { mutableStateOf(com.moling.core.live.Mood.POUT) }
    var overlayAllowed by remember { mutableStateOf(android.provider.Settings.canDrawOverlays(context)) }
    // 看前台应用：权限在系统设置里，回来时刷新一下状态。
    var usageAllowed by remember { mutableStateOf(Usage.allowed(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                usageAllowed = Usage.allowed(context)
                overlayAllowed = android.provider.Settings.canDrawOverlays(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 后台通知：Android 13 起第一次打开要问一次；不给也不影响后台跑，只是看不到常驻通知。
    val askNotif = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) {
            Toast.makeText(context, "没给通知权限：后台照常跑，但看不到「墨灵在」那条通知", Toast.LENGTH_LONG).show()
        }
    }

    // 返回手势：先关里面的页 / 回设置首页（首页时交给外面关掉设置）
    androidx.activity.compose.BackHandler(enabled = debugOpen || aboutOpen || bridgeOpen || section != null) {
        when {
            debugOpen -> debugOpen = false
            aboutOpen -> aboutOpen = false
            bridgeOpen -> bridgeOpen = false
            else -> section = null
        }
    }

    // 调试页占满全屏，自己带返回。
    if (debugOpen) {
        DebugScreen(onDone = { debugOpen = false })
        return
    }
    if (bridgeOpen) {
        BridgeScreen(onDone = { bridgeOpen = false })
        return
    }
    if (aboutOpen) {
        AboutScreen(onDone = { aboutOpen = false })
        return
    }

    var url by remember { mutableStateOf(saved.baseUrl) }
    var key by remember { mutableStateOf(saved.apiKey) }
    var model by remember { mutableStateOf(saved.model) }
    var persona by remember { mutableStateOf(saved.persona) }
    var callUser by remember { mutableStateOf(saved.callUser) }
    var speak by remember { mutableStateOf(saved.speak) }

    var testing by remember { mutableStateOf(false) }
    var testText by remember { mutableStateOf<String?>(null) }
    var testOk by remember { mutableStateOf(false) }
    var usage by remember { mutableStateOf(store.usageToday()) }
    // 导入前先校验、再弹二次确认；确认后才覆盖文件（见函数末尾的 AlertDialog）。
    var pendingImport by remember { mutableStateOf<BackupText?>(null) }
    val scope = rememberCoroutineScope()

    // 记忆备份（M16）：导出 / 导入两个 json，走系统文档选择器，不需要权限。
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        val dir = File(context.filesDir, "mind")
                        val text = buildJsonObject {
                            put("v", 1)
                            put("moling", readTextOrEmpty(File(dir, "moling.json")))
                            put("director", readTextOrEmpty(File(dir, "director.json")))
                        }.toString()
                        val out = context.contentResolver.openOutputStream(uri) ?: error("打不开要写的文件")
                        out.use { it.write(text.toByteArray(Charsets.UTF_8)) }
                        ""
                    }.getOrElse { it.message ?: "写不进去" }
                }
                Toast.makeText(
                    context,
                    if (result.isEmpty()) "已导出" else "导出失败：$result",
                    Toast.LENGTH_LONG,
                ).show()
            }
        }
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                val backup = withContext(Dispatchers.IO) {
                    runCatching {
                        val text = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
                            ?: error("读不到这个文件")
                        parseBackup(text)
                    }.getOrNull()
                }
                if (backup == null) {
                    Toast.makeText(context, "这个文件不对，没动现在的记忆", Toast.LENGTH_LONG).show()
                } else {
                    pendingImport = backup
                }
            }
        }
    }

    /** 屏幕上的值整理成一份配置：空的人格和称呼用默认的，免得提示词里出现空段。 */
    fun draft() = ModelSettings(
        baseUrl = url.trim(),
        apiKey = key.trim(),
        model = model.trim(),
        persona = persona.trim().ifBlank { DEFAULT_PERSONA },
        callUser = callUser.trim().ifBlank { "你" },
        speak = speak,
    )

    Column(Modifier.fillMaxSize()) {
        // 顶部返回：设置页不套 Scaffold，直接给标题栏，少一层壳。
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = { if (section != null) section = null else onDone() }) { Text("← 返回") }
            Text(SECTIONS.firstOrNull { it.first == section }?.second ?: "设置", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (section == null) SettingsHub(
                callUser = callUser, speak = speak, model = model, calls = usage.calls,
                liveOn = liveOn, eager = eager.label, zk = zkLabel(brainState.lastStatus), tier = tierShort(brainState.userMax),
                background = backgroundOn, usageAllowed = usageAllowed,
                notion = store.notion().ready(), relay = store.relay().ready(),
                memory = fileSummary(File(File(context.filesDir, "mind"), "moling.json")),
                open = { section = it },
                openBridge = { bridgeOpen = true },
                openAbout = { aboutOpen = true },
                openPresence = { com.moling.app.overlay.PresenceActivity.open(context, "settings") },
                openDebug = { debugOpen = true },
            )
            if (section == "ai") {
            Text("AI 接口", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("接口地址") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("填到 /v1 也行；没写 /chat/completions 会自动补上。") },
            )
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("密钥") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("只存在这台手机上（加密保存），不会传到别处。") },
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it },
                label = { Text("模型名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("DeepSeek 填 deepseek-chat。") },
            )

            }
            if (section == "zk") {
            Text("总控", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    zkLabel(brainState.lastStatus),
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = {
                        if (!zkBusy) {
                            zkBusy = true
                            scope.launch {
                                // 心跳会把总控状态拉回来；这一下失败也不影响聊天。
                                runCatching { BrainHost.tickNow() }
                                zkBusy = false
                            }
                        }
                    },
                    enabled = !zkBusy,
                ) { Text(if (zkBusy) "连接中…" else "重新连接") }
            }
            Text(
                "总控管拦截和锁机。授权内的事倒计时后执行，其他建议仍由你在总控里确认；连不上时墨灵照常聊。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                brainState.lastStatus?.grants?.ifBlank { "总控还没有返回授权书，请重新连接或更新总控。" }
                    ?: "总控未连接，暂时读不到授权书。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = {
                val intent = context.packageManager.getLaunchIntentForPackage("com.zongkong.app")
                    ?.putExtra("route", "settings/moling")
                if (intent == null || runCatching { context.startActivity(intent) }.isFailure) {
                    Toast.makeText(context, "打不开总控，请先安装或更新总控", Toast.LENGTH_LONG).show()
                }
            }) { Text("去总控改授权书") }

            }
            if (section == "bg") {
            Text("后台", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("后台陪着（常驻通知）")
                    Text(
                        "开着：她常驻在通知栏，每分钟看一眼总控和今天的安排；关掉就只在你打开 App 时动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = backgroundOn,
                    onCheckedChange = { on ->
                        backgroundOn = on
                        store.setBackgroundHeart(on)
                        if (on) {
                            if (Build.VERSION.SDK_INT >= 33 &&
                                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                            ) {
                                askNotif.launch(Manifest.permission.POST_NOTIFICATIONS)
                            }
                            HeartService.start(context)
                        } else {
                            HeartService.stop(context)
                        }
                    },
                )
            }

            Text("看前台应用", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (usageAllowed) "已允许：知道你在用哪个 App" else "没开：看不到前台应用（水流偏了就不会提醒）",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                )
                OutlinedButton(
                    onClick = {
                        runCatching {
                            context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                        }
                    },
                ) { Text(if (usageAllowed) "去改" else "去打开") }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("早上自动排水道")
                    Text(
                        "6 点到 22 点之间、今天还没有水道时，自动排一份草稿（不定稿、不交给总控）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = autoPlanOn,
                    onCheckedChange = { on ->
                        autoPlanOn = on
                        store.setAutoPlan(on)
                    },
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("到点提醒我一键采用计划")
                    Text(
                        "到点只发一条提醒（通知 + 今天页按钮）；你点了才算采用，不会自动采用、不会交给总控。默认开。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = planRemindOn,
                    onCheckedChange = { on ->
                        planRemindOn = on
                        store.setPlanRemindOn(on)
                    },
                )
            }
            if (planRemindOn) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "排好草稿后多久提醒",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    listOf(10, 20, 30).forEach { m ->
                        TextButton(onClick = {
                            planRemindMin = m
                            store.setPlanRemindMin(m)
                        }) {
                            Text(if (m == planRemindMin) "✓ " + m + " 分" else "" + m + " 分")
                        }
                    }
                }
            }

            }
            if (section == "live") {
            // ---------------------------------------------------------------- 活的墨灵（v5）
            Text("活的墨灵", style = MaterialTheme.typography.titleSmall)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                MolingFace(
                    faceMood,
                    Modifier.size(72.dp).clickable {
                        val all = com.moling.core.live.Mood.entries
                        faceMood = all[(faceMood.ordinal + 1) % all.size]
                    },
                )
                Text(
                    "她现在是「${faceMood.label}」（点她换个表情）。刷久了、该做事时在刷、夜里躺着刷，她会自己决定要不要蹦出来说一句。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("她会主动找你")
                    Text(
                        "每天最多出声 10 次、两次至少隔 20 分钟；你老划掉她，她就少说。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = liveOn, onCheckedChange = { on -> liveOn = on; store.setLiveOn(on) })
            }
            if (liveOn) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("她多主动", modifier = Modifier.weight(1f))
                com.moling.core.live.Eagerness.entries.forEach { e ->
                    TextButton(onClick = { eager = e; store.setEagerness(e) }) {
                        Text(if (eager == e) "【${e.label}】" else e.label)
                    }
                }
            }
            if (liveOn) Text(
                when (eager) {
                    com.moling.core.live.Eagerness.LOW -> "一天最多 5 次，至少隔 45 分钟；只在刷久了、做完事、她有话时来。"
                    com.moling.core.live.Eagerness.MID -> "一天最多 10 次，至少隔 20 分钟；刷久了、刚拿起手机、做完事、她有话时来。"
                    com.moling.core.live.Eagerness.HIGH -> "一天最多 16 次，至少隔 12 分钟；你闲着的时候她也会来找你聊两句。"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("她可以拦住你")
                    Text(
                        "只拦娱乐 / 购物（电话、短信、支付、地图、设置永远不拦）。回她一句认真的话就放；" +
                            "每天最多 3 次，每次最多 5 分钟、最多 3 轮，永远有「急事」按钮。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = gateOn, enabled = liveOn, onCheckedChange = { on -> gateOn = on; store.setGateOn(on) })
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("她可以在耳机里说话")
                    Text(
                        "戴着耳机时，她会把音乐压低、在耳机里说一句；说完在屏幕边上亮 8 秒「回她」，你点了才回。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = whisperOn,
                    enabled = liveOn,
                    onCheckedChange = { on -> whisperOn = on; store.setWhisperOn(on) },
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("刷着刷着慢慢褪色")
                    Text(
                        "只在她觉得你在机械地刷、而且你还停在娱乐应用里时；只让画面变灰，不挡操作；离开就恢复；每天最多 20 分钟。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(
                    checked = fadeOn,
                    enabled = liveOn,
                    onCheckedChange = { on -> fadeOn = on; store.setFadeOn(on) },
                )
            }
            if (liveOn && !overlayAllowed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "没开“显示在其他应用上层”：她只能发通知，蹦不出来",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedButton(onClick = {
                        runCatching {
                            context.startActivity(
                                Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION, android.net.Uri.parse("package:${context.packageName}")),
                            )
                        }
                    }) { Text("去打开") }
                }
            }

            // B4 不想要的方式：选中 = 不要（拦人不在此列，它有自己的开关）。
            Text("不想要的方式", style = MaterialTheme.typography.titleSmall)
            Text(
                "选中的方式她不会再用（拦人不受影响，它有自己的开关）。关掉的方式一律退回底部卡片。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.moling.core.live.Modality.entries
                    .filter { it != com.moling.core.live.Modality.GATE && it != com.moling.core.live.Modality.PEEK }
                    .take(3)
                    .forEach { m ->
                        FilterChip(
                            selected = m in disabled,
                            enabled = liveOn,
                            onClick = {
                                val next = if (m in disabled) disabled - m else disabled + m
                                disabled = next
                                store.setDisabledModalities(next)
                            },
                            label = { Text(shortModality(m)) },
                        )
                    }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                com.moling.core.live.Modality.entries
                    .filter { it != com.moling.core.live.Modality.GATE && it != com.moling.core.live.Modality.PEEK }
                    .drop(3)
                    .forEach { m ->
                        FilterChip(
                            selected = m in disabled,
                            enabled = liveOn,
                            onClick = {
                                val next = if (m in disabled) disabled - m else disabled + m
                                disabled = next
                                store.setDisabledModalities(next)
                            },
                            label = { Text(shortModality(m)) },
                        )
                    }
            }

            }
            if (section == "mem") {
            Text("记忆", style = MaterialTheme.typography.titleSmall)
            Text(
                "备份是两个文件：对话、提议、体系图和导演的记忆。导入会整个覆盖，不含接口密钥。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // 每次重组现算：文件大小和修改时间不缓存，导入导出后刷新页面就能看到。
            val mindDir = File(context.filesDir, "mind")
            Text(
                "moling.json：${fileSummary(File(mindDir, "moling.json"))}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Text(
                "director.json：${fileSummary(File(mindDir, "director.json"))}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { exportLauncher.launch("moling-备份-${backupStamp()}.json") }) {
                    Text("导出")
                }
                OutlinedButton(onClick = { importLauncher.launch(arrayOf("application/json")) }) {
                    Text("导入")
                }
            }

            }
            if (section == "she") {
            Text("她是谁", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(
                value = persona,
                onValueChange = { persona = it },
                label = { Text("人格设定") },
                minLines = 6,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("导演和对话都会带上这段；不动就用墨灵默认的口吻。") },
            )
            OutlinedTextField(
                value = callUser,
                onValueChange = { callUser = it },
                label = { Text("称呼") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                supportingText = { Text("她怎么叫你。") },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("朗读回复")
                    Text(
                        "对话页把她的回复念出来（用系统语音）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = speak, onCheckedChange = { speak = it })
            }

            // 她的声音（v8）：填了语音模型就用它（自然得多），不填用系统朗读
            var vUrl by remember { mutableStateOf(store.voice().baseUrl) }
            var vKey by remember { mutableStateOf(store.voice().apiKey) }
            var vModel by remember { mutableStateOf(store.voice().model) }
            var vName by remember { mutableStateOf(store.voice().voice) }
            Text(
                "更自然的声音（可选）：填一个 OpenAI 兼容的语音接口。比如 OpenAI：地址 https://api.openai.com/v1、模型 gpt-4o-mini-tts、音色 nova。" +
                    "按字数付费；不通会自动退回系统朗读。对话页的「对讲」也用它。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(value = vUrl, onValueChange = { vUrl = it.trim() }, label = { Text("语音接口地址") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                value = vKey, onValueChange = { vKey = it.trim() }, label = { Text("语音接口密钥") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value = vModel, onValueChange = { vModel = it.trim() }, label = { Text("模型") }, singleLine = true, modifier = Modifier.weight(1f))
                OutlinedTextField(value = vName, onValueChange = { vName = it.trim() }, label = { Text("音色") }, singleLine = true, modifier = Modifier.weight(1f))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    store.saveVoice(com.moling.app.data.VoiceSettings(vUrl, vKey, vModel, vName))
                    Toast.makeText(context, if (vModel.isBlank()) "用系统朗读" else "存好了", Toast.LENGTH_SHORT).show()
                }) { Text("保存声音") }
                OutlinedButton(onClick = {
                    store.saveVoice(com.moling.app.data.VoiceSettings(vUrl, vKey, vModel, vName))
                    Speaker.prepare(context)
                    Speaker.speak("我在。今天先做哪一件？")
                }) { Text("试听") }
            }

            }
            if (section == "ai") {
            // 先存下屏幕上的值再测：测的应该是正在填的这一份，不是上次保存的。
            OutlinedButton(
                onClick = {
                    if (!testing) {
                        store.save(draft())
                        testing = true
                        testText = null
                        scope.launch {
                            val r = MolingAsk(store).test()
                            testOk = r is LlmResult.Ok
                            testText = when (r) {
                                is LlmResult.Ok -> "连接成功：${r.value.trim().take(30)}"
                                is LlmResult.Err -> r.message
                            }
                            usage = store.usageToday()
                            testing = false
                        }
                    }
                },
                enabled = !testing,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (testing) "测试中…" else "测试连接")
            }
            testText?.let { text ->
                Surface(
                    color = if (testOk) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
                    shape = MaterialTheme.shapes.medium,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(text, modifier = Modifier.padding(10.dp), style = MaterialTheme.typography.bodyMedium)
                }
            }
            Text(
                "今天调用 ${usage.calls} 次、${usage.chars} 字",
                style = MaterialTheme.typography.bodyMedium,
            )

            }
            if (section == "zk") {
            Text("权柄上限", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tier.entries.forEach { tier ->
                    FilterChip(
                        selected = brainState.userMax == tier,
                        onClick = {
                            // 权柄由大脑检查；授权内的请求仍由总控核对边界。
                            scope.launch { runCatching { BrainHost.setUserMax(tier) } }
                        },
                        label = { Text(tierShort(tier)) },
                    )
                }
            }
            Text(
                "她实际能做的 = min(这个上限, 挣到的等级)。提议永远要你点「好」；放宽只能向总控申请、等 24 小时。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text("她能做什么（只读）", style = MaterialTheme.typography.titleSmall)
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    Charter.describe(BrainHost.tier()),
                    modifier = Modifier.padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Text(
                "等级由她在核心库里的统计决定；这里只展示，改不了。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            }
        }

        if (section == "ai" || section == "she") Surface(tonalElevation = 2.dp) {
            Button(
                onClick = {
                    store.save(draft())
                    section = null
                },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) { Text("保存") }
        }
    }

    // 导入的二次确认：点了“导入”才覆盖文件，写完立刻重启。
    // 心跳每分钟会写一次大脑状态，晚一点杀进程可能把刚导入的记忆又盖回去。
    pendingImport?.let { backup ->
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入记忆") },
            text = { Text("会覆盖现在的记忆，导入后 App 会重启。") },
            confirmButton = {
                TextButton(onClick = {
                    pendingImport = null
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                val dir = File(context.filesDir, "mind")
                                writeAtomic(File(dir, "moling.json"), backup.moling)
                                writeAtomic(File(dir, "director.json"), backup.director)
                                ""
                            }.getOrElse { it.message ?: "写不进去" }
                        }
                        if (result.isEmpty()) {
                            Toast.makeText(context, "导入好了，App 会重启", Toast.LENGTH_LONG).show()
                            Process.killProcess(Process.myPid())
                        } else {
                            Toast.makeText(context, "导入失败：$result", Toast.LENGTH_LONG).show()
                        }
                    }
                }) { Text("导入") }
            },
            dismissButton = { TextButton(onClick = { pendingImport = null }) { Text("取消") } },
        )
    }
}

/** 不想要的方式：短名字。 */
private fun shortModality(m: com.moling.core.live.Modality): String = when (m) {
    com.moling.core.live.Modality.PET -> "桌宠"
    com.moling.core.live.Modality.STARE -> "盯着你"
    com.moling.core.live.Modality.WHISPER -> "耳语"
    com.moling.core.live.Modality.COUNCIL -> "参谋弹幕"
    com.moling.core.live.Modality.QUIZ -> "捉虫题"
    else -> m.label
}

/** 上限三档的短名字，FilterChip 里放不下整句。 */
private fun tierShort(t: Tier): String = when (t) {
    Tier.ADVISOR -> "参谋"
    Tier.PLANNER -> "规划者"
    Tier.STEWARD -> "管家"
}

/**
 * 总控状态翻成一句话。null = 连不上（没装 / 被杀 / 版本太旧）。
 * 总控掉线时大脑会保留上一次读到的状态，所以超过 2 分钟没刷新也当连不上——
 * 不然强行停止总控后设置页还会显示“已连接”。
 */
private fun zkLabel(s: ZkStatus?): String {
    if (s == null) return "总控：连不上"
    val fresh = s.at > 0 && System.currentTimeMillis() - s.at <= 2 * 60_000L
    if (!fresh) return "总控：连不上"
    return "总控：已连接（" + (if (s.strict) "严管" else "正常") + "）"
}

/** 校验通过的备份内容（两个文件的原文；空串 = 空记忆）。 */
private data class BackupText(val moling: String, val director: String)

private val FILE_TIME = DateTimeFormatter.ofPattern("MM-dd HH:mm")
private val STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm")

/** 文件大小和最后修改时间；不存在写「还没有」。每次重组现算，就是读两次文件属性。 */
private fun fileSummary(f: File): String =
    if (!f.exists()) "还没有"
    else "${"%.1f".format(f.length() / 1024.0)} KB · ${FILE_TIME.format(Instant.ofEpochMilli(f.lastModified()).atZone(BEIJING))}"

/** 导出文件名里的时间：yyyyMMdd-HHmm（北京时间）。 */
private fun backupStamp(): String = STAMP.format(LocalDateTime.now(BEIJING))

/** 读文件；不存在或读不了当空串（导出时也允许空记忆）。 */
private fun readTextOrEmpty(f: File): String =
    runCatching { if (f.exists()) f.readText() else "" }.getOrDefault("")

/** 必须是 {v:1, moling:"…", director:"…"}；字段在但不是字符串也算不对。 */
private fun parseBackup(text: String): BackupText? {
    val obj = runCatching { Json.parseToJsonElement(text) as? JsonObject }.getOrNull() ?: return null
    val v = (obj["v"] as? JsonPrimitive)?.intOrNull
    val moling = (obj["moling"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    val director = (obj["director"] as? JsonPrimitive)?.takeIf { it.isString }?.content
    return if (v == 1 && moling != null && director != null) BackupText(moling, director) else null
}

/** 先写 .tmp 再改名；rename 失败就删原文件再改名（文件被占 / 跨文件系统时也尽量写成）。 */
private fun writeAtomic(target: File, text: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    tmp.writeText(text)
    if (tmp.renameTo(target)) return
    target.delete()
    if (!tmp.renameTo(target)) throw IOException("写不进 ${target.name}")
}
/** 设置的几节：键 → 标题。 */
private val SECTIONS = listOf(
    "she" to "她是谁",
    "ai" to "AI 接口",
    "live" to "活的墨灵",
    "zk" to "总控与权柄",
    "bg" to "后台与权限",
    "mem" to "记忆与备份",
)

/**
 * 设置首页：一节一张卡（小插画 + 名字 + 一句现在的状态），点进去才是细项。
 * 贯通、了解、在你身边、调试是单独的页，也放在这里。
 */
@Composable
private fun SettingsHub(
    callUser: String,
    speak: Boolean,
    model: String,
    calls: Int,
    liveOn: Boolean,
    eager: String,
    zk: String,
    tier: String,
    background: Boolean,
    usageAllowed: Boolean,
    notion: Boolean,
    relay: Boolean,
    memory: String,
    open: (String) -> Unit,
    openBridge: () -> Unit,
    openAbout: () -> Unit,
    openPresence: () -> Unit,
    openDebug: () -> Unit,
) {
    @Composable
    fun Card(title: String, line: String, art: @Composable () -> Unit, onClick: () -> Unit) {
        Surface(
            shape = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            border = androidx.compose.foundation.BorderStroke(0.7.dp, Ink.copy(alpha = 0.14f)),
            modifier = Modifier.fillMaxWidth().inkEnter(distance = 8.dp).clickable(onClick = onClick),
        ) {
            Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp), contentAlignment = Alignment.Center) { art() }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.titleSmall)
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                Text("›", style = MaterialTheme.typography.titleLarge, color = Ink.copy(alpha = 0.35f))
            }
        }
    }
    val m = Modifier.size(40.dp)
    com.moling.app.ui.art.InkTitle("她")
    Card("她是谁", "叫你「$callUser」 · 朗读${if (speak) "开" else "关"} · 人格和声音", { MolingFace(com.moling.core.live.Mood.CALM, m) }) { open("she") }
    Card("活的墨灵", if (liveOn) "会主动找你（$eager）· 拦人 · 桌宠 · 耳语 · 褪色" else "关着：她不会主动找你", { com.moling.app.ui.art.InkRipple(m, animated = false) }) { open("live") }
    Card("了解 · 周报 · 参谋", "她对你的了解、每周复盘、四个参谋、她定的唤醒", { com.moling.app.ui.art.InkLeaf(0.7f, m) }, openAbout)
    com.moling.app.ui.art.InkTitle("连接")
    Card("AI 接口", "${model.ifBlank { "还没填" }} · 今天 $calls 次", { com.moling.app.ui.art.InkHalo(m, animated = false) }) { open("ai") }
    Card(
        "贯通：GPT · Notion · 深想",
        listOfNotNull(if (relay) "联络站已连" else null, if (notion) "Notion 已连" else null).ifEmpty { listOf("联络站、Notion、交接包、ChatGPT 记录") }.joinToString(" · "),
        { com.moling.app.ui.art.InkBubbles(m, animated = false) },
        openBridge,
    )
    Card("总控与权柄", "$zk · 上限「$tier」", { com.moling.app.ui.art.InkSteps(2, 3, m) }) { open("zk") }
    com.moling.app.ui.art.InkTitle("手机")
    Card("后台与权限", "后台陪着${if (background) "开" else "关"} · 看前台应用${if (usageAllowed) "已允许" else "没开"}", { com.moling.app.ui.art.InkRing(if (background && usageAllowed) 1f else 0.5f, m) }) { open("bg") }
    Card("在你身边", "小窗、先答一问、晨报、分享", { com.moling.app.ui.art.InkClouds(m, animated = false) }, openPresence)
    Card("记忆与备份", "moling.json：$memory", { com.moling.app.ui.art.InkPapers(m, animated = false) }) { open("mem") }
    Card("调试", "只读：权柄、总控、提议、导演、后台、日志", { com.moling.app.ui.art.InkGalaxy(m, animated = false) }, openDebug)
    Text(
        "墨灵 ${com.moling.app.BuildConfig.VERSION_NAME}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    )
}