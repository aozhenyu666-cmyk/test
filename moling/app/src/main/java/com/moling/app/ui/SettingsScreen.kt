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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
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
    var backgroundOn by remember { mutableStateOf(store.backgroundHeart()) }
    var autoPlanOn by remember { mutableStateOf(store.autoPlan()) }
    // 看前台应用：权限在系统设置里，回来时刷新一下状态。
    var usageAllowed by remember { mutableStateOf(Usage.allowed(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) usageAllowed = Usage.allowed(context)
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

    // 调试页占满全屏，自己带返回。
    if (debugOpen) {
        DebugScreen(onDone = { debugOpen = false })
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
            TextButton(onClick = onDone) { Text("← 返回") }
            Text("设置", style = MaterialTheme.typography.titleLarge)
        }

        Column(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
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
                "总控管拦截和锁机，墨灵只提议、由你在总控里确认；它连不上时墨灵照常聊，只是交不了建议。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

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

            Text("权柄上限", style = MaterialTheme.typography.titleSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Tier.entries.forEach { tier ->
                    FilterChip(
                        selected = brainState.userMax == tier,
                        onClick = {
                            // 上限只影响她多主动地提建议，不会跳过“你点好”这一步。
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

            TextButton(onClick = { com.moling.app.overlay.PresenceActivity.open(context, "settings") }) { Text("在你身边") }
            TextButton(
                onClick = { debugOpen = true },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("调试（只读：权柄 / 总控 / 提议 / 导演 / 后台 / 日志）") }
        }

        Surface(tonalElevation = 2.dp) {
            Button(
                onClick = {
                    store.save(draft())
                    onDone()
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