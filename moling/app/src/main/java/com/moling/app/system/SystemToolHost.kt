package com.moling.app.system

import android.content.Context
import com.moling.app.data.SettingsStore
import com.moling.core.flow.Busy
import com.moling.core.system.CalendarEventTemplate
import com.moling.core.system.CalendarHandoff
import com.moling.core.system.Capability
import com.moling.core.system.CapabilityKind
import com.moling.core.system.DndChange
import com.moling.core.system.DndMode
import com.moling.core.system.DndRestoreKind
import com.moling.core.system.DndRestorePlan
import com.moling.core.system.PlaylistHandoff
import com.moling.core.system.ToolError
import com.moling.core.system.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext

/**
 * B5：系统工具在 App 里的入口（设置页 / 调试页 / 排计划都从这里读）。
 *
 * 规矩：
 *  - 三项工具各自独立，读/写失败只影响这一项；
 *  - 每个动作都返回开始时间、结果、错误说明和能不能撤销，界面不能把 Intent 当回执；
 *  - 日历忙闲只在开关打开 + 选中日历 + 有权限时参与排计划；其余情况返回空，不编造。
 */
data class SystemToolState(
    val config: SystemToolConfig = SystemToolConfig(),
    val capabilities: List<Capability> = emptyList(),
    val calendars: List<CalendarChoice> = emptyList(),
    val busy: List<Busy> = emptyList(),
    val syncedAt: Long = 0,
    val dndChange: DndChange? = null,
    val actions: List<SystemAction> = emptyList(),
) {
    fun capability(kind: SystemToolKind): Capability? =
        capabilities.firstOrNull { it.kind == kind.capabilityKind }
}

val SystemToolKind.capabilityKind: CapabilityKind
    get() = when (this) {
        SystemToolKind.CALENDAR -> CapabilityKind.CALENDAR_READ
        SystemToolKind.DND -> CapabilityKind.DND
        SystemToolKind.PLAYLIST -> CapabilityKind.PLAYLIST
    }

