package com.zongkong.app.system

import android.Manifest
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.zongkong.app.guard.Diag
import com.zongkong.app.guard.GuardService
import com.zongkong.app.guard.RebindCoordinator
import com.zongkong.app.guard.SentinelClient
import com.zongkong.app.health.HealthAction
import com.zongkong.app.health.HealthConfig
import com.zongkong.app.health.HealthStatus
import com.zongkong.app.health.HealthVerdict
import com.zongkong.app.healthwire.GuardHealth
import com.zongkong.app.zk
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 无障碍自动恢复（T11/T12）。
 *
 * v2 的关键变化（报告 §8）：
 *   **重绑成功 ≠ 修好了。** 重绑只记「恢复请求已发出」；必须等到
 *   ①新的无障碍实例（instanceId 换了）②恢复开始之后的一次真 dispatcherAck，
 *   才能变 HEALTHY。最多两次，超了进入预设的失效处理（ESCALATE）。
 *
 * 另外两条硬要求：
 *   - 重绑前后**重新读取**当前无障碍列表，把其他已启用服务原样保留（T12）；
 *   - 与哨兵**串行**（跨进程文件锁 + 本地锁），避免两边同时改同一份系统配置。
 *
 * 只修「开着但没在运行 / 不再响应」；你自己在设置里关掉的，不替你打开。
 */
object SelfHeal {
    private const val TAG = "SelfHeal"
    const val GRANT_COMMAND = "adb shell pm grant com.zongkong.app android.permission.WRITE_SECURE_SETTINGS"

    private val cfg = HealthConfig()

    @Volatile private var lastHealMono = 0L
    @Volatile private var state = ""
    @Volatile private var reminded = false

    fun canWrite(context: Context): Boolean =
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * 把总控的无障碍服务从列表里拿掉、再放回去：系统会重新挂上它。
     *
     * 返回 true **只表示「恢复请求已发出」**。真正是否修好由 [HealthLedger] 判：
     * 要新 instanceId + 恢复之后的真 ack。
     */
    suspend fun rebind(context: Context): Boolean {
        if (!canWrite(context)) return false
        val me = ComponentName(context, GuardService::class.java)
        // RebindCoordinator 内部：跨进程文件锁串行 + 写回前重新读取 + 保留其他服务。
        val sent = RebindCoordinator.rebind(context, me)
        if (sent) {
            delay(1_200)
            Diag.log(context, "rebind_sent", "恢复请求已发出（不是已修好）：等新实例 + 真 ack")
        }
        return sent
    }

