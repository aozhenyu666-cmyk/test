package com.zongkong.app.guard

import android.accessibilityservice.AccessibilityService
import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.zongkong.core.Defaults
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/*
 * 哨兵：第二双眼睛（0.11）。
 *
 * 为什么要它：总控的无障碍服务（GuardService）和整个 App 在同一个进程里——AI、Notion 同步、语音、截图、所有界面。
 * 这个进程只要死一次（哪里崩了、被系统清理），系统就把无障碍服务标成“出现故障”，不再自动拉起，要你手动关开。
 * 掉线的那几个小时，什么都管不住，正好变成畅玩。
 *
 * 哨兵是另一个无障碍服务，跑在自己的小进程（:sentinel）里：不碰 AI、不碰网络、不画 Compose 界面，几乎不会崩。
 *   - 总控在跑：哨兵什么都不做（只看总控的心跳）。
 *   - 总控掉了（心跳停了 90 秒）：哨兵接管——拦截名单里的应用一律拦（掉线 = 娱乐关着，不再是畅玩），
 *     弹出一个极简的页面，一键修复；给过“写安全设置”权限的话，自己把总控重新挂上。
 *   - 反过来，总控也盯着哨兵：哨兵开着却没在跑，总控把它重新挂上。
 *
 * 两边只通过 filesDir/zk/sentinel/ 下的两个小文件说话：state.json（总控写，哨兵读）、beat（哨兵写）。
 */
object SentinelLink {
    private const val TAG = "SentinelLink"
    /** 总控心跳停了这么久，算掉线。 */
    const val STALE_MS = 90_000L

    data class State(
        /** 总控上一次写的时刻（心跳）。 */
        val at: Long,
        val blocked: Set<String>,
        val strict: Boolean,
        /** 休假 / 紧急放行到几点：这之前哨兵也不拦。 */
        val freeUntil: Long,
    )

    private fun dir(c: Context) = File(c.filesDir, "zk/sentinel").apply { mkdirs() }

    /** 总控写：每次 tick 和窗口切换（最多 5 秒一次）都写一遍，顺便就是心跳。 */
    fun writeState(c: Context, blocked: Set<String>, strict: Boolean, freeUntil: Long, at: Long = System.currentTimeMillis()) {
        try {
            val o = JSONObject().put("at", at).put("strict", strict).put("freeUntil", freeUntil).put("blocked", JSONArray(blocked.toList()))
            val f = File(dir(c), "state.json")
            val tmp = File(dir(c), "state.json.tmp")
            tmp.writeText(o.toString())
            if (!tmp.renameTo(f)) { f.writeText(o.toString()); tmp.delete() }
        } catch (e: Throwable) {
            Log.w(TAG, "写哨兵状态失败", e)
        }
    }

    fun readState(c: Context): State? = try {
        val f = File(dir(c), "state.json")
        if (!f.exists()) null else {
            val o = JSONObject(f.readText())
            val arr = o.optJSONArray("blocked") ?: JSONArray()
            State(o.optLong("at"), (0 until arr.length()).map { arr.getString(it) }.toSet(), o.optBoolean("strict"), o.optLong("freeUntil"))
        }
    } catch (e: Throwable) {
        null
    }

    /** 哨兵写自己的心跳。 */
    fun beatSentinel(c: Context) {
        try { File(dir(c), "beat").writeText(System.currentTimeMillis().toString()) } catch (_: Throwable) {}
    }

    fun sentinelBeat(c: Context): Long = try { File(dir(c), "beat").readText().trim().toLong() } catch (_: Throwable) { 0L }

    fun mainAlive(s: State?, now: Long) = s != null && now - s.at < STALE_MS

    // ---------- 无障碍列表 ----------

    fun enabled(c: Context, cls: Class<*>): Boolean {
        val list = Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(c, cls)
        return list.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun canWrite(c: Context) = c.checkSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) == android.content.pm.PackageManager.PERMISSION_GRANTED

