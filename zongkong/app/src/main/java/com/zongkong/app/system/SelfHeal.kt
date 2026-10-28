package com.zongkong.app.system

import android.Manifest
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityManager
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.GuardService
import com.zongkong.app.ui.PermissionStatus
import com.zongkong.app.zk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无障碍服务“开着但没在运行”（系统把它停了，设置里还显示开着）：
 *  - 给过“写安全设置”权限（电脑上 adb 执行一次 pm grant，见权限页）：自己把它关掉再打开，系统重新挂上，不用你动手。
 *  - 没给：每 20 分钟大声提醒一次（高优先级通知 + 震动），点开就是权限页。
 * 只修“开着但没在运行”；你自己在设置里关掉的，不替你打开。
 */
object SelfHeal {
    private const val TAG = "SelfHeal"
    const val GRANT_COMMAND = "adb shell pm grant com.zongkong.app android.permission.WRITE_SECURE_SETTINGS"

    /** 连着修这么多次都不成，就别再开关了：系统彻底不让它跑时，开关只是白折腾，改成提醒。 */
    const val MAX_HEALS = 3

    /** 两次自动修复之间至少隔这么久：系统挂载要时间，贴太紧会自己打断自己。 */
    const val HEAL_GAP_MS = 3 * 60_000L

    @Volatile private var lastHeal = 0L
    @Volatile private var heals = 0
    /** 上一次判定出来的状态：running / stale / disabled。只在状态变化时写诊断，免得每分钟刷一条。 */
    @Volatile private var state = ""
    /** 这次掉线是不是已经提醒过了（诊断里只记第一次提醒）。 */
    @Volatile private var reminded = false

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    // ---------- 纯判断（不碰 Android，单测直接喂值） ----------

    /** 这一分钟该做什么。 */
    enum class Step {
        /** 不用动：已经在跑。 */
        NONE,
        /** 自己修：开着没跑、有写权限、还没修满次数。 */
        HEAL,
        /** 提醒：被关掉了 / 没授权 / 修了几次都不成。 */
        WARN,
    }

    /**
     * 该修还是该提醒。
     *
     * 修是有上限的：连着修 [MAX_HEALS] 次都没修好，多修只会一直开关服务，反而把系统惹毛，
     * 这时候把决定权交回用户（大声提醒），比装作在修诚实。
     */
    fun step(running: Boolean, enabled: Boolean, canWrite: Boolean, heals: Int, now: Long, lastHeal: Long): Step = when {
        running -> Step.NONE
        !enabled -> Step.WARN
        !canWrite -> Step.WARN
        heals >= MAX_HEALS -> Step.WARN
        now - lastHeal < HEAL_GAP_MS -> Step.WARN
        else -> Step.HEAL
    }

    /**
     * 一次自动修复的结果。
     *
     * 为什么不是 Boolean：`putString` 不抛异常 ≠ 系统把服务挂起来了，vivo/iQOO 上这两件事经常不一样。
     * 把“写进去了没有”和“系统认了没有”分开记，诊断和权限页才能说清到底卡在哪一步。
     */
    data class Rebind(
        /** 写入层面：安全设置里最后那份列表还包含本服务。 */
        val ok: Boolean,
        /** 结果层面：系统自己报告的可运行列表里真的有本服务（唯一可信的成功标准）。 */
        val verified: Boolean,
        /** 试了几档：1 = 只做“拿掉→放回”，2 = 还试了“清空→写回”。 */
        val attempts: Int,
        /** 一句中文：成在哪、败在哪。给诊断和权限页显示。 */
        val reason: String,
    )

    /**
     * 放回时用的列表：别的服务原样保留（顺序不动），本服务只留一份、放最后。
     *
     * 顺序必须保住：系统按列表顺序挂载，乱动别人的位置等于替用户改了无障碍设置。
     */
    fun enabledList(current: String, me: String): String = (othersOf(current, me) + me).joinToString(":")

    /** 当前列表里除本服务以外的（去空、去重、保持原顺序）。 */
    fun othersOf(current: String, me: String): List<String> {
        val mine = normalize(me)
        return current.split(':').map { it.trim() }.filter { it.isNotBlank() && normalize(it) != mine }.distinct()
    }

    /** 系统报告的在跑服务里有没有本服务（包名 + 类名逐项对上）。 */
    fun inList(reported: List<Pair<String, String>>, pkg: String, cls: String): Boolean =
        reported.any { (p, c) -> p == pkg && fullClass(p, c) == fullClass(pkg, cls) }

    /** 扁平组件名归一：`包名/.类` 补全成 `包名/包名.类`，免得简写和全名对不上。 */
    fun normalize(flat: String): String {
        val s = flat.trim()
        val pkg = s.substringBefore('/')
        val cls = s.substringAfter('/', "")
        if (pkg.isEmpty() || cls.isEmpty()) return s
        return "$pkg/${fullClass(pkg, cls)}"
    }