    /** 看一眼并（在预算内）修一次。每次判定都记进诊断（状态不变不重复刷）。 */
    suspend fun check(context: Context) {
        val store = context.zk.store
        if (store.config.value.pausedUntil > System.currentTimeMillis()) return
        checkSentinel(context)

        if (runCatching { GuardHealth.init(context) }.getOrNull() == null) return
        val permissionEnabled = GuardHealth.permissionEnabled(context)
        val v: HealthVerdict = GuardHealth.verdict(permissionEnabled)
        // 只要此刻有无障碍实例，就算「我们确实被系统挂载过」——比「跑出过 HEALTHY」更早成立。
        // 装完新版本、系统把服务摘掉之前，它通常先连上过一次；用这个当证据，
        // 才不至于因为「还没跑出过 HEALTHY」就把这一次摘除当成「用户还没开启」。
        if (GuardHealth.instanceId != null) markEverRan(context)
        logStateChange(context, v)

        when (v.status) {
            HealthStatus.HEALTHY -> {
                // 真的在响应：收尾恢复流程。
                if (v.action == HealthAction.NONE) GuardHealth.endRecovery()
                reminded = false
                // 见过自己在正常运行：以后「设置里突然没有我」才有依据判断是系统摘的。
                // 落盘：装完新版本系统顺手摘掉的那一刻，进程刚好是重启的，内存标志会丢。
                markEverRan(context)
                // 恢复正常：把「未接入」那条通道的计数一起清零。
                // 不清的话，头三次故障就用掉了全部预算，之后真正的掉线再也修不了。
                resetUnregBudget()
                return
            }
            HealthStatus.DISABLED -> {
                // 这里要分两种「设置里没有」：
                //   1. 你自己在设置里关掉的 → 不替你打开，只提醒（尊重你的决定）；
                //   2. 系统把它从列表里摘掉的（崩过之后 / 被一键清理删了）→ 该修。
                //
                // 真机实测的教训：这两种在 `enabled_accessibility_services` 里长得一模一样
                // （都只是「没有我这一项」），所以内核一律判 DISABLED。如果照字面「用户关的」处理，
                // 系统摘掉的那种就永远不会被修——这正是「只能手点」的另一半原因。
                // 判据用「我们自己的无障碍开关还在不在」：用户去设置里关，通常整个开关会变成 0；
                // 而系统把某一项摘掉时，总开关一般还是 1。这个判据不完美，但比不区分强得多，
                // 而且只在「本来在跑、突然没了」之后才生效（见 [wasRunningBefore]）。
                if (systemRemovedUs(context)) {
                    // 系统摘的：按「未接入」同样处理，允许重绑。
                    Diag.log(context, "stale", "无障碍被系统从列表里摘掉了：按自动恢复处理")
                    when (unregisteredStep(instanceAlive = false, canWrite = canWrite(context))) {
                        UnregStep.REBIND -> rebindUnregistered(context)
                        UnregStep.WAIT -> return
                        UnregStep.WARN -> warn(context, enabled = false, verdict = v)
                    }
                    return
                }
                // 用户自己关的：不替你打开，只提醒。
                warn(context, enabled = false, verdict = v)
                return
            }
            HealthStatus.UNKNOWN, HealthStatus.PAUSED -> return
            HealthStatus.RECOVERING -> return
            HealthStatus.SUSPECT -> {
                // 还没到确认失联：不重绑（避免抖动），交给下一轮。
                warn(context, enabled = true, verdict = v)
                return
            }
            HealthStatus.UNRESPONSIVE -> {
                if (v.action == HealthAction.START_RECOVERY) {
                    tryRecover(context, v)
                    return
                }
                if (v.action == HealthAction.ESCALATE) {
                    // 恢复预算用完：停止重试，按预先显示的失效处理（本地规则兜底 + 明说）。
                    escalate(context, v)
                    return
                }
            }
            HealthStatus.UNREGISTERED -> {
                // 这里以前是 `return`——什么都不做。这就是「老是挂掉、只能手点」的主因。
                //
                // 现场证据（iQOO 12，0.16.0-health-v2）：哨兵的通道静默之后 `registered` 变成 false，
                // 内核 `evaluate()` 在「恢复中」之前就 `if (!registered) return UNREGISTERED/NONE`，
                // 也就是**永远进不了恢复态**；于是每 10 分钟只发一条「健康异常」通知，
                // 诊断里零条 rebind_sent，从 15:25 一直卡到重新打开 App（实测 6 小时）。
                //
                // 关键：这条路不能依赖内核的恢复预算（那条路被上面的短路挡死了），
                // 所以这里用自己的一套节流 + 计数，直接发一次重绑。
                //   · 实例没连上（instanceId 为空）→ 典型的「崩过之后系统不再 bind」，重绑有效；
                //   · 实例还在、只是没登记 → 重绑解决不了，别去动系统设置，等它登记，等太久才提醒。
                if (!permissionEnabled) return
                when (unregisteredStep(GuardHealth.instanceId != null, canWrite(context))) {
                    UnregStep.REBIND -> rebindUnregistered(context)
                    UnregStep.WAIT -> return
                    UnregStep.WARN -> warnUnregistered(context)
                }
                return
            }
        }
    }

