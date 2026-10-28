package com.yishou.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.gate.GateActivity
import com.yishou.app.gate.GateService
import com.yishou.app.gate.GateTrace
import kotlinx.coroutines.delay

/**
 * 拦截体检：打开娱乐应用没被拦时来这里看。上面是各项开关的现状，下面是每次切换应用的拦截日志。
 * 点“复制体检结果”可以整段发给别人帮你看。
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GateDoctorScreen(onBack: () -> Unit, onPickApps: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val connected by GateService.connected.collectAsStateWithLifecycle()
    val problem by GateService.problem.collectAsStateWithLifecycle()
    val clipboard = LocalClipboardManager.current
    var tick by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { GateTrace.init(context); tick++ }
    LaunchedEffect(Unit) { while (true) { delay(2_000); tick++ } }
    val entries = remember(tick) { GateTrace.recent() }
    var passes by remember { mutableStateOf<List<String>>(emptyList()) }
    LaunchedEffect(tick, prefs.watched) {
        val now = System.currentTimeMillis()
        passes = prefs.watched.values.distinct().mapNotNull { g ->
            app.database.dao().activePass(g, now)?.let { "「$g」放行到 ${GateTrace.stamp(it.endAt)}" }
        }
    }
    val pm = context.packageManager
    fun label(pkg: String) = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault("（没装）")

    val enabled = remember(tick) { PermissionStatus.accessibility(context) }
    val battery = remember(tick) { PermissionStatus.batteryUnrestricted(context) }
    val overlay = remember(tick) { Settings.canDrawOverlays(context) }
    val last = GateService.lastEventAt
    val checks = listOf(
        "关注的应用" to if (prefs.watched.isEmpty()) "✗ 一个都没选，什么都不会拦" else "✓ ${prefs.watched.size} 个",
        "无障碍开关" to if (enabled) "✓ 系统里开着" else "✗ 没开（或被系统关掉了）",
        "无障碍服务" to if (connected) "✓ 已连上" else if (enabled) "✗ 开关开着但服务没在运行：关掉再打开一次；再不行就是被系统杀了，去开自启动和后台" else "✗ 没在运行",
        "最近一次收到切换" to if (last == 0L) "✗ 这次开机以来一次都没收到" else "${(System.currentTimeMillis() - last) / 1000} 秒前",
        "显示在其他应用上层" to if (overlay) "✓ 允许" else "✗ 没允许（有的手机把“后台弹出界面”也放在这里）",
        "电池不限制" to if (battery) "✓ 不限制" else "✗ 受限制，容易被系统停掉",
    )
    val report = buildString {
        appendLine("一手拦截体检 ${GateTrace.stamp(System.currentTimeMillis())}")
        checks.forEach { (k, v) -> appendLine("$k：$v") }
        appendLine("关注：" + prefs.watched.entries.joinToString("、") { "${label(it.key)}(${it.key})→${it.value}" })
        if (passes.isNotEmpty()) appendLine("当前放行：" + passes.joinToString("；"))
        problem?.let { appendLine("提示：$it") }
        appendLine("—— 拦截日志（新的在下）——")
        append(GateTrace.export())
    }

    Scaffold(topBar = { BackTopBar("拦截体检", onBack) }) { padding ->
        LazyColumn(Modifier.padding(padding).fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            item {
                Text("打开娱乐应用没被拦时，先看这里。打一个 ✗ 的就是要修的地方。下面的日志记着每次切换时一手看到了什么、为什么拦或不拦。",
                    style = MaterialTheme.typography.bodySmall)
            }
            items(checks) { (k, v) ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(k)
                    Text(v, color = if (v.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                }
            }
            item {
                Text("关注的应用", style = MaterialTheme.typography.titleSmall)
                if (prefs.watched.isEmpty()) Text("还没选。")
                prefs.watched.forEach { (pkg, group) -> Text("· ${label(pkg)}（$pkg）→ $group", style = MaterialTheme.typography.bodySmall) }
                passes.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
            item {
                Text("国产手机最常见的原因：没开“后台弹出界面”。一手弹出入口页时系统不报错，只是不显示。" +
                    "现在一手会改用遮罩挡住；想要直接弹出题目页，请打开它：设置 → 应用 → 一手 → 权限 → 后台弹出界面（vivo/OPPO/小米都有这一项，有的叫“后台弹出页面”）。",
                    style = MaterialTheme.typography.bodySmall)
            }
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(onClick = { clipboard.setText(AnnotatedString(report)) }) { Text("复制体检结果") }
                    OutlinedButton(onClick = { context.startActivity(GateActivity.testIntent(context)) }) { Text("试一道题") }
                    OutlinedButton(onClick = onPickApps) { Text("选择娱乐应用") }
                    OutlinedButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("无障碍设置") }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName)))
                    }) { Text("一手的权限页") }
                    OutlinedButton(onClick = { GateTrace.clear(); tick++ }) { Text("清空日志") }
                }
            }
            item { Text("拦截日志（新的在上）", style = MaterialTheme.typography.titleSmall) }
            if (entries.isEmpty()) item { Text("还没有记录。去打开一次娱乐应用，再回来看。", style = MaterialTheme.typography.bodySmall) }
            items(entries) { e ->
                Text("${GateTrace.stamp(e.at)}  ${e.text}" + (e.pkg?.let { "\n    $it" } ?: ""),
                    style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}
