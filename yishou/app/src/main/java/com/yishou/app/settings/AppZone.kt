package com.yishou.app.settings

import java.text.SimpleDateFormat
import java.time.ZoneId
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 应用用的时区。默认固定北京时间：手机时区被改来改去时，“今天”、陪练窗口、每晚总结都不跟着乱。
 * 设置里可以改成跟随系统。全应用只从这里取时区，不直接用系统时区。
 */
object AppZone {
    const val BEIJING = "Asia/Shanghai"
    const val SYSTEM = "system"

    @Volatile
    var id: String = BEIJING

    val zone: ZoneId
        get() = if (id == SYSTEM) ZoneId.systemDefault() else runCatching { ZoneId.of(id) }.getOrDefault(ZoneId.of(BEIJING))

    fun format(pattern: String, t: Long): String =
        SimpleDateFormat(pattern, Locale.CHINA).apply { timeZone = TimeZone.getTimeZone(zone) }.format(Date(t))
}
