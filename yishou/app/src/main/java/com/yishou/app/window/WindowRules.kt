package com.yishou.app.window

import com.yishou.app.settings.AppPrefs

/**
 * 开局规则：陪练窗口进行中（且不在休息）时，切到哪些应用可以、哪些要被拉回陪练。
 * 系统界面、桌面、输入法、电话、相机、系统设置等永远放行——
 * 透明原则要求用户随时能进系统设置关掉本应用。
 */
object WindowRules {

    enum class Decision {
        /** 不管 */
        ALLOW,
        /** 可以短暂停留，超过时长再拉回 */
        SHORT,
        /** 立即拉回陪练 */
        BLOCK,
    }

    fun decide(pkg: String, p: AppPrefs, systemAllowed: Set<String>, now: Long): Decision = when {
        !p.windowStrict -> Decision.ALLOW
        p.windowPauseUntil > now -> Decision.ALLOW
        pkg in systemAllowed || pkg in STATIC_SYSTEM -> Decision.ALLOW
        pkg in p.windowAllowed -> Decision.ALLOW
        pkg in p.windowShortApps -> Decision.SHORT
        else -> Decision.BLOCK
    }

    /** 各家系统常见的系统界面包名。桌面、输入法、相机、拨号由运行时查询补全。 */
    val STATIC_SYSTEM = setOf(
        "android",
        "com.android.systemui",
        "com.android.settings",
        "com.vivo.settings",
        "com.android.incallui",
        "com.android.server.telecom",
        "com.android.dialer",
        "com.android.phone",
        "com.android.permissioncontroller",
        "com.google.android.permissioncontroller",
        "com.android.packageinstaller",
        "com.google.android.packageinstaller",
        "com.android.documentsui",
        "com.google.android.documentsui",
        "com.android.providers.media",
        "com.android.providers.media.module",
        "com.google.android.providers.media.module",
        "com.google.android.photopicker",
        "com.android.intentresolver",
        "com.android.camera",
        "com.android.camera2",
        "com.vivo.permissionmanager",
        "com.vivo.upslide",
        "com.vivo.smartshot",
        "com.iqoo.secure",
    )
}
