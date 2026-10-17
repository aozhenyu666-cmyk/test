package com.zongkong.app.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.graphicsLayer
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

/** 部门印章：方框里一个字。颜色变化（放行/严管切换）时淡过去，并轻轻弹一下。 */
@Composable
fun Seal(text: String, color: Color, size: Dp = 36.dp, filled: Boolean = false, modifier: Modifier = Modifier) {
    val c by animateColorAsState(color, tween(320), label = "sealColor")
    val reduce = LocalReduceMotion.current
    val pop = remember { androidx.compose.animation.core.Animatable(1f) }
    LaunchedEffect(color) {
        if (!reduce) {
            pop.snapTo(1.06f)
            pop.animateTo(1f, spring(stiffness = androidx.compose.animation.core.Spring.StiffnessMedium))
        }
    }
    Box(
        modifier = modifier
            .size(size)
            .graphicsLayer { scaleX = pop.value; scaleY = pop.value }
            .then(if (filled) Modifier.background(c, RoundedCornerShape(size * 0.18f)) else Modifier)
            .border(BorderStroke(if (size > 40.dp) 2.5.dp else 2.dp, c), RoundedCornerShape(size * 0.18f)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            color = if (filled) MaterialTheme.colorScheme.surface else c,
            fontWeight = FontWeight.Black,
            fontSize = (size.value * 0.5f).sp,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
fun DeptSeal(dept: Dept, size: Dp = 36.dp, filled: Boolean = false, modifier: Modifier = Modifier) =
    Seal(dept.seal, LocalSignals.current.dept(dept), size, filled, modifier)

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
    val c by animateColorAsState(color, tween(320), label = "tagColor")
    Surface(color = c.copy(alpha = 0.12f), shape = RoundedCornerShape(8.dp)) {
        Text(
            text,
            color = c,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

/**
 * 卡片：纸面上一块更亮的纸，圆角、无边框；accent 给一圈有颜色的细边。
 * [pulse]：让这道边轻轻呼吸（正在拦截、当前主线这类“活的”卡片）。可点的卡片按下会微缩。
 */
@Composable
fun Panel(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    pulse: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val base = accent
    val borderColor: Color? = if (base == null) null else if (pulse && !LocalReduceMotion.current) {
        val t = rememberInfiniteTransition(label = "panelPulse")
        val k by t.animateFloat(
            0.45f, 1f,
            infiniteRepeatable(tween(2000, easing = androidx.compose.animation.core.LinearEasing), RepeatMode.Reverse),
            label = "pk",
        )
        base.copy(alpha = k)
    } else {
        base.copy(alpha = 0.7f)
    }
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .animateContentSize(tween(220))
            .then(if (onClick != null) Modifier.pressScale(source) else Modifier)
            .then(if (onClick != null) Modifier.clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick) else Modifier),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = borderColor?.let { BorderStroke(1.5.dp, it) },
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

/** 页面大标题：衬线大字 + 一行小字。 */
@Composable
fun ScreenTitle(title: String, subtitle: String = "", modifier: Modifier = Modifier) {
    Column(modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        if (subtitle.isNotBlank()) Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LocalSignals.current.muted)
    }
}

/** 大按钮：一页只有一个主动作时用，56dp 高。按下微缩，可用状态变化时颜色淡过去。 */
@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary, enabled: Boolean = true) {
    val source = remember { MutableInteractionSource() }
    val c by animateColorAsState(
        if (enabled) color else MaterialTheme.colorScheme.surfaceVariant,
        tween(260),
        label = "bigButton",
    )
    androidx.compose.material3.Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth().heightIn(min = 56.dp).pressScale(source),
        shape = RoundedCornerShape(16.dp),
        interactionSource = source,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = c,
            disabledContainerColor = c,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        ),
    ) { Text(text, style = MaterialTheme.typography.titleMedium) }
}

/** 向导里的一个大选项：一行标题 + 一行说明，选中时描边（选中/取消时颜色淡过去）。 */
@Composable
fun ChoiceCard(title: String, detail: String = "", selected: Boolean = false, color: Color = MaterialTheme.colorScheme.primary, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val source = androidx.compose.runtime.remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
    val border by animateColorAsState(if (selected) color else LocalSignals.current.line, tween(220), label = "choiceBorder")
    val bg by animateColorAsState(if (selected) color.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface, tween(220), label = "choiceBg")
    val tc by animateColorAsState(if (selected) color else MaterialTheme.colorScheme.onSurface, tween(220), label = "choiceText")
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .pressScale(source)
            .clickable(interactionSource = source, indication = androidx.compose.material3.ripple(), onClick = onClick),
        color = bg,
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(if (selected) 2.dp else 1.dp, border),
    ) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = tc)
            if (detail.isNotBlank()) Text(detail, style = MaterialTheme.typography.bodyMedium, color = LocalSignals.current.muted)
        }
    }
}

/** 小药丸：状态、数字。颜色变化时淡过去。 */
@Composable
fun Pill(text: String, color: Color, dot: Boolean = false) {
    val c by animateColorAsState(color, tween(320), label = "pill")
    Surface(color = c.copy(alpha = 0.12f), shape = RoundedCornerShape(50)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (dot) PulseDot(c)
            MorphText(text, style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold), color = c)
        }
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
        title = { Text(title, style = MaterialTheme.typography.titleLarge.merge(SerifTitle)) },
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

fun clock(ms: Long, zone: ZoneId = com.zongkong.core.DayClock.BEIJING): String =
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
    val c by animateColorAsState(valueColor, tween(320), label = "keyValue")
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(key, style = MaterialTheme.typography.bodyMedium, color = LocalSignals.current.muted, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium.merge(Mono), color = c)
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