    /**
     * 把 [cls] 从已开启的无障碍列表里拿掉、1.2 秒后放回：系统会清掉“出现故障”的标记、重新挂上。
     * 要“写安全设置”权限（adb 授权一次）；没有就返回 false。
     */
    fun rebind(c: Context, cls: Class<*>): Boolean {
        if (!canWrite(c)) return false
        return try {
            val cr = c.contentResolver
            val me = ComponentName(c, cls)
            val others = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
            Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    val now = (Settings.Secure.getString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: "")
                        .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it) != me }
                    Settings.Secure.putString(cr, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (now + me.flattenToString()).joinToString(":"))
                    Settings.Secure.putInt(cr, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
                } catch (e: Throwable) {
                    Log.w(TAG, "放回无障碍列表失败", e)
                }
            }, 1_200)
            true
        } catch (e: Throwable) {
            Log.w(TAG, "重新挂上失败", e)
            false
        }
    }
}

/** 哨兵的无障碍服务。跑在 :sentinel 进程里，不用 App 里的任何单例。 */
class SentinelService : AccessibilityService() {
    private val main = Handler(Looper.getMainLooper())
    private var lastPkg: String? = null
    private var lastBlockAt = 0L
    private var lastHealAt = 0L
    private var wasDown = false

    private val loop = object : Runnable {
        override fun run() {
            try {
                SentinelLink.beatSentinel(this@SentinelService)
                // 一直停在一个被拦的应用里（没有新的窗口事件）：也再看一眼
                lastPkg?.let { check(it, recheck = false) }
            } catch (e: Throwable) {
                Log.w(TAG, "哨兵循环出错", e)
            }
            main.postDelayed(this, 30_000)
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        try {
            running = true
            SentinelLink.beatSentinel(this)
            Diag.log(this, "sentinel_on", "哨兵连上了")
            main.post(loop)
        } catch (e: Throwable) {
            Log.e(TAG, "哨兵连接时出错", e)
        }
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        try {
            if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
            val pkg = event.packageName?.toString() ?: return
            if (pkg == packageName || pkg == "com.android.systemui") return
            lastPkg = pkg
            check(pkg, recheck = true)
        } catch (e: Throwable) {
            Log.w(TAG, "哨兵处理事件出错", e)
        }
    }

    /**
     * 总控在跑就不管。掉线了：拦截名单里的一律拦（休假、紧急放行时除外）。
     * [recheck]：刚切到这个应用时，总控也同时收到了这个窗口事件，给它 2.5 秒写心跳，免得误拦。
     */
    private fun check(pkg: String, recheck: Boolean) {
        val now = System.currentTimeMillis()
        val st = SentinelLink.readState(this)
        if (SentinelLink.mainAlive(st, now)) {
            if (wasDown) { wasDown = false; Diag.log(this, "sentinel_main_back", "总控的眼睛回来了") }
            return
        }
        if (st != null && now < st.freeUntil) return
        val blocked = st?.blocked?.takeIf { it.isNotEmpty() } ?: Defaults.suggestedBlocked.toSet()
        if (pkg !in blocked) return
        if (!interactive()) return
        if (recheck) {
            main.postDelayed({ try { if (lastPkg == pkg) check(pkg, recheck = false) } catch (_: Throwable) {} }, 2_500)
            return
        }
        if (!wasDown) {
            wasDown = true
            Diag.log(this, "sentinel_takeover", "总控掉线 ${st?.let { (now - it.at) / 1000 } ?: -1} 秒，哨兵接管：拦 $pkg")
        }
        // 能自己修就修（两分钟最多一次）
        if (now - lastHealAt > 120_000 && SentinelLink.canWrite(this)) {
            lastHealAt = now
            if (SentinelLink.rebind(this, GuardService::class.java)) Diag.log(this, "rebind_ok", "哨兵把总控重新挂上")
        }
        if (now - lastBlockAt < 1_500) return
        lastBlockAt = now
        try {
            startActivity(SentinelBlockActivity.intent(this, pkg))
        } catch (e: Throwable) {
            Log.e(TAG, "哨兵打不开拦截页", e)
            performGlobalAction(GLOBAL_ACTION_HOME)
        }
    }

    private fun interactive(): Boolean = try {
        (getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    } catch (_: Throwable) {
        true
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        running = false
        main.removeCallbacks(loop)
        runCatching { Diag.log(this, "sentinel_off", "哨兵停了") }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "Sentinel"
        @Volatile var running = false
    }
}

/**
 * 哨兵的拦截页：总控掉线时出现。普通 View 写的，不依赖 Compose 和 App 里的任何东西，尽量不出错。
 */
class SentinelBlockActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG).orEmpty()
        val label = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString() } catch (_: Throwable) { pkg }
        val dp = resources.displayMetrics.density
        fun px(v: Int) = (v * dp).toInt()
        val ink = Color.rgb(0x2A, 0x24, 0x1F)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(0xF4, 0xF1, 0xEA))
            setPadding(px(24), px(64), px(24), px(24))
        }
        fun text(t: String, size: Float, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
            text = t; textSize = size; setTextColor(color); if (bold) typeface = Typeface.DEFAULT_BOLD
            setPadding(0, 0, 0, px(14)); setLineSpacing(0f, 1.2f)
        }
        fun button(t: String, primary: Boolean, onClick: () -> Unit) = Button(this).apply {
            text = t; textSize = 16f; isAllCaps = false
            setTextColor(if (primary) Color.WHITE else ink)
            setBackgroundColor(if (primary) Color.rgb(0xB5, 0x3A, 0x2A) else Color.rgb(0xE6, 0xE1, 0xD6))
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, px(56)).apply { topMargin = px(12) }
        }
        root.addView(text("哨兵", 14f, Color.rgb(0xB5, 0x3A, 0x2A), bold = true))
        root.addView(text("总控的眼睛掉了，「$label」先关着", 24f, bold = true))
        root.addView(text(
            "总控的无障碍服务停了（系统设置里会显示“此服务出现故障”）。在它恢复之前，拦截名单里的应用由哨兵拦着——掉线不再等于畅玩。\n\n" +
                "修好只要一下：在无障碍设置里把「总控」关掉再打开。",
            15f, Color.rgb(0x5A, 0x53, 0x4B),
        ))
        val canWrite = SentinelLink.canWrite(this)
        root.addView(button(if (canWrite) "一键修复" else "去无障碍设置修复", primary = true) {
            if (canWrite && SentinelLink.rebind(this, GuardService::class.java)) {
                Diag.log(this, "rebind_ok", "在哨兵页一键修复")
                goHome()
            } else {
                try { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
                finish()
            }
        })
        root.addView(button("打开总控", primary = false) {
            try {
                startActivity(Intent().setClassName(packageName, "com.zongkong.app.ui.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (_: Throwable) {}
            finish()
        })
        root.addView(button("回桌面", primary = false) { goHome() })
        if (!canWrite) root.addView(text("\n不想每次手动修：电脑上执行一次\nadb shell pm grant $packageName android.permission.WRITE_SECURE_SETTINGS\n以后哨兵会自己把总控挂回去。", 13f, Color.rgb(0x8A, 0x83, 0x7B)))
        setContentView(root)
    }

    private fun goHome() {
        try { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } catch (_: Throwable) {}
        finish()
    }

    @Deprecated("返回键：回桌面，不回被拦的应用")
    override fun onBackPressed() = goHome()

    companion object {
        const val EXTRA_PKG = "pkg"
        fun intent(c: Context, pkg: String): Intent = Intent(c, SentinelBlockActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            .putExtra(EXTRA_PKG, pkg)
    }
}
