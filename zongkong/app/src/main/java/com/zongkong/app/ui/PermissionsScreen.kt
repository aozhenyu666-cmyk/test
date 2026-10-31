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
import kotlinx.coroutines.launch
import com.zongkong.app.guard.BlockActivity
import com.zongkong.app.guard.GuardService
import com.zongkong.app.guard.SentinelClient
import com.zongkong.app.health.HealthStatus
import com.zongkong.app.healthwire.GuardHealth
import com.zongkong.app.healthwire.HealthLabels
import com.zongkong.app.healthwire.LocalGuardian
import com.zongkong.app.healthwire.LocalLockExecutor
import com.zongkong.app.system.Notifications
import com.zongkong.app.system.Usage
import com.zongkong.app.zk

/**
 * 权限与运行状态的**事实读取**（报告 §6 / §10 / 规范 §7）。
 *
 * 一条铁律：宁可显示「未知」，也不要涂绿。
 *   - 无障碍：系统设置里开没开 + 真实 dispatcherAck 是否新鲜（不是 `GuardService.running`）；
 *   - 设备管理员：能不能独立 force-lock（没有就如实说「没有」并引导系统界面）；
 *   - 安全锁屏：有没有设置（没设置时 lockNow 可能只是休眠，不得报成安全锁定）；
 *   - 哨兵：区分「未接入 / 未知 / 无障碍未开启 / 在响应 / 失联」五种，不合并成一个绿点。
 */