    /**
     * 这个「设置里没有我」到底是用户关的，还是系统摘的？
     *
     * 真机上两者在 `enabled_accessibility_services` 里无法区分（都只是少了我们这一项），
     * 所以用两条旁证：
     *   1. 无障碍总开关 `accessibility_enabled` 还是 1 —— 用户通常会连总开关一起关；
     *   2. 我们这一轮之前确实见过自己在运行（[everRan]）—— 从没跑过的不算，那时是还没打开。
     *
     * 两条都成立才认定「是系统摘的」。判错的方向是「多修一次」，不会把用户主动关的打开，
     * 因为用户主动关一般会把总开关一起关掉。
     */
    /**
     * 我们到底有没有见过自己在正常运行。
     *
     * **必须落盘**：真机上踩到的坑——这个标志原来只在内存里，而「装完新版本，
     * 系统顺手把无障碍从列表里摘掉」这件事恰恰发生在进程重启的那一刻。
     * 于是重启后 `everRan=false`，判定「从没跑过就不算被摘掉」，永远不修，
     * 表现就是「更新完总控，无障碍就一直显示开着但没在运行」。
     * 存进 SharedPreferences 之后，只要历史上真的跑过，重启也认得。
     */
    private const val PREF = "selfheal"
    private const val K_EVER_RAN = "ever_ran"

    private fun everRan(context: Context): Boolean =
        runCatching { context.getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(K_EVER_RAN, false) }
            .getOrDefault(false)

    private fun markEverRan(context: Context) {
        runCatching {
            val p = context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            if (!p.getBoolean(K_EVER_RAN, false)) p.edit().putBoolean(K_EVER_RAN, true).apply()
        }
    }

    private fun systemRemovedUs(context: Context): Boolean {
        val masterOn = runCatching {
            android.provider.Settings.Secure.getInt(context.contentResolver, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED) == 1
        }.getOrDefault(false)
        return systemRemoved(everRan(context), masterOn)
    }

    /**
     * 纯逻辑版：判「设置里没有我」是系统摘的还是用户关的。
     *
     * [everRan] 我们见过自己在正常运行；[masterOn] 无障碍总开关还开着。
     * 两条都成立才算系统摘的。抽出来是为了能单测——这个判断错了，要么永远不修，
     * 要么把用户主动关掉的又打开，两种都很难受。
     */
    fun systemRemoved(everRan: Boolean, masterOn: Boolean): Boolean = everRan && masterOn

    /**
     * 把「未接入」这条通道的重绑计数清零。
     *
     * 和 HEALTHY 时清零的理由一样：一轮故障用掉的预算不能带走下一轮。
     */
    private fun resetUnregBudget() {
        unregTries = 0
        unregisteredSince = 0L
    }


    /** UNREGISTERED 这一刻该干什么。 */
    enum class UnregStep {
        /** 服务实例不在、又有写安全设置权限：直接重绑。 */
        REBIND,

        /** 实例在、只是没登记：等它自己登记，别去动系统设置。 */
        WAIT,

        /** 修不了（没权限 / 等太久）：提醒人。 */
        WARN,
    }

    /**
     * 判定 UNREGISTERED 该走哪一步。
     *
     * 抽成纯函数是为了能测：这条分支以前什么都不做，正是「老是挂掉只能手点」的根因，
     * 不能再让它悄悄退回去。三个输入就够了：
     *   · [instanceAlive] 无障碍实例还在不在（不在 = 崩过之后系统不再 bind，重绑有效）；
     *   · [canWrite] 有没有写安全设置的权限（没有就只剩提醒）。
     *
     * 规则：没有实例 + 有权限 → 重绑；没有实例 + 没权限 → 提醒；实例在 → 等（协议层的事，重绑无用）。
     */
    fun unregisteredStep(instanceAlive: Boolean, canWrite: Boolean): UnregStep = when {
        instanceAlive -> UnregStep.WAIT
        canWrite -> UnregStep.REBIND
        else -> UnregStep.WARN
    }

