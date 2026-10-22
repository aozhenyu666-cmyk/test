package com.moling.app.system

import com.moling.core.system.Capability
import com.moling.core.system.CapabilityKind
import com.moling.core.system.CapabilityState
import com.moling.core.system.PlaylistTargets
import com.moling.core.mind.BEIJING
import kotlinx.serialization.Serializable
import java.time.Instant

/*
 * 第十五批 B5（W13 G4）：系统工具（日历 / 勿扰 / 歌单）的配置与纯规则。
 *
 * 这里不碰 Android API，方便在普通 JVM 单测里验证“每个工具独立、没权限就明确降级”：
 *  - 日历没开 / 没权限 / 没选日历 → 不是一个可以假装“今天没安排”的 READY；
 *  - 勿扰先看系统“通知策略访问”有没有给，没给就只说没给，不写“已生效”；
 *  - 歌单必须是用户选的 App + 可验证 URI；上次打开失败（App 没装）就退回配置。
 *
 * 真正的 Android 适配器在 AndroidSystemPorts.kt；core 的 SystemPorts 契约不变。
 */

/** 三项系统工具各自独立的开关和配置。关掉其中一项不影响另外两项，也不影响案板/聊天/陪练。 */
@Serializable
data class SystemToolConfig(
    /** 日历：允许读忙闲 / 打开系统日程编辑页。 */
    val calendarOn: Boolean = false,
    /** 用户选的日历（CalendarContract Calendars._ID）。空 = 还没选。 */
    val calendarId: String = "",
    val calendarName: String = "",
    /** 勿扰：允许墨灵在需要时改系统勿扰；恢复只认墨灵自己留下的记录。 */
    val dndOn: Boolean = false,
    /** 歌单：允许在用户点开时用显式 Intent 打开他选的 App + URI。 */
    val playlistOn: Boolean = false,
    val playlistPackage: String = "",
    val playlistUri: String = "",
    /** 上次显式 Intent 说目标 App 没装；改配置或打开成功就清掉。 */
    val playlistMissing: Boolean = false,
)

/** 设置页 / 调试页用的分类。 */
enum class SystemToolKind(val label: String) {
    CALENDAR("日历"),
    DND("勿扰"),
    PLAYLIST("歌单"),
}

/** 用户选中的一个日历（列表里只给可见日历）。 */
@Serializable
data class CalendarChoice(val id: String, val name: String, val account: String = "")

/**
 * 一次系统工具动作的记录：开始时间、结果、错误说明、能不能撤销。
 * 只用于界面/调试展示；不因为“发出了 Intent”就当成完成。
 */
@Serializable
data class SystemAction(
    val kind: SystemToolKind,
    val at: Long,
    val ok: Boolean,
    val message: String,
    val undo: String = "",
)

/** 纯规则：能力状态从“开关 + 权限 + 配置”算出来，不碰系统。 */
object SystemToolRules {

    fun calendarCapability(
        enabled: Boolean,
        permissionGranted: Boolean,
        hasSelection: Boolean,
        now: Long = 0,
    ): Capability {
        if (!enabled) {
            return Capability(CapabilityKind.CALENDAR_READ, CapabilityState.NOT_CONFIGURED, "日历没开", now)
        }
        if (!permissionGranted) {
            // 选过日历 = 以前拿到过权限；现在没了就是被撤销，不是“没配置”。
            return if (hasSelection) {
                Capability(CapabilityKind.CALENDAR_READ, CapabilityState.REVOKED, "日历权限被撤销了：去系统设置里重新给", now)
            } else {
                Capability(CapabilityKind.CALENDAR_READ, CapabilityState.PERMISSION_DENIED, "没给日历权限：读不了忙闲，也打不开日程页", now)
            }
        }
        if (!hasSelection) {
            return Capability(CapabilityKind.CALENDAR_READ, CapabilityState.NOT_CONFIGURED, "选一个日历再读忙闲", now)
        }
        return Capability(CapabilityKind.CALENDAR_READ, CapabilityState.READY, "已选日历，可以读忙闲", now)
    }

    fun dndCapability(
        enabled: Boolean,
        supported: Boolean,
        accessGranted: Boolean,
        now: Long = 0,
    ): Capability {
        if (!enabled) {
            return Capability(CapabilityKind.DND, CapabilityState.NOT_CONFIGURED, "勿扰没开", now)
        }
        if (!supported) {
            return Capability(CapabilityKind.DND, CapabilityState.UNSUPPORTED, "这台设备的系统没有勿扰策略接口", now)
        }
        if (!accessGranted) {
            return Capability(CapabilityKind.DND, CapabilityState.PERMISSION_DENIED, "没给通知策略访问：墨灵改不了勿扰", now)
        }
        return Capability(CapabilityKind.DND, CapabilityState.READY, "可以改勿扰；只恢复墨灵自己改过的状态", now)
    }

    fun playlistCapability(
        enabled: Boolean,
        appPackage: String,
        uri: String,
        lastMissing: Boolean = false,
        now: Long = 0,
    ): Capability {
        if (!enabled) {
            return Capability(CapabilityKind.PLAYLIST, CapabilityState.NOT_CONFIGURED, "歌单没开", now)
        }
        if (lastMissing && appPackage.isNotBlank()) {
            return Capability(CapabilityKind.PLAYLIST, CapabilityState.APP_MISSING, "上次打不开 $appPackage：检查应用是否安装、URI 对不对", now)
        }
        val target = PlaylistTargets.check(appPackage, uri)
        return if (target.usable) {
            Capability(CapabilityKind.PLAYLIST, CapabilityState.READY, "已选 $appPackage，可以显式打开", now)
        } else {
            Capability(CapabilityKind.PLAYLIST, target.state, target.detail, now)
        }
    }
}

/** 纯时间规则：设置页填 HH:mm，换成今天的毫秒；也用来给“读今天忙闲”算范围。 */
object SystemToolTimes {

    /** "20:30" / "2030" / "20：30"（全角冒号）→ 当天分钟；非法返回 null。 */
    fun parseMinute(text: String): Int? {
        val m = Regex("^\\s*(\\d{1,2})\\s*[:：]?\\s*(\\d{2})\\s*$").find(text) ?: return null
        val h = m.groupValues[1].toIntOrNull() ?: return null
        val min = m.groupValues[2].toIntOrNull() ?: return null
        if (h !in 0..23 || min !in 0..59) return null
        return h * 60 + min
    }

    fun dayStart(now: Long): Long =
        Instant.ofEpochMilli(now).atZone(BEIJING).toLocalDate().atStartOfDay(BEIJING).toInstant().toEpochMilli()

    fun at(now: Long, minute: Int): Long = dayStart(now) + minute.coerceIn(0, 24 * 60) * 60_000L

    /** 今天 00:00 到明天 00:00。 */
    fun todayRange(now: Long): Pair<Long, Long> {
        val start = dayStart(now)
        return start to start + 24 * 60 * 60_000L
    }
}
