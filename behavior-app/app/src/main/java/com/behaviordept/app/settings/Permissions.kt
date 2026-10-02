package com.behaviordept.app.settings

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.behaviordept.app.container
import com.behaviordept.app.reminder.Notifications
import com.behaviordept.app.reminder.ReminderScheduler
import com.behaviordept.app.ui.components.LineButton
import com.behaviordept.app.ui.theme.Paper
import kotlinx.coroutines.launch

/** 通知、精确闹钟两项权限的状态和开启入口。拒绝也能用，只是对应提醒不出现或不准点。 */
@Composable
fun PermissionRows() {
    val context = LocalContext.current
    // 每次回到前台都重新读一遍权限状态（用户可能刚从系统设置回来）。
    var tick by remember { mutableIntStateOf(0) }
    LifecycleResumeEffect(Unit) {
        tick++
        onPauseOrDispose { }
    }
    val notifyOk = remember(tick) { Notifications.canPost(context) }
    val exactOk = remember(tick) { ReminderScheduler.canExact(context) }

    val notifyLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { tick++ }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PermissionRow(
            title = "通知",
            desc = if (notifyOk) "已开启：每日提醒和自测到期会推送" else "没开：收不到每日提醒",
            granted = notifyOk,
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    notifyLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    openAppNotificationSettings(context)
                }
            },
        )
        PermissionRow(
            title = "精确闹钟",
            desc = if (exactOk) "已开启：每日提醒准点响" else "没开：提醒可能晚几分钟到",
            granted = exactOk,
            onGrant = {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    runCatching {
                        context.startActivity(
                            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")),
                        )
                    }
                }
            },
        )
    }
    // 权限变化后重排一次闹钟（精确 ↔ 近似）。
    androidx.compose.runtime.LaunchedEffect(exactOk) {
        val c = context.container
        c.appScope.launch { ReminderScheduler.reschedule(context, c.settings.current()) }
    }
}

private fun openAppNotificationSettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }
}

@Composable
private fun PermissionRow(title: String, desc: String, granted: Boolean, onGrant: () -> Unit) {
    val p = Paper.colors
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = p.ink)
            Text(desc, style = MaterialTheme.typography.bodySmall, color = if (granted) p.ink2 else p.red)
        }
        Spacer(Modifier.width(12.dp))
        if (granted) {
            Text("✓", style = MaterialTheme.typography.titleLarge, color = p.red)
        } else {
            LineButton("去开启", onGrant)
        }
    }
}