    // ---------- UNREGISTERED 的重绑通道（不经内核恢复预算） ----------

    /** 这条通道自己的节流：两次重绑至少隔 3 分钟。 */
    @Volatile private var lastUnregRebind = 0L

    /** 这条通道自己的预算：最多连试 3 次，然后停下来提醒人（免得把系统设置刷成抖动）。 */
    @Volatile private var unregTries = 0

    /** 什么时候开始一直 UNREGISTERED 的（用来提示人）。 */
    @Volatile private var unregisteredSince = 0L

    /**
     * 「服务实例不在了」时的重绑。
     *
     * 和 [tryRecover] 分开写的理由见上面那段注释：内核那条路对 UNREGISTERED 是死路，
     * 硬塞进去只会被短路回来。这里只做一件事——按 [RebindCoordinator] 的规矩把它挂回去。
     */
    private suspend fun rebindUnregistered(context: Context) {
        val now = System.currentTimeMillis()
        if (unregisteredSince <= 0L) unregisteredSince = now
        if (!canWrite(context)) {
            // 没授权就只剩提醒：说清楚「执行一次那条命令以后就不用管了」。
            warnUnregistered(context)
            return
        }
        val nowMono = SystemClock.elapsedRealtime()
        // 第一次尝试不节流：故障刚发生时正是该立刻修的时刻。
        // 只有「重试」之间才需要间隔，避免把系统设置刷成抖动。
        if (unregTries > 0 && lastUnregRebind > 0 && nowMono - lastUnregRebind < 3 * 60_000L) {
            Diag.log(context, "rebind_skip", "未接入：距上次重绑不到 3 分钟，本轮让路")
            return
        }
        if (unregTries >= 3) {
            // 试够了：大概是系统彻底不让它跑，转成提醒，不再动系统设置。
            Diag.log(context, "rebind_fail", "未接入：本次故障已重绑 $unregTries 次都无效，转提醒")
            warnUnregistered(context)
            return
        }
        lastUnregRebind = nowMono
        unregTries++
        val sent = rebind(context)
        if (sent) {
            Diag.log(context, "rebind_sent", "未接入：已重绑（第 $unregTries 次），等系统重新 bind")
            context.zk.store.logWake("无障碍未接入：已重绑第 $unregTries 次")
        } else {
            Diag.log(context, "rebind_fail", "未接入：重绑没发出去（第 $unregTries 次，可能没写安全设置权限或锁被占）")
        }
    }

    /** UNREGISTERED 里「实例在、只是没登记」那一支：先等，等太久才提醒，不反复吵。 */
    private fun warnUnregistered(context: Context) {
        val now = System.currentTimeMillis()
        if (unregisteredSince <= 0L) unregisteredSince = now
        // 刚进入这个状态的两分钟内不提醒：很可能只是还没登记完
        if (now - unregisteredSince < 2 * 60_000L) return
        warn(context, enabled = true, verdict = HealthVerdict(HealthStatus.UNREGISTERED, HealthAction.NONE, "无障碍没有接入（服务实例不在）"))
    }

    /** 有限恢复：最多 [HealthConfig.maxRecoveries] 次，只在预算内，且与哨兵串行。 */
    private suspend fun tryRecover(context: Context, v: HealthVerdict) {
        if (!canWrite(context)) {
            warn(context, enabled = true, verdict = v)
            return
        }
        // 节流：两次重绑之间至少 3 分钟，避免把系统设置刷成抖动。
        val nowMono = SystemClock.elapsedRealtime()
        if (lastHealMono > 0 && nowMono - lastHealMono < 3 * 60_000L) return
        // 只有真的记上「恢复已开始」才发重绑（保证次数上限由台账统一管）。
        if (!GuardHealth.beginRecovery(cfg.maxRecoveries)) {
            escalate(context, v)
            return
        }
        lastHealMono = nowMono
        val sent = rebind(context)
        if (!sent) {
            // 请求都没发出去：不算一次成功尝试，但次数已经计过了（最多两次的约束仍在）。
            Diag.log(context, "rebind_fail", "恢复请求没发出去（没有写安全设置权限或锁被占用）")
            warn(context, enabled = true, verdict = v)
            return
        }
        // 只记「请求已发出」，**不**置健康、**不**清失败计数。
        state = "recovering"
        context.zk.store.logWake("无障碍不再响应：恢复请求已发出（等新实例 + 真 ack）")
        Diag.log(context, "rebind_ok", "恢复请求已发出：等新实例 + 真 ack 才算恢复")
    }

