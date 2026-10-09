package com.zongkong.app.ui

import android.Manifest
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import com.zongkong.app.guard.BlockActivity
import com.zongkong.app.guard.GuardService
import com.zongkong.app.system.Notifications

object PermissionStatus {
    fun accessibility(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, GuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun notifications(context: Context) = Notifications.canPost(context)

    fun battery(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)
}

/** vivo（OriginOS）上还要手动打开的几项，系统没有查询接口。 */
private val VIVO_STEPS = listOf(
    "无障碍里「总控」是灰色、点不开：设置 → 应用与权限 → 应用管理 → 总控 → 右上角 ⋮ → 允许受限制的设置，再回来开启。",
    "允许后台弹出界面：设置 → 应用与权限 → 应用管理 → 总控 → 权限 → 后台弹出界面 → 允许。拦截页靠它弹出来。",
    "允许自启动：i管家 → 应用管理 → 权限管理 → 自启动，打开「总控」。",
    "允许后台高耗电：设置 → 电池 → 后台耗电管理 → 总控 → 允许后台高耗电。",
    "在最近任务里把「总控」下拉锁定，清理后台时不被杀掉。",
)

@Composable
fun PermissionsScreen(nav: NavHostController) {
    val context = LocalContext.current
    var key by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { key++ }
    var asked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { key++ }
    val acc = remember(key) { PermissionStatus.accessibility(context) }
    val running = remember(key) { GuardService.running }
    val notif = remember(key) { PermissionStatus.notifications(context) }
    val battery = remember(key) { PermissionStatus.battery(context) }

    Column(Modifier.fillMaxSize()) {
        BackBar("权限与运行状态", { nav.popBackStack() })
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PermCard(
                "无障碍服务（必需）",
                "总控的眼睛：只读取当前前台应用的包名，判断是不是拦截名单里的应用。不读屏幕内容、不读输入。" +
                    if (acc && !running) "\n已开启但服务没在运行：关掉再打开一次。" else "",
                acc && running, "去开启",
            ) { safeStart(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            PermCard("通知", "常驻状态条，以及到点、超时、该报到的提醒。", notif, "去开启") {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
                    asked = true
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    safeStart(context, Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName))
                }
            }
            PermCard("不限制电池优化", "减少系统在后台关掉无障碍服务。", battery, "去设置") {
                safeStart(context, Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${context.packageName}")))
            }

            SectionLabel("vivo 手机还要手动设置")
            VIVO_STEPS.forEachIndexed { i, t -> Text("${i + 1}. $t", style = MaterialTheme.typography.bodyMedium) }
            OutlinedButton(onClick = { safeStart(context, appDetails(context)) }, modifier = Modifier.fillMaxWidth()) {
                Text("打开「总控」的应用信息页")
            }
            Hint("如果同时在用「一手」：两个 App 可以同时开无障碍，各管各的。严管时总控会把你弹回总控；一手的思考页照常工作。")

            SectionLabel("测试")
            Button(
                onClick = { context.startActivity(BlockActivity.intent(context, "com.example.test")) },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("打开一次拦截页看看") }
        }
    }
}

@Composable
private fun PermCard(title: String, detail: String, ok: Boolean, action: String, onAction: () -> Unit) {
    val sig = LocalSignals.current
    Panel(accent = if (ok) null else sig.strict) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Tag(if (ok) "已开启" else "未开启", if (ok) sig.free else sig.strict)
        }
        Hint(detail)
        if (!ok) Button(onClick = onAction) { Text(action) }
    }
}
