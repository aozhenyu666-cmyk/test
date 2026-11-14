package io.github.ccch1mneyyy.dsh_app.phone.a11y

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager

/**
 * R7 P0-1: 判定要用的事实里，需要问 Android 的那几项都收在这里——默认桌面、是不是系统应用、
 * 输入法、她自己。这样 [SessionPolicy] 保持纯函数、ProactiveRuntime 只接线。
 */
object SessionFactsOf {
    /** 桌面和包名都只查一次：换桌面/换构建要重启进程，舱里的判定等不起每次去问 PackageManager。 */
    @Volatile private var home: Set<String> = emptySet()
    @Volatile private var self: Set<String> = emptySet()

    fun of(
        context: Context,
        pkg: String,
        allow: Set<String>,
        whitelist: Set<String>,
        entertainment: Set<String>,
        dwellMs: Long,
        graceMs: Long,
        breakGlass: Boolean,
    ) = SessionFacts(
        allow = allow,
        whitelist = whitelist,
        home = home(context),
        entertainment = entertainment,
        self = selfPkgs(context),
        ime = FrontChange.imePackages(),
        systemApp = isSystem(context, pkg),
        dwellMs = dwellMs,
        graceMs = graceMs,
        breakGlass = breakGlass,
    )

    /** 当前默认桌面：`ACTION_MAIN + CATEGORY_HOME` 解析出来的包，兜底用 [SessionPolicy.NEUTRAL]。 */
    fun home(context: Context): Set<String> {
        if (home.isNotEmpty()) return home
        val pkg = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )?.activityInfo?.packageName
        }.getOrNull()
        return (SessionPolicy.NEUTRAL + setOfNotNull(pkg)).also { home = it }
    }

    fun selfPkgs(context: Context): Set<String> {
        if (self.isNotEmpty()) return self
        return setOf(context.packageName, context.packageName + ".fish").also { self = it }
    }

    /** 图库 / 文件选择器 / 相机 / GMS 弹层都是系统应用；名字查不到就当不是（宁可宽限也不误锁）。 */
    fun isSystem(context: Context, pkg: String): Boolean = runCatching {
        val info = context.packageManager.getApplicationInfo(pkg, 0)
        (info.flags and (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
    }.getOrDefault(false)
}
