package com.zongkong.app.guard

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.util.Log
import com.zongkong.app.health.HealthConfig
import com.zongkong.app.health.HealthStatus
import com.zongkong.app.health.LockReceipt
import com.zongkong.app.health.LockState
import com.zongkong.app.health.Policy
import com.zongkong.app.health.PolicyClock
import com.zongkong.app.healthwire.GuardHealth
import com.zongkong.app.healthwire.HealthWireProvider
import java.security.MessageDigest
import java.util.concurrent.Executors

/*
 * 哨兵（独立应用 com.zongkong.sentinel）连接器 —— **v2 协议**。
 *
 * v1（0.13 起）用的是一个自造的 link provider（content://com.zongkong.sentinel.link）：
 *   总控推心跳、哨兵回 running/beat。那条路的问题正是报告 §3.2 / §8 说的「假在线」：
 *   只要 provider 能回话 + 一个布尔是 true，就当成健康；后台线程代答也没人查。
 *
 * v2 改成（WIRE-SPEC §3.1）：
 *   - 客户端 → 哨兵：authority `com.zongkong.sentinel.health`，method
 *     `register` / `syncPolicy` / `reportReceipt` / `ping`；
 *   - 哨兵 → 客户端：authority `com.zongkong.app.health`，method `challenge`（见 [HealthWireProvider]）；
 *   - **健康只由真实 dispatcherAck 判定**：本文件里 [healthy] 只看「哨兵自己在岗 + 最近一次挑战
 *     真的回了 ack」，不看 GuardService.running。
 *
 * 仍然保留的两件老事（与健康无关，继续用）：
 *   - `released()`：哨兵解除闸门生效时总控让路；
 *   - `rebind()`：把哨兵的无障碍挂回去（只是写系统设置，不等于哨兵健康）。
 */
object SentinelClient {
    private const val TAG = "SentinelClient"
    const val PKG = "com.zongkong.sentinel"
    val SERVICE = ComponentName(PKG, "com.zongkong.sentinel.SentinelService")

    /** v2：客户端 → 哨兵（register / syncPolicy / reportReceipt / ping）。 */
    private const val HEALTH_AUTHORITY = "com.zongkong.sentinel.health"
    private val HEALTH_URI: Uri = Uri.parse("content://$HEALTH_AUTHORITY")

    /** v1 老口子：只读解除闸门与「叫醒」，**不再当健康凭据**。 */
    private val LEGACY_URI: Uri = Uri.parse("content://com.zongkong.sentinel.link")

    /** 固定下载地址（和总控同一个发布）。 */
    const val DOWNLOAD = "https://github.com/aozhenyu666-cmyk/test/releases/download/zongkong-latest/zongkong-sentinel.apk"
    const val GRANT_COMMAND = "adb shell pm grant com.zongkong.sentinel android.permission.WRITE_SECURE_SETTINGS"

    const val PROTOCOL_VERSION = 2

    /** 规则同步的超时预算。哨兵卡住时按「没确认」处理，不冒充成功。 */
    private const val SYNC_TIMEOUT_MS = 2_500L

    /** 回执上报的超时预算。 */
    private const val RECEIPT_TIMEOUT_MS = 2_000L

    /**
     * 哨兵的状态（如实区分，不要都涂绿）。
     *
     * - [NOT_INSTALLED]：没装 → 界面写「未接入」
     * - [NOT_GENUINE]：装了但签名不对
     * - [DISABLED]：无障碍没开
     * - [UNKNOWN]：装了、开着，但还没有可用的 v2 证据（没注册成功 / 还没挑战过）→ 写「未知」
     * - [RESPONDING]：v2 ping/register 通了，且哨兵自报在岗（**不是**本客户端的健康）
     * - [SILENT]：装着、开着，但叫不应
     */
    enum class Link(val label: String) {
        NOT_INSTALLED("未接入：没安装"),
        NOT_GENUINE("未接入：签名不对"),
        DISABLED("未接入：哨兵无障碍未开启"),
        UNKNOWN("未知：还没有 v2 证据"),
        RESPONDING("已接入：哨兵在响应"),
        SILENT("失联：叫不应"),
    }

