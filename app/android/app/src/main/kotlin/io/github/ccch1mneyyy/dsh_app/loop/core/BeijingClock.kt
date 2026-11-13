package io.github.ccch1mneyyy.dsh_app.loop.core

import java.util.Calendar
import java.util.TimeZone

/**
 * R7 P0-2：手机侧只有一个时间基准——北京时间。
 *
 * 原来 `ProactiveRuntime` 和 `InterceptRuntime` 各有一份一模一样的私有 `minuteOfDay` / `dayOf`，
 * 都用 `TimeZone.getDefault()`。手机时区被改成纽约（或模拟器默认 UTC）时，深夜锁、晨间开门、
 * 21:30 对账门的触发时刻会整体平移，用户看到的时间和她说的也对不上。
 *
 * 这个对象不读设备时区，也不读配置——**固定 `Asia/Shanghai`**。插件侧对应的是 `lockTimeZone`
 * 与 `tz.js` 的 `DEFAULT_TZ`，两边是同一个约定。
 */
object BeijingClock {
    /** 唯一的时间基准。中国无夏令时，全年恒定 +08:00。 */
    val ZONE: TimeZone = TimeZone.getTimeZone("Asia/Shanghai")

    /** 这一瞬在 Beijing 是当天第几分钟，0–1439。 */
    fun minuteOfDay(wall: Long): Int = calendar(wall).run {
        get(Calendar.HOUR_OF_DAY) * 60 + get(Calendar.MINUTE)
    }

    /**
     * 这一瞬属于哪一天：`年 * 1000 + 一年中的第几天`。
     * 只用来判断「是不是同一天 / 换天了没有」，不做日期运算。
     */
    fun dayOf(wall: Long): Long = calendar(wall).run {
        get(Calendar.YEAR) * 1000L + get(Calendar.DAY_OF_YEAR)
    }

    /** 这一瞬所在那天 Beijing 的零点（同一时间轴上的毫秒）。 */
    fun startOfDay(wall: Long): Long = calendar(wall).run {
        set(Calendar.HOUR_OF_DAY, 0)
        set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0)
        set(Calendar.MILLISECOND, 0)
        timeInMillis
    }

    /** 每次都新建：Calendar 不是线程安全的，这些调用会从调度线程和主线程一起进来。 */
    private fun calendar(wall: Long): Calendar =
        Calendar.getInstance(ZONE).apply { timeInMillis = wall }
}
