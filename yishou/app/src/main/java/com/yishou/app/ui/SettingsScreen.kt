package com.yishou.app.ui

import android.app.TimePickerDialog
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.yishou.app.llm.LlmConfig
import com.yishou.app.settings.AppPrefs
import com.yishou.app.speech.SpeechConfig

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onEditTask: (Long) -> Unit,
    onNewTask: () -> Unit,
    onAbout: () -> Unit,
    onPermissions: () -> Unit,
    onWatchedApps: () -> Unit,
    onLog: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val saved by vm.llm.collectAsStateWithLifecycle()
    val tasks by vm.tasks.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var baseUrl by rememberSaveable { mutableStateOf(saved.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(saved.apiKey) }
    var model by rememberSaveable { mutableStateOf(saved.model) }
    var jsonMode by rememberSaveable { mutableStateOf(saved.jsonMode) }
    var showKey by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackTopBar("设置", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            SectionTitle("当前任务")
            val current = tasks.firstOrNull { it.isCurrent }
            if (current == null) {
                Text("还没有当前任务。")
            } else {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(current.title, fontWeight = FontWeight.Bold)
                        Text(current.goal, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = { onEditTask(current.id) }) { Text("编辑任务和断点") }
                    }
                }
            }
            OutlinedButton(onClick = onNewTask) { Text("新建任务并切换过去") }

            val others = tasks.filter { !it.isCurrent }
            if (others.isNotEmpty()) {
                Text("其他任务（点一下切换为当前任务，断点都保留着）", style = MaterialTheme.typography.bodySmall)
                others.forEach { t ->
                    Text(
                        t.title,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vm.switchTo(t.id) }
                            .padding(vertical = 10.dp),
                    )
                }
            }

            HorizontalDivider()
            SectionTitle("入口思考页")
            NavRow("权限与运行状态", "无障碍、通知、闹钟、电池，以及 vivo 的手动设置", onPermissions)
            NavRow(
                "关注的应用与放行时长",
                "已关注 ${prefs.watched.size} 个应用，${prefs.groups.size} 个应用组",
                onWatchedApps,
            )
            LabeledRow("离线放行时长") {
                Stepper(prefs.offlinePassMinutes, 1..30, "分钟") { v -> vm.update { it.copy(offlinePassMinutes = v) } }
            }
            LabeledRow("离线放行每天最多") {
                Stepper(prefs.offlineDailyLimit, 0..10, "次") { v -> vm.update { it.copy(offlineDailyLimit = v) } }
            }

            HorizontalDivider()
            SectionTitle("分组训练")
            Text(
                "像练体能一样分组：一组是几手有效回答，组间歇一下。起步目标每天几组，" +
                    "连续两天达标自动加一组，连续两天没达标自动减一组。",
                style = MaterialTheme.typography.bodySmall,
            )
            LabeledRow("一组") {
                Stepper(prefs.setSize, 1..10, "手") { v -> vm.update { it.copy(setSize = v) } }
            }
            LabeledRow("组间休息") {
                Stepper(prefs.restMinutes, 1..15, "分钟") { v -> vm.update { it.copy(restMinutes = v) } }
            }
            LabeledRow("起步每天") {
                Stepper(prefs.baseSets, 1..8, "组") { v -> vm.update { it.copy(baseSets = v) } }
            }

            HorizontalDivider()
            SectionTitle("陪练窗口")
            SwitchRow("每天定时开始", prefs.windowEnabled) { on -> vm.update(reschedule = true) { it.copy(windowEnabled = on) } }
            LabeledRow("开始时间") {
                TextButton(onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        vm.update(reschedule = true) { it.copy(windowHour = h, windowMinute = m) }
                    }, prefs.windowHour, prefs.windowMinute, true).show()
                }) { Text("%02d:%02d".format(prefs.windowHour, prefs.windowMinute)) }
            }
            LabeledRow("窗口时长") {
                Stepper(prefs.windowMinutes, 15..120, "分钟") { v -> vm.update { it.copy(windowMinutes = v) } }
            }
            LabeledRow("第一次无回应提醒") {
                Stepper(prefs.remindFirstMinutes, 1..10, "分钟后") { v -> vm.update { it.copy(remindFirstMinutes = v) } }
            }
            LabeledRow("第二次提醒") {
                Stepper(prefs.remindSecondMinutes, 1..10, "分钟后") { v -> vm.update { it.copy(remindSecondMinutes = v) } }
            }
            SwitchRow("朗读问题和提醒", prefs.ttsEnabled) { on -> vm.update { it.copy(ttsEnabled = on) } }
            SwitchRow("没应声就拉回窗口", prefs.pullBackEnabled) { on -> vm.update { it.copy(pullBackEnabled = on) } }
            if (prefs.pullBackEnabled) {
                LabeledRow("第二次提醒后每隔") {
                    Stepper(prefs.pullBackMinutes, 1..15, "分钟拉回") { v -> vm.update { it.copy(pullBackMinutes = v) } }
                }
                Text(
                    "两次提醒都没动静，就把陪练窗口拉到前台（不管你在哪个应用），每一手最多 3 次。" +
                        "你在窗口允许的学习应用、相机、电话里，或者屏幕关着时不拉，只朗读。需要开着入口思考页（无障碍服务）。",
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            HorizontalDivider()
            SectionTitle("每晚总结")
            SwitchRow("每天自动总结", prefs.summaryEnabled) { on -> vm.update(resummary = true) { it.copy(summaryEnabled = on) } }
            LabeledRow("总结时间") {
                TextButton(onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        vm.update(resummary = true) { it.copy(summaryHour = h, summaryMinute = m) }
                    }, prefs.summaryHour, prefs.summaryMinute, true).show()
                }) { Text("%02d:%02d".format(prefs.summaryHour, prefs.summaryMinute)) }
            }

            HorizontalDivider()
            SectionTitle("防掉线")
            SwitchRow("每天检查：还没应手就提醒", prefs.nudgeEnabled) { on -> vm.update(renudge = true) { it.copy(nudgeEnabled = on) } }
            LabeledRow("检查时间") {
                TextButton(onClick = {
                    TimePickerDialog(context, { _, h, m ->
                        vm.update(renudge = true) { it.copy(nudgeHour = h, nudgeMinute = m) }
                    }, prefs.nudgeHour, prefs.nudgeMinute, true).show()
                }) { Text("%02d:%02d".format(prefs.nudgeHour, prefs.nudgeMinute)) }
            }
            LabeledRow("一手停了") {
                Stepper(prefs.idleHours, 0..12, "小时叫你") { v -> vm.update(renudge = true) { it.copy(idleHours = v) } }
            }
            Text(
                "白天（8 点到 23 点）一手停了这么久没人应，就发通知，写上那一手；设为 0 关掉。" +
                    "定时窗口开始 10 分钟还没走第一手、“先停”的休息时间到了，也会提醒你。",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider()
            SectionTitle("界面与掷骰")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(0 to "跟随系统", 1 to "白局", 2 to "夜局").forEach { (m, label) ->
                    FilterChip(
                        selected = prefs.themeMode == m,
                        onClick = { vm.update { it.copy(themeMode = m) } },
                        label = { Text(label) },
                    )
                }
            }
            Text("白局是青白纸面配黑子，夜局是靛青夜空配白子和金色星位。", style = MaterialTheme.typography.bodySmall)
            SwitchRow("摇一摇手机掷骰子", prefs.shakeToRoll) { on -> vm.update { it.copy(shakeToRoll = on) } }

            HorizontalDivider()
            SectionTitle("大模型接口")
            Text(
                "OpenAI 兼容接口。DeepSeek 示例：地址 https://api.deepseek.com，模型 deepseek-chat。",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it; message = null },
                label = { Text("接口地址") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it; message = null },
                label = { Text("密钥") },
                singleLine = true,
                visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    TextButton(onClick = { showKey = !showKey }) { Text(if (showKey) "隐藏" else "显示") }
                },
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = model,
                onValueChange = { model = it; message = null },
                label = { Text("模型名") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("JSON 模式")
                    Text(
                        "要求接口只返回 JSON。DeepSeek 支持；如果报 400 错误再关掉。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Switch(checked = jsonMode, onCheckedChange = { jsonMode = it; message = null })
            }
            Button(onClick = {
                message = vm.saveLlm(LlmConfig(baseUrl, apiKey, model, jsonMode)) ?: "已保存"
            }) { Text("保存接口设置") }
            message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }

            HorizontalDivider()
            VisionSection(vm)

            HorizontalDivider()
            VoiceSection(vm, prefs)

            HorizontalDivider()
            TextButton(onClick = onLog) { Text("运行日志") }
            TextButton(onClick = onAbout) { Text("关于「一手」") }
        }
    }
}

