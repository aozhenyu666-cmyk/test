package com.dshdriver.core

/** 拦，还是放。 */
enum class Verdict { ALLOW, BLOCK }

/**
 * 拦截名单 + 每个包各自的放行到期时间。
 *
 * 名单是"拦这几个"，不是 0.1.0 那种"只许用这几个"的允许名单——允许名单要预先知道 Gemini 会调起的所有
 * 系统界面（文件选择器、相机、权限框），真机上误伤过。
 *
 * 不可变：改规则返回新实例，判断永远只看构造时的那份。这样无障碍服务持有一份就不会读到写了一半的状态。
 */
data class BlockRules(
    val blocked: Set<String> = emptySet(),
    val allowedUntil: Map<String, Long> = emptyMap(),
    /** 永远放行的包：输入法、系统界面、桌面、本 APP 自己、电话。由调用方组装，这里只管收。 */
    val always: Set<String> = emptySet(),
) {

    /** [now] 用真时间（`System.currentTimeMillis()`），放行到期时间也按它存。 */
    fun decide(pkg: String?, now: Long): Verdict {
        if (pkg.isNullOrBlank()) return Verdict.ALLOW
        if (pkg in always) return Verdict.ALLOW
        if (pkg !in blocked) return Verdict.ALLOW
        val until = allowedUntil[pkg] ?: return Verdict.BLOCK
        // 到点那一刻就算过期：到期后再开应该被拦，而不是多放一秒
        return if (until > now) Verdict.ALLOW else Verdict.BLOCK
    }

    /** 这个包还能放到什么时候；没放行就是 null。 */
    fun expiry(pkg: String): Long? = allowedUntil[pkg]?.takeIf { it > 0 }

    /** 给某个包一段放行时间。 */
    fun allow(pkg: String, until: Long): BlockRules = copy(allowedUntil = allowedUntil + (pkg to until))

    /** 收窄放行（比如从名单里移掉这个 App）。 */
    fun revoke(pkg: String): BlockRules = copy(allowedUntil = allowedUntil - pkg)

    fun withBlocked(pkgs: Set<String>): BlockRules = copy(blocked = pkgs.filter { it.isNotBlank() }.toSet())

    /** 清掉已经过期的放行，别让表一直长。 */
    fun prune(now: Long): BlockRules {
        val live = allowedUntil.filterValues { it > now }
        return if (live.size == allowedUntil.size) this else copy(allowedUntil = live)
    }

    companion object {
        /** 系统界面、电话这类，拦了就出人命，任何配置都不能放开。 */
        val NEVER_BLOCK = setOf(
            "com.android.systemui",
            "android",
            "com.android.phone",
            "com.android.dialer",
            "com.android.incallui",
            "com.android.server.telecom",
        )
    }
}