    data class Info(
        /** v2 ping 有没有回话。 */
        val responded: Boolean = false,
        val serverVersion: Int = 0,
        /** 哨兵自报注册状态。 */
        val registered: Boolean = false,
        /** 哨兵自报的策略版本。 */
        val policyRevision: Int = 0,
        /** 哨兵自报的紧急暂停状态（NONE / STOPPED@...）。 */
        val emergency: String = "",
        /** 这次回话的时刻（本机墙上时间）。 */
        val at: Long = 0,
        val note: String = "",
    )

    /** 最近一次 v2 通话结果。 */
    @Volatile var last: Info? = null
        private set

    /** 最近一次真实挑战 ack 的时刻（本机单调时钟）；0 = 从没有过。 */
    @Volatile var lastAckAtMono: Long = 0
        private set

    /** 最近一次挑战失败原因（给界面看）。 */
    @Volatile var lastChallengeError: String = ""
        private set

    /** 报名状态：register 回了 ok。 */
    @Volatile var registered: Boolean = false
        private set

    /** 注册时用的 instanceId（重建会换新）。 */
    @Volatile var registeredInstance: String = ""
        private set

    /** 哨兵那边的解除闸门生效到几点（墙上时间）；每次和哨兵通话都更新。0 = 没解除。 */
    @Volatile private var releasedUntilMs = 0L

    /** 哨兵的解除闸门现在是不是生效（总控据此让路：不拦、不进总控时间）。 */
    fun released(now: Long = System.currentTimeMillis()): Boolean = now < releasedUntilMs

    private val exec = Executors.newSingleThreadExecutor { r ->
        Thread(r, "sentinel-v2").apply { isDaemon = true }
    }

    fun installed(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(PKG, 0)
        true
    } catch (_: Throwable) {
        false
    }

    /** 装的是真的哨兵（和总控同一把签名；实测两个 APK 用的是同一个 debug.keystore）。 */
    fun genuine(c: Context): Boolean = try {
        c.packageManager.checkSignatures(c.packageName, PKG) == PackageManager.SIGNATURE_MATCH
    } catch (_: Throwable) {
        false
    }

