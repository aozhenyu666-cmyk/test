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
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import java.util.concurrent.Executors

/*
 * 哨兵（0.13 起是独立应用 com.zongkong.sentinel）。
 *
 * 0.11 的哨兵和总控在同一个应用里（只是换了个进程）：总控被系统“强行停止”、被清理、更新、崩溃时，
 * 系统按应用处理——两个一起停，设置里两个一起显示“出现故障”。所以拆成了另一个应用：
 * 单独的包名、单独的用户 ID、单独的进程，总控出什么事都碰不到它。
 *
 * 两边只通过对方开的 ContentProvider 说话，而且只认同一把签名：
 *   - 总控 → 哨兵（content://com.zongkong.sentinel.link）：state = 心跳 + 拦截名单 + 是否休假（最多 5 秒一次）；ping = 你在不在跑
 *   - 哨兵 → 总控（content://com.zongkong.app.sentinel）：wake = 碰一下，把总控的进程拉起来、让它自己检查一遍
 * 哨兵看到心跳停了 90 秒就接管：拦截名单里的应用一律拦，提醒你，有权限就把总控的无障碍重新挂上。
 * 总控也盯着哨兵：哨兵装了、开着却没在跑，总控把它重新挂上。
 */
object SentinelClient {
    private const val TAG = "SentinelClient"
    const val PKG = "com.zongkong.sentinel"
    val SERVICE = ComponentName(PKG, "com.zongkong.sentinel.SentinelService")
    private val URI: Uri = Uri.parse("content://com.zongkong.sentinel.link")
    /** 固定下载地址（和总控同一个发布）。 */
    const val DOWNLOAD = "https://github.com/aozhenyu666-cmyk/test/releases/download/zongkong-latest/zongkong-sentinel.apk"
    const val GRANT_COMMAND = "adb shell pm grant com.zongkong.sentinel android.permission.WRITE_SECURE_SETTINGS"

    data class Info(
        val running: Boolean,
        val beat: Long,
        val takeovers: Int,
        val lastTakeover: Long,
        val canWrite: Boolean,
        val version: String,
        val log: List<String>,
        /** 这次回话的时刻。 */
        val at: Long,
    )

    /** 最近一次从哨兵那里拿到的状态（每次写心跳都顺便带回来）。 */
    @Volatile var last: Info? = null
        private set

    /** 哨兵那边的解除闸门生效到几点（墙上时间）；每次和哨兵通话都更新。0 = 没解除。 */
    @Volatile private var releasedUntilMs = 0L

    /** 哨兵的解除闸门现在是不是生效（总控据此让路：不拦、不进总控时间）。 */
    fun released(now: Long = System.currentTimeMillis()): Boolean = now < releasedUntilMs

    private val exec = Executors.newSingleThreadExecutor { r -> Thread(r, "sentinel-link").apply { isDaemon = true } }

    fun installed(c: Context): Boolean = try {
        c.packageManager.getPackageInfo(PKG, 0); true
    } catch (_: Throwable) {
        false
    }

    /** 装的是真的哨兵（和总控同一把签名）。 */
    fun genuine(c: Context): Boolean = try {
        c.packageManager.checkSignatures(c.packageName, PKG) == PackageManager.SIGNATURE_MATCH
    } catch (_: Throwable) {
        false
    }

