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
     * 直接问总控：你的眼睛（无障碍服务）在不在？顺便把它的进程拉起来（死了的话），它起来后会自己检查、自己修。
     * 同步调用，要在后台线程里做。true = 在；false = 不在或者叫不应。
     */
    fun askMain(c: Context): Boolean = try {
        c.contentResolver.call(android.net.Uri.parse(Proto.MAIN_WAKE_URI), Proto.WAKE, null, null)?.getBoolean("guard") == true
    } catch (e: Throwable) {
        Log.w(TAG, "叫不应总控", e)
        false
    }

    fun wakeMain(c: Context) {
        Thread { askMain(c) }.start()
    }

    fun grantCommands(): String =
        "adb shell pm grant com.zongkong.sentinel android.permission.WRITE_SECURE_SETTINGS\n" +
            "adb shell pm grant com.zongkong.app android.permission.WRITE_SECURE_SETTINGS"
}
