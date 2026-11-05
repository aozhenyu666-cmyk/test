package com.yishou.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.companion.CompanionHealth
import com.yishou.app.companion.CompanionPrefs
import com.yishou.app.companion.CompanionRunner
import com.yishou.app.core.CompanionConfig
import com.yishou.app.data.Breakpoint
import com.yishou.app.data.Echo
import com.yishou.app.data.EchoKind
import com.yishou.app.data.Task
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 伴读主页：它现在看到什么、问过什么、记住了什么、多久问一次。
 *
 * 一屏之内能完成：开关、改节奏、回答当前问题、看它记住的判断。
 */
@Composable
fun CompanionScreen(
    onBack: () -> Unit,
    onOpenWatched: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val store = remember { CompanionPrefs(context) }
    var cfg by remember { mutableStateOf(store.load()) }
    var tick by remember { mutableIntStateOf(0) }
    var task by remember { mutableStateOf<Task?>(null) }
    var bp by remember { mutableStateOf<Breakpoint?>(null) }
    var memories by remember { mutableStateOf<List<Echo>>(emptyList()) }
    var pending by remember { mutableStateOf<String?>(null) }
    var misses by remember { mutableIntStateOf(0) }
    var asked by remember { mutableIntStateOf(0) }
    var lastAt by remember { mutableStateOf(0L) }
    var answer by remember { mutableStateOf("") }
    var note by remember { mutableStateOf<String?>(null) }
    val serviceOn = remember(tick) { PermissionStatus.accessibility(context) }
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val llm by app.settings.llm.collectAsStateWithLifecycle()
    val vision by app.settings.vision.collectAsStateWithLifecycle()
    val modelOn = llm.isComplete && vision.isComplete

    val fixService: () -> Unit = {
        if (!CompanionHealth.rebindIfAllowed(context)) {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            }
        }
        tick += 1
    }

    LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_RESUME) { tick++ }

    LaunchedEffect(tick) {
        val dao = app.database.dao()
        task = dao.getCurrentTask()
        bp = task?.let { dao.getBreakpoint(it.id) }
        memories = app.database.echoes().recent().filter { it.scene == "companion" }.take(4)
        pending = store.pendingQuestion()
        misses = store.misses()
        asked = store.promptsInHalfHour(System.currentTimeMillis())
        lastAt = store.lastPromptAt()
        CompanionHealth.check(context, serviceOn)
    }

    fun persist(next: CompanionConfig) {
        cfg = next.sane()
        store.save(cfg)
        tick++
    }

    Scaffold(topBar = { BackTopBar("伴读", onBack) }) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            HeroCard(
                cfg = cfg,
                serviceOn = serviceOn,
                modelOn = modelOn,
                asked = asked,
                misses = misses,
                lastAt = lastAt,
                onToggle = { on -> persist(cfg.copy(enabled = on)) },
                onFixService = fixService,
            )

            AskCard(
                pending = pending,
                answer = answer,
                note = note,
                onAnswer = { answer = it },
                onSubmit = {
                    val text = answer.trim()
                    if (text.isEmpty()) {
                        note = "先回一句。"
                    } else {
                        val check = CompanionRunner.answer(context, text)
                        if (check.pass) {
                            note = null
                            answer = ""
                            pending = null
                            tick++
                        } else {
                            note = check.hint
                        }
                    }
                },
                onSkip = {
                    CompanionRunner.skip(context)
                    pending = null
                    note = null
                    answer = ""
                    tick++
                },
            )

            ContextCard(
                task = task,
                bp = bp,
                watched = prefs.watched.size,
                onOpenWatched = onOpenWatched,
                modelOn = modelOn,
            )

            RhythmCard(cfg = cfg, onChange = ::persist)

            MemoryCard(memories = memories)

            Text(
                "截图只在内存里，不保存；关掉伴读就完全停止。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = onOpenSettings) { Text("更多设置") }
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun HeroCard(
    cfg: CompanionConfig,
    serviceOn: Boolean,
    modelOn: Boolean,
    asked: Int,
    misses: Int,
    lastAt: Long,
    onToggle: (Boolean) -> Unit,
    onFixService: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("伴读", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        if (cfg.enabled) "在关注的应用里待够时间，它会问一句" else "已关闭，不会主动开口",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = cfg.enabled, onCheckedChange = onToggle)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                StatusDot(serviceOn, if (serviceOn) "无障碍在跑" else "无障碍没开", MaterialTheme.colorScheme.error)
                StatusDot(modelOn, if (modelOn) "模型已配" else "本地模式", MaterialTheme.colorScheme.tertiary)
            }
            if (!serviceOn) {
                TextButton(onClick = onFixService) { Text("去开启无障碍") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Stat("近 30 分钟", "$asked 次")
                Stat("今天没答", "$misses 次")
                Stat("上次开口", if (lastAt == 0L) "还没有" else ago(lastAt))
            }
        }
    }
}