    private fun escalate(context: Context, v: HealthVerdict) {
        val store = context.zk.store
        // 停止重试，进入预先显示的失效处理：说清楚，不宣称已修复。
        if (!reminded) {
            reminded = true
            Diag.log(context, "escalate", "恢复预算用完，停止重试：${v.reason}")
            runCatching {
                Notifications.hqCall(
                    context,
                    "自动恢复用完了",
                    "${v.reason}。已停止重试。本地规则会在自己健康时按规则兜底；请到权限页手动关开一次无障碍。",
                    fullScreen = false,
                    route = "settings/perm",
                )
            }
            store.logWake("恢复预算用完：已停止重试（按预设失效处理）")
        }
    }

    private fun logStateChange(context: Context, v: HealthVerdict) {
        val nowState = "${v.status.name}/${v.action.name}"
        if (state == nowState) return
        state = nowState
        when (v.status) {
            HealthStatus.DISABLED -> Diag.log(context, "disabled", "无障碍在设置里是关着的")
            HealthStatus.UNRESPONSIVE -> Diag.log(context, "unresponsive", "确认失联：${v.reason}")
            HealthStatus.SUSPECT -> Diag.log(context, "stale", "有失败迹象：${v.reason}")
            HealthStatus.RECOVERING -> Diag.log(context, "recovering", "正在等新实例 + 真 ack")
            HealthStatus.HEALTHY -> Diag.log(context, "running", "真实执行线程在响应")
            else -> {}
        }
    }

    @Volatile private var lastSentinelHeal = 0L
    @Volatile private var sentinelState = ""

    /**
     * 反过来盯哨兵（独立应用）：**不再用 GuardService.running 判**，也不再拿「装了+开着」当在岗。
     * 用 v2 的连接分类；不在岗且有权限就把它挂回去（10 分钟最多一次），并说清楚「只是请求已发出」。
     */
    private fun checkSentinel(context: Context) {
        val now = System.currentTimeMillis()
        // link() 自带超时与在途上限，在常驻服务的后台线程上调用是安全的；
        // problem() 复用同一份缓存，不再多探一次。
        val label = runCatching { SentinelClient.link(context) }
            .getOrElse { SentinelClient.Link.UNKNOWN }
        val problem = problemText(label)
        val st = label.name
        if (st != sentinelState) {
            sentinelState = st
            Diag.log(context, if (problem == null) "sentinel_on" else "sentinel_off", problem ?: "哨兵在岗（v2 已接入）")
        }
        if (problem == null || !SentinelClient.installed(context) || !SentinelClient.genuine(context)) return

        // UNKNOWN 也要救。
        //
        // 以前 UNKNOWN（「还没有 v2 证据」）被归进「有问题」里，但真正被处理的是靠下面那条
        // respawn 逻辑；而它只在 SILENT 时才拉起进程。真机上发生的是：哨兵进程被系统清掉后，
        // 它既没有回包（SILENT 需要「调不通」这个证据），也没有新实例 → 一直停在 UNKNOWN，
        // 于是既没重绑、也没拉起进程，诊断里就是一小时一条 sentinel_off，直到人手动打开 App。
        // 所以：UNKNOWN 且它的进程确实不在时，按「需要拉起」处理。
        val needsSpawn = label == SentinelClient.Link.UNKNOWN || label == SentinelClient.Link.SILENT
        if (needsSpawn && !SentinelClient.processAlive(context)) {
            SentinelClient.spawn(context)
            Diag.log(context, "sentinel_wake", "哨兵没有进程：已尝试拉起（$label）")
        }

        if (!canWrite(context) || now - lastSentinelHeal < 10 * 60_000L) return
        lastSentinelHeal = now
        if (SentinelClient.rebind(context)) {
            Diag.log(context, "rebind_sent", "哨兵的恢复请求已发出（不是已修好：等哨兵自己新实例 + ack）")
        }
    }