    private fun fullClass(pkg: String, cls: String): String = if (cls.startsWith(".")) pkg + cls else cls

    // ---------- 真正的修复 ----------

    /** 简单入口：只回答“真的修好了没有”（旧调用方用的是这个语义）。 */
    suspend fun rebind(context: Context): Boolean = rebindDetail(context).verified

    /**
     * 把总控的无障碍服务从列表里拿掉、再放回去，并**验证系统有没有真的认**。
     * 每次结果都写诊断（自动修、权限页手动修、回到应用时补修，都走这里）。
     */
    suspend fun rebindDetail(context: Context): Rebind = attempt(context).also { r ->
        Diag.log(
            context,
            if (r.verified) "rebind_ok" else "rebind_fail",
            "重新挂上（试了 ${r.attempts} 档）：${r.reason}",
        )
    }

    /**
     * 真正动手的两档：
     *  1. 开关置 1 → 拿掉 → 放回（多数机型到这就好了）。
     *  2. 验证不过再来一档更硬的：先清空整个已启用列表 → 等一下 → 写回「原有的其他服务 + 本服务」。
     *     vivo/iQOO 上只改列表经常不触发重扫，清空能让它重新走一遍。
     */
    private suspend fun attempt(context: Context): Rebind {
        if (!canWrite(context)) return Rebind(false, false, 0, "没授权“写安全设置”，修不了")
        val cr = context.contentResolver
        val me = ComponentName(context, GuardService::class.java)
        val meFlat = me.flattenToString()
        val current = Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val list = enabledList(current, meFlat)

        // 第一档：开总开关 → 拿掉 → 放回。
        // 先置 1 是因为 vivo 上 ACCESSIBILITY_ENABLED=0 时列表怎么写都不会挂载。
        try {
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, othersOf(current, meFlat).joinToString(":"))
            delay(1_200)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)
        } catch (e: Exception) {
            Log.w(TAG, "第一档重新挂上失败", e)
            return Rebind(false, false, 1, "写安全设置被拒绝（${e.javaClass.simpleName}）")
        }
        delay(1_500)
        if (verified(context, me)) return Rebind(written(context, meFlat), true, 1, "拿掉再放回就好了，系统已认")

        // 第二档：清空 → 等一下 → 写回「原有的其他服务 + 本服务」。
        try {
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, "")
            delay(1_000)
            Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)
        } catch (e: Exception) {
            Log.w(TAG, "第二档重新挂上失败", e)
            return Rebind(false, false, 2, "清空写回被拒绝（${e.javaClass.simpleName}）")
        }
        delay(2_000)
        return if (verified(context, me)) {
            Rebind(written(context, meFlat), true, 2, "清空再写回才好，系统已认")
        } else {
            Rebind(written(context, meFlat), false, 2, "写进去了，但系统没把服务挂起来")
        }
    }

    /**
     * 真的读回来确认：安全设置里那份列表现在有没有本服务。
     *
     * 不推断、只读盘——vivo 上 `putString` 不抛异常但值没落地（或被系统改写）是见过的，
     * 那正是“以为修好了其实没好”的来源。
     */
    private fun written(context: Context, meFlat: String): Boolean = try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            .orEmpty()
            .split(':')
            .any { normalize(it) == normalize(meFlat) }
    } catch (e: Exception) {
        Log.w(TAG, "读回无障碍列表失败", e)
        false
    }

    /**
     * 系统到底认没认：问无障碍管理器要“已启用的服务”列表，看本服务在不在。
     *
     * 这是目前能拿到的最强信号，比 putString 没抛异常可信得多；但它只说明系统收了这份配置，
     * 不保证 onServiceConnected 已经回调——真正“在跑”还得看心跳（[PermissionStatus.guardRunning]）。
     */
    private fun verified(context: Context, me: ComponentName): Boolean = try {
        val am = context.getSystemService(AccessibilityManager::class.java)
        val reported = am?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.mapNotNull { info ->
                info.resolveInfo?.serviceInfo?.let { si -> si.packageName to si.name }
            }
            .orEmpty()
        inList(reported, me.packageName, me.className)
    } catch (e: Exception) {
        Log.w(TAG, "读已启用的无障碍服务列表失败", e)
        false
    }

    /** 看一眼：在跑就算了；开着没跑就修（或提醒）；整个关掉了就提醒。每次判定都记进诊断。 */
    suspend fun check(context: Context) {
        val store = context.zk.store
        if (store.config.value.pausedUntil > System.currentTimeMillis()) return
        if (PermissionStatus.guardRunning(context)) {
            heals = 0
            state = "running"
            reminded = false
            return
        }
        val now = System.currentTimeMillis()
        val enabled = PermissionStatus.accessibility(context)
        // 诊断：只在“运行 / 开着没跑 / 被关”之间变化时记一条，否则每分钟一条会把时间线刷满
        val nowState = if (enabled) "stale" else "disabled"
        if (state != nowState) {
            state = nowState
            if (enabled) Diag.log(context, "stale", "开着但没在运行")
            else Diag.log(context, "disabled", "无障碍在设置里是关着的")
        }
        if (step(false, enabled, canWrite(context), heals, now, lastHeal) == Step.HEAL) {
            lastHeal = now
            heals++
            // 走 attempt 而不是 rebindDetail：这里要带上“第几次”，而且诊断由本函数自己写
            val r = attempt(context)
            val detail = "自动重新挂上（第 $heals 次，试了 ${r.attempts} 档）：${r.reason}"
            if (r.verified) {
                Diag.log(context, "rebind_ok", detail)
                state = "running"
                reminded = false
                store.logWake("无障碍开着但没在运行：自动重新挂上（第 $heals 次）")
                return
            }
            Diag.log(context, "rebind_fail", detail)
        }
        warn(context, enabled, now)
    }

    private fun warn(context: Context, enabled: Boolean, now: Long) {
        val store = context.zk.store
        val minute = java.time.Instant.ofEpochMilli(now).atZone(store.zone).let { it.hour * 60 + it.minute }
        if (com.zongkong.core.hq.Hq.quiet(store.config.value.hq, now, store.zone)) return
        if (!store.firstAlert("acc-warn:${minute / 20}")) return
        val title = if (enabled) "总控“开着但没在运行”" else "总控的无障碍服务被关了"
        val text = (if (enabled) "系统把它停了：现在拦不住、锁不住、看不到。" else "现在拦不住、锁不住、看不到。") +
            "点这里去权限页，关掉再打开一次。" +
            if (canWrite(context)) "" else "嫌麻烦：在电脑上执行一次权限页里的命令，以后它自己会修。"
        Notifications.hqCall(context, title, text, fullScreen = false, route = "settings/perm")
        Buzz.once(context, Buzz.Level.FIRM)
        store.logWake("$title：已提醒")
        // 诊断只记这次掉线的第一次提醒（提醒本身每 20 分钟一格，不必每次重复记）
        if (!reminded) {
            reminded = true
            if (enabled) Diag.log(context, "stale", "已提醒：开着但没在运行，去权限页关掉再打开")
            else Diag.log(context, "disabled", "已提醒：无障碍被关掉了，去权限页重新打开")
        }
    }
}