    fun enabled(c: Context): Boolean =
        (Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
            .split(':').any { ComponentName.unflattenFromString(it) == SERVICE }

    /** 写心跳和拦截状态（后台线程，不卡总控）。[at] = 0 表示“总控这就下线了，马上接管”。 */
    fun push(c: Context, blocked: Set<String>, strict: Boolean, freeUntil: Long, at: Long = System.currentTimeMillis()) {
        val app = c.applicationContext
        exec.execute {
            call(app, "state", Bundle().apply {
                putLong("at", at)
                putBoolean("strict", strict)
                putLong("freeUntil", freeUntil)
                putStringArray("blocked", blocked.toTypedArray())
            })
        }
    }

    /** 从哨兵回来的 Bundle 里读解除闸门的到期时间。 */
    private fun readReleased(r: Bundle?) {
        if (r != null && !r.containsKey("error")) releasedUntilMs = r.getLong("releasedUntil", 0L)
    }

    /** 问一句哨兵在不在跑（同步，跨进程一次，通常几毫秒）。没装、不是真的、叫不应：null。 */
    fun ping(c: Context): Info? = call(c, "ping", null)

    private fun call(c: Context, method: String, extras: Bundle?): Info? {
        if (!installed(c) || !genuine(c)) return null
        return try {
            val r = c.contentResolver.call(URI, method, null, extras) ?: return null
            if (r.containsKey("error")) {
                Log.w(TAG, "哨兵拒绝：${r.getString("error")}")
                return null
            }
            readReleased(r)
            Info(
                r.getBoolean("running"), r.getLong("beat"), r.getInt("takeovers"), r.getLong("lastTakeover"),
                r.getBoolean("canWrite"), r.getString("version").orEmpty(), r.getStringArray("log")?.toList() ?: last?.log.orEmpty(),
                System.currentTimeMillis(),
            ).also { last = it }
        } catch (e: Throwable) {
            Log.w(TAG, "连不上哨兵", e)
            null
        }
    }

    /** 哨兵在岗：装了、开着、在跑、3 分钟内有心跳。最近一分钟问过就用问过的，否则现问一次。 */
    fun healthy(c: Context): Boolean {
        if (!installed(c) || !enabled(c)) return false
        val now = System.currentTimeMillis()
        val info = last?.takeIf { now - it.at < 60_000 } ?: ping(c) ?: return false
        return info.running && now - info.beat < 3 * 60_000L
    }

    /** 为什么不在岗（给权限页和首页看）。在岗返回 null。 */
    fun problem(c: Context): String? = when {
        !installed(c) -> "没装「总控哨兵」"
        !genuine(c) -> "装的「总控哨兵」签名不对"
        !enabled(c) -> "「总控哨兵」的无障碍没开"
        !healthy(c) -> "「总控哨兵」开着但没在运行"
        else -> null
    }

    /**
     * 把哨兵挂回去：在列表里没在跑 → 拿掉再放回；被“强行停止 / 一键清理”从列表里删掉了 → 直接加回去。
     * 要总控有“写安全设置”权限；哨兵没装就不管。
     */
    fun rebind(c: Context): Boolean {
        if (c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED || !installed(c)) return false
        return try {
            val cr = c.contentResolver
            fun others() = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != SERVICE }
            fun putBack() {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others() + SERVICE.flattenToString()).joinToString(":"))
                Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            }
            if (!enabled(c)) {
                putBack()
            } else {
                Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others().joinToString(":"))
                Handler(Looper.getMainLooper()).postDelayed({
                    try { putBack() } catch (e: Throwable) { Log.w(TAG, "放回无障碍列表失败", e) }
                }, 1_200)
            }
            true
        } catch (e: Throwable) {
            Log.w(TAG, "重新挂上哨兵失败", e)
            false
        }
    }
}

/**
 * 总控开给哨兵的口子：哨兵看到总控掉线时碰一下（wake），总控的进程就会被拉起来，
 * 起来后自己检查一遍无障碍（能修就修）。只认哨兵（包名 + 同一把签名）。
 */
class SentinelWakeProvider : ContentProvider() {
    @Volatile private var lastWakeLog = 0L

    override fun onCreate(): Boolean = true

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
        val guard = GuardService.running
        // 眼睛不在：记一笔（5 分钟最多一条），自查一遍（能修就修）
        if (!guard) {
            val now = System.currentTimeMillis()
            if (now - lastWakeLog > 5 * 60_000L) {
                lastWakeLog = now
                runCatching { Diag.log(c, "sentinel_wake", "哨兵来问：总控的眼睛不在") }
                Thread {
                    try { kotlinx.coroutines.runBlocking { com.zongkong.app.system.SelfHeal.check(c) } } catch (e: Throwable) { Log.w("SentinelWake", "自查出错", e) }
                }.start()
            }
        }
        return Bundle().apply { putBoolean("alive", true); putBoolean("guard", guard) }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