@Composable
private fun AskCard(
    pending: String?,
    answer: String,
    note: String?,
    onAnswer: (String) -> Unit,
    onSubmit: () -> Unit,
    onSkip: () -> Unit,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("现在", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            if (pending == null) {
                Text("没有待回答的问题。", style = MaterialTheme.typography.titleMedium)
                Text(
                    "下一次它开口时，会带着你当前的任务或卡点，不会泛泛问“在干嘛”。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(pending, style = MaterialTheme.typography.titleLarge.copy(lineHeight = 30.sp))
                OutlinedTextField(
                    value = answer,
                    onValueChange = onAnswer,
                    placeholder = { Text("判断 / 休息 / 现在回去 / 定个时间") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 2,
                    shape = RoundedCornerShape(16.dp),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("休息", "现在回去", "定个时间").forEach { preset ->
                        FilterChip(
                            selected = answer == preset,
                            onClick = { onAnswer(preset) },
                            label = { Text(preset) },
                            shape = RoundedCornerShape(12.dp),
                        )
                    }
                }
                note?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = onSubmit,
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(14.dp),
                    ) { Text("交这一句") }
                    TextButton(onClick = onSkip) { Text("不想答") }
                }
            }
        }
    }
}

@Composable
private fun ContextCard(
    task: Task?,
    bp: Breakpoint?,
    watched: Int,
    onOpenWatched: () -> Unit,
    modelOn: Boolean,
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("它看到的", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            if (task == null) {
                Text("还没有当前任务。", style = MaterialTheme.typography.titleMedium)
                Text("设一个当前任务，它才能把“你在刷视频”和“你本来要做什么”连起来。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text(task.title, style = MaterialTheme.typography.titleMedium)
                if (!bp?.stuck.isNullOrBlank()) {
                    Text("卡在：${bp?.stuck}", style = MaterialTheme.typography.bodyMedium)
                }
                if (!bp?.nextQuestion.isNullOrBlank()) {
                    Text("上次留下的问题：${bp?.nextQuestion}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("关注的应用", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = onOpenWatched) { Text("$watched 个，去管理") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("模型", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (modelOn) "识屏 + 出题" else "本地提醒（没配模型）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun RhythmCard(cfg: CompanionConfig, onChange: (CompanionConfig) -> Unit) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("节奏", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Preset("轻盈", cfg.triggerSeconds >= 60) { onChange(LIGHT) }
                Preset("标准", cfg.triggerSeconds in 30..59) { onChange(STANDARD) }
                Preset("紧一点", cfg.triggerSeconds < 30) { onChange(TIGHT) }
            }
            SettingRow("连续用多久开始", cfg.triggerSeconds, 10..180, "秒") { v ->
                onChange(cfg.copy(triggerSeconds = v))
            }
            SettingRow("两次之间至少", cfg.gapSeconds, 60..900, "秒") { v ->
                onChange(cfg.copy(gapSeconds = v))
            }
            SettingRow("30 分钟最多", cfg.maxPerHalfHour, 1..12, "次") { v ->
                onChange(cfg.copy(maxPerHalfHour = v))
            }
            SettingRow("不答多久提醒", cfg.nudgeSeconds, 30..240, "秒") { v ->
                onChange(cfg.copy(nudgeSeconds = v))
            }
            SettingRow("不答多久收回", cfg.closeSeconds, 60..300, "秒") { v ->
                onChange(cfg.copy(closeSeconds = v))
            }
            SettingRow("一天几次没答就停", cfg.stopAfterMisses, 1..6, "次") { v ->
                onChange(cfg.copy(stopAfterMisses = v))
            }
        }
    }
}

@Composable
private fun MemoryCard(memories: List<Echo>) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("它记得你", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.tertiary)
            if (memories.isEmpty()) {
                Text(
                    "还没有记住的判断。答过几次之后，这里会出现你同意过什么、在哪儿卡住过。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                memories.forEach { echo ->
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            EchoKind.label(echo.kind),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(echo.text, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun StatusDot(on: Boolean, label: String, offColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(8.dp)
                .background(
                    if (on) Color(0xFF3E9B6B) else offColor,
                    CircleShape,
                ),
        )
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun Stat(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun SettingRow(label: String, value: Int, range: IntRange, unit: String, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Stepper(value, range, unit, onChange)
    }
}

@Composable
private fun Preset(label: String, selected: Boolean, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(label) },
        shape = RoundedCornerShape(12.dp),
    )
}

private fun ago(at: Long): String {
    val minutes = ((System.currentTimeMillis() - at) / 60_000).toInt()
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "${minutes} 分钟前"
        else -> SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(at))
    }
}

private val LIGHT = CompanionConfig(
    enabled = true,
    triggerSeconds = 90,
    gapSeconds = 300,
    maxPerHalfHour = 3,
    nudgeSeconds = 120,
    closeSeconds = 180,
    stopAfterMisses = 2,
)

private val STANDARD = CompanionConfig(
    enabled = true,
    triggerSeconds = 45,
    gapSeconds = 180,
    maxPerHalfHour = 6,
    nudgeSeconds = 90,
    closeSeconds = 150,
    stopAfterMisses = 3,
)

private val TIGHT = CompanionConfig(
    enabled = true,
    triggerSeconds = 20,
    gapSeconds = 120,
    maxPerHalfHour = 10,
    nudgeSeconds = 60,
    closeSeconds = 120,
    stopAfterMisses = 4,
)
