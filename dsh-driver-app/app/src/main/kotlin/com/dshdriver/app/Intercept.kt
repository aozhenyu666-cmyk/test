package com.dshdriver.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.dshdriver.core.BlockRules
import com.dshdriver.core.Verdict
import java.time.LocalDate
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * 拦截规则的取用与放行记录。无障碍服务只把"现在前台是什么"喂进来，判断都在这里。
 *
 * 窗口事件一秒可能好几次，规则缓存 3 秒，避免每次都读文件。改开关 / 名单 / 放行后调 [invalidate]。
 */
object Intercept {
    private const val CACHE_MS = 3_000L
    private const val TRIGGER_COOLDOWN_MS = 8_000L

    /** 同一个包 8 秒内只拉一次门：无障碍和 UsageStats 两条路可能同时看到它。 */
    private val lastTrigger = ConcurrentHashMap<String, Long>()

    /** 原子占坑：两条线程同时命中同一个包时，只有一条能成功。 */
    fun claimTrigger(pkg: String, now: Long): Boolean = synchronized(lastTrigger) {
        val last = lastTrigger[pkg] ?: 0L
        if (now - last < TRIGGER_COOLDOWN_MS) false
        else {
            lastTrigger[pkg] = now
            true
        }
    }

    @Volatile private var cached: BlockRules? = null
    @Volatile private var loadedAt = 0L

    fun rules(context: Context): BlockRules {
        val now = System.currentTimeMillis()
        cached?.let { if (now - loadedAt < CACHE_MS) return it }
        val b = Engine.store.blocking()
        val fresh = BlockRules(
            // 开关关着时给一个空名单，等于全放行
            blocked = if (b.enabled) b.blocked.toSet() else emptySet(),
            allowedUntil = b.allowedUntil,
            always = alwaysSet(context),
        )
        cached = fresh
        loadedAt = now
        return fresh
    }

    fun invalidate() {
        cached = null
        loadedAt = 0
    }

    fun decide(context: Context, pkg: String?, now: Long): Verdict = rules(context).decide(pkg, now)

    fun enabled(context: Context): Boolean = Engine.store.blocking().enabled

    /**
     * 答对之后放行 [minutes] 分钟。返回 null 表示放行成功，否则是拒绝原因。
     *
     * 三条加严：夜间禁用、每日总额、同一 App 当天重复打开递减（每多开一次少 1 分钟，最少 1 分钟）。
     */
    @Synchronized
    fun allow(context: Context, pkg: String, minutes: Int): String? {
        if (isNight()) {
            val b0 = Engine.store.blocking()
            if (b0.nightBan) return "现在是夜间禁用时段（23:00–06:00），不开放。"
        }
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        val b0 = Engine.store.blocking()
        val b = if (b0.day == today) b0
        else b0.copy(day = today, grantedToday = emptyMap(), opensToday = emptyMap())

        val opens = b.opensToday[pkg] ?: 0
        var grant = (minutes.coerceIn(1, 120) - opens).coerceAtLeast(1)
        if (b.dailyMinutes > 0) {
            val left = b.dailyMinutes - b.grantedToday.values.sum()
            if (left <= 0) return "今天的放行额度用完了（每天 ${b.dailyMinutes} 分钟）。"
            grant = minOf(grant, left)
        }
        val until = now + grant * 60_000L
        Engine.store.saveBlocking(
            b.copy(
                allowedUntil = (b.allowedUntil - pkg) + (pkg to until),
                grantedToday = b.grantedToday + (pkg to ((b.grantedToday[pkg] ?: 0) + grant)),
                opensToday = b.opensToday + (pkg to (opens + 1)),
            ),
        )
        invalidate()
        return null
    }

    fun isNight(now: LocalTime = LocalTime.now()): Boolean = now.hour >= 23 || now.hour < 6

    /** 默认放行多久。 */
    fun minutes(context: Context): Int = Engine.store.blocking().minutes

    /** 这个包离放行到期还有多久（毫秒），没放行返回 0。 */
    fun remaining(context: Context, pkg: String): Long {
        val until = Engine.store.blocking().allowedUntil[pkg] ?: return 0L
        return (until - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    /** 永远不拦的：自己、桌面、输入法、系统界面、电话。拦了这几个等于把手机变砖。 */
    private fun alwaysSet(context: Context): Set<String> {
        val s = LinkedHashSet(BlockRules.NEVER_BLOCK)
        s.add(context.packageName)
        runCatching {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            context.packageManager.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)
                ?.activityInfo?.packageName?.let(s::add)
        }
        runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
                ?.substringBefore('/')?.takeIf { it.isNotBlank() }?.let(s::add)
        }
        return s
    }
}
