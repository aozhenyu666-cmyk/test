package com.yishou.app.ui

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.gate.GateActivity
import com.yishou.app.gate.GateService

@Composable
fun EntertainmentGateCard(onSettings: (() -> Unit)? = null) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    val connected by GateService.connected.collectAsStateWithLifecycle()
    val problem by GateService.problem.collectAsStateWithLifecycle()
    var enabled by remember { mutableStateOf(PermissionStatus.accessibility(context)) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { enabled = PermissionStatus.accessibility(context) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("娱乐收费站", style = MaterialTheme.typography.titleMedium)
            Text(when { prefs.watched.isEmpty() -> "先选要拦的娱乐应用"; connected -> "运行中 · 已选 ${prefs.watched.size} 个应用"; enabled -> "系统开关开着，但服务没连上。进去关一次再打开"; else -> "还没开启无障碍服务，打开娱乐应用时不会拦截" })
            Text("打开已选应用先做题，正常通过换 15 分钟，到点重新锁。", style = MaterialTheme.typography.bodySmall)
            problem?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (onSettings != null) TextButton(onClick = onSettings) { Text("选择娱乐应用") }
                if (!connected) TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }) { Text("连接服务") }
                TextButton(onClick = { context.startActivity(GateActivity.testIntent(context)) }) { Text("试一道题") }
            }
        }
    }
}
