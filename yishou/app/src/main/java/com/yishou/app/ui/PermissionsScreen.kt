package com.yishou.app.ui

import android.Manifest
import android.app.AlarmManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.yishou.app.YishouApp
import com.yishou.app.gate.GateActivity
import com.yishou.app.gate.GateService
import com.yishou.app.system.Notifications
import com.yishou.app.wallpaper.BoardWallpaperService
import com.yishou.app.widget.BoardWidget
import com.yishou.app.window.WindowScheduler

/** 读取各项权限的当前状态。 */
object PermissionStatus {
    fun accessibility(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, GateService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun notifications(context: Context): Boolean = Notifications.canPost(context)

    fun exactAlarm(context: Context): Boolean =
        WindowScheduler.canExact(context.getSystemService(AlarmManager::class.java))

    fun batteryUnrestricted(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}

/** 手动勾选的厂商设置项（系统不提供查询接口）。 */
private val MANUAL_ITEMS = listOf(
    "restricted" to "如果无障碍里的「一手」是灰色、点不开：设置 → 应用与权限 → 应用管理 → 一手 → 右上角 ⋮ → 允许受限制的设置，再回来开启无障碍。",
    "autostart" to "允许自启动：i管家 → 应用管理 → 权限管理 → 自启动（或 设置 → 应用与权限 → 权限管理 → 自启动），打开「一手」。",
    "popup" to "允许后台弹出界面：设置 → 应用与权限 → 应用管理 → 一手 → 权限 → 后台弹出界面 → 允许。思考页靠它弹出来。",
    "background" to "允许后台高耗电：设置 → 电池 → 后台耗电管理 → 一手 → 允许后台高耗电。",
    "lock" to "在最近任务里把「一手」下拉锁定，清理后台时不被杀掉。",
)

@Composable
fun PermissionsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as YishouApp
    val prefs by app.settings.app.collectAsStateWithLifecycle()
    // 从系统设置返回时刷新状态
    var refreshKey by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        refreshKey++
        WindowScheduler.reschedule(context)
    }
    var askedNotif by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { refreshKey++ }

    val acc = remember(refreshKey) { PermissionStatus.accessibility(context) }
    val notif = remember(refreshKey) { PermissionStatus.notifications(context) }
    val exact = remember(refreshKey) { PermissionStatus.exactAlarm(context) }
    val battery = remember(refreshKey) { PermissionStatus.batteryUnrestricted(context) }
    val wallpaper = remember(refreshKey) { BoardWallpaperService.isActive(context) }
    val widget = remember(refreshKey) { BoardWidget.ids(context).isNotEmpty() }
    var widgetNote by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { BackTopBar("权限与运行状态", onBack) }) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                "所有权限都由你自己在系统设置里打开，随时可以关掉。下面的状态从系统设置回来后会自动刷新。",
                style = MaterialTheme.typography.bodySmall,
            )

            PermissionCard(
                title = "无障碍服务（必需）",
                detail = "用于入口思考页：只读取当前前台应用的包名，不读取屏幕内容。在列表里找到「一手」并打开。",
                ok = acc,
                action = "去开启",
            ) { safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

            PermissionCard(
                title = "通知",
                detail = "常驻通知“一手正在运行”，以及“陪练窗口开始”提醒。",
                ok = notif,
                action = "去开启",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !askedNotif) {
                    askedNotif = true
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    // 已经拒绝过时系统不再弹窗，直接打开通知设置
                    safeStart(
                        context,
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
                    )
                }
            }

            PermissionCard(
                title = "精确闹钟",
                detail = "让“陪练窗口开始”准点提醒。不开也能用，但可能晚几分钟。",
                ok = exact,
                action = "去开启",
            ) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    safeStart(context, Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:${context.packageName}")))
                }
            }

            PermissionCard(
                title = "不限制电池优化",
                detail = "减少系统在后台关掉无障碍服务和定时任务的情况。",
                ok = battery,
                action = "去设置",
            ) {
                safeStart(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }

            Text("防止误清后台", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            PermissionCard(
                title = "壁纸守护：局面壁纸",
                detail = "把桌面背景换成一张写着“下一问”的淡棋盘。系统会一直保持壁纸所在的应用运行，" +
                    "清理后台时「一手」不容易被杀；局面也一直摆在眼前。",
                ok = wallpaper,
                action = "设为壁纸",
            ) { BoardWallpaperService.open(context) }
            PermissionCard(
                title = "桌面小组件",
                detail = "桌面上显示当前任务和下一问，点一下就能应手。",
                ok = widget,
                action = "添加到桌面",
            ) {
                widgetNote = if (BoardWidget.requestPin(context)) null
                else "这个桌面不支持一键添加：长按桌面空白处 → 小组件 → 找到「一手」拖出来。"
            }
            widgetNote?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
            Text(
                "陪练窗口进行中时，「一手」会挂一条“陪练窗口进行中”的通知，保持前台运行；从最近任务里划掉界面，计时和朗读也不会停。",
                style = MaterialTheme.typography.bodySmall,
            )

            Text("vivo 手机还需要手动设置（系统不提供查询，设好后自己勾上）", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            MANUAL_ITEMS.forEach { (key, text) ->
                Row(verticalAlignment = Alignment.Top) {
                    Checkbox(
                        checked = key in prefs.manualChecks,
                        onCheckedChange = { on ->
                            app.settings.updateApp { p ->
                                p.copy(manualChecks = if (on) p.manualChecks + key else p.manualChecks - key)
                            }
                        },
                    )
                    Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 12.dp))
                }
            }
            OutlinedButton(
                onClick = { safeStart(context, appDetails(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开「一手」的应用信息页") }

            Text("测试", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Button(
                onClick = { context.startActivity(GateActivity.testIntent(context)) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开一次思考页（测试，不放行）") }
        }
    }
}

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))

/** 个别系统没有对应的设置页，打不开时退回到应用信息页。 */
private fun safeStart(context: Context, intent: Intent) {
    try {
        context.startActivity(intent)
    } catch (e: Exception) {
        try {
            context.startActivity(appDetails(context))
        } catch (_: Exception) {
        }
    }
}

@Composable
private fun PermissionCard(title: String, detail: String, ok: Boolean, action: String, onAction: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (ok) MaterialTheme.colorScheme.surfaceVariant else MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text(if (ok) "已开启" else "未开启", color = if (ok) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error)
            }
            Text(detail, style = MaterialTheme.typography.bodySmall)
            if (!ok) Button(onClick = onAction) { Text(action) }
        }
    }
}
