package com.yishou.app.window

import android.app.Activity
import android.media.projection.MediaProjectionManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.look.ScreenLookService
import com.yishou.app.settings.AppPrefs
import com.yishou.app.ui.AppPickerDialog
import com.yishou.app.ui.Stepper
import com.yishou.app.ui.rememberAppLabels

/** 开局规则：这一局里允许用哪些应用。 */
@Composable
fun RulesCard(prefs: AppPrefs, onChange: ((AppPrefs) -> AppPrefs) -> Unit) {
    var pick by remember { mutableStateOf<String?>(null) }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("开局规则", fontWeight = FontWeight.Bold)
                    Text("窗口里只能用下面这些应用；“先停”休息时不限制。", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = prefs.windowStrict, onCheckedChange = { on -> onChange { it.copy(windowStrict = on) } })
            }
            if (prefs.windowStrict) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("学习用的应用（随便用）", style = MaterialTheme.typography.labelLarge)
                        Text(rememberAppLabels(prefs.windowAllowed), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { pick = "allowed" }) { Text("选择") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("可以短暂切过去的", style = MaterialTheme.typography.labelLarge)
                        Text(rememberAppLabels(prefs.windowShortApps), style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = { pick = "short" }) { Text("选择") }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("每次最多", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    Stepper(prefs.windowShortMinutes, 1..15, "分钟") { v -> onChange { it.copy(windowShortMinutes = v) } }
                }
                Text("桌面、输入法、电话、相机和系统设置始终可以用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    when (pick) {
        "allowed" -> AppPickerDialog("学习用的应用", prefs.windowAllowed, onDone = { set ->
            onChange { it.copy(windowAllowed = set, windowShortApps = it.windowShortApps - set) }
            pick = null
        }, onDismiss = { pick = null })
        "short" -> AppPickerDialog("可以短暂切过去的应用", prefs.windowShortApps, onDone = { set ->
            onChange { it.copy(windowShortApps = set, windowAllowed = it.windowAllowed - set) }
            pick = null
        }, onDismiss = { pick = null })
    }
}

/** 让陪练看屏：开启、自动间隔、停止。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LookCard(prefs: AppPrefs, onChange: ((AppPrefs) -> AppPrefs) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val running by ScreenLookService.running.collectAsStateWithLifecycle()
    val status by ScreenLookService.status.collectAsStateWithLifecycle()
    val vision by app.settings.vision.collectAsStateWithLifecycle()
    var note by remember { mutableStateOf<String?>(null) }
    val consent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        val data = r.data
        if (r.resultCode == Activity.RESULT_OK && data != null) {
            ScreenLookService.start(context, r.resultCode, data)
            note = null
        } else {
            note = "没有同意看屏，陪练就看不到。"
        }
    }

    Surface(color = MaterialTheme.colorScheme.surfaceVariant, shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("让陪练看屏", fontWeight = FontWeight.Bold)
            if (!running) {
                Text(
                    "开启后回到学习应用，下拉通知栏点“看一眼”，陪练会看你正在做的题，针对这一步提问。" +
                        "只在你点的时候截屏，图片不保存。",
                    style = MaterialTheme.typography.bodySmall,
                )
                Button(onClick = {
                    if (!vision.isComplete) {
                        note = "先到 设置 → 识图模型 里填好接口，陪练才能看懂截屏。"
                    } else {
                        val mpm = context.getSystemService(MediaProjectionManager::class.java)
                        consent.launch(mpm.createScreenCaptureIntent())
                    }
                }) { Text("开启看屏") }
            } else {
                Text(status ?: "已开启。回到学习应用，下拉通知栏点“看一眼”。", style = MaterialTheme.typography.bodySmall)
                Text("自动看一眼", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(0 to "关", 3 to "3 分钟", 5 to "5 分钟", 10 to "10 分钟").forEach { (m, label) ->
                        FilterChip(
                            selected = prefs.lookIntervalMinutes == m,
                            onClick = { onChange { it.copy(lookIntervalMinutes = m) } },
                            label = { Text(label) },
                        )
                    }
                }
                OutlinedButton(onClick = { ScreenLookService.stop(context) }) { Text("停止看屏") }
            }
            note?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
