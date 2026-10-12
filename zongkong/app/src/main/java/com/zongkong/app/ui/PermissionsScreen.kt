package com.zongkong.app.ui

import android.Manifest
import android.app.NotificationManager
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.navigation.NavHostController
import com.zongkong.app.guard.BlockActivity
import com.zongkong.app.guard.GuardService
import com.zongkong.app.system.Notifications
import com.zongkong.app.system.Usage
import com.zongkong.app.zk

object PermissionStatus {
    fun accessibility(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, GuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /** 无障碍开着，而且服务真的在跑（最近一分半有心跳）。 */
    fun guardRunning(context: Context): Boolean =
        GuardService.running || System.currentTimeMillis() - context.zk.store.heartbeat < 90_000

    fun notifications(context: Context) = Notifications.canPost(context)

    fun battery(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun exactAlarm(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

    /** Android 14 起全屏通知要单独允许。 */
    fun fullScreen(context: Context) =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** 必需的权限里缺了哪些（首页顶上提示用）。 */
    fun missingCritical(context: Context): List<String> = buildList {
        if (!accessibility(context)) add("无障碍服务") else if (!guardRunning(context)) add("无障碍服务（开着但没在运行）")
        if (!notifications(context)) add("通知")
        if (!exactAlarm(context)) add("准点闹钟")
        if (!fullScreen(context)) add("全屏通知")
    }
}

/** 一项权限：叫什么、干什么用、手机里在哪、现在开没开（null = 系统查不到，要你自己确认）。 */
private data class Perm(
    val title: String,
    val why: String,
    val where: String,
    val ok: Boolean?,
    val intent: () -> Intent,
    val tag: String,
)

/**
 * 权限与运行状态：总控需要的每一项权限，一张卡一项——干什么用、现在开没开、手机里在哪、一键去开。
 * 系统查得到的自动打勾；vivo 自己的几项系统查不到，写清楚路径让你自己确认。
 */
@Composable
fun PermissionsScreen(nav: NavHostController) {
    val context = LocalContext.current
    val sig = LocalSignals.current
    var key by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { key++ }
    var asked by remember { mutableStateOf(false) }
    val notifLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { key++ }
    val pkgUri = Uri.parse("package:${context.packageName}")

    val required = remember(key) {
        val acc = PermissionStatus.accessibility(context)
        val running = PermissionStatus.guardRunning(context)
        listOf(
            Perm(
                "无障碍服务",
                "总控的眼睛和手：看前台是哪个应用（只读包名，不读屏幕内容和输入），严管时把娱乐应用弹回去，总控时间里锁住手机。" +
                    (if (acc && !running) "\n开着但没在运行：系统把它停了。关掉再打开一次。" else "") +
                    "\n更新总控后、被清理后台后，系统有时会把它关掉——到点没进总控时间，多半是这个。",
                "设置 → 快捷与辅助 → 无障碍 → 已安装的服务 → 总控（灰色点不开：先到 应用管理 → 总控 → 右上角 ⋮ → 允许受限制的设置）",
                acc && running, { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) }, "perm-acc",
            ),
            Perm(
                "通知",
                "常驻状态条；到点、超时、该报到、总控时间、总控主动找你，全靠它。",
                "设置 → 通知与状态栏 → 应用通知管理 → 总控 → 允许通知（“总控时间”“总控”两个类别都打开）",
                PermissionStatus.notifications(context),
                { Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName) }, "perm-notif",
            ),
            Perm(
                "准点闹钟",
                "总控时间、回访、行动提醒准点叫醒手机；无障碍被关掉时，这是总控唯一还能找你的办法。",
                "设置 → 应用与权限 → 应用管理 → 总控 → 权限 → 闹钟和提醒",
                PermissionStatus.exactAlarm(context),
                { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, pkgUri) else appDetails(context) }, "perm-alarm",
            ),
            Perm(
                "全屏通知",
                "总控时间到点像闹钟一样亮屏（锁屏上也能看到）。Android 14 起要单独允许。",
                "设置 → 应用与权限 → 应用管理 → 总控 → 权限 → 全屏通知",
                PermissionStatus.fullScreen(context),
                { if (Build.VERSION.SDK_INT >= 34) Intent(Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT, pkgUri) else appDetails(context) }, "perm-full",
            ),
        )
    }
    val recommended = remember(key) {
        listOf(
            Perm(
                "不限制电池优化",
                "减少系统在后台把总控和无障碍服务停掉。",
                "设置 → 电池 → 更多电池设置 → 电池优化 → 总控 → 不优化",
                PermissionStatus.battery(context),
                { Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, pkgUri) }, "perm-battery",
            ),
            Perm(
                "使用情况访问",
                "总控者对账用：系统自己记的应用使用时间，总控没在运行的时段也查得到。不开的话，只能用总控自己记的。",
                "设置 → 安全与隐私 → 更多安全设置 → 有权查看使用情况的应用 → 总控（不同系统也叫“使用情况访问权限”）",
                Usage.granted(context),
                { Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS) }, "perm-usage",
            ),
        )
    }
    val vivo = listOf(
        Perm("后台弹出界面", "拦截页、总控时间靠它从后台弹出来。没开的话：到点只有通知，不会弹出总控。", "设置 → 应用与权限 → 应用管理 → 总控 → 权限 → 后台弹出界面 → 允许", null, { appDetails(context) }, "perm-popup"),
        Perm("自启动", "开机、被清理后能自己起来，闹钟才能叫醒它。", "i管家 → 应用管理 → 权限管理 → 自启动 → 打开「总控」", null, { appDetails(context) }, "perm-autostart"),
        Perm("后台高耗电", "允许在后台一直运行（无障碍服务才不会被停）。", "设置 → 电池 → 后台耗电管理 → 总控 → 允许后台高耗电", null, { appDetails(context) }, "perm-power"),
        Perm("锁定在最近任务", "一键清理时不会把总控清掉。", "打开最近任务 → 按住「总控」的卡片（或下拉）→ 锁定", null, { appDetails(context) }, "perm-lock"),
    )
    val missing = (required + recommended).count { it.ok == false }

    Column(Modifier.fillMaxSize()) {
        BackBar("权限与运行状态", { nav.popBackStack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Panel(accent = if (missing == 0) sig.free else sig.strict, modifier = Modifier.testTag("perm-summary")) {
                Text(if (missing == 0) "能查到的都开好了" else "还有 $missing 项没开", style = MaterialTheme.typography.titleMedium, color = if (missing == 0) sig.free else sig.strict)
                Hint("下面每一项都写了：干什么用、手机里在哪、点按钮直接跳过去。vivo 自己的 4 项系统查不到开没开，请照着路径确认一遍。")
            }

            SectionLabel("必需")
            required.forEach { PermCard(it) { p ->
                if (p.tag == "perm-notif" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
                    asked = true
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else safeStart(context, p.intent())
            } }
            SectionLabel("强烈建议")
            recommended.forEach { PermCard(it) { p -> safeStart(context, p.intent()) } }
            SectionLabel("vivo / iQOO 还要手动开（系统查不到）")
            vivo.forEach { PermCard(it) { p -> safeStart(context, p.intent()) } }
            SectionLabel("可选")
            val boss = context.zk.store.config.value.boss
            Panel(onClick = { nav.navigate("dossier") }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("截图抽查", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                    Tag(if (boss.shots) "开着" else "没开", if (boss.shots) sig.free else sig.muted)
                }
                Hint("Android 11 起，无障碍服务可以截图（不用另外授权）。默认关；在“总控 → 档案 → 总控的眼睛”里开。截图只存在手机上。")
            }

            SectionLabel("叫醒记录")
            val log = remember(key) { context.zk.store.wakeLog.takeLast(12).reversed() }
            Panel(modifier = Modifier.testTag("wake-log")) {
                Hint("总控时间的提醒发没发、到点时总控在不在运行。到点没反应时先看这里。")
                if (log.isEmpty()) Text("还没有记录（到点前 10 分钟会记第一条）。", style = MaterialTheme.typography.bodySmall, color = sig.muted)
                log.forEach { Text(it, style = MaterialTheme.typography.bodySmall.merge(Mono)) }
            }

            OutlinedButton(onClick = { safeStart(context, appDetails(context)) }, modifier = Modifier.fillMaxWidth()) { Text("打开「总控」的应用信息页") }
            Hint("如果同时在用「一手」：两个 App 可以同时开无障碍，各管各的。")
            SectionLabel("测试")
            Button(onClick = { context.startActivity(BlockActivity.intent(context, "com.example.test")) }, modifier = Modifier.fillMaxWidth()) { Text("打开一次拦截页看看") }
        }
    }
}

@Composable
private fun PermCard(p: Perm, onGo: (Perm) -> Unit) {
    val sig = LocalSignals.current
    val (label, color) = when (p.ok) {
        true -> "已开启" to sig.free
        false -> "未开启" to sig.strict
        null -> "请确认" to sig.warn
    }
    Panel(accent = if (p.ok == false) sig.strict else null, modifier = Modifier.testTag(p.tag)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(p.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Tag(label, color)
        }
        Hint(p.why)
        Text("在哪：${p.where}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f))
        if (p.ok != true) {
            if (p.ok == false) Button(onClick = { onGo(p) }) { Text("去开启") }
            else TextButton(onClick = { onGo(p) }) { Text("打开应用信息页") }
        }
    }
}
