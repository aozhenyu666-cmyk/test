package cn.companion.pet.ui

import android.content.Context
import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import cn.companion.pet.data.SecretWriteResult
import cn.companion.pet.net.EndpointPolicy
import cn.companion.pet.work.NightlyWorker
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsTab(context: Context) {
    val app = PetApp.instance
    val s = app.settings
    val scope = rememberCoroutineScope()
    fun toast(t: String) = Toast.makeText(context, t, Toast.LENGTH_SHORT).show()

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Section("DeepSeek")
        var endpoint by remember { mutableStateOf(s.endpoint) }
        var model by remember { mutableStateOf(s.model) }
        var key by remember { mutableStateOf("") }
        var limit by remember { mutableStateOf(s.dailyLimit.toString()) }
        OutlinedTextField(endpoint, { endpoint = it }, Modifier.fillMaxWidth(), label = { Text("接口地址（OpenAI 兼容）") }, singleLine = true,
            isError = !EndpointPolicy.isValid(endpoint))
        OutlinedTextField(model, { model = it }, Modifier.fillMaxWidth(), label = { Text("模型名") }, singleLine = true)
        OutlinedTextField(key, { key = it }, Modifier.fillMaxWidth(), label = { Text(if (app.apiKey() != null) "API Key（已保存，留空不改）" else "API Key") },
            singleLine = true, visualTransformation = PasswordVisualTransformation())
        NumberField("每日调用上限", limit) { limit = it }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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

        Section("Gemini")
        var pkg by remember { mutableStateOf(s.geminiPackage) }
        OutlinedTextField(pkg, { pkg = it; s.geminiPackage = it.trim() }, Modifier.fillMaxWidth(), label = { Text("Gemini 包名") }, singleLine = true)
        var auto by remember { mutableStateOf(s.geminiAutoSend) }
        Toggle("自动发送（实验）", "打开 Gemini 后用无障碍自动粘贴并点发送；3 秒找不到输入框就退回手动粘贴。", auto) {
            auto = it; s.geminiAutoSend = it
        }

        Section("其他")
        var tts by remember { mutableStateOf(s.ttsEnabled) }
        Toggle("语音播报", "用系统 TTS 念出它说的话。默认关，机械音会破坏伴生感。", tts) { tts = it; s.ttsEnabled = it }

        Section("人设")
        var persona by remember { mutableStateOf("") }
        LaunchedEffect(Unit) { persona = withContext(Dispatchers.IO) { synchronized(app.memoryLock) { app.memory.persona() } } }
        OutlinedTextField(persona, { persona = it }, Modifier.fillMaxWidth().heightIn(min = 160.dp), label = { Text("persona.md") })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
    HorizontalDivider(Modifier.padding(top = 6.dp))
    Text(title, style = MaterialTheme.typography.titleMedium)
}

@Composable
private fun NumberField(label: String, value: String, modifier: Modifier = Modifier.fillMaxWidth(), onChange: (String) -> Unit) {
    OutlinedTextField(value, { onChange(it.filter(Char::isDigit)) }, modifier, label = { Text(label) }, singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
}

@Composable
private fun Toggle(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(desc, style = MaterialTheme.typography.bodySmall)
        }
        Switch(value, onChange)
    }
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
