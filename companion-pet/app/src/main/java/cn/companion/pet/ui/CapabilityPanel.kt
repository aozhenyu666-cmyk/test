package cn.companion.pet.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cn.companion.pet.core.action.*
import cn.companion.pet.core.session.AssistantApp
import cn.companion.pet.ui.components.*
import cn.companion.pet.ui.theme.CompanionSpacing

fun ActionKind.label(): String = when (this) {
    ActionKind.READ_MEMORY -> "读取记忆"
    ActionKind.READ_MATERIALS -> "查看素材"
    ActionKind.SAVE_NOTE -> "保存念头"
    ActionKind.OPEN_ASSISTANT -> "打开 AI 应用"
    ActionKind.SPEAK -> "朗读文字"
    ActionKind.GET_DEVICE_STATE -> "设备状态"
    ActionKind.FIND_NODE -> "查找控件"
    ActionKind.OPEN_APP -> "打开应用"
    ActionKind.TAP_NODE -> "点击控件"
    ActionKind.SCROLL -> "滚动页面"
    ActionKind.INPUT_TEXT -> "输入文字"
    ActionKind.BACK -> "返回"
    ActionKind.GET_LAST_OBSERVATION -> "最近观察"
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CapabilityPanel(
    state: ActionSessionState,
    onSubmit: (ActionKind, Map<String, String>) -> Boolean,
    onCancel: () -> Unit,
    onCheck: () -> Unit,
    onBack: () -> Unit,
    history: List<ActionHistoryEntry> = emptyList(),
    historyError: String? = null,
    onHistoryCheck: (ActionHistoryEntry) -> Unit = {},
) {
    var selected by rememberSaveable { mutableStateOf(ActionKind.READ_MEMORY.name) }
    var input by rememberSaveable(selected) { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf(AssistantApp.GEMINI.name) }
    var deviceText by rememberSaveable(selected) { mutableStateOf("") }
    var preview by remember { mutableStateOf<Map<String, String>?>(null) }
    val kind = ActionKind.valueOf(selected)
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(CompanionSpacing.page), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onBack) { Text("返回设置") }
            PageHeader("能力面板", "按你的指令读取、记录或打开应用。保存前会再次确认。")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ActionKind.entries.forEach { choice ->
                    FilterChip(selected = kind == choice, onClick = { selected = choice.name },
                        enabled = !state.busy, label = { Text(choice.label()) })
                }
            }
            when (kind) {
                ActionKind.READ_MEMORY -> OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(),
                    enabled = !state.busy, label = { Text("搜索记忆（可留空）") })
                ActionKind.SAVE_NOTE, ActionKind.SPEAK -> OutlinedTextField(input, { input = it },
                    Modifier.fillMaxWidth(), enabled = !state.busy,
                    label = { Text(if (kind == ActionKind.SAVE_NOTE) "想记下什么" else "想朗读什么") })
                ActionKind.OPEN_ASSISTANT -> {
                    Text("将打开：" + AssistantApp.valueOf(target).label)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistantApp.entries.forEach { app ->
                            FilterChip(target == app.name, { target = app.name }, enabled = !state.busy,
                                label = { Text(app.label) })
                        }
                    }
                    Text("系统接受打开请求后，请在目标应用中核对。", style = MaterialTheme.typography.bodySmall)
                }
                ActionKind.READ_MATERIALS -> Text("只查看你主动保存、仍然有效的素材，并标明只有链接、摘录或字幕。")
                ActionKind.GET_DEVICE_STATE, ActionKind.BACK, ActionKind.GET_LAST_OBSERVATION ->
                    Text("只做一次受限制的原生调用；结果需要重新观察，不能仅凭 API 返回 true 声称完成。")
                ActionKind.FIND_NODE -> OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(),
                    enabled = !state.busy, label = { Text("控件文字/资源 ID") })
                ActionKind.OPEN_APP -> OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(),
                    enabled = !state.busy, label = { Text("已授权应用的包名") })
                ActionKind.TAP_NODE, ActionKind.SCROLL -> OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(),
                    enabled = !state.busy, label = { Text("最近观察里的节点 ID") })
                ActionKind.INPUT_TEXT -> {
                    OutlinedTextField(input, { input = it }, Modifier.fillMaxWidth(), enabled = !state.busy,
                        label = { Text("最近观察里的输入框节点 ID") })
                    OutlinedTextField(deviceText, { deviceText = it }, Modifier.fillMaxWidth(), enabled = !state.busy,
                        label = { Text("要写入的普通文字（不要密码/验证码）") })
                }
            }
            if (kind == ActionKind.SPEAK) Text("需在设置中开启朗读。提交成功表示语音引擎已接受，不代表已播完。",
                style = MaterialTheme.typography.bodySmall)
            CutAction(if (kind == ActionKind.SAVE_NOTE) "预览念头" else "执行", enabled = !state.busy, onClick = {
                val args = when (kind) {
                    ActionKind.READ_MEMORY -> mapOf("query" to input)
                    ActionKind.READ_MATERIALS -> emptyMap()
                    ActionKind.SAVE_NOTE, ActionKind.SPEAK -> mapOf("text" to input)
                    ActionKind.OPEN_ASSISTANT -> mapOf("target" to target)
                    ActionKind.GET_DEVICE_STATE, ActionKind.BACK, ActionKind.GET_LAST_OBSERVATION -> emptyMap()
                    ActionKind.FIND_NODE -> mapOf("query" to input)
                    ActionKind.OPEN_APP -> mapOf("package" to input)
                    ActionKind.TAP_NODE -> mapOf("node_id" to input)
                    ActionKind.SCROLL -> mapOf("node_id" to input)
                    ActionKind.INPUT_TEXT -> mapOf("node_id" to input, "text" to deviceText)
                }
                if (kind == ActionKind.SAVE_NOTE) preview = args.toMap() else onSubmit(kind, args)
            })
            if (state.busy) {
                Text(if (state.phase == ActionPhase.PENDING) "等待执行" else "正在执行：" + state.request!!.kind.label())
                OutlinedButton(onClick = onCancel) { Text("取消本次操作") }
            }
            state.result?.let { result ->
                Panel {
                    Text(result.kind.label() + " · " + when (result.status) {
                        ActionStatus.SUCCESS -> "成功"
                        ActionStatus.UNAVAILABLE -> "暂不可用"
                        ActionStatus.FAILED -> "未完成"
                        ActionStatus.CANCELLED -> "已取消"
                    }, style = MaterialTheme.typography.titleMedium)
                    Text(result.reason)
                    val content = result.data["displayText"] ?: result.data["text"]
                    if (!content.isNullOrBlank()) Text(content)
                }
            }
            if (state.request != null && !state.busy) {
                TextButton(onClick = onCheck) { Text("查看上次操作结果") }
                Text("此按钮只核对记录；再次点执行会发起一项新操作。", style = MaterialTheme.typography.bodySmall)
            }
            historyError?.let { Text(it) }
            if (history.isNotEmpty()) {
                GroupTitle("最近写入和系统操作")
                Text("重启后也可核对。未确认的打开或朗读不会自动重做。", style = MaterialTheme.typography.bodySmall)
                history.forEach { entry ->
                    val time = java.time.Instant.ofEpochMilli(entry.preparedAt).atZone(java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("MM-dd HH:mm"))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Column(Modifier.weight(1f)) {
                            Text(entry.kind.label())
                            Text(time + " · " + when (entry.status) {
                                null -> "需要核对"
                                ActionStatus.SUCCESS -> "已收到成功回执"
                                ActionStatus.UNAVAILABLE -> "暂不可用"
                                ActionStatus.FAILED -> "未完成"
                                ActionStatus.CANCELLED -> "已取消"
                            }, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(enabled = !state.busy, onClick = { onHistoryCheck(entry) }) { Text("核对") }
                    }
                    Rule()
                }
            }
        }
    }
    preview?.let { frozen ->
        AlertDialog(onDismissRequest = { preview = null }, title = { Text("确认保存念头") },
            text = { Text(frozen["text"].orEmpty()) },
            confirmButton = { TextButton(onClick = { onSubmit(ActionKind.SAVE_NOTE, frozen); preview = null }) { Text("确认保存") } },
            dismissButton = { TextButton(onClick = { preview = null }) { Text("返回修改") } })
    }
}
