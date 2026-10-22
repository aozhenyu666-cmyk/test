package com.moling.core.system

import com.moling.core.flow.Busy
import kotlinx.serialization.Serializable

/*
 * 第十三批 G4：系统环境工具（日历 / 勿扰 / 歌单）的**接口与本地规则**。
 *
 * 本批不新增 Android 权限、不改 Manifest、不申请 READ/WRITE_CALENDAR，也不接通知策略访问；
 * 只定义端口、能力状态、明确的拒绝/降级，以及可以在纯逻辑里验证的规则：
 *  - 没权限/没配置/设备不支持/App 没装 → 明确失败，绝不写成功；
 *  - 日历写入优先系统编辑页（ActionInsert 由用户保存），不能把“打开了 Intent”当成“已写入”；
 *  - 勿扰只恢复墨灵本次确实改过、且用户期间没手动改过的状态；
 *  - 歌单必须由用户选应用和可验证 URI，找不到就回配置，不模拟播放成功。
 *
 * 真正的 Android 适配器（CalendarContract / NotificationManager / Intent）留给后续批次；
 * 在接入前默认用 [UnavailableCalendar]、[UnavailableDnd]、[UnavailablePlaylist] 明确拒绝。
 */

@Serializable
enum class CapabilityKind(val label: String) {
    CALENDAR_READ("读日历忙闲"),
    CALENDAR_INSERT("打开系统日程编辑页"),
    CALENDAR_DIRECT_WRITE("直接写日历（需单独开启）"),
    DND("勿扰"),
    PLAYLIST("打开歌单"),
}

@Serializable
enum class CapabilityState(val label: String) {
    READY("可用"),
    NOT_CONFIGURED("没配置"),
    PERMISSION_DENIED("没给权限"),
    REVOKED("权限被撤销"),
    UNSUPPORTED("设备不支持"),
    APP_MISSING("目标 App 没装"),
}

@Serializable
data class Capability(
    val kind: CapabilityKind,
    val state: CapabilityState = CapabilityState.NOT_CONFIGURED,
    val detail: String = "",
    val checkedAt: Long = 0,
) {
    val usable: Boolean get() = state == CapabilityState.READY
}

@Serializable
enum class ToolError(val code: String) {
    NONE("NONE"),
    NOT_CONFIGURED("NOT_CONFIGURED"),
    PERMISSION_DENIED("PERMISSION_DENIED"),
    REVOKED("REVOKED"),
    UNSUPPORTED("UNSUPPORTED"),
    APP_MISSING("APP_MISSING"),
    INVALID_TARGET("INVALID_TARGET"),
    NO_SAVE_RECEIPT("NO_SAVE_RECEIPT"),
    FAILED("FAILED"),
}

data class ToolResult<out T>(
    val ok: Boolean,
    val value: T? = null,
    val error: ToolError = ToolError.NONE,
    val message: String = "",
    val at: Long = 0,
) {
    companion object {
        fun <T> ok(value: T, message: String = "", at: Long = 0) = ToolResult(true, value, ToolError.NONE, message, at)
        fun <T> fail(error: ToolError, message: String, at: Long = 0) = ToolResult<T>(false, null, error, message, at)
    }
}

/** 系统编辑页返回的不是“已保存”：用户在系统里点保存才算，墨灵不做假回执。 */
@Serializable
data class CalendarHandoff(
    val action: String,
    val opened: Boolean,
    val needsUserSave: Boolean,
    val message: String,
)

@Serializable
data class CalendarEventTemplate(
    val title: String,
    val start: Long,
    val end: Long,
    val location: String = "",
)

@Serializable
data class CalendarSyncResult(
    val busy: List<Busy> = emptyList(),
    val syncedAt: Long = 0,
    val added: Int = 0,
    val duplicates: Int = 0,
)

