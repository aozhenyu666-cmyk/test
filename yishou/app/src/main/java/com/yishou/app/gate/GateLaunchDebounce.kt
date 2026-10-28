package com.yishou.app.gate

/** 连续窗口事件去重只在同一次停留内有效，离开后再进入必须立刻拦截。 */
class GateLaunchDebounce(private val gapMs: Long = 2_000) {
    private var foreground: String? = null
    private var launchedPackage: String? = null
    private var launchedAt = 0L

    fun onForeground(pkg: String?) {
        if (pkg != foreground) {
            foreground = pkg
            launchedPackage = null
        }
    }

    fun ready(pkg: String, nowMono: Long): Boolean =
        launchedPackage != pkg || nowMono - launchedAt >= gapMs

    fun launched(pkg: String, nowMono: Long) {
        launchedPackage = pkg
        launchedAt = nowMono
    }
}

