package com.yishou.app.ui

import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.focus.*
import com.yishou.app.gate.GateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun FocusPlanScreen(onBack: () -> Unit, onStarted: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val defaults by app.focusSettings.state.collectAsStateWithLifecycle()
    val connected by GateService.connected.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var task by rememberSaveable { mutableStateOf("") }
    var goal by rememberSaveable { mutableStateOf("") }
    var total by rememberSaveable { mutableStateOf("60") }
    var work by rememberSaveable { mutableStateOf(defaults.workMin.toString()) }
    var rest by rememberSaveable { mutableStateOf(defaults.restMin.toString()) }
    var installed by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selected by remember { mutableStateOf<Set<String>>(emptySet()) }
    var messaging by remember { mutableStateOf(defaults.messaging) }
    var resolved by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var picker by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { installed = withContext(Dispatchers.IO) { focusInstalledApps(context) } }

    fun paste() {
        val p = runCatching { FocusPlans.parse(clipboard.getText()?.text.orEmpty()) }.getOrNull()
        if (p == null) { error = "没读出计划，请粘贴带【任务】【总时长】【每组】的六行"; return }
        task = p.task; goal = p.goal; total = p.totalMin.toString(); work = p.workMin.toString(); rest = p.restMin.toString()
        resolved = FocusRules.resolve(p.allowedNames, installed.associate { it.pkg to it.label })
        selected = resolved.values.filterNotNull().toSet()
        error = null
    }

    Scaffold(topBar = { BackTopBar("专注流 · 排计划", onBack) }) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = {
                clipboard.setText(AnnotatedString(FocusPlans.ASK_AI))
                Toast.makeText(context, "已复制，发给 ChatGPT/Gemini，说说你的情况", Toast.LENGTH_LONG).show()
            }) { Text("复制给 AI 的模板") }
            OutlinedButton(onClick = ::paste, enabled = installed.isNotEmpty()) { Text("粘贴计划") }
            OutlinedTextField(task, { task = it }, label = { Text("任务") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(goal, { goal = it }, label = { Text("目标（可不填）") }, modifier = Modifier.fillMaxWidth())
            FocusNumber("总时长（10–240 分钟）", total) { total = it }
            FocusNumber("每组（3–60 分钟）", work) { work = it }
            FocusNumber("休息（0–15 分钟）", rest) { rest = it }
            Text("允许的应用", style = MaterialTheme.typography.titleMedium)
            resolved.forEach { (name, pkg) ->
                if (pkg == null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("没找到「$name」，点这里选", color = MaterialTheme.colorScheme.error, modifier = Modifier.weight(1f).clickable { picker = name }.padding(8.dp))
                    TextButton(onClick = { resolved = resolved - name }) { Text("去掉") }
                }
            }
            installed.filter { it.pkg in selected || it.pkg in resolved.values }.forEach { a ->
                FocusCheck(a.label, a.pkg in selected) { checked -> selected = if (checked) selected + a.pkg else selected - a.pkg }
            }
            OutlinedButton(onClick = { picker = "" }) { Text("从列表加") }
            Text("通讯应用（可去掉）", style = MaterialTheme.typography.titleMedium)
            installed.filter { it.pkg in defaults.messaging || it.pkg in messaging || it.pkg in FocusRules.DEFAULT_MESSAGING }.forEach { a ->
                FocusCheck(a.label, a.pkg in messaging) { checked -> messaging = if (checked) messaging + a.pkg else messaging - a.pkg }
            }
            Text("系统界面、电话和一手能用。别的全拦，休息也一样；收费站的放行不算数。")
            if (!connected) {
                Text("无障碍服务没连上，先打开才能开始", color = MaterialTheme.colorScheme.error)
                OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("去开无障碍服务") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            if (app.focus.state.value != null) OutlinedButton(onClick = onStarted) { Text("回到当前专注页") }
            Button(onClick = {
                val plan = FocusPlan(task.trim(), goal.trim(), total.toIntOrNull() ?: -1, work.toIntOrNull() ?: -1, rest.toIntOrNull() ?: -1, resolved.keys.toList())
                val problem = FocusPlans.check(plan)
                when {
                    problem != null -> error = problem
                    resolved.values.any { it == null } -> error = "先选好没找到的应用，或把它去掉"
                    !GateService.connected.value -> error = "无障碍服务没连上，先打开才能开始"
                    else -> {
                        try {
                            if (app.focus.start(plan, selected, messaging.intersect(installed.map { it.pkg }.toSet()))) {
                                FocusService.start(context)
                                onStarted()
                            } else error = "已经有一场专注流，先回去继续"
                        } catch (e: Exception) {
                            error = "计时没能启动：${e.javaClass.simpleName}。这一场已保存，请回专注页重试"
                        }
                    }
                }
            }, modifier = Modifier.fillMaxWidth(), enabled = connected) { Text("开始") }
        }
    }
    picker?.let { name ->
        FocusAppPicker(installed, { picker = null }) { a ->
            selected = selected + a.pkg
            if (name.isNotEmpty()) resolved = resolved + (name to a.pkg)
            picker = null
        }
    }
}

