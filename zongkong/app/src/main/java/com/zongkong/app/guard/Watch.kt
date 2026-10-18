package com.zongkong.app.guard

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.WatchRule
import java.lang.ref.WeakReference

/**
 * 低屏模式的判定：连续用一个应用超过时限，就弹回 [GateActivity] 做一道题，冷却后再放行。
 *
 * 只记包名和时刻，不记任何内容：
 * - 内存里记“这个包名连续前台的开始时刻”（进程没了就重来，够用）；
 * - SharedPreferences("watch") 里 `grant_<pkg>` = 放行到期时间戳；`lastGate_<pkg>` = 上次弹的时刻（防重复）。
 *
 * 谁来调：[GuardService] 每看到一次窗口切换（以及自己的 tick）就调 [tick]。
 * 为了不依赖 tick 的频率，[tick] 里会给自己排一个「到时限再看一眼」的定时检查：
 * 哪怕用户一直停在同一个应用里、系统一直不产生窗口事件，时间到了也会弹。
 * 熄屏时不弹，等亮屏后的下一次检查。
 */
object Watch {

    private const val TAG = "Watch"
    private const val PREFS = "watch"

    /** 同一个包名 3 秒内不弹第二次（无障碍事件偶尔会连发几条）。 */
    private const val DEDUPE_MS = 3_000L

    /** 到点后再多等一点点，避免刚好卡在边界上反复判。 */
    private const val EDGE_MS = 100L

    /** 熄屏时先不弹，过一会儿再看（亮屏后会重新看到窗口事件）。 */
    private const val SCREEN_OFF_RETRY_MS = 30_000L

    private val lock = Any()

    /** 正在盯的应用；null = 不在低屏模式的应用里。 */
    @Volatile
    private var trackedPkg: String? = null

    /** 这一轮连续使用的开始时刻（放行没到期时顺延到放行到期时刻）。 */
    @Volatile
    private var trackedSince = 0L

    /** 上一次弹做题页的时刻（内存里再防一次重复）。 */
    @Volatile
    private var lastGateAt = 0L

    /** 最近一次调 tick 的无障碍服务：定时检查时用它 startActivity。 */
    @Volatile
    private var serviceRef: WeakReference<GuardService>? = null

    private val handler = Handler(Looper.getMainLooper())

    private val checker = Runnable {
        try {
            check()
        } catch (e: Throwable) {
            Log.w(TAG, "低屏模式定时检查失败", e)
        }
    }

    // ---------- 给 GuardService 用 ----------

    /**
     * 无障碍服务看到前台应用是 [pkg] 时调用（窗口切换、tick 都行）。
     *
     * - 不在规则里 / 低屏模式关了：清掉计时，离开应用即重置；
     * - 刚切进一个规则里的应用：从这里开始计时；
     * - 放行还没到期：先不弹（放行到期时间是下限，不会因为计时基准没更新而反复弹）；
     * - 连续时长 ≥ maxMinutes，且放行已到期，且 3 秒内没弹过：弹做题页。
     */
    fun tick(service: GuardService, pkg: String, now: Long) {
        val rule = ruleFor(service, pkg)
        synchronized(lock) {
            serviceRef = WeakReference(service)
            if (rule == null) {
                trackedPkg = null
                trackedSince = 0L
                handler.removeCallbacks(checker)
                return
            }
            if (trackedPkg != pkg) {
                trackedPkg = pkg
                trackedSince = now
            }
            val due = dueAt(service, pkg, rule)
            if (now >= due) {
                fire(service, pkg, now)
            } else {
                schedule(due - now + EDGE_MS)
            }
        }
    }

    /**
     * 什么时候该弹：
     * - 打开就答题的（拦截名单里的娱乐应用）：放行到期就弹，一打开没有放行就马上弹；
     * - 低屏规则：连续用满 maxMinutes，并且放行也已到期（做完题再给的 maxMinutes）。
     */
    private fun dueAt(context: Context, pkg: String, rule: WatchRule): Long =
        if (rule.onOpen) grantUntil(context, pkg) else maxOf(trackedSince + rule.maxMinutes * 60_000L, grantUntil(context, pkg))

    /** 放行到期时间戳；0 = 没有放行。 */
    fun grantUntil(context: Context, pkg: String): Long = try {
        prefs(context).getLong(GRANT + pkg, 0L)
    } catch (e: Exception) {
        0L
    }

