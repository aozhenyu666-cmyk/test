package com.zongkong.sentinel

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.ZoneId

/** 哨兵自己的一点状态：总控写来的心跳和名单、自己的心跳、接管次数、最近的记录。全在自己的 filesDir 里。 */
object Store {
    private const val TAG = "SentinelStore"
    @Volatile private var mem: Decide.State? = null
    @Volatile private var loaded = false
    @Volatile private var lastDisk = 0L
    @Volatile private var lastDiskKey = ""
    @Volatile var beat = 0L
        private set

    private fun f(c: Context, name: String) = File(c.filesDir, name)

    @Synchronized
    fun state(c: Context): Decide.State? {
        if (!loaded) {
            loaded = true
            mem = try {
                val file = f(c, "state.json")
                if (!file.exists()) null else JSONObject(file.readText()).let { o ->
                    val arr = o.optJSONArray("blocked") ?: JSONArray()
                    Decide.State(o.optLong("at"), (0 until arr.length()).map { arr.getString(it) }.toSet(), o.optBoolean("strict"), o.optLong("freeUntil"))
                }
            } catch (e: Throwable) {
                null
            }
        }
        return mem
    }

    /** 总控写来的状态。内存里每次都更新；磁盘只在名单变了或者一分钟一次时写（总控几秒写一次，不必每次落盘）。 */
    @Synchronized
    fun put(c: Context, s: Decide.State) {
        state(c)
        mem = s
        val key = "${s.blocked.sorted()}|${s.strict}|${s.freeUntil}|${s.at == 0L}"
        val now = System.currentTimeMillis()
        if (key == lastDiskKey && now - lastDisk < 60_000) return
        lastDiskKey = key
        lastDisk = now
        try {
            val o = JSONObject().put("at", s.at).put("strict", s.strict).put("freeUntil", s.freeUntil).put("blocked", JSONArray(s.blocked.toList()))
            val tmp = f(c, "state.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f(c, "state.json"))) { f(c, "state.json").writeText(o.toString()); tmp.delete() }
        } catch (e: Throwable) {
            Log.w(TAG, "写状态失败", e)
        }
    }

    fun touch() { beat = System.currentTimeMillis() }

    /** 问过总控、它说眼睛在：当作一次心跳（只改内存）。 */
    @Synchronized
    fun refresh(c: Context, now: Long) {
        state(c)?.let { mem = it.copy(at = now) }
    }

    // ---------- 接管次数 ----------

    fun takeovers(c: Context): Int = c.getSharedPreferences("s", Context.MODE_PRIVATE).getInt("takeovers", 0)
    fun lastTakeover(c: Context): Long = c.getSharedPreferences("s", Context.MODE_PRIVATE).getLong("lastTakeover", 0)
    fun countTakeover(c: Context) {
        val p = c.getSharedPreferences("s", Context.MODE_PRIVATE)
        p.edit().putInt("takeovers", p.getInt("takeovers", 0) + 1).putLong("lastTakeover", System.currentTimeMillis()).apply()
    }

    // ---------- 策略版本（报告 P0-03） ----------

    /**
     * 哨兵现在持有的策略版本；null = 还没收到过任何带版本的策略。
     *
     * 为什么要落盘：进程重启后如果忘了自己收到过哪一版，
     * 一条迟到的旧推送就会被当新策略接受，用户明明已经退出监管却继续被拦。
     */
    fun policyRevision(c: Context): Int? =
        c.getSharedPreferences("s", Context.MODE_PRIVATE)
            .let { if (it.contains(PREF_REVISION)) it.getInt(PREF_REVISION, 0) else null }

    /** 记下已接受的最新的策略版本（只增不减：仲裁已保证不会倒退写入）。 */
    fun setPolicyRevision(c: Context, revision: Int) {
        c.getSharedPreferences("s", Context.MODE_PRIVATE).edit().putInt(PREF_REVISION, revision).apply()
    }

    private const val PREF_REVISION = "policyRevision"

    // ---------- 解除闸门（0.15） ----------

    @Volatile private var releaseLoaded = false
    @Volatile private var releaseMem: Release.Request? = null

    @Synchronized
    fun release(c: Context): Release.Request? {
        if (!releaseLoaded) {
            releaseLoaded = true
            releaseMem = try {
                val file = f(c, "release.json")
                if (!file.exists()) null else JSONObject(file.readText()).let { o ->
                    Release.Request(
                        Release.Kind.valueOf(o.getString("kind")),
                        o.getString("analysis"),
                        o.getLong("s1w"), o.getLong("s1m"),
                        o.optLong("s2w"), o.optLong("grant"),
                    )
                }
            } catch (e: Throwable) {
                null
            }
        }
        return releaseMem
    }

    /** 存 / 清当前的解除申请（null = 清掉）。 */
    @Synchronized
    fun putRelease(c: Context, req: Release.Request?) {
        release(c)
        releaseMem = req
        try {
            val file = f(c, "release.json")
            if (req == null) { file.delete(); return }
            val o = JSONObject()
                .put("kind", req.kind.name).put("analysis", req.analysis)
                .put("s1w", req.stage1Wall).put("s1m", req.stage1Mono)
                .put("s2w", req.stage2Wall).put("grant", req.grantedUntil)
            val tmp = f(c, "release.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(file)) { file.writeText(o.toString()); tmp.delete() }
        } catch (e: Throwable) {
            Log.w(TAG, "写解除申请失败", e)
        }
    }

    /** 闸门参数：固定默认（以后要可调再从总控写过来）。 */
    val releaseRule = Release.Rule()

    /** 现在是不是有生效中的解除（哨兵据此不拦，总控据此让路）。顺手清掉已走完的申请。 */
    fun releaseGranted(c: Context, wall: Long = System.currentTimeMillis(), mono: Long = android.os.SystemClock.elapsedRealtime()): Boolean {
        val req = release(c) ?: return false
        if (Release.done(req, wall, mono, releaseRule)) { putRelease(c, null); return false }
        return Release.granted(req, wall, mono, releaseRule)
    }

    // ---------- 记录（最近 80 条） ----------

    private val ZONE: ZoneId = ZoneId.of("Asia/Shanghai")

    @Synchronized
    fun log(c: Context, text: String) {
        try {
            val t = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(ZONE)
            val line = "%02d-%02d %02d:%02d  %s".format(t.monthValue, t.dayOfMonth, t.hour, t.minute, text)
            val file = f(c, "log.txt")
            val lines = (if (file.exists()) file.readLines() else emptyList()) + line
            file.writeText(lines.takeLast(80).joinToString("\n"))
            Log.i("Sentinel", text)
        } catch (_: Throwable) {}
    }

    fun recent(c: Context, n: Int): List<String> = try {
        f(c, "log.txt").takeIf { it.exists() }?.readLines()?.takeLast(n)?.reversed().orEmpty()
    } catch (_: Throwable) {
        emptyList()
    }
}

/** 系统那边的事：总控装没装、签名对不对、无障碍开没开、重新挂上。 */
object Sys {
    private const val TAG = "SentinelSys"

    fun mainInstalled(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(Proto.MAIN_PKG, 0); true
    } catch (_: Throwable) {
        false
    }

    /** 调用方是不是真的总控（包名对、签名和哨兵一样）。 */
    fun callerIsMain(c: Context): Boolean = try {
        val pm = c.packageManager
        pm.getPackagesForUid(Binder.getCallingUid()).orEmpty()
            .any { it == Proto.MAIN_PKG && pm.checkSignatures(c.packageName, it) == PackageManager.SIGNATURE_MATCH }
    } catch (_: Throwable) {
        false
    }

    fun enabledList(c: Context): List<String> =
        (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "").split(':').filter { it.isNotBlank() }

    fun enabled(c: Context, component: ComponentName): Boolean = enabledList(c).any { ComponentName.unflattenFromString(it) == component }

    fun selfComponent(c: Context) = ComponentName(c, SentinelService::class.java)
    fun mainGuard(): ComponentName = ComponentName.unflattenFromString(Proto.MAIN_GUARD)!!

    fun canWrite(c: Context) = c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

    /**
     * 把 [component] 挂回去：
     *  - 在列表里但没在跑（“出现故障”）：拿掉、1.2 秒后放回，系统清掉故障标记、重新挂上；
     *  - 不在列表里了（“强行停止”/一键清理会把被停应用的无障碍从列表里删掉）：直接加回去。
     * 要“写安全设置”权限（adb 授权一次）；没有就返回 false。
     */
    fun rebind(c: Context, component: ComponentName): Boolean {
        if (!canWrite(c)) return false
        return try {
            val cr = c.contentResolver
            fun others() = enabledList(c).filter { ComponentName.unflattenFromString(it) != component }
            fun putBack() {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others() + component.flattenToString()).joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            if (!enabled(c, component)) {
                putBack()
            } else {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others().joinToString(":"))
                Handler(Looper.getMainLooper()).postDelayed({
                    try { putBack() } catch (e: Throwable) { Log.w(TAG, "放回无障碍列表失败", e) }
                }, 1_200)
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "重新挂上失败", e)
            false
        }
    }

    /**
     * v2：**只负责把总控的进程拉活**，不判断健康。
     *
     * 规范 §8 明确要求：`wake` 的返回值（总控老版本直接回 `GuardService.running`）**不能**当健康凭据。
     * 健康一律由 `:watch` 的 v2 挑战 ack 判定（见 `watch/HealthScheduler` 与 `HealthEvaluator`）。
     * 所以这里**故意返回 Unit**：调用方拿不到那个布尔，就不可能再拿它糊弄健康状态（T02）。
     *
     * 在后台线程里做（拦截页的按钮会直接调它）。
     */
    fun wakeMain(c: Context) {
        Thread { wakeMainBlocking(c) }.start()
    }

    private fun wakeMainBlocking(c: Context) {
        try {
            c.contentResolver.call(android.net.Uri.parse(Proto.MAIN_WAKE_URI), Proto.WAKE, null, null)
        } catch (e: Throwable) {
            Log.w(TAG, "叫总控的进程失败（不影响健康判定）", e)
        }
    }

    /**
     * 总控现在还在管着吗（只用来决定「哨兵要不要接管拦截」和「要不要续心跳」，不对外报健康）。
     * 同步调用，要在后台线程里做。
     *
     * **白名单**：只有正面证据算在——新版总控回 v2 的 `HEALTHY`，或者老版总控（没有 healthStatus）
     * 明确回 `guard=true`。未知 / 可疑 / 恢复中 / 暂停 / 无障碍关了 / 确认失联 / 没注册 / 看不懂的字符串，
     * 一律算不在：判据在 [MainGuard.interpret]（纯函数，JVM 单测覆盖）。
     *
     * 特别注意：说「不在」**不等于**立刻锁屏。这里只是不再替总控续心跳，让
     * `Decide.STALE_MS`（90 秒）那道过期闸门按老规矩走；真要锁还有 `:watch` 自己的确认预算和限流。
     */
    fun mainGuarding(c: Context): MainGuard = try {
        val r = c.contentResolver.call(android.net.Uri.parse(Proto.MAIN_WAKE_URI), Proto.WAKE, null, null)
        // Bundle → Map 只摊这三个字段；判断全交给纯函数（那边不依赖 Android，JVM 单测覆盖）。
        MainGuard.interpret(r?.let { b ->
            buildMap {
                if (b.containsKey(MainGuard.KEY_STATUS)) put(MainGuard.KEY_STATUS, b.getString(MainGuard.KEY_STATUS))
                if (b.containsKey(MainGuard.KEY_GUARD)) put(MainGuard.KEY_GUARD, b.getBoolean(MainGuard.KEY_GUARD))
                if (b.containsKey(MainGuard.KEY_ERROR)) put(MainGuard.KEY_ERROR, b.getString(MainGuard.KEY_ERROR))
            }
        })
    } catch (e: Throwable) {
        Log.w(TAG, "叫不应总控", e)
        MainGuard.NOT_GUARDING
    }

    fun grantCommands(): String =
        "adb shell pm grant com.zongkong.sentinel android.permission.WRITE_SECURE_SETTINGS\n" +
            "adb shell pm grant com.zongkong.app android.permission.WRITE_SECURE_SETTINGS"
}