/**
 * 常驻前台服务：让总控所在的进程不容易被系统清掉（无障碍服务也在这个进程里），
 * 并每分钟看一眼无障碍服务还在不在跑（[SelfHeal.check]）。
 * 通知就是那条常驻状态条。
 *
 * 自己被系统杀掉后没人自愈，所以 [HealAlarm] 每 15 分钟从外部叫一次（见 [HealReceiver]）。
 */
class KeepAliveService : Service() {
    private var scope: CoroutineScope? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            val n = Notifications.statusNotification(this, "总控在运行", "看着手机和你的计划。")
            if (Build.VERSION.SDK_INT >= 34) startForeground(Notifications.ID_STATUS, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(Notifications.ID_STATUS, n)
        } catch (e: Exception) {
            Log.w("KeepAlive", "开不了前台服务", e)
            stopSelf()
            return START_NOT_STICKY
        }
        alive = true
        // 每次起来都把下一次外部唤醒排上（开机流程没跑到、或上一轮被清掉时，这里是补排的地方）
        HealAlarm.schedule(this)
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
                s.launch {
                    // 刚起来时无障碍服务可能还在连：先等一分钟
                    delay(60_000)
                    while (isActive) {
                        try { SelfHeal.check(this@KeepAliveService) } catch (e: Throwable) { Log.w("KeepAlive", "检查出错", e) }
                        // 看门狗：眼睛没在运行超过 60 秒就提醒（每 60 秒问一次）
                        try { Watchdog.check(this@KeepAliveService) } catch (e: Throwable) { Log.w("KeepAlive", "看门狗出错", e) }
                        delay(60_000)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        alive = false
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
        /** 此刻是不是真的活着（不是“曾经 start 过”）：外部闹钟靠它决定要不要再拉一次，免得每次白闪一条通知。 */
        @Volatile
        var alive = false
            private set

        /** 能开就开（后台开不了时系统会拒绝，接住就行：下次从前台或闹钟再开）。 */
        fun start(context: Context) {
            try {
                context.startForegroundService(Intent(context, KeepAliveService::class.java))
            } catch (e: Exception) {
                Log.w("KeepAlive", "现在开不了常驻服务", e)
            }
        }
    }
}
