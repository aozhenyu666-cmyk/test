package com.moling.app.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.moling.app.BrainHost
import com.moling.app.overlay.Presence
import com.moling.app.overlay.PresenceStore
import kotlinx.coroutines.launch

@Composable
fun PresenceSettings(onDone: () -> Unit) {
    val context = LocalContext.current
    val store = remember { PresenceStore(context) }
    var allowed by remember { mutableStateOf(Settings.canDrawOverlays(context)) }
    var revision by remember { mutableIntStateOf(0) }
    val lifecycle = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event -> if (event == Lifecycle.Event.ON_RESUME) { allowed = Settings.canDrawOverlays(context); revision++ } }
        lifecycle.lifecycle.addObserver(observer)
        onDispose { lifecycle.lifecycle.removeObserver(observer) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        TextButton(onClick = onDone) { Text("← 返回") }
        Text("在你身边", style = MaterialTheme.typography.headlineSmall)
        listOf("overlay" to "小窗", "question" to "先答一问", "brief" to "晨报", "reminders" to "到点提醒", "share" to "分享进墨灵", "handoff" to "自动接住收线").forEach { (key, label) ->
            var checked by remember(key) { mutableStateOf(store.enabled(key)) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(label, Modifier.weight(1f).padding(top = 12.dp))
                Switch(checked, onCheckedChange = {
                    checked = it; store.set(key, it)
                    if (key == "question" && !it) Presence.question.value = null
                    scope.launch { Presence.render(context) }
                })
            }
        }
        // 第十一批 R3：明确停止 → 安静期；可查看、可恢复。
        val brainState by BrainHost.state.collectAsState()
        val quiet = com.moling.core.live.Habits.quietLine(brainState.habits, System.currentTimeMillis())
        Text(if (quiet.isBlank()) "安静期：没有" else quiet)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = { scope.launch { BrainHost.stopLive() } }) { Text("先安静") }
            TextButton(enabled = quiet.isNotBlank(), onClick = { scope.launch { BrainHost.restoreLive() } }) { Text("恢复") }
        }
        Text("安静期内她不会自发出声；你手动开电台 / 找她说话不受影响。", style = MaterialTheme.typography.bodySmall)
        Text("悬浮窗：${if (allowed) "已允许" else "未允许，会话改用通知"}")
        TextButton(onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))) } }) { Text("去打开悬浮窗权限") }
        Text("先答一问需要悬浮窗和使用情况访问；晨报还需要设置里的朗读开关。小窗不会拦截应用。", style = MaterialTheme.typography.bodySmall)
        Text(remember(revision) { Presence.debug(context) }, style = MaterialTheme.typography.bodySmall)
    }
}
