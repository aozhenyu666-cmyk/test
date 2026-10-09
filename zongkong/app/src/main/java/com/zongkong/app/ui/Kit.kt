package com.zongkong.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.zongkong.core.Dept
import com.zongkong.core.GatePhase
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.ZoneId

/** 部门印章：方框里一个字。 */
@Composable
fun Seal(text: String, color: Color, size: Dp = 36.dp, filled: Boolean = false) {
    Box(
        modifier = Modifier
            .size(size)
            .then(if (filled) Modifier.background(color, RoundedCornerShape(4.dp)) else Modifier)
            .border(BorderStroke(2.dp, color), RoundedCornerShape(4.dp)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) MaterialTheme.colorScheme.surface else color,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.5f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun DeptSeal(dept: Dept, size: Dp = 36.dp, filled: Boolean = false) = Seal(dept.seal, LocalSignals.current.dept(dept), size, filled)

@Composable
fun phaseColor(phase: GatePhase): Color {
    val s = LocalSignals.current
    return when (phase) {
        GatePhase.NOT_OPEN -> s.muted
        GatePhase.OPEN -> MaterialTheme.colorScheme.primary
        GatePhase.DUE_SOON -> s.warn
        GatePhase.OVERDUE -> s.strict
        GatePhase.DONE -> s.free
        GatePhase.DONE_LATE -> s.warn
    }
}

@Composable
fun Tag(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.12f), shape = RoundedCornerShape(4.dp)) {
        Text(
            text,
            color = color,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/** 带细边框的卡片。 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(if (accent != null) 1.5.dp else 1.dp, accent ?: LocalSignals.current.line),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = LocalSignals.current.muted,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title, style = MaterialTheme.typography.titleMedium) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") }
        },
        actions = { actions() },
        colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.background),
        windowInsets = WindowInsets(0, 0, 0, 0),
    )
}

@Composable
fun NumberField(label: String, value: String, onChange: (String) -> Unit, modifier: Modifier = Modifier, suffix: String = "") {
    OutlinedTextField(
        value = value,
        onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(5)) },
        label = { Text(label) },
        suffix = if (suffix.isNotEmpty()) ({ Text(suffix) }) else null,
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        modifier = modifier,
    )
}

/** 每 [periodMillis] 刷新一次的“现在”。 */
@Composable
fun rememberNow(periodMillis: Long = 15_000): Long {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(periodMillis) {
        while (true) {
            delay(periodMillis)
            now = System.currentTimeMillis()
        }
    }
    return now
}

fun clock(ms: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(ms).atZone(zone).toLocalTime().toString().take(5)

/** 1:05 / 12 分钟 */
fun span(ms: Long): String {
    val m = (ms / 60_000).coerceAtLeast(0)
    return if (m >= 60) "${m / 60} 小时 ${m % 60} 分" else "$m 分钟"
}

@Composable
fun Hint(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = LocalSignals.current.muted, modifier = modifier)
}

@Composable
fun KeyValue(key: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onSurface) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = LocalSignals.current.muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Mono), color = valueColor)
    }
}

// ---------- 系统跳转 ----------

fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/** 个别系统没有对应的设置页，打不开时退回到应用信息页。 */
fun safeStart(context: Context, intent: Intent) {
    try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (e: Exception) {
        try {
            context.startActivity(appDetails(context).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}

/** “去做”：应用包名就打开应用，链接就用浏览器 / Notion 打开。返回是否成功。 */
fun launchTarget(context: Context, target: String): Boolean {
    val t = target.trim()
    if (t.isEmpty()) return false
    val intent = if (t.startsWith("http://") || t.startsWith("https://")) {
        Intent(Intent.ACTION_VIEW, Uri.parse(t))
    } else {
        val pm = context.packageManager
        pm.getLaunchIntentForPackage(t) ?: pm.getLaunchIntentForPackage("$t.debug") ?: return false
    }
    return try {
        context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        true
    } catch (e: Exception) {
        false
    }
}

// ---------- 事项、行动用的小部件 ----------

/** 衬线标题（任务卡、事项名）。 */
val SerifTitle = androidx.compose.ui.text.TextStyle(
    fontFamily = androidx.compose.ui.text.font.FontFamily.Serif,
    fontWeight = FontWeight.SemiBold,
)

@Composable
fun actionColor(s: com.zongkong.core.work.ActionStatus): Color {
    val sig = LocalSignals.current
    return when (s) {
        com.zongkong.core.work.ActionStatus.TODO -> MaterialTheme.colorScheme.primary
        com.zongkong.core.work.ActionStatus.DOING -> sig.warn
        com.zongkong.core.work.ActionStatus.CONFIRM -> sig.think
        com.zongkong.core.work.ActionStatus.DONE -> sig.free
        com.zongkong.core.work.ActionStatus.STUCK -> sig.strict
        com.zongkong.core.work.ActionStatus.RESCHEDULED -> sig.muted
        com.zongkong.core.work.ActionStatus.CANCELLED -> sig.muted
    }
}

@Composable
fun threadColor(s: com.zongkong.core.work.ThreadStatus): Color {
    val sig = LocalSignals.current
    return when (s) {
        com.zongkong.core.work.ThreadStatus.COLLECTING -> sig.info
        com.zongkong.core.work.ThreadStatus.THINKING -> sig.think
        com.zongkong.core.work.ThreadStatus.READY -> MaterialTheme.colorScheme.primary
        com.zongkong.core.work.ThreadStatus.ACTIVE -> sig.warn
        com.zongkong.core.work.ThreadStatus.STUCK -> sig.strict
        com.zongkong.core.work.ThreadStatus.DONE -> sig.free
        com.zongkong.core.work.ThreadStatus.PAUSED, com.zongkong.core.work.ThreadStatus.CANCELLED -> sig.muted
    }
}

/** 同步状态：已同步 / 待同步 / 同步失败 / 有冲突 / 仅本机。 */
@Composable
fun SyncTag(r: com.zongkong.core.work.Rec, notionReady: Boolean) {
    val sig = LocalSignals.current
    val (label, c) = when {
        !notionReady -> "仅本机" to sig.muted
        r.sync == com.zongkong.core.work.SyncState.SYNCED && r.syncError.isNotBlank() -> "已同步·有列缺失" to sig.warn
        r.sync == com.zongkong.core.work.SyncState.SYNCED -> "已同步" to sig.free
        r.sync == com.zongkong.core.work.SyncState.FAILED -> "同步失败" to sig.strict
        r.sync == com.zongkong.core.work.SyncState.CONFLICT -> "有冲突" to sig.strict
        else -> "待同步" to sig.warn
    }
    Text(label, style = MaterialTheme.typography.labelSmall, color = c)
}

/** 整体同步状态的一行字。 */
@Composable
fun SyncLine(work: com.zongkong.core.work.Work, notionReady: Boolean, onSync: (() -> Unit)?) {
    val sig = LocalSignals.current
    val text = when {
        !notionReady -> "Notion 未连接：记录只在手机上（设置 → Notion）"
        work.lastSyncAt == 0L -> "还没同步过 Notion"
        work.lastSyncOk -> "Notion ${clock(work.lastSyncAt)} 同步：${work.lastSyncMessage}" + if (work.pendingCount > 0) " · ${work.pendingCount} 条待同步" else ""
        else -> "Notion ${clock(work.lastSyncAt)} ${work.lastSyncMessage}" + if (work.pendingCount > 0) " · ${work.pendingCount} 条待同步" else ""
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text, style = MaterialTheme.typography.bodySmall,
            color = if (!work.lastSyncOk && notionReady) sig.strict else sig.muted, modifier = Modifier.weight(1f),
        )
        if (onSync != null && notionReady) {
            androidx.compose.material3.TextButton(onClick = onSync, modifier = Modifier.testTag("sync-now")) { Text("同步") }
        }
    }
}

/** 一行字段：标签 + 内容，点了可以编辑。 */
@Composable
fun FieldRow(label: String, value: String, placeholder: String = "（空）", onClick: (() -> Unit)? = null) {
    Column(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = LocalSignals.current.muted)
        Text(
            value.ifBlank { placeholder },
            style = MaterialTheme.typography.bodyMedium,
            color = if (value.isBlank()) LocalSignals.current.muted else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 编辑一个文本字段的对话框。 */
@Composable
fun EditDialog(title: String, initial: String, hint: String = "", singleLine: Boolean = false, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(initial) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (hint.isNotBlank()) Hint(hint)
                androidx.compose.material3.OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = singleLine,
                    minLines = if (singleLine) 1 else 3, modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(text); onDismiss() }) { Text("保存") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