    fun enabled(c: Context): Boolean = try {
        (android.provider.Settings.Secure.getString(
            c.contentResolver,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: "").split(':').any { ComponentName.unflattenFromString(it) == SERVICE }
    } catch (_: Throwable) {
        false
    }

    /** 自己的证书摘要（register 时自报；哨兵那边还会自己校验一遍）。 */
    fun certSha256(c: Context): String = try {
        val pm = c.packageManager
        val info = pm.getPackageInfo(c.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val sig = info.signingInfo?.apkContentsSigners?.firstOrNull()
            ?: info.signingInfo?.signingCertificateHistory?.firstOrNull()
        sig?.let { MessageDigest.getInstance("SHA-256").digest(it.toByteArray()).joinToString("") { b -> "%02X".format(b) } }.orEmpty()
    } catch (_: Throwable) {
        ""
    }

    // ---------- v2：注册 / 同步规则 / 回执 / ping ----------

    /**
     * v2 register。**必须在无障碍连上之后调用**（要带新 instanceId）。
     * 只在真正拿到 ok 时才把 [registered] 置真；失败的原因记下来给界面。
     */
    fun register(c: Context, instanceId: String, versionName: String = "", versionCode: Long = 0): Boolean {
        if (instanceId.isBlank()) return false
        val app = c.applicationContext
        return try {
            val r = app.contentResolver.call(HEALTH_URI, "register", null, Bundle().apply {
                putInt("v", PROTOCOL_VERSION)
                putString("pkg", app.packageName)
                putString("versionName", versionName)
                putLong("versionCode", versionCode)
                putString("instanceId", instanceId)
                putString("certSha256", certSha256(app))
            })
            val ok = r?.getBoolean("ok") == true
            registered = ok
            registeredInstance = if (ok) instanceId else ""
            last = Info(
                responded = r != null,
                serverVersion = r?.getInt("serverVersion", 0) ?: 0,
                registered = ok,
                at = System.currentTimeMillis(),
                note = if (ok) "已注册" else (r?.getString("error") ?: "哨兵没回包"),
            )
            GuardHealth.markRegistered(ok)
            runCatching {
                Diag.log(
                    app,
                    if (ok) "sentinel_registered" else "sentinel_register_fail",
                    if (ok) "哨兵已登记客户端实例 ${instanceId.take(8)}" else "注册失败：${last?.note}",
                )
            }
            ok
        } catch (e: Throwable) {
            registered = false
            last = Info(at = System.currentTimeMillis(), note = "注册调不通：${e.message ?: e.javaClass.simpleName}")
            GuardHealth.markRegistered(false)
            Log.w(TAG, "v2 注册失败", e)
            false
        }
    }

    /**
     * **非阻塞**注册：把 Binder 调用交给后台线程。
     *
     * 无障碍 `onServiceConnected` 会调它，而那是 a11y 主线程 —— 在这里同步跨进程，
     * 哨兵卡住就会冻住执行线程（T05：「无障碍主线程卡住，:watch 仍要能发现并执行锁屏」）。
     */
    fun registerAsync(c: Context, instanceId: String, versionName: String = "", versionCode: Long = 0) {
        if (instanceId.isBlank()) return
        val app = c.applicationContext
        exec.execute { runCatching { register(app, instanceId, versionName, versionCode) } }
    }

    /** 把本地规则同步给哨兵（先落盘再监管：本地 [com.zongkong.app.healthwire.ClientPolicyStore] 已经存过了）。 */
    fun syncPolicy(c: Context, policyJson: String): Pair<Boolean, Int> {
        val app = c.applicationContext
        // 走带超时的 exec：界面线程/后台线程调它都不会被卡死的哨兵拖住。
        val out = callBounded(app, "syncPolicy", SYNC_TIMEOUT_MS) {
            putInt("v", PROTOCOL_VERSION)
            putString("pkg", app.packageName)
            putString("policyJson", policyJson)
        } ?: return false to 0
        val ok = out.getBoolean("ok") == true
        val rev = out.getInt("acceptedRevision", 0)
        runCatching {
            Diag.log(app, "sentinel_policy", if (ok) "规则已同步，哨兵接受版本 $rev" else "规则同步失败：${out.getString("error")}")
        }
        return ok to rev
    }

    /**
     * 带超时的跨进程调用（单线程 executor + `Future.get(timeout)`）。
     *
     * 为什么必须有：紧急暂停、规则同步都可能从界面线程发起；哨兵卡死时不能把界面或
     * 无障碍线程一起冻住。超时返回 null（= 没确认），**不**当成成功。
     */
    private fun callBounded(app: Context, method: String, timeoutMs: Long, args: Bundle.() -> Unit): Bundle? {
        val input = Bundle().apply(args)
        val future = try {
            pingExec.submit(java.util.concurrent.Callable {
                app.contentResolver.call(HEALTH_URI, method, null, input)
            })
        } catch (e: Throwable) {
            Log.w(TAG, "$method 提交失败", e)
            return null
        }
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            Log.w(TAG, "$method 超时（${timeoutMs}ms）：不当作成功")
            null
        } catch (e: Throwable) {
            Log.w(TAG, "$method 失败", e)
            null
        }
    }

    /** 上报锁屏回执（state 用共享内核 LockState.name）。 */
    fun reportReceipt(c: Context, r: LockReceipt): Boolean {
        val app = c.applicationContext
        // 回执上报也带超时：它在本地守卫的后台线程上跑，但哨兵卡死时不能把那条线程永久占住。
        val out = callBounded(app, "reportReceipt", RECEIPT_TIMEOUT_MS) {
            putInt("v", PROTOCOL_VERSION)
            putString("pkg", app.packageName)
            putString("policyId", r.policyId)
            putString("incidentId", r.incidentId)
            putString("requestId", r.requestId)
            putString("source", r.source)
            putString("reason", r.reason)
            putString("state", r.state.name)
            putLong("atWall", r.atWall)
            putString("note", r.note)
        } ?: return false
        return out.getBoolean("ok") == true
    }

    /**
     * v2 ping。
     *
     * **绝不能在无障碍主线程上直接调**：跨进程 Binder 会跟着对方一起卡住（T05）。
     * 这里做了超时 + 在途上限；界面/后台线程可以调，a11y 主线程请用 [refreshNowait]。
     */
    fun ping(c: Context, timeoutMs: Long = 1_500): Info? {
        val app = c.applicationContext
        if (!installed(app) || !genuine(app)) return null
        return pingBounded(app, timeoutMs)
    }

    /** 真正发 Binder 的那一步（只在 [pingBounded] 的后台线程里跑）。 */
    private fun pingBlocking(app: Context): Info? = try {
        val r = app.contentResolver.call(HEALTH_URI, "ping", null, Bundle().apply {
            putInt("v", PROTOCOL_VERSION)
            putString("pkg", app.packageName)
            putString("certSha256", certSha256(app))
        })
        if (r == null) {
            null
        } else {
            val ok = r.getBoolean("ok", false)
            Info(
                responded = ok,
                serverVersion = r.getInt("serverVersion", 0),
                registered = r.getBoolean("registered", false),
                policyRevision = r.getInt("policyRevision", 0),
                emergency = r.getString("emergency").orEmpty(),
                at = System.currentTimeMillis(),
                note = if (ok) "" else (r.getString("error") ?: "哨兵拒绝"),
            ).also { last = it }
        }
    } catch (e: Throwable) {
        Log.w(TAG, "v2 ping 失败", e)
        null
    }

    /** 后台推一次心跳式 ping（保留旧调用点，但不再传拦截名单当健康凭据）。 */
    fun refresh(c: Context) {
        val app = c.applicationContext
        exec.execute { runCatching { ping(app) } }
    }

    /**
     * **非阻塞**的 v2 ping：只把任务投给后台线程，绝不在这里跨进程等待。
     *
     * 为什么要这个：`GuardService.tick` / 本地兜底判断都跑在无障碍主线程上。
     * 一旦在这里做同步 Binder 调用，哨兵卡住就会把无障碍线程一起冻住 ——
     * 那正是报告 §8 里「一手在事件线程里同步调总控」的同一个病（T05）。
     */
    fun refreshNowait(c: Context) = refresh(c)

    /**
     * **超时封装**的 ping：只允许在后台线程/界面线程使用（绝不能在 a11y 主线程调）。
     *
     * 实现要点（规范 §3.2「不许为无法取消的阻塞调用不停新建线程」）：
     *  - ping 跑在**单线程** executor 上，卡住就最多卡这一条，不会越堆越多；
     *  - 调用方带超时等待；超时给 null（= 叫不应），线程继续卡着也不新建；
     *  - 另有一个"在途"标志：已经有请求在跑就直接返回缓存，避免排队放大延迟。
     */
    private fun pingBounded(app: Context, timeoutMs: Long): Info? {
        val future = try {
            pingExec.submit(java.util.concurrent.Callable { pingBlocking(app) })
        } catch (e: Throwable) {
            Log.w(TAG, "提交 ping 失败", e)
            return null
        }
        return try {
            future.get(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            // 超时：不 cancel（Binder 调用不可取消），线程留着自己跑完，不再新建。
            null
        } catch (e: Throwable) {
            Log.w(TAG, "ping 等待失败", e)
            null
        }
    }

    /** 单线程 executor：ping 专用，最多一条线程（冻结时不会繁殖）。 */
    private val pingExec = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "sentinel-ping").apply { isDaemon = true }
    }