internal fun focusInstalledApps(context: android.content.Context): List<InstalledApp> {
    val launchable = loadLaunchableApps(context)
    val extra = FocusRules.DEFAULT_MESSAGING.mapNotNull { pkg ->
        runCatching { context.packageManager.getApplicationInfo(pkg, 0).let { InstalledApp(pkg, context.packageManager.getApplicationLabel(it).toString(), null) } }.getOrNull()
    }
    return (launchable + extra).distinctBy { it.pkg }.sortedBy { it.label }
}

@Composable internal fun FocusNumber(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit).take(3)) }, label = { Text(label) }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), singleLine = true, modifier = Modifier.fillMaxWidth())
}

@Composable internal fun FocusCheck(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked, onChange); Text(label)
    }
}

@Composable internal fun FocusAppPicker(apps: List<InstalledApp>, onDismiss: () -> Unit, onPick: (InstalledApp) -> Unit) {
    var query by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("选应用") }, text = {
        Column {
            OutlinedTextField(query, { query = it }, label = { Text("搜索") })
            LazyColumn(Modifier.heightIn(max = 350.dp)) {
                items(apps.filter { it.label.contains(query, true) || it.pkg.contains(query, true) }, key = { it.pkg }) { a ->
                    TextButton(onClick = { onPick(a) }) { Text("${a.label}\n${a.pkg}") }
                }
            }
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("返回") } })
}

@Composable
fun FocusSettingsSection() {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val defaults by app.focusSettings.state.collectAsStateWithLifecycle()
    var work by rememberSaveable { mutableStateOf(defaults.workMin.toString()) }
    var rest by rememberSaveable { mutableStateOf(defaults.restMin.toString()) }
    var message by remember { mutableStateOf<String?>(null) }
    var installed by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var pick by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { installed = withContext(Dispatchers.IO) { focusInstalledApps(context) } }
    Text("专注流", style = MaterialTheme.typography.titleMedium)
    FocusNumber("默认每组（3–60 分钟）", work) { work = it }
    FocusNumber("默认休息（0–15 分钟）", rest) { rest = it }
    OutlinedButton(onClick = {
        val w = work.toIntOrNull(); val r = rest.toIntOrNull()
        if (w == null || w !in 3..60 || r == null || r !in 0..15) message = "每组 3–60 分钟，休息 0–15 分钟"
        else { app.focusSettings.save(defaults.copy(workMin = w, restMin = r)); message = "已保存，下场生效" }
    }) { Text("保存默认节奏") }
    message?.let { Text(it) }
    Text("通讯应用（改动从下场生效）")
    installed.filter { it.pkg in defaults.messaging || it.pkg in FocusRules.DEFAULT_MESSAGING }.forEach { a ->
        FocusCheck(a.label, a.pkg in defaults.messaging) { on -> app.focusSettings.save(defaults.copy(messaging = if (on) defaults.messaging + a.pkg else defaults.messaging - a.pkg)) }
    }
    OutlinedButton(onClick = { pick = true }) { Text("添加通讯应用") }
    if (pick) FocusAppPicker(installed, { pick = false }) { a -> app.focusSettings.save(defaults.copy(messaging = defaults.messaging + a.pkg)); pick = false }
}
