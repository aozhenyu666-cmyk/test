package com.behaviordept.app.settings

import android.app.TimePickerDialog
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import com.behaviordept.app.AppContainer
import com.behaviordept.app.BuildConfig
import com.behaviordept.app.ai.AiException
import com.behaviordept.app.ai.Prompts
import com.behaviordept.app.data.AppSettings
import com.behaviordept.app.data.BackupFile
import com.behaviordept.app.data.EventType
import com.behaviordept.app.data.Provider
import com.behaviordept.app.reminder.ReminderScheduler
import com.behaviordept.app.ui.appViewModel
import com.behaviordept.app.ui.components.CardShape
import com.behaviordept.app.ui.components.Hint
import com.behaviordept.app.ui.components.InkButton
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.components.PageHeader
import com.behaviordept.app.ui.components.PaperCard
import com.behaviordept.app.ui.components.RuledTextField
import com.behaviordept.app.ui.components.SectionLabel
import com.behaviordept.app.ui.theme.Paper
import com.behaviordept.app.ui.theme.SerifSC
import com.behaviordept.app.util.Time
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(private val c: AppContainer) : ViewModel() {
    var loaded by mutableStateOf(false)
        private set
    var provider by mutableStateOf(Provider.ANTHROPIC)
    var baseUrl by mutableStateOf("")
    var apiKey by mutableStateOf("")
    var cheapModel by mutableStateOf("")
    var flagshipModel by mutableStateOf("")
    var reminderEnabled by mutableStateOf(true)
        private set
    var reminderHour by mutableStateOf(8)
        private set
    var reminderMinute by mutableStateOf(30)
        private set

    var aiMessage by mutableStateOf<String?>(null)
        private set
    var aiMessageIsError by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var dataMessage by mutableStateOf<String?>(null)
        private set
    var pendingImport by mutableStateOf<BackupFile?>(null)
        private set

    val keyStorageAvailable: Boolean get() = c.secrets.available

    init {
        viewModelScope.launch {
            val s = c.settings.current()
            provider = s.provider
            baseUrl = s.baseUrl
            cheapModel = s.cheapModel
            flagshipModel = s.flagshipModel
            reminderEnabled = s.reminderEnabled
            reminderHour = s.reminderHour
            reminderMinute = s.reminderMinute
            apiKey = c.secrets.apiKey.value
            loaded = true
        }
    }

    fun chooseProvider(p: Provider) {
        if (p == provider) return
        // 换接口类型时，地址还是默认值就跟着换；自己改过的地址保留。
        if (Provider.entries.any { it.defaultBaseUrl == baseUrl.trim() } || baseUrl.isBlank()) baseUrl = p.defaultBaseUrl
        if (p == Provider.ANTHROPIC && cheapModel.isBlank()) cheapModel = AppSettings.DEFAULT_CHEAP_MODEL
        provider = p
    }

    private suspend fun persistAi() {
        c.settings.saveAi(provider, baseUrl, cheapModel, flagshipModel)
        withContext(Dispatchers.IO) { c.secrets.save(apiKey) }
    }

    fun saveAi() {
        viewModelScope.launch {
            persistAi()
            aiMessageIsError = false
            aiMessage = "已保存"
        }
    }

    fun testAi() {
        if (busy) return
        busy = true
        aiMessage = null
        viewModelScope.launch {
            try {
                persistAi()
                aiMessage = "连接成功 · " + c.coach.testConnection()
                aiMessageIsError = false
            } catch (e: AiException) {
                aiMessage = e.message
                aiMessageIsError = true
            } catch (e: Exception) {
                aiMessage = "失败：${e.message}"
                aiMessageIsError = true
            } finally {
                busy = false
            }
        }
    }

    fun setReminder(enabled: Boolean, hour: Int, minute: Int) {
        reminderEnabled = enabled
        reminderHour = hour
        reminderMinute = minute
        viewModelScope.launch {
            c.settings.saveReminder(enabled, hour, minute)
            ReminderScheduler.reschedule(c.appContext, c.settings.current())
        }
    }

    fun export(uri: Uri) {
        viewModelScope.launch {
            dataMessage = try {
                val text = c.backup.export(Time.now())
                withContext(Dispatchers.IO) {
                    c.appContext.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
                        ?: error("无法写入文件")
                }
                "已导出（不含 API Key）"
            } catch (e: Exception) {
                "导出失败：${e.message}"
            }
        }
    }

    fun readImport(uri: Uri) {
        viewModelScope.launch {
            try {
                val text = withContext(Dispatchers.IO) {
                    c.appContext.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
                        ?: error("无法读取文件")
                }
                pendingImport = c.backup.parse(text)
                dataMessage = null
            } catch (e: Exception) {
                dataMessage = "导入失败：${e.message}"
            }
        }
    }

    fun cancelImport() {
        pendingImport = null
    }

    fun confirmImport() {
        val file = pendingImport ?: return
        pendingImport = null
        viewModelScope.launch {
            dataMessage = try {
                c.backup.restore(file)
                c.events.log(EventType.DATA_IMPORTED, note = "${file.units.size} 个单元，${file.events.size} 条事件")
                "已导入：${file.units.size} 个学习单元，${file.events.size} 条事件"
            } catch (e: Exception) {
                "导入失败：${e.message}"
            }
        }
    }

    /** 设置页停留时长计入“折腾系统时间”。在应用级作用域里写，离开页面也写得进去。 */
    fun logSettingsTime(millis: Long) {
        if (millis < 10_000) return
        val minutes = Math.round(millis / 6_000.0) / 10.0
        c.appScope.launch { c.events.log(EventType.SETTINGS_TIME, value = minutes, note = "设置页") }
    }
}