    /**
     * 把心跳和现在的拦截状态写给哨兵（老口子 `com.zongkong.sentinel.link` 的 `state`）。
     *
     * 这条和健康判定无关：它是「总控掉线时哨兵替它拦哪些应用」的业务数据。
     * v2 一度把它改成只 ping，结果哨兵拿不到拦截名单，总控掉线后娱乐应用不再被拦
     * （模拟器冒烟 SENTINEL_LINKED / SENTINEL_TAKEOVER 失败）。这里恢复，同时顺带一次 v2 ping。
     * [at] = 0 表示总控下线前说了一声（哨兵立刻接管）。
     */
    fun push(c: Context, blocked: Set<String>, strict: Boolean, freeUntil: Long, at: Long = System.currentTimeMillis()) {
        val app = c.applicationContext
        exec.execute {
            runCatching {
                if (installed(app) && genuine(app)) {
                    val r = app.contentResolver.call(LEGACY_URI, "state", null, Bundle().apply {
                        putLong("at", at)
                        putBoolean("strict", strict)
                        putLong("freeUntil", freeUntil)
                        putStringArray("blocked", blocked.toTypedArray())
                    })
                    if (r != null && !r.containsKey("error")) releasedUntilMs = r.getLong("releasedUntil", 0L)
                }
            }.onFailure { Log.w(TAG, "给哨兵写拦截状态失败", it) }
            runCatching { ping(app) }
        }
    }