    /** 做题通过：到 [until] 之前不再弹。由 [GateActivity] 调用。 */
    fun grant(context: Context, pkg: String, until: Long) {
        try {
            prefs(context).edit().putLong(GRANT + pkg, until).apply()
        } catch (e: Exception) {
            Log.w(TAG, "写放行记录失败", e)
        }
    }

    // ---------- 给界面用 ----------

    /** 这一轮已经连续用了多久（毫秒）；不是正在盯的应用就是 0。 */
    fun usedMillis(pkg: String, now: Long = System.currentTimeMillis()): Long = synchronized(lock) {
        if (trackedPkg == pkg && trackedSince > 0L) (now - trackedSince).coerceAtLeast(0L) else 0L
    }

    fun usedMinutes(pkg: String, now: Long = System.currentTimeMillis()): Int =
        (usedMillis(pkg, now) / 60_000L).toInt()

    /** 还能连续用多少秒：有未到期的放行先算放行剩余，否则算这一轮的剩余。 */
    fun remainingSeconds(context: Context, pkg: String, now: Long = System.currentTimeMillis()): Int {
        val rule = ruleFor(context, pkg) ?: return 0
        val cap = rule.maxMinutes * 60
        val grant = grantUntil(context, pkg)
        if (now < grant) return ((grant - now) / 1000L).toInt().coerceIn(0, cap)
        val used = usedMillis(pkg, now)
        if (used <= 0L) return cap
        return (cap - (used / 1000L)).toInt().coerceIn(0, cap)
    }

    // ---------- 内部 ----------

    /**
     * 这个应用归谁管：自己加的低屏规则，或者拦截名单里的娱乐应用（打开就答题）。
     * 严管时拦截名单里的应用由拦截页管（直接弹回），这里不重复弹。
     */
    fun ruleFor(context: Context, pkg: String): WatchRule? {
        val store = context.zk.store
        val config = store.config.value
        val rule = config.watch.ruleFor(pkg, config.blocked) ?: return null
        if (pkg in config.blocked && store.status().strict) return null
        return rule
    }

    /** 定时到期检查：条件和 [tick] 一样，只是不用等下一次窗口事件。 */
    private fun check() {
        val service = serviceRef?.get() ?: return
        if (!GuardService.running) return
        val now = System.currentTimeMillis()
        synchronized(lock) {
            val pkg = trackedPkg ?: return
            val rule = ruleFor(service, pkg)
            if (rule == null) {
                trackedPkg = null
                trackedSince = 0L
                return
            }
            if (!interactive(service)) {
                schedule(SCREEN_OFF_RETRY_MS)
                return
            }
            // 和 tick 同一套判定
            val due = dueAt(service, pkg, rule)
            if (now + EDGE_MS < due) {
                schedule(due - now)
            } else {
                fire(service, pkg, now)
            }
        }
    }

    /** 弹做题页。3 秒内弹过（内存或 SharedPreferences 里的记录）就不弹。 */
    private fun fire(service: GuardService, pkg: String, now: Long) {
        val lastFromPrefs = try {
            prefs(service).getLong(GATE + pkg, 0L)
        } catch (e: Exception) {
            0L
        }
        if (now - maxOf(lastGateAt, lastFromPrefs) < DEDUPE_MS) return
        lastGateAt = now
        // 弹过以后从这一刻重新计时：服务可能还没看到窗口切到做题页，免得过几秒又弹一次
        trackedSince = now
        try {
            prefs(service).edit().putLong(GATE + pkg, now).apply()
        } catch (e: Exception) {
            Log.w(TAG, "记 lastGate 失败", e)
        }
        try {
            Log.i(TAG, "低屏模式：连续用 $pkg 到时限，弹回去做题")
            service.startActivity(GateActivity.intent(service, pkg))
        } catch (e: Exception) {
            Log.e(TAG, "打不开做题页", e)
        }
    }

    private fun schedule(delayMs: Long) {
        handler.removeCallbacks(checker)
        handler.postDelayed(checker, delayMs.coerceAtLeast(500L))
    }

    private fun interactive(context: Context): Boolean = try {
        (context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != false
    } catch (e: Exception) {
        true
    }

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private const val GRANT = "grant_"
    private const val GATE = "lastGate_"
}