private const val FIDDLE_LIMIT_MS = 10 * 60 * 1000L

@Composable
fun SettingsScreen(onBackToTraining: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val p = Paper.colors
    val context = LocalContext.current

    // —— 折腾系统计时：只算页面在前台的时间；累计满 10 分钟提示回到训练 ——
    var accumulated by remember { mutableLongStateOf(0L) }
    var resumedAt by remember { mutableLongStateOf(0L) }
    var nudge by remember { mutableStateOf(false) }
    var nudged by remember { mutableStateOf(false) }
    LifecycleResumeEffect(Unit) {
        resumedAt = Time.now()
        onPauseOrDispose {
            if (resumedAt != 0L) {
                val spent = Time.now() - resumedAt
                accumulated += spent
                resumedAt = 0L
                vm.logSettingsTime(spent)
            }
        }
    }
    LaunchedEffect(resumedAt) {
        if (resumedAt == 0L || nudged) return@LaunchedEffect
        delay((FIDDLE_LIMIT_MS - accumulated).coerceAtLeast(0L))
        nudge = true
        nudged = true
    }

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) vm.export(uri)
    }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readImport(uri)
    }

    if (!vm.loaded) return

    Column(
        Modifier
            .fillMaxSize()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PageHeader("设置", "配置项尽量少。在这里停留的时间会记成“折腾系统”。")

        // —— AI 教练 ——
        PaperCard {
            SectionLabel("AI 教练")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Provider.entries.forEach { pv ->
                    Choice(pv.label, selected = vm.provider == pv, modifier = Modifier.weight(1f)) { vm.chooseProvider(pv) }
                }
            }
            Spacer(Modifier.height(14.dp))
            Field("接口地址", if (vm.provider == Provider.ANTHROPIC) "默认 https://api.anthropic.com" else "填到 /v1 为止，例如 https://api.deepseek.com/v1") {
                RuledTextField(vm.baseUrl, { vm.baseUrl = it }, singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
            }
            Field("API Key", if (vm.keyStorageAvailable) "加密保存在本机，不进备份、不进日志" else "本机加密存储不可用，Key 无法保存") {
                RuledTextField(
                    vm.apiKey,
                    { vm.apiKey = it },
                    singleLine = true,
                    placeholder = "sk-…",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    secret = true,
                )
            }
            Field("便宜档模型", "出题、批改讲解和作答、点评举一反三") {
                RuledTextField(vm.cheapModel, { vm.cheapModel = it }, singleLine = true)
            }
            Field("旗舰档模型", "周复盘、技能拆解（以后的阶段才会用到）") {
                RuledTextField(vm.flagshipModel, { vm.flagshipModel = it }, singleLine = true)
            }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineButton("保存", onClick = { vm.saveAi() }, modifier = Modifier.weight(1f))
                LineButton(if (vm.busy) "测试中…" else "保存并测试", onClick = { vm.testAi() }, modifier = Modifier.weight(1f), enabled = !vm.busy)
            }
            vm.aiMessage?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = if (vm.aiMessageIsError) p.red else p.ink2)
            }
        }

        // —— 提醒 ——
        PaperCard {
            SectionLabel("每日提醒")
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "%02d:%02d".format(vm.reminderHour, vm.reminderMinute),
                        fontFamily = SerifSC,
                        fontWeight = FontWeight.Black,
                        style = MaterialTheme.typography.displaySmall,
                        color = if (vm.reminderEnabled) p.ink else p.ink2,
                        modifier = Modifier.clickable {
                            TimePickerDialog(context, { _, h, m -> vm.setReminder(vm.reminderEnabled, h, m) }, vm.reminderHour, vm.reminderMinute, true).show()
                        },
                    )
                    Hint("点时间修改。到点推送今日一件事，点通知直达今日。")
                }
                Switch(
                    checked = vm.reminderEnabled,
                    onCheckedChange = { vm.setReminder(it, vm.reminderHour, vm.reminderMinute) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = p.page,
                        checkedTrackColor = p.ink,
                        uncheckedThumbColor = p.ink2,
                        uncheckedTrackColor = p.grid,
                        uncheckedBorderColor = p.divider,
                    ),
                )
            }
            Spacer(Modifier.height(16.dp))
            PermissionRows()
        }

        // —— 数据 ——
        PaperCard {
            SectionLabel("数据")
            Spacer(Modifier.height(6.dp))
            Hint("全部数据只存在这台手机上。换机或备份时导出 JSON 文件。")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                LineButton("导出 JSON", onClick = { exportLauncher.launch("行为管理部-${Time.today()}.json") }, modifier = Modifier.weight(1f))
                LineButton("从 JSON 导入", onClick = { importLauncher.launch(arrayOf("application/json", "text/plain", "application/octet-stream")) }, modifier = Modifier.weight(1f))
            }
            vm.dataMessage?.let {
                Spacer(Modifier.height(10.dp))
                Text(it, style = MaterialTheme.typography.bodySmall, color = p.ink2)
            }
        }

        // —— 关于 ——
        PaperCard {
            SectionLabel("关于")
            Spacer(Modifier.height(6.dp))
            Hint("行为管理部 ${BuildConfig.VERSION_NAME} · 训练引擎（学习、三角洲、考公、动作技能）、防线、记录与周复盘")
            Hint(
                "提示词版本：" + listOf(Prompts.CRITIQUE, Prompts.QUESTIONS, Prompts.GRADE, Prompts.TRANSFER, Prompts.DECOMPOSE, Prompts.DRILLS, Prompts.WEEKLY).joinToString("、") { it.tag },
            )
        }

        InkButton("回到训练", onClick = onBackToTraining)
        Spacer(Modifier.height(16.dp))
    }

    if (nudge) {
        AlertDialog(
            onDismissRequest = { nudge = false },
            containerColor = p.page,
            title = { Text("已经在设置里待了 10 分钟", color = p.ink) },
            text = { Text("这段时间记成“折腾系统”。系统够用就行，回去练吧。", color = p.ink2) },
            confirmButton = {
                TextButton(onClick = { nudge = false; onBackToTraining() }) {
                    Text("回到训练", color = p.red, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = { TextButton(onClick = { nudge = false }) { Text("再待一会儿", color = p.ink2) } },
        )
    }

    vm.pendingImport?.let { file ->
        AlertDialog(
            onDismissRequest = { vm.cancelImport() },
            containerColor = p.page,
            title = { Text("用备份替换本机数据？", color = p.ink) },
            text = {
                Text(
                    "备份导出于 ${Time.full(file.exportedAt)}，含 ${file.units.size} 个学习单元、${file.reviews.size} 次自测、${file.events.size} 条事件。本机现有数据会被替换，API Key 和设置不变。",
                    color = p.ink2,
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmImport() }) { Text("替换", color = p.red, fontWeight = FontWeight.Bold) }
            },
            dismissButton = { TextButton(onClick = { vm.cancelImport() }) { Text("取消", color = p.ink) } },
        )
    }
}

@Composable
private fun Field(label: String, hint: String, content: @Composable () -> Unit) {
    Column(Modifier.padding(bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall, color = Paper.colors.ink)
        content()
        Hint(hint)
    }
}

@Composable
private fun Choice(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val p = Paper.colors
    Box(
        modifier
            .height(44.dp)
            .let { if (selected) it.background(p.ink, CardShape) else it.border(1.dp, p.divider, CardShape) }
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (selected) p.page else p.ink)
    }
}