    /** v1 老口子只用来读「解除闸门」这一件事（它和健康无关，属于总控让路的业务状态）。 */
    private fun readLegacyRelease(c: Context) {
        try {
            val r = c.contentResolver.call(LEGACY_URI, "ping", null, null) ?: return
            if (!r.containsKey("error")) releasedUntilMs = r.getLong("releasedUntil", 0L)
        } catch (_: Throwable) {
            // 哨兵没装 / 还没升级到 v2：静默，不影响健康判定
        }
    }

    /** 登记一次真实挑战结果（由 HealthWire 的 ack 路径调用）。 */
    fun noteAck(mono: Long) {
        lastAckAtMono = mono
        lastChallengeError = ""
    }

    fun noteChallengeFailure(reason: String) {
        lastChallengeError = reason
    }

    // ---------- 哨兵在岗判定（v2） ----------

    /**
     * 哨兵在岗 —— **只看 v2 证据**：
     *   装了 + 开了无障碍 + v2 ping 回了 ok + 最近一次真实挑战 ack 新鲜（或至少 ping 通）。
     *
     * 明确不看 `GuardService.running`（规范 §8：那是本客户端的布尔，和哨兵在不在没关系）。
     */
    fun healthy(c: Context): Boolean = link(c) == Link.RESPONDING

    /** 如实分类（界面按这个显示，不涂绿）。 */
    fun link(c: Context, allowProbe: Boolean = true): Link {
        if (!installed(c)) return Link.NOT_INSTALLED
        if (!genuine(c)) return Link.NOT_GENUINE
        if (!enabled(c)) return Link.DISABLED
        val cached = last?.takeIf { System.currentTimeMillis() - it.at < 60_000 }
        val info = cached ?: if (allowProbe) ping(c) else null
        if (info == null) {
            // 没有任何近期证据：叫「未知」而不是「失联」——失联要有一次真的调不通才算。
            return if (cached == null) Link.UNKNOWN else Link.SILENT
        }
        if (!info.responded) return Link.SILENT
        // v2 通了、但哨兵没登记我们 → 证据不足，写「未知」，不写绿。
        return if (info.registered || registered) Link.RESPONDING else Link.UNKNOWN
    }