    /** 从已判定好的分类翻一句人话（不再二次探测，避免一拍两次跨进程）。 */
    private fun problemText(l: SentinelClient.Link): String? = when (l) {
        SentinelClient.Link.RESPONDING -> null
        SentinelClient.Link.NOT_INSTALLED -> "没装「总控哨兵」"
        SentinelClient.Link.NOT_GENUINE -> "装的「总控哨兵」签名不对"
        SentinelClient.Link.DISABLED -> "「总控哨兵」的无障碍没开"
        SentinelClient.Link.UNKNOWN -> "「总控哨兵」还没有 v2 证据（可能不是 v2 版本，健康能力不足）"
        SentinelClient.Link.SILENT -> "「总控哨兵」开着但叫不应"
    }

    private fun warn(context: Context, enabled: Boolean, verdict: HealthVerdict) {
        val store = context.zk.store
        val now = System.currentTimeMillis()
        val minute = java.time.Instant.ofEpochMilli(now).atZone(store.zone).let { it.hour * 60 + it.minute }
        if (com.zongkong.core.hq.Hq.quiet(store.config.value.hq, now, store.zone)) return
        if (!store.firstAlert("acc-warn:${minute / 20}")) return
        val title = when {
            !enabled -> "总控的无障碍服务被关了"
            verdict.status == HealthStatus.SUSPECT -> "总控的执行线程开始不正常"
            else -> "总控的执行线程不再响应"
        }
        val text = buildString {
            append(if (enabled) "现在拦不住、锁不住、看不到。" else "现在拦不住、锁不住、看不到。")
            append("（${verdict.reason}）")
            append("点这里去权限页，关掉再打开一次。")
            if (!canWrite(context)) append("嫌麻烦：在电脑上执行一次权限页里的命令，以后它自己会修。")
        }
        Notifications.hqCall(context, title, text, fullScreen = false, route = "settings/perm")
        Buzz.once(context, Buzz.Level.FIRM)
        store.logWake("$title：已提醒")
        if (!reminded) {
            reminded = true
            Diag.log(context, if (enabled) "stale" else "disabled", "已提醒：${verdict.reason}")
        }
    }
}

/**
 * 常驻前台服务：让总控所在的进程不容易被系统清掉（无障碍服务也在这个进程里），
 * 并每分钟看一眼真实的健康证据（[SelfHeal.check]）与掉线提醒（[Watchdog.check]）。
 * 通知就是那条常驻状态条。
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
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { s ->
                s.launch {
                    // 刚起来时无障碍服务可能还在连：先等一分钟
                    delay(60_000)
                    while (isActive) {
                        try {
                            SelfHeal.check(this@KeepAliveService)
                        } catch (e: Throwable) {
                            Log.w("KeepAlive", "检查出错", e)
                        }
                        try {
                            Watchdog.check(this@KeepAliveService)
                        } catch (e: Throwable) {
                            Log.w("KeepAlive", "看门狗出错", e)
                        }
                        // 本地规则兜底与紧急暂停：不依赖无障碍 Handler，也不依赖网络（T05）。
                        try {
                            com.zongkong.app.healthwire.LocalGuardian.tick(this@KeepAliveService)
                        } catch (e: Throwable) {
                            Log.w("KeepAlive", "本地守卫出错", e)
                        }
                        delay(60_000)
                    }
                }
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    companion object {
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