class SystemToolHost(
    private val context: Context,
    private val settings: SettingsStore,
) {
    private val _state = MutableStateFlow(SystemToolState(
        config = settings.systemTools(),
        dndChange = settings.dndChange(),
    ))
    val state: StateFlow<SystemToolState> = _state

    init {
        publish()
    }

    private fun calendar(cfg: SystemToolConfig) = AndroidCalendarPort(context, cfg)

    private fun dnd(cfg: SystemToolConfig) = AndroidDndPort(context, cfg)

    private fun playlist(cfg: SystemToolConfig) = AndroidPlaylistPort(context, cfg)

    /** 重读配置与能力状态；设置页从后台回来时也会调。 */
    fun refresh() = publish()

    private fun publish() {
        val cfg = settings.systemTools()
        _state.update {
            it.copy(
                config = cfg,
                capabilities = listOf(
                    calendar(cfg).capability(),
                    dnd(cfg).capability(),
                    playlist(cfg).capability(cfg.playlistPackage, cfg.playlistUri),
                ),
                dndChange = settings.dndChange(),
            )
        }
    }

    // ---------------------------------------------------------------- 开关与配置

    fun setCalendarOn(on: Boolean) {
        settings.saveSystemTools(settings.systemTools().copy(calendarOn = on))
        publish()
    }

    fun setCalendar(choice: CalendarChoice) {
        settings.saveSystemTools(settings.systemTools().copy(calendarId = choice.id, calendarName = choice.name))
        publish()
    }

    fun setDndOn(on: Boolean) {
        settings.saveSystemTools(settings.systemTools().copy(dndOn = on))
        publish()
    }

    fun setPlaylistOn(on: Boolean) {
        settings.saveSystemTools(settings.systemTools().copy(playlistOn = on))
        publish()
    }

    fun setPlaylist(packageName: String, uri: String) {
        val old = settings.systemTools()
        val changed = old.playlistPackage != packageName.trim() || old.playlistUri != uri.trim()
        settings.saveSystemTools(
            old.copy(
                playlistPackage = packageName.trim(),
                playlistUri = uri.trim(),
                playlistMissing = if (changed) false else old.playlistMissing,
            ),
        )
        publish()
    }

    // ---------------------------------------------------------------- 日历

    /** 读系统里用户可见的日历；没权限明确失败，不返回空列表。 */
    suspend fun calendars(): ToolResult<List<CalendarChoice>> = withContext(Dispatchers.IO) {
        val r = AndroidCalendars.list(context)
        if (r.ok) _state.update { it.copy(calendars = r.value ?: emptyList()) }
        note(SystemToolKind.CALENDAR, r.ok, r.message)
        r
    }

    /** 读今天忙闲；同步时间只在成功后更新。 */
    suspend fun readBusyToday(now: Long): ToolResult<List<Busy>> = withContext(Dispatchers.IO) {
        val (from, to) = SystemToolTimes.todayRange(now)
        val r = calendar(settings.systemTools()).readBusy(from, to)
        if (r.ok) {
            val rows = r.value ?: emptyList()
            _state.update { it.copy(busy = rows, syncedAt = r.at) }
            note(SystemToolKind.CALENDAR, true, "读到 ${rows.size} 段忙闲")
        } else {
            note(SystemToolKind.CALENDAR, false, r.message)
        }
        r
    }

    /** 写日程：只打开系统编辑页，用户自己保存；不会写成“已完成”。 */
    suspend fun insertEvent(title: String, startText: String, endText: String, now: Long): ToolResult<CalendarHandoff> =
        withContext(Dispatchers.IO) {
            val startMin = SystemToolTimes.parseMinute(startText)
            val endMin = SystemToolTimes.parseMinute(endText)
            if (startMin == null || endMin == null) {
                val r = ToolResult.fail<CalendarHandoff>(ToolError.INVALID_TARGET, "时间要填 HH:mm（例如 20:30）", now)
                note(SystemToolKind.CALENDAR, false, r.message)
                return@withContext r
            }
            if (endMin <= startMin) {
                val r = ToolResult.fail<CalendarHandoff>(ToolError.INVALID_TARGET, "结束时间要晚于开始时间", now)
                note(SystemToolKind.CALENDAR, false, r.message)
                return@withContext r
            }
            val event = CalendarEventTemplate(
                title = title.ifBlank { "墨灵记下的事" },
                start = SystemToolTimes.at(now, startMin),
                end = SystemToolTimes.at(now, endMin),
            )
            val r = calendar(settings.systemTools()).insertEvent(event, now)
            note(SystemToolKind.CALENDAR, r.ok, r.message)
            r
        }

    // ---------------------------------------------------------------- 勿扰

    suspend fun dndApply(mode: DndMode, now: Long): ToolResult<DndChange> = withContext(Dispatchers.IO) {
        val r = dnd(settings.systemTools()).apply(mode, "设置页手动", now)
        // 只有真的改了档位才留恢复记录；本来就是这一档时不覆盖以前留下的记录。
        if (r.ok) r.value?.takeIf { it.original != it.applied }?.let { settings.saveDndChange(it) }
        publish()
        note(
            SystemToolKind.DND,
            r.ok,
            r.message,
            if (r.ok && r.value?.original != r.value?.applied) "可以恢复成墨灵改之前的档位" else "",
        )
        r
    }

    suspend fun dndRestore(now: Long): ToolResult<DndRestorePlan> = withContext(Dispatchers.IO) {
        val change = settings.dndChange()
        if (change == null) {
            val r = ToolResult.fail<DndRestorePlan>(ToolError.NOT_CONFIGURED, "墨灵没有改过勿扰，没有可恢复的", now)
            note(SystemToolKind.DND, false, r.message)
            return@withContext r
        }
        val r = dnd(settings.systemTools()).restore(change, now)
        if (r.ok) {
            // 恢复成功 / 用户自己改过 / 已经回到原样 / 过期：记录都不再有用了，免得下次误动。
            settings.saveDndChange(null)
            publish()
        }
        note(SystemToolKind.DND, r.ok, r.message)
        r
    }

    /** 调试/设置页显示：墨灵上次改过的勿扰记录。 */
    fun lastDndChange(): DndChange? = settings.dndChange()

    // ---------------------------------------------------------------- 歌单

    suspend fun openPlaylist(now: Long): ToolResult<PlaylistHandoff> = withContext(Dispatchers.IO) {
        val cfg = settings.systemTools()
        val r = playlist(cfg).open(cfg.playlistPackage, cfg.playlistUri, now)
        if (r.ok) {
            settings.saveSystemTools(cfg.copy(playlistMissing = false))
        } else if (r.error == ToolError.APP_MISSING) {
            settings.saveSystemTools(cfg.copy(playlistMissing = true))
        }
        publish()
        note(
            SystemToolKind.PLAYLIST,
            r.ok,
            r.message,
            if (r.ok) "打开的是系统页面；墨灵不能替你暂停/切歌" else "",
        )
        r
    }

    // ---------------------------------------------------------------- 排计划用

    /**
     * 排今天水道时用的忙闲：只有日历开关打开、选中日历、有权限且读成功才返回；
     * 任何失败都返回空（排计划照常，不因为日历不可用就崩），失败原因在设置/调试页能看。
     */
    suspend fun busyForPlanning(now: Long): List<Busy> = withContext(Dispatchers.IO) {
        val cfg = settings.systemTools()
        if (!cfg.calendarOn || cfg.calendarId.isBlank()) return@withContext emptyList()
        if (!SystemPermissions.calendarGranted(context)) return@withContext emptyList()
        val (from, to) = SystemToolTimes.todayRange(now)
        val r = calendar(cfg).readBusy(from, to)
        if (r.ok) {
            val rows = r.value ?: emptyList()
            _state.update { it.copy(busy = rows, syncedAt = r.at) }
            rows
        } else {
            emptyList()
        }
    }

    // ---------------------------------------------------------------- 动作记录

    private fun note(kind: SystemToolKind, ok: Boolean, message: String, undo: String = "") {
        val action = SystemAction(kind, System.currentTimeMillis(), ok, message, undo)
        _state.update { it.copy(actions = (listOf(action) + it.actions).take(20)) }
    }
}