object CalendarSync {
    /** 导入忙闲：同 (开始, 结束, 标题) 去重，并记同步时间；不会把旧的同步时间当成新数据。 */
    fun merge(existing: List<Busy>, incoming: List<Busy>, at: Long): CalendarSyncResult {
        val seen = existing.associateBy { it.start to (it.end to it.title) }.toMutableMap()
        var added = 0
        var dup = 0
        incoming.forEach { b ->
            if (b.end <= b.start) return@forEach
            val key = b.start to (b.end to b.title)
            if (seen.containsKey(key)) dup++ else {
                seen[key] = b
                added++
            }
        }
        return CalendarSyncResult(seen.values.sortedBy { it.start }, at, added, dup)
    }
}

interface CalendarPort {
    fun capability(): Capability

    /** 只有 READY 才有数据；没权限返回明确错误，不返回空列表冒充“今天没安排”。 */
    fun readBusy(from: Long, to: Long): ToolResult<List<Busy>>

    /** 打开系统日程编辑页（ACTION_INSERT），由用户保存。 */
    fun insertEvent(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff>

    /** 直接写入：必须单独开启并单独授权；本批默认拒绝。 */
    fun writeDirect(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff>
}

/** 系统勿扰的档位。不是只有“开/关”：用户期间可能改成另一档，这时不能覆盖。 */
@Serializable
enum class DndMode(val label: String) {
    OFF("关"),
    PRIORITY("仅优先"),
    ALARMS("仅闹钟"),
    NONE("完全静音"),
}

@Serializable
data class DndChange(
    val changeId: String,
    val original: DndMode,
    val applied: DndMode,
    val at: Long,
)

@Serializable
enum class DndRestoreKind(val label: String) {
    RESTORED("已恢复"),
    NOT_CHANGED("不用恢复"),
    USER_CHANGED("你期间手动改过，不动"),
    EXPIRED("太久以前改的，不动"),
}

@Serializable
data class DndRestorePlan(
    val kind: DndRestoreKind,
    val restoreTo: DndMode? = null,
    val message: String = "",
)

object DndPolicy {
    /** 只恢复墨灵那次确实改过、且用户没再手动改的状态。 */
    fun plan(change: DndChange, current: DndMode, now: Long, maxAgeMs: Long = 6 * 60 * 60_000L): DndRestorePlan {
        if (now - change.at > maxAgeMs) {
            return DndRestorePlan(DndRestoreKind.EXPIRED, null, "太久以前改的勿扰状态，不去动它")
        }
        if (change.applied == change.original) {
            return DndRestorePlan(DndRestoreKind.NOT_CHANGED, null, "本来就是这个状态，没有可恢复的")
        }
        if (current == change.original) {
            return DndRestorePlan(DndRestoreKind.NOT_CHANGED, null, "已经是原来的状态")
        }
        if (current != change.applied) {
            return DndRestorePlan(DndRestoreKind.USER_CHANGED, null, "你期间自己改过勿扰，我不覆盖你的设置")
        }
        return DndRestorePlan(DndRestoreKind.RESTORED, change.original, "恢复成墨灵改之前的状态")
    }
}

interface DndPort {
    fun capability(): Capability

    fun current(): ToolResult<DndMode>

    /** 返回墨灵这次确实改过的记录；失败时没有记录，也不写成功。 */
    fun apply(mode: DndMode, reason: String, now: Long): ToolResult<DndChange>

    fun restore(change: DndChange, now: Long): ToolResult<DndRestorePlan>
}

@Serializable
data class PlaylistHandoff(
    val started: Boolean,
    val appPackage: String,
    val uri: String,
    val message: String,
)

object PlaylistTargets {
    /** 用户选的 App + URI 是否可验证。不可验证就不打开，回配置。 */
    fun check(appPackage: String, uri: String): Capability {
        if (appPackage.isBlank()) return Capability(CapabilityKind.PLAYLIST, CapabilityState.NOT_CONFIGURED, "还没选音乐 App")
        if (uri.isBlank() || !uri.contains("://")) return Capability(CapabilityKind.PLAYLIST, CapabilityState.NOT_CONFIGURED, "URI 不可验证，重新选一个")
        return Capability(CapabilityKind.PLAYLIST, CapabilityState.READY, "已选 $appPackage")
    }
}

interface PlaylistPort {
    fun capability(appPackage: String, uri: String): Capability

    /** 显式 Intent 打开选定的歌单/歌曲；started 只表示“系统接受打开”，不表示播放成功。 */
    fun open(appPackage: String, uri: String, now: Long): ToolResult<PlaylistHandoff>
}

// ------------------------------------------------------------------ 默认适配器：明确拒绝/降级

object UnavailableCalendar : CalendarPort {
    private val cap = Capability(CapabilityKind.CALENDAR_READ, CapabilityState.PERMISSION_DENIED, "本批没有申请日历权限")
    override fun capability(): Capability = cap
    override fun readBusy(from: Long, to: Long): ToolResult<List<Busy>> =
        ToolResult.fail(ToolError.PERMISSION_DENIED, "没给日历权限：看不了忙闲，排计划先不参考日历")
    override fun insertEvent(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> =
        ToolResult.fail(ToolError.PERMISSION_DENIED, "没给日历权限：打不开系统日程编辑页")
    override fun writeDirect(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> =
        ToolResult.fail(ToolError.PERMISSION_DENIED, "没给日历写入权限：不会直接写")
}

object UnavailableDnd : DndPort {
    private val cap = Capability(CapabilityKind.DND, CapabilityState.UNSUPPORTED, "还没接系统通知策略，设备支持未知")
    override fun capability(): Capability = cap
    override fun current(): ToolResult<DndMode> =
        ToolResult.fail(ToolError.UNSUPPORTED, "还没接系统通知策略，读不到勿扰状态")
    override fun apply(mode: DndMode, reason: String, now: Long): ToolResult<DndChange> =
        ToolResult.fail(ToolError.UNSUPPORTED, "还没接系统通知策略，勿扰没有生效")
    override fun restore(change: DndChange, now: Long): ToolResult<DndRestorePlan> =
        ToolResult.fail(ToolError.UNSUPPORTED, "还没接系统通知策略，恢复不了")
}

object UnavailablePlaylist : PlaylistPort {
    override fun capability(appPackage: String, uri: String): Capability {
        val target = PlaylistTargets.check(appPackage, uri)
        return if (target.usable) Capability(CapabilityKind.PLAYLIST, CapabilityState.NOT_CONFIGURED, "本批没有接音乐 App 打开器") else target
    }
    override fun open(appPackage: String, uri: String, now: Long): ToolResult<PlaylistHandoff> =
        ToolResult.fail(ToolError.NOT_CONFIGURED, "还没接音乐 App；配置好应用和歌单 URI 再试")
}

/** 一把持有三种工具的入口；每个工具独立，没开某个不影响案板/聊天/陪练。 */
class SystemTools(
    private val calendar: CalendarPort = UnavailableCalendar,
    private val dnd: DndPort = UnavailableDnd,
    private val playlist: PlaylistPort = UnavailablePlaylist,
) {
    fun capabilities(now: Long = 0): List<Capability> = listOf(calendar.capability(), dnd.capability(), playlist.capability("", ""))

    fun calendarBusy(from: Long, to: Long): ToolResult<List<Busy>> = calendar.readBusy(from, to)

    fun calendarInsert(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> = calendar.insertEvent(event, now)

    fun calendarWriteDirect(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> = calendar.writeDirect(event, now)

    fun dndCurrent(): ToolResult<DndMode> = dnd.current()

    fun dndApply(mode: DndMode, reason: String, now: Long): ToolResult<DndChange> = dnd.apply(mode, reason, now)

    fun dndRestore(change: DndChange, now: Long): ToolResult<DndRestorePlan> = dnd.restore(change, now)

    fun openPlaylist(appPackage: String, uri: String, now: Long): ToolResult<PlaylistHandoff> = playlist.open(appPackage, uri, now)

    fun playlistCapability(appPackage: String, uri: String): Capability = playlist.capability(appPackage, uri)
}
