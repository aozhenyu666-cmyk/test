package io.github.ccch1mneyyy.dsh_app.phone.a11y

/**
 * 纯 Kotlin 底层免死放行清单（供 FocusPolicy / SessionPolicy / SystemBypassClassifier 共用）。
 *
 * 根治问题：此前只有 4 个基础包在 FocusPolicy.ALWAYS_ALLOWED 里，vivo 桌面 (com.bbk.launcher2)、
 * 下拉悬浮窗/小窗容器与 OEM 自带文件管理未统一放行，导致严格模式下切桌面或开文件管理即 0 秒瞬锁。
 */
object SystemSafeAllowlist {
    /** 电话、短信、地图、支付：任何模式下绝对放行。 */
    val EMERGENCY_PACKAGES = setOf(
        "com.android.dialer",
        "com.google.android.dialer",
        "com.android.phone",
        "com.android.server.telecom",
        "com.android.incallui",
        "com.android.mms",
        "com.google.android.apps.messaging",
        "com.android.contacts",
        "com.autonavi.minimap",
        "com.baidu.BaiduMap",
        "com.google.android.apps.maps",
        "com.tencent.map",
        "com.eg.android.AlipayGphone",
        "com.unionpay",
    )

    /** 系统桌面、系统 UI、下拉通知栏、多窗小窗容器、权限弹窗。 */
    val SYSTEM_UI_AND_LAUNCHERS = setOf(
        "android",
        "com.android.systemui",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.captiveportallogin",
        "com.google.android.captiveportallogin",
        "com.miui.home",
        "com.google.android.apps.nexuslauncher",
        "com.android.launcher3",
        "com.oppo.launcher",
        "com.huawei.android.launcher",
        "com.sec.android.app.launcher",
        "com.vivo.launcher",
        "com.bbk.launcher2",
        "com.hihonor.android.launcher",
        "com.realme.launcher",
        "com.oneplus.launcher",
        "com.vivo.smartmultiwindow",
        "com.vivo.floatingwindow",
        "com.miui.freeform",
        "com.oplus.exsystemservice",
        "com.coloros.floatassistant",
        // iQOO / vivo（OriginOS）：控制中心、全局搜索、i 管家
        "com.vivo.upslide",
        "com.vivo.globalsearch",
        "com.iqoo.secure",
    )

    /** 连 Gemini 必须先开的代理（NekoBox 等）。锁掉它等于锁掉连麦。 */
    val PROXY_PACKAGES = setOf(
        "moe.nb4a",
        "io.nekohasekai.sfa",
        "io.nekohasekai.sagernet",
        "com.v2ray.ang",
        "com.github.kr328.clash",
        "com.github.metacubex.clash.meta",
    )

    /** OEM 文件管理 / 文档选择器 / 相册 / 相机（不再在严格模式中误伤导致无法导题或看材料）。 */
    val FILE_AND_ESSENTIAL_TOOLS = setOf(
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.google.android.apps.nbu.files",
        "com.android.fileexplorer",
        "com.mi.android.globalFileexplorer",
        "com.vivo.filemanager",
        "com.bbk.filemanager",
        "com.coloros.filemanager",
        "com.oplus.filemanager",
        "com.huawei.hidisk",
        "com.hihonor.hidisk",
        "com.sec.android.app.myfiles",
        "com.android.camera",
        "com.android.camera2",
        "com.miui.gallery",
        "com.google.android.apps.photos",
        "com.android.deskclock",
        "com.google.android.deskclock",
        "com.lbe.security.miui",
        "com.miui.securitycenter",
        "com.coloros.safecenter",
        "com.vivo.permissionmanager",
        "com.huawei.systemmanager",
        // iQOO / vivo：相册、文件管理、Jovi 负一屏、截屏；系统选图片 / 选文件 / 分享页
        "com.vivo.gallery",
        "com.android.filemanager",
        "com.vivo.assistant",
        "com.vivo.smartshot",
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
        "com.android.intentresolver",
    )

    /** 2026-11-20 常驻 App：Gemini 本体（不含 Google App），和 DSH 自己同级，任何锁都不锁。和插件 never_lock.js 的 RESIDENT_PACKAGES 同一份。 */
    val RESIDENT_PACKAGES = setOf("com.google.android.apps.bard")

    val SAFE_BASE = EMERGENCY_PACKAGES + SYSTEM_UI_AND_LAUNCHERS + FILE_AND_ESSENTIAL_TOOLS + PROXY_PACKAGES + RESIDENT_PACKAGES

    fun isSelfPackage(pkg: String, ownPackage: String): Boolean {
        if (pkg.isBlank() || ownPackage.isBlank()) return false
        return pkg == ownPackage || pkg == "$ownPackage.fish"
    }

    fun isSafeSystemOrHome(pkg: String, extraHome: Set<String> = emptySet()): Boolean {
        val p = pkg.trim()
        if (p.isEmpty()) return false
        if (p in SAFE_BASE || p in extraHome) return true
        val low = p.lowercase()
        return low.endsWith(".launcher") || low.endsWith(".launcher2") ||
            low.contains("documentsui") || low.contains("filemanager") || low.contains("fileexplorer")
    }

    /**
     * 2026-11-18 锁机事故的总闸：**任何一条锁入口落锁前都必须先问这里**。
     *
     * 命中就绝不落锁：DSH 自己（锁页本身就是 DSH，锁它会死循环）、默认桌面（[home] 由运行时解析
     * 传进来）、系统界面、输入法（[ime]）、急事 App、代理、文件 / 相机等基础工具；包名为空也不锁。
     */
    fun neverLock(
        pkg: String?,
        ownPackage: String,
        home: Set<String> = emptySet(),
        ime: Set<String> = emptySet(),
    ): Boolean {
        val p = pkg?.trim().orEmpty()
        if (p.isEmpty()) return true
        if (isSelfPackage(p, ownPackage)) return true
        if (p.startsWith("io.github.ccch1mneyyy.dsh_app")) return true
        if (p in ime) return true
        val low = p.lowercase()
        if (low.contains("inputmethod") || low.contains("keyboard")) return true
        return isSafeSystemOrHome(p, home)
    }
}