object PermissionStatus {
    fun accessibility(context: Context): Boolean {
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        val me = ComponentName(context, GuardService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    /**
     * 无障碍「真的在干活」：只看真实 dispatcherAck + 事件进度（v2）。
     *
     * 以前这里是 `GuardService.running || 心跳 90 秒内` —— 那两条都会被「进程活着但执行线程
     * 卡死」骗过（报告 §3.2）。现在统一走共享内核的 HealthEvaluator。
     */
    fun guardRunning(context: Context): Boolean = healthStatus(context) == HealthStatus.HEALTHY

    /** 健康状态（给界面用）。读不到 → UNKNOWN，不猜。 */
    fun healthStatus(context: Context): HealthStatus = try {
        GuardHealth.init(context)
        GuardHealth.verdict(GuardHealth.permissionEnabled(context)).status
    } catch (_: Throwable) {
        HealthStatus.UNKNOWN
    }

    /** 一句话：状态 + 原因（不涂绿）。 */
    fun healthText(context: Context): String = try {
        GuardHealth.init(context)
        val v = GuardHealth.verdict(GuardHealth.permissionEnabled(context))
        "${HealthLabels.status(v.status)}（${v.reason}）"
    } catch (_: Throwable) {
        "未知：读不到健康状态"
    }

    fun notifications(context: Context) = Notifications.canPost(context)

    fun battery(context: Context) =
        context.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(context.packageName)

    fun exactAlarm(context: Context) =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || context.getSystemService(android.app.AlarmManager::class.java).canScheduleExactAlarms()

    /** Android 14 起全屏通知要单独允许。 */
    fun fullScreen(context: Context) =
        Build.VERSION.SDK_INT < 34 || context.getSystemService(NotificationManager::class.java).canUseFullScreenIntent()

    /** 哨兵（独立应用）**接口状态**：只有 RESPONDING 才算接上，其余一律不算绿。 */
    fun sentinel(context: Context): Boolean = SentinelClient.healthy(context)

    /** 哨兵的如实分类（未接入 / 未知 / DISABLED / 在响应 / 失联）。 */
    fun sentinelLink(context: Context): SentinelClient.Link = SentinelClient.link(context)

    fun sentinelText(context: Context): String = SentinelClient.describe(context)

    /** 设备管理员（force-lock）——独立于无障碍的锁屏权限。 */
    fun deviceAdmin(context: Context): LocalLockExecutor.AdminState = LocalLockExecutor.admin(context)

    /** 有没有设置安全锁屏（PIN/图案/密码）。没设置时 lockNow 可能只是休眠。 */
    fun secureLock(context: Context): Boolean = LocalLockExecutor.secureLockSet(context)

    /** 本地规则有没有落盘 + 紧急暂停状态。 */
    fun policyText(context: Context): String = try {
        val s = LocalGuardian.store(context)
        val now = System.currentTimeMillis()
        val eff = s.effective(now)
        // 暂停要看**到期时刻**：以前只看有没有 STOPPED 字样，会一直显示「已暂停」（P0-6a）。
        val emergency = s.emergencyStatus(now)
        when {
            s.lastError.isNotBlank() -> "规则文件有问题：${s.lastError}"
            emergency.active -> "已紧急暂停（本地立即生效；${emergency.why}）"
            eff.isEmpty() -> "没有生效中的本地规则（版本 ${s.revision}）"
            else -> "生效中 ${eff.size} 条（版本 ${s.revision}，最近到期 ${eff.maxOf { it.endsAtWall } - now} ms）"
        }
    } catch (_: Throwable) {
        "读不到本地规则"
    }

    /** 必需的权限里缺了哪些（首页顶上提示用）。 */
    fun missingCritical(context: Context): List<String> = buildList {
        if (!accessibility(context)) {
            add("无障碍服务")
        } else {
            when (healthStatus(context)) {
                HealthStatus.HEALTHY -> {}
                HealthStatus.UNKNOWN -> add("无障碍（未知：还没有真实响应）")
                HealthStatus.DISABLED -> add("无障碍（系统里被关掉了）")
                else -> add("无障碍（开着但没有真实响应）")
            }
        }
        if (!deviceAdmin(context).active) add("设备管理员（不能独立锁屏）")
        if (!sentinel(context)) add("哨兵（${sentinelText(context)}）")
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
    /** 自定义状态标签（不涂绿时用）。 */
    val label: String? = null,
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
        val health = PermissionStatus.healthStatus(context)
        val admin = PermissionStatus.deviceAdmin(context)
        val sentinel = PermissionStatus.sentinelLink(context)
        listOf(
            Perm(
                "无障碍服务",
                "总控的眼睛和手：看前台是哪个应用（只读包名，不读屏幕内容和输入），严管时把娱乐应用弹回去，总控时间里锁住手机。" +
                    "\n现在：${PermissionStatus.healthText(context)}" +
                    "\n“健康”只看真实执行线程有没有在响应挑战；进程活着但线程卡死，会如实显示“未知/失联”，不会涂绿。" +
                    "\n更新总控后、被清理后台后，系统有时会把它关掉——到点没进总控时间，多半是这个。",
                "设置 → 快捷与辅助 → 无障碍 → 已安装的服务 → 总控（灰色点不开：先到 应用管理 → 总控 → 右上角 ⋮ → 允许受限制的设置）",
                acc && health == HealthStatus.HEALTHY,
                { Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS) },
                "perm-acc",
                label = when {
                    !acc -> "未开启"
                    health == HealthStatus.HEALTHY -> "已开启并真实响应"
                    else -> HealthLabels.status(health)
                },
            ),
            Perm(
                "设备管理员（独立锁屏）",
                "锁屏的首选通道：有它就调用系统 lockNow()，不依赖无障碍。" +
                    "\n${admin.text}" +
                    "\n没设置安全锁屏（PIN/图案/密码）时，lockNow 可能只是把屏幕熄灭——总控会把它记成“未验证”，不会报成安全锁定。" +
                    "\n现在：${if (PermissionStatus.secureLock(context)) "已设置安全锁屏" else "没有设置安全锁屏（锁屏回执只能是“未验证”）"}",
                "点下面的按钮 → 系统“设备管理员”界面 → 启用「总控」（随时可以在同一处撤销）",
                admin.active,
                { LocalLockExecutor.requestAdminIntent(context) },
                "perm-admin",
                label = if (admin.active) "已启用" else "未启用",
            ),
            Perm(
                "安全锁屏（系统设置）",
                "系统里有没有设 PIN / 图案 / 密码。没设的话，任何锁屏调用都可能是假锁；总控不会把它报成成功。",
                "设置 → 安全 → 屏幕锁定（不同系统路径不同）",
                PermissionStatus.secureLock(context),
                { Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) },
                "perm-secure",
                label = if (PermissionStatus.secureLock(context)) "已设置" else "未设置",
            ),
            Perm(
                "哨兵（独立应用「总控哨兵」）",
                "总控的无障碍一被系统停掉（显示“此服务出现故障”），掉线的那段时间就什么都管不住。哨兵是另一个应用——单独安装、单独运行，" +
                    "总控崩了、被清理、被强行停止都碰不到它。" +
                    "\nv2 起：哨兵会反过来给总控发“挑战”，只有总控在无障碍执行线程上真处理完才算健康响应；" +
                    "哨兵自己也会显示“未接入 / 未知 / 无障碍未开启 / 在响应 / 失联”，不会都涂绿。" +
                    "\n现在：${PermissionStatus.sentinelText(context)}",
                if (!SentinelClient.installed(context)) "点“去开启”下载安装「总控哨兵」（和总控同一个发布页），装好后在 设置 → 快捷与辅助 → 无障碍 → 已安装的服务 → 总控哨兵 → 打开"
                else "设置 → 快捷与辅助 → 无障碍 → 已安装的服务 → 总控哨兵 → 打开（和「总控」是两个开关，都要开）。vivo：i管家 → 应用管理 → 总控哨兵 → 允许后台运行",
                sentinel == SentinelClient.Link.RESPONDING,
                {
                    if (!SentinelClient.installed(context)) Intent(Intent.ACTION_VIEW, Uri.parse(SentinelClient.DOWNLOAD))
                    else Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                },
                "perm-sentinel",
                label = sentinel.label,
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
                Text(if (missing == 0) "能查到的都开好了" else "还有 $missing 项没开/没确认", style = MaterialTheme.typography.titleMedium, color = if (missing == 0) sig.free else sig.strict)
                Hint("下面每一项都写了：干什么用、手机里在哪、点按钮直接跳过去。vivo 自己的 4 项系统查不到开没开，请照着路径确认一遍。")
                Hint("“开着”不等于“在干活”：无障碍与哨兵都按真实响应判，证据不足会显示“未知”。")
            }

            SectionLabel("必需")
            required.forEach { PermCard(it) { p ->
                if (p.tag == "perm-notif" && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !asked) {
                    asked = true
                    notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else safeStart(context, p.intent())
            } }
            HealthV2Card(key)
            SelfHealCard(key) { key++ }
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

/**
 * v2 健康 / 规则 / 紧急暂停卡片。
 *
 * 三件用户真正需要知道的事：
 *  1. 我自己的执行线程有没有在响应（真实 ack）；
 *  2. 本地规则现在是什么状态、有没有生效；
 *  3. 一键紧急暂停（本地立即生效，不依赖网络和模型）。
 */
@Composable
private fun HealthV2Card(key: Int) {
    val context = LocalContext.current
    val sig = LocalSignals.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var msg by remember { mutableStateOf<String?>(null) }
    var reason by remember { mutableStateOf("") }
    val status = remember(key) { PermissionStatus.healthStatus(context) }
    val green = HealthLabels.isGreen(status)
    Panel(accent = if (green) null else sig.warn, modifier = Modifier.testTag("perm-health-v2")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("健康协议 v2", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Tag(HealthLabels.status(status), if (green) sig.free else sig.warn)
        }
        Hint("现在：${PermissionStatus.healthText(context)}")
        Hint("本地规则：${PermissionStatus.policyText(context)}")
        Hint(
            "判定口径：只有哨兵的挑战被投到无障碍执行线程、在那里处理完再带回，才算一次真实响应。" +
                "“进程还在”“provider 能回话”“以前是开着的”都不算。休眠唤醒后先重建观测，不会把漏掉的心跳全算成失败。",
        )
        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 2.dp))
        Text("紧急暂停原因（可空）", style = MaterialTheme.typography.bodySmall, color = sig.muted)
        androidx.compose.material3.OutlinedTextField(
            value = reason,
            onValueChange = { reason = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("perm-emergency-reason"),
            placeholder = { Text("比如：现在在开车") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                onClick = {
                    msg = LocalGuardian.emergencyStop(context, reason.ifBlank { "紧急暂停（未填原因）" })
                },
                modifier = Modifier.testTag("perm-emergency-stop"),
            ) { Text("紧急暂停（立即生效）") }
            OutlinedButton(onClick = { msg = LocalGuardian.resume(context) }) { Text("恢复监管") }
            TextButton(onClick = { msg = PermissionStatus.policyText(context) }) { Text("刷新") }
        }
        Hint("紧急暂停只停“自动锁屏”，不需要写够字数、不等冷静期、不经过模型；和“正常提前结束监管”是两条独立通道。没收到哨兵确认时会如实写“哨兵未确认”。")
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sig.muted) }
    }
}

/**
 * 无障碍“开着但没在运行”的自动修复：电脑上 adb 授权一次“写安全设置”，以后总控自己把服务重新挂上。
 * 没授权的话，常驻服务每 20 分钟大声提醒一次。
 *
 * v2 起口径变了：**重绑成功只等于「恢复请求已发出」**，要等新实例 + 真 ack 才算恢复。
 */
@Composable
private fun SelfHealCard(key: Int, refresh: () -> Unit) {
    val context = LocalContext.current
    val sig = LocalSignals.current
    val clip = androidx.compose.ui.platform.LocalClipboardManager.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val granted = remember(key) { com.zongkong.app.system.SelfHeal.canWrite(context) }
    var msg by remember { mutableStateOf<String?>(null) }
    Panel(accent = if (granted) null else sig.warn, modifier = Modifier.testTag("perm-selfheal")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("无障碍自动修复", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            Tag(if (granted) "已授权" else "没授权", if (granted) sig.free else sig.warn)
        }
        Hint(
            if (granted) "无障碍不再响应时，总控会把服务从列表里拿掉再放回（写回前重新读取，保留其他已启用服务），最多两次；" +
                "而且只记“恢复请求已发出”——要等新实例 + 真 ack 才算修好。"
            else "无障碍老是“开着但没在运行”、每次都要手动关开？在电脑上执行一次下面这条命令（手机开 USB 调试、连上电脑），以后总控自己修。" +
                "不授权也行：总控会每 20 分钟大声提醒你一次，直到它重新跑起来。",
        )
        val sentinelNeeds = remember(key) {
            // 哨兵那边也要有这个权限，才能在同一条恢复链路上串行操作（跨进程文件锁）。
            SentinelClient.installed(context) && !granted
        }
        val cmd = listOfNotNull(
            com.zongkong.app.system.SelfHeal.GRANT_COMMAND.takeIf { !granted },
            SentinelClient.GRANT_COMMAND.takeIf { sentinelNeeds },
        ).joinToString("\n")
        if (cmd.isNotEmpty()) {
            Text(cmd, style = MaterialTheme.typography.bodySmall.merge(Mono))
            if (granted) Hint("总控这边授权了；上面这条给哨兵——总控掉线时，哨兵就能自己把它挂回去。")
        }
        if (!granted) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { clip.setText(androidx.compose.ui.text.AnnotatedString(cmd)); msg = "复制了" }) { Text("复制命令") }
                TextButton(onClick = refresh) { Text("授权好了，刷新") }
            }
        } else {
            OutlinedButton(onClick = {
                scope.launch {
                    val sent = com.zongkong.app.system.SelfHeal.rebind(context)
                    msg = if (sent) "恢复请求已发出。等新实例 + 真 ack 才算恢复（不是“已经修好”）" else "请求没发出去（权限或串行锁）"
                    kotlinx.coroutines.delay(4_000)
                    refresh()
                }
            }) { Text("现在发一次恢复请求") }
        }
        msg?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = sig.muted) }
    }
}

@Composable
private fun PermCard(p: Perm, onGo: (Perm) -> Unit) {
    val sig = LocalSignals.current
    val (label, color) = when (p.ok) {
        true -> (p.label ?: "已开启") to sig.free
        false -> (p.label ?: "未开启") to sig.strict
        null -> (p.label ?: "请确认") to sig.warn
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