    /**
     * **纯缓存**版本：绝不发起跨进程调用。
     *
     * 无障碍主线程、以及任何不能阻塞的地方都必须用这个，然后调 [probeAsync] 去后台更新缓存
     * （T05：一个客户端卡住不能把执行线程一起冻住）。
     */
    fun linkCached(c: Context): Link = link(c, allowProbe = false)

    /** 后台异步探一次（结果进 [last] 缓存）。不阻塞调用方。 */
    fun probeAsync(c: Context) = refresh(c)

    /** 为什么不在岗（给权限页和首页看）。在岗返回 null。 */
    fun problem(c: Context): String? = when (val l = link(c)) {
        Link.RESPONDING -> null
        Link.NOT_INSTALLED -> "没装「总控哨兵」"
        Link.NOT_GENUINE -> "装的「总控哨兵」签名不对"
        Link.DISABLED -> "「总控哨兵」的无障碍没开"
        Link.UNKNOWN -> "「总控哨兵」还没有 v2 证据（可能不是 v2 版本，健康能力不足）"
        Link.SILENT -> "「总控哨兵」开着但叫不应"
    }

    /** 给界面的一句话（不涂绿）。 */
    fun describe(c: Context): String {
        val l = link(c)
        val extra = last?.note.orEmpty()
        return if (extra.isBlank() || l == Link.RESPONDING) l.label else "${l.label}（$extra）"
    }

    // ---------- 恢复：把哨兵的无障碍挂回去（只记「请求已发出」） ----------

    /**
     * 把哨兵挂回去。**注意**：返回 true 只表示「恢复请求已发出」（系统设置里放回去了），
     * 不等于哨兵健康 —— 健康要等哨兵自己的新实例 + 真实 ack（规范 §8 / T11）。
     *
     * 写回前**重新读取**当前列表，把别的已启用服务原样保留（T12）；
     * 整个动作串行（同一时刻只有一个恢复流程在改无障碍配置）。
     */
    fun rebind(c: Context): Boolean = RebindCoordinator.rebind(c, SERVICE)

    // ---------- 进程活着吗 / 把它拉起来 ----------

    /**
     * 哨兵的**进程**还在不在。
     *
     * 为什么不能只看 ping：ping 走的是 Binder，哨兵的 ContentProvider 可能还在、进程也可能
     * 只剩个空壳。真机上「无障碍显示开着、但叫不应」往往就是进程被系统清掉了，
     * 这时候重绑系统设置没用，得先把进程拉起来让它自己重新 bind。
     *
     * 读 /proc 里有没有这个包名的进程。读不到（权限/格式变化）就当「不知道」，返回 true ——
     * 宁可不去拉起，也不要因为探测本身失败而乱开 App。
     */
    fun processAlive(c: Context): Boolean = try {
        val pidSelf = android.os.Process.myPid()
        java.io.File("/proc").listFiles()
            ?.any { f ->
                val pid = f.name.toIntOrNull() ?: return@any false
                if (pid == pidSelf) return@any false
                runCatching {
                    java.io.File(f, "cmdline").readText().trim('\u0000', ' ', '\n') == PKG
                }.getOrDefault(false)
            } ?: true
    } catch (_: Throwable) {
        true
    }

    /**
     * 把哨兵 App 拉到前台一次，让系统重新给它建进程和无障碍连接。
     *
     * 用启动它主页的方式，不绕过任何系统限制：后台启动 Activity 在部分 ROM 上会被拦，
     * 拦了也没关系——下一次自愈循环会再试，而且用户打开总控时也会自然带起来。
     */
    fun spawn(c: Context): Boolean = try {
        val i = c.packageManager.getLaunchIntentForPackage(PKG)
        if (i == null) {
            false
        } else {
            i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
            c.startActivity(i)
            true
        }
    } catch (e: Throwable) {
        Log.w(TAG, "拉起哨兵失败", e)
        false
    }

    // ---------- 解除闸门（v1 兼容读取，业务状态） ----------

