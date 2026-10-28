package com.yishou.app.system

/**
 * 外出许可：一手自己打开系统组件（相册、相机、文件选择、屏幕授权弹窗）时，
 * 各家手机的这些界面包名五花八门，开局规则会把它们当成别的应用拦下。
 * 所以由一手打开它们之前先开一张许可：几分钟内开局规则不拦，回到一手就立刻收回。
 */
object ExternalGrace {
    private const val MINUTES = 3

    @Volatile
    private var until = 0L

    fun open(now: Long = System.currentTimeMillis()) {
        until = now + MINUTES * 60_000L
    }

    fun close() {
        until = 0
    }

    fun active(now: Long = System.currentTimeMillis()): Boolean = now < until
}
