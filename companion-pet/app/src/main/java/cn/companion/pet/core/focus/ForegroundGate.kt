package cn.companion.pet.core.focus

/** 一次窗口事件的处理结论。 */
sealed interface GatePlan {
    /** 需要等防抖后重新确认。 */
    data class Schedule(val packageName: String, val delayMillis: Long) : GatePlan

    /** 直接忽略，原因只用于日志/诊断。 */
    data class Ignore(val reason: String) : GatePlan
}

sealed interface GateResult {
    /** 确认为真实前台切换。 */
    data class Confirmed(val packageName: String) : GateResult

    /** 不值得当作“离开/进入”。 */
    data class Dropped(val reason: String) : GateResult
}

/**
 * 前台确认：窗口事件先过滤，再防抖，最后用实际活动窗口包名确认。
 *
 * 过滤的东西：系统 UI、输入法、桌面（如果传入）、本应用自己的窗口和覆盖层。
 * 这样弹出菜单、键盘、权限对话框、通知栏不会被当成“用户离开了目标应用”。
 */
class ForegroundGate(
    private val ownPackage: String,
    private val systemPackages: Set<String> = DEFAULT_SYSTEM,
    private val debounceMillis: Long = 300,
) {
    private var pendingPackage: String? = null
    private var pendingAt = 0L

    fun plan(eventPackage: String?, now: Long): GatePlan {
        if (eventPackage.isNullOrBlank()) return GatePlan.Ignore("没有包名")
        if (isTransient(eventPackage)) return GatePlan.Ignore("系统/输入法/自身窗口")
        pendingPackage = eventPackage
        pendingAt = now
        return GatePlan.Schedule(eventPackage, debounceMillis)
    }

    /**
     * 防抖到期后用实际活动窗口确认。[activePackage] 应来自 rootInActiveWindow。
     * 如果实际活动窗口是系统窗口/输入法/自身，则丢弃这次事件，不产生前台切换。
     */
    fun confirm(activePackage: String?, now: Long): GateResult {
        val expected = pendingPackage ?: return GateResult.Dropped("没有待确认事件")
        if (now - pendingAt + 1 < debounceMillis) return GateResult.Dropped("防抖未到")
        pendingPackage = null
        if (activePackage.isNullOrBlank()) return GateResult.Dropped("没有活动窗口")
        if (activePackage == ownPackage) return GateResult.Dropped("还是自己的窗口")
        if (isTransient(activePackage)) return GateResult.Dropped("活动窗口是系统/输入法")
        return GateResult.Confirmed(activePackage)
    }

    fun isTransient(pkg: String): Boolean = pkg == ownPackage || pkg in systemPackages

    fun hasPending(): Boolean = pendingPackage != null

    companion object {
        val DEFAULT_SYSTEM: Set<String> = setOf(
            "android",
            "com.android.systemui",
            "com.android.settings.intent",
            "com.google.android.permissioncontroller",
            "com.android.permissioncontroller",
            "com.android.packageinstaller",
            "com.vivo.permissionmanager",
            "com.android.inputmethod.latin",
            "com.google.android.inputmethod.latin",
            "com.sohu.inputmethod.sogou",
            "com.iflytek.inputmethod",
            "com.baidu.input",
            // 通话与紧急功能：接电话、拨号、紧急呼叫不算离开，也绝不拦。
            "com.android.incallui",
            "com.android.dialer",
            "com.google.android.dialer",
            "com.android.phone",
            "com.android.server.telecom",
            "com.android.emergency",
            "com.google.android.apps.safetyhub",
        )
    }
}