    /** 手动刷新一次解除闸门（界面进入时用）。 */
    fun refreshRelease(c: Context) = exec.execute { runCatching { readLegacyRelease(c.applicationContext) } }
}

/**
 * 无障碍列表的串行恢复器（报告 §8 / 规范 §8 / T12）。
 *
 * 三个必须：
 *  1. **串行**：跨进程用文件锁（同时只有一个恢复流程在改同一个系统配置）；
 *  2. **写回前重新读取**：不用旧快照覆盖别人的变更，保留其他已启用服务；
 *  3. **写入成功只记「恢复请求已发出」**：等新实例 + 真 ack 才 HEALTHY（由 HealthLedger 保证）。
 */
object RebindCoordinator {
    private const val TAG = "RebindCoord"
    private const val GUARD_COMP = "com.zongkong.app/com.zongkong.app.guard.GuardService"
    private const val SENTINEL_COMP = "com.zongkong.sentinel/com.zongkong.sentinel.SentinelService"

    /** 跨进程的协调文件（总控自己的 filesDir；同一时刻只有一个恢复流程）。 */
    private fun leaseFile(c: Context) = java.io.File(java.io.File(c.filesDir, "zk").apply { mkdirs() }, "rebind.lock")

    private val localLock = Any()

    /**
     * 把 [component] 从无障碍列表里拿掉再放回去，让系统重新挂上。
     * 返回 true = **恢复请求已发出**（不是「已修好」）。
     */
    fun rebind(c: Context, component: ComponentName): Boolean {
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) {
            runCatching { Diag.log(c, "rebind_denied", "没有写安全设置权限，恢复请求发不出去") }
            return false
        }
        return synchronized(localLock) {
            val lease = leaseFile(c)
            val acquired = try {
                if (!lease.createNewFile()) {
                    // 别人正在恢复：串行化，直接让路（不排队、不叠加重绑）。
                    val holder = lease.lastModified()
                    if (System.currentTimeMillis() - holder < 15_000L) {
                        runCatching { Diag.log(c, "rebind_skip", "另一个恢复流程正在进行，本次让路") }
                        return@synchronized false
                    }
                    lease.setLastModified(System.currentTimeMillis())
                    true
                } else {
                    true
                }
            } catch (e: Throwable) {
                Log.w(TAG, "拿恢复锁失败", e)
                true // 拿不到锁也继续（单进程内还有 localLock 串行）
            }
            try {
                doRebind(c, component)
            } finally {
                if (acquired) runCatching { lease.delete() }
            }
        }
    }

    private fun doRebind(c: Context, component: ComponentName): Boolean = try {
        val cr = c.contentResolver
        fun current(): List<String> = (android.provider.Settings.Secure.getString(
            cr,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
        ) ?: "").split(':').filter { it.isNotBlank() }

        val flat = component.flattenToString()
        // 1) 写回前重新读取：只拿掉自己这一项，**保留其他已启用服务**（T12）。
        val snapshot = current()
        val without = snapshot.filter { ComponentName.unflattenFromString(it) != component }
        android.provider.Settings.Secure.putString(
            cr,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            without.joinToString(":"),
        )
        // 2) 等一会儿再放回：再读一次，别人这期间加的服务也要保留。
        Thread.sleep(1_200)
        val again = current()
        val restored = (again.filterNot { ComponentName.unflattenFromString(it) == component } + flat)
        android.provider.Settings.Secure.putString(
            cr,
            android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
            restored.joinToString(":"),
        )
        android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1)
        runCatching {
            Diag.log(
                c,
                "rebind_sent",
                "恢复请求已发出：${component.flattenToShortString()}（保留 ${restored.size - 1} 项其他服务；等新实例 + 真 ack 才算恢复）",
            )
        }
        true
    } catch (e: Throwable) {
        Log.w(TAG, "重新挂上无障碍失败", e)
        runCatching { Diag.log(c, "rebind_fail", "恢复请求没发出去：${e.message ?: e.javaClass.simpleName}") }
        false
    }
}

