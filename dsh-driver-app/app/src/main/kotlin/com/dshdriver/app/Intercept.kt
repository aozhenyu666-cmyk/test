package com.dshdriver.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import com.dshdriver.core.BlockRules
import com.dshdriver.core.Verdict

/**
 * 拦截规则的取用与放行记录。无障碍服务只把"现在前台是什么"喂进来，判断都在这里。
 *
 * 窗口事件一秒可能好几次，规则缓存 3 秒，避免每次都读文件。改开关 / 名单 / 放行后调 [invalidate]。
 */
object Intercept {
    private const val CACHE_MS = 3_000L

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

    /** 答对之后放行 [minutes] 分钟。 */
    fun allow(context: Context, pkg: String, minutes: Int) {
        val b = Engine.store.blocking()
        val until = System.currentTimeMillis() + minutes.coerceIn(1, 120) * 60_000L
        Engine.store.saveBlocking(b.copy(allowedUntil = (b.allowedUntil - pkg) + (pkg to until)))
        invalidate()
    }

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
