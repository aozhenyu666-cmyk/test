package com.yishou.app.reflex

/**
 * 早上第一次解锁：直接打开思维线程的断点（没有线程就开一张入口题）。
 * 只在设定的时段里、每天一次；用户自己在设置里开，随时能关。
 */
data class MorningConfig(val enabled: Boolean = true, val fromHour: Int = 6, val toHour: Int = 10)

object Morning {

    /**
     * hour：现在几点（本地时间）；today / lastWakeDay：本地日期的天数编号（如 LocalDate.toEpochDay()）。
     */
    fun shouldWake(cfg: MorningConfig, hour: Int, today: Long, lastWakeDay: Long?): Boolean =
        cfg.enabled && hour in cfg.fromHour until cfg.toHour && lastWakeDay != today
}