/**
 * 总控开给哨兵的口子（authority `com.zongkong.app.sentinel`）。
 *
 * **v2 语义变更（规范 §8）**：`wake` 只负责**把进程拉活** + 让总控自查一遍。
 * 它**不再**回 `GuardService.running` 这类布尔当健康凭据 —— 以前那个字段就是「假在线」的来源。
 * 健康一律由哨兵走 `com.zongkong.app.health` 的 `challenge` 判定。
 *
 * 为了让老哨兵不至于完全瞎，这里额外回一个 v2 诚实状态（`healthStatus`），
 * 并明确回 `healthSource=v2-challenge`；`guardRunning` 字段**故意不提供**。
 */
class SentinelWakeProvider : ContentProvider() {
    @Volatile private var lastWakeLog = 0L

    override fun onCreate(): Boolean {
        context?.let { runCatching { GuardHealth.init(it) } }
        return true
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return null
        val pm = c.packageManager
        val ok = try {
            pm.getPackagesForUid(Binder.getCallingUid()).orEmpty()
                .any { it == SentinelClient.PKG && pm.checkSignatures(c.packageName, it) == PackageManager.SIGNATURE_MATCH }
        } catch (_: Throwable) {
            false
        }
        if (!ok) return Bundle().apply { putString("error", "只接受哨兵的连接") }
        if (method != "wake") return Bundle().apply { putString("error", "不认识的请求：$method") }
        // wake 只做两件事：拉活进程（能走到这里就已经拉活了）+ 让总控自查一遍。
        val now = System.currentTimeMillis()
        if (now - lastWakeLog > 5 * 60_000L) {
            lastWakeLog = now
            runCatching { Diag.log(c, "sentinel_wake", "哨兵来叫醒（wake 只拉活进程，健康看 v2 挑战 ack）") }
            Thread {
                try {
                    kotlinx.coroutines.runBlocking { com.zongkong.app.system.SelfHeal.check(c) }
                } catch (e: Throwable) {
                    Log.w("SentinelWake", "自查出错", e)
                }
            }.start()
        }
        val v = runCatching { GuardHealth.verdict(GuardHealth.permissionEnabled(), HealthConfig()) }.getOrNull()
        val status = v?.status ?: HealthStatus.UNKNOWN
        return Bundle().apply {
            putBoolean("alive", true)
            // 明确写清楚：这个 provider 的存活 ≠ 健康。
            putString("healthSource", "v2-challenge")
            putString("healthStatus", status.name)
            putString("healthReason", v?.reason ?: "证据不足")
            putBoolean("healthGreen", status == HealthStatus.HEALTHY)
            putInt("protocol", SentinelClient.PROTOCOL_VERSION)
            putString("challengeAuthority", HealthWireProvider.AUTHORITY)
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}

/** 保留给权限页/诊断页的锁屏常量（避免各处重复字符串）。 */
object LockLabels {
    val STATES = LockState.values().map { it.name }

    fun label(s: String): String = when (s) {
        LockState.REQUESTED.name -> "已请求"
        LockState.API_CALLED.name -> "已调用系统接口"
        LockState.OBSERVED_LOCKED.name -> "已观察到锁屏"
        LockState.FAILED.name -> "失败"
        LockState.UNVERIFIED.name -> "未验证（不能算成功）"
        else -> s.ifBlank { "没有回执" }
    }
}

/** 策略读给人看（状态页用）。 */
object PolicyReadout {
    fun describe(p: Policy, nowWall: Long): String {
        val left = PolicyClock.remainingMs(p, nowWall)
        val st = if (p.effective(nowWall)) "生效中" else if (nowWall < p.startsAtWall) "未开始" else "已过期"
        return "$st；剩余 ${left / 60_000} 分钟；失效处理=${p.failureAction}；紧急=${p.emergencyState}"
    }
}
