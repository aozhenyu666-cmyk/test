package io.github.ccch1mneyyy.dsh_app.phone.a11y

/**
 * R7 P0-1: 连麦舱里的前台判定，从「白名单以外一律 4 级锁」改成三级。
 *
 * 原来舱一开，判定就翻成 `!sessionAllows(pkg)`——不在白名单里就当成了「切到别的 App」。
 * 于是在 Gemini 里点「添加图片 / 添加文件」、账号选择弹层、相机、甚至回去点一下桌面，
 * 全都被 4 级锁踢回桌面（用户卸载过一次包）。
 *
 * 现在的顺序（先命中先返回）：
 *
 *   1. 急事底线 / 她自己 / 输入法·系统弹层 → 放行
 *   2. 插件白名单 + 用户放行名单 → 放行
 *   3. 当前默认桌面（运行时解析，[NEUTRAL] 兜底）→ 放行
 *   4. 娱乐目标（拦下的 App）→ 立刻锁
 *   5. 系统应用（图库、文件选择器、相机、GMS 都在这里）→ 放行
 *   6. 其余第三方 App → 宽限 [SessionFacts.graceMs]，超时才锁
 *
 * 纯 Kotlin（没有 Android import），和 [FrontChange] 一样在 JVM 上单测。
 */
enum class SessionVerdict { ALLOW, GRACE, LOCK }

/** 判定要用到的事实，全部由调用方从系统里查好；这个对象不做 IO。 */
data class SessionFacts(
    /** 插件 `/plan` 的 `session.allow`：这场连麦允许的 App。 */
    val allow: Set<String> = emptySet(),
    /** 用户自己的放行名单（R4-0 起舱内同样作数）。 */
    val whitelist: Set<String> = emptySet(),
    /** 当前默认桌面 + [SessionPolicy.NEUTRAL] 兜底。 */
    val home: Set<String> = emptySet(),
    /** 娱乐目标：`/plan` 的 block ∪ 拦截规则里的 App。 */
    val entertainment: Set<String> = emptySet(),
    /** DSH 自己（含 `.fish` 构建）。 */
    val self: Set<String> = emptySet(),
    /** 系统报告的输入法包名。 */
    val ime: Set<String> = emptySet(),
    /** 被判定这个包是不是系统应用（FLAG_SYSTEM / FLAG_UPDATED_SYSTEM_APP）。 */
    val systemApp: Boolean = false,
    /** 这个包连续在前台多久。换 App 时清零。 */
    val dwellMs: Long = 0L,
    /** 第三方 App 的宽限；插件下发的 `session.graceMs`。 */
    val graceMs: Long = SessionPolicy.DEFAULT_GRACE_MS,
    /** 破窗放风期间什么都不锁。 */
    val breakGlass: Boolean = false,
)

object SessionPolicy {
    /** 从白名单 App 跳出去、或者进了一个没见过的第三方 App，多久之后才升到锁。 */
    const val DEFAULT_GRACE_MS = 45_000L

    /** 电话、短信、地图、支付：任何模式都不锁（真急事的底线）。 */
    val NEVER_LOCK = setOf(
        "com.android.dialer", "com.google.android.dialer", "com.android.phone", "com.android.server.telecom", "com.android.mms",
        "com.google.android.apps.messaging", "com.android.contacts", "com.autonavi.minimap", "com.baidu.BaiduMap",
        "com.google.android.apps.maps", "com.tencent.map", "com.eg.android.AlipayGphone", "com.unionpay",
    )

    /**
     * 桌面兜底名单：`PackageManager.resolveActivity(ACTION_MAIN + CATEGORY_HOME)` 解析不到时用。
     * 荣耀、realme、第三方桌面的包名没法穷举——所以默认桌面靠运行时解析，这份只是兜底。
     */
    val NEUTRAL = setOf(
        "android", "com.android.systemui", "com.miui.home", "com.google.android.apps.nexuslauncher",
        "com.android.launcher3", "com.oppo.launcher", "com.huawei.android.launcher",
        "com.sec.android.app.launcher", "com.vivo.launcher", "com.bbk.launcher2",
    )

    fun judge(pkg: String, facts: SessionFacts): SessionVerdict {
        // 破窗放风：他申请下来的这 10 分钟就是"随便用"，锁了等于没放。
        if (facts.breakGlass) return SessionVerdict.ALLOW
        if (pkg in NEVER_LOCK) return SessionVerdict.ALLOW
        if (pkg in facts.self) return SessionVerdict.ALLOW
        if (FrontChange.isLayer(pkg, facts.ime, facts.self)) return SessionVerdict.ALLOW
        if (pkg in facts.allow || pkg in facts.whitelist) return SessionVerdict.ALLOW
        if (pkg in facts.home) return SessionVerdict.ALLOW
        // 娱乐目标在系统应用之前判：装成系统的娱乐 App 也得立刻锁。
        if (pkg in facts.entertainment) return SessionVerdict.LOCK
        // 图库 / 文件选择器 / 相机 / GMS 弹层：连麦里点"添加图片"必经之路，放行。
        if (facts.systemApp) return SessionVerdict.ALLOW
        return if (facts.dwellMs.coerceAtLeast(0L) < facts.graceMs) SessionVerdict.GRACE else SessionVerdict.LOCK
    }
}