/** 识图模型：拍照作答时把图片转写成文字。DeepSeek 不能看图，需要另填一个支持图片的模型。 */
@Composable
private fun VisionSection(vm: SettingsViewModel) {
    val saved by vm.vision.collectAsStateWithLifecycle()
    var baseUrl by rememberSaveable { mutableStateOf(saved.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(saved.apiKey) }
    var model by rememberSaveable { mutableStateOf(saved.model) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    SectionTitle("识图模型（可选）")
    Text(
        "用于拍照作答：先把图片转写成文字，再交给陪练判定。DeepSeek 不能看图，需要另填一个支持图片的 OpenAI 兼容接口。" +
            "例如通义千问：地址 https://dashscope.aliyuncs.com/compatible-mode/v1，模型 qwen-vl-max（或更便宜的 qwen-vl-plus）。",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it; message = null },
        label = { Text("识图接口地址") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it; message = null },
        label = { Text("识图密钥") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = model,
        onValueChange = { model = it; message = null },
        label = { Text("识图模型名") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { message = vm.saveVision(LlmConfig(baseUrl, apiKey, model, false)) ?: "已保存" }) {
        Text("保存识图设置")
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

/** 语音：陪练窗口里读完题就听你说；可选填 OpenAI 兼容的听写 / 朗读接口，空着用手机自带的。 */
@Composable
private fun VoiceSection(vm: SettingsViewModel, prefs: AppPrefs) {
    val saved by vm.speech.collectAsStateWithLifecycle()
    var baseUrl by rememberSaveable { mutableStateOf(saved.baseUrl) }
    var apiKey by rememberSaveable { mutableStateOf(saved.apiKey) }
    var stt by rememberSaveable { mutableStateOf(saved.sttModel) }
    var tts by rememberSaveable { mutableStateOf(saved.ttsModel) }
    var voiceName by rememberSaveable { mutableStateOf(saved.ttsVoice) }
    var message by rememberSaveable { mutableStateOf<String?>(null) }

    SectionTitle("语音")
    SwitchRow("语音陪练：读完题就听我说", prefs.voiceMode) { on -> vm.update { it.copy(voiceMode = on) } }
    LabeledRow("听写好后自动发出") {
        Stepper(prefs.voiceAutoSendSeconds, 0..10, "秒后") { v -> vm.update { it.copy(voiceAutoSendSeconds = v) } }
    }
    Text(
        "设为 0 就不自动发，听写的字留在输入框里等你确认。短口令直接当操作：" +
            "“我不知道”“再说一遍”“先停”“示范”“换一个”“等一下”“取消”。",
        style = MaterialTheme.typography.bodySmall,
    )
    Text(
        "下面的接口可选，按 OpenAI 兼容格式（/audio/transcriptions 听写、/audio/speech 朗读）。" +
            "听写模型空着就用手机自带的语音识别，朗读模型空着就用系统朗读。录音只发给这里填的接口，不保存。",
        style = MaterialTheme.typography.bodySmall,
    )
    OutlinedTextField(
        value = baseUrl,
        onValueChange = { baseUrl = it; message = null },
        label = { Text("语音接口地址") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = apiKey,
        onValueChange = { apiKey = it; message = null },
        label = { Text("语音密钥") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = stt,
        onValueChange = { stt = it; message = null },
        label = { Text("听写模型（例如 whisper-1）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = tts,
        onValueChange = { tts = it; message = null },
        label = { Text("朗读模型（例如 tts-1）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    OutlinedTextField(
        value = voiceName,
        onValueChange = { voiceName = it; message = null },
        label = { Text("朗读音色（接口要求时填）") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    Button(onClick = { message = vm.saveSpeech(SpeechConfig(baseUrl, apiKey, stt, tts, voiceName)) ?: "已保存" }) {
        Text("保存语音设置")
    }
    message?.let { Text(it, color = MaterialTheme.colorScheme.primary) }
}

@Composable
private fun NavRow(title: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 8.dp),
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun LabeledRow(label: String, content: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f))
        content()
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    LabeledRow(label) { Switch(checked = checked, onCheckedChange = onChange) }
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}
