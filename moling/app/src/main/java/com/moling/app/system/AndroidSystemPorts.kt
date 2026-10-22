package com.moling.app.system

import android.Manifest
import android.app.NotificationManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.moling.core.flow.Busy
import com.moling.core.system.CalendarEventTemplate
import com.moling.core.system.CalendarHandoff
import com.moling.core.system.CalendarPort
import com.moling.core.system.Capability
import com.moling.core.system.DndChange
import com.moling.core.system.DndMode
import com.moling.core.system.DndPolicy
import com.moling.core.system.DndPort
import com.moling.core.system.DndRestoreKind
import com.moling.core.system.DndRestorePlan
import com.moling.core.system.PlaylistHandoff
import com.moling.core.system.PlaylistPort
import com.moling.core.system.PlaylistTargets
import com.moling.core.system.ToolError
import com.moling.core.system.ToolResult
import java.util.UUID

/*
 * B5：SystemPorts 的 Android 适配器。只做三件事，并且每次都从系统读回执：
 *  1. 读选中日历的忙闲（READ_CALENDAR；没给就说没给）；
 *  2. 打开系统日程编辑页（ACTION_INSERT），保存与否由用户在系统里决定；
 *  3. 改 / 恢复勿扰（通知策略访问）与显式 Intent 打开用户选的歌单。
 *
 * 绝不把“发出了 Intent”写成“已完成”；也绝不返回空列表冒充“今天没安排”。
 */

/** 日历权限现在给没给。设置页也用它刷新状态。 */
object SystemPermissions {
    fun calendarGranted(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
}

/** 列出用户可见的日历。只有拿到 READ_CALENDAR 才给列表，否则明确失败。 */
object AndroidCalendars {
    fun list(context: Context): ToolResult<List<CalendarChoice>> {
        if (!SystemPermissions.calendarGranted(context)) {
            return ToolResult.fail(ToolError.PERMISSION_DENIED, "没给日历权限：列不出日历", System.currentTimeMillis())
        }
        val now = System.currentTimeMillis()
        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.VISIBLE,
        )
        return try {
            val rows = mutableListOf<CalendarChoice>()
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                projection,
                "${CalendarContract.Calendars.VISIBLE} = 1",
                null,
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC",
            )?.use { c ->
                val idCol = c.getColumnIndexOrThrow(CalendarContract.Calendars._ID)
                val nameCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME)
                val accountCol = c.getColumnIndexOrThrow(CalendarContract.Calendars.ACCOUNT_NAME)
                while (c.moveToNext()) {
                    val id = c.getLong(idCol).toString()
                    val name = c.getString(nameCol).orEmpty().ifBlank { "日历" }
                    val account = c.getString(accountCol).orEmpty()
                    rows += CalendarChoice(id, name, account)
                }
            }
            ToolResult.ok(rows, "读到 ${rows.size} 个日历", now)
        } catch (e: SecurityException) {
            ToolResult.fail(ToolError.REVOKED, "日历权限被撤销了：列不出日历", now)
        } catch (e: Exception) {
            ToolResult.fail(ToolError.FAILED, "列日历失败：${e.message ?: e.javaClass.simpleName}", now)
        }
    }
}

class AndroidCalendarPort(
    private val context: Context,
    private val config: SystemToolConfig,
) : CalendarPort {

    override fun capability(): Capability = SystemToolRules.calendarCapability(
        enabled = config.calendarOn,
        permissionGranted = SystemPermissions.calendarGranted(context),
        hasSelection = config.calendarId.isNotBlank(),
        now = System.currentTimeMillis(),
    )

    override fun readBusy(from: Long, to: Long): ToolResult<List<Busy>> {
        val now = System.currentTimeMillis()
        if (!config.calendarOn) return ToolResult.fail(ToolError.NOT_CONFIGURED, "日历没开", now)
        if (!SystemPermissions.calendarGranted(context)) {
            val err = if (config.calendarId.isNotBlank()) ToolError.REVOKED else ToolError.PERMISSION_DENIED
            return ToolResult.fail(err, "没给日历权限：读不到忙闲，排计划先不参考日历", now)
        }
        if (config.calendarId.isBlank()) return ToolResult.fail(ToolError.NOT_CONFIGURED, "先选一个日历", now)
        if (to <= from) return ToolResult.fail(ToolError.INVALID_TARGET, "时间范围不对", now)

        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(from.toString())
            .appendPath(to.toString())
            .build()
        val projection = arrayOf(
            CalendarContract.Instances.TITLE,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
        )
        val selection = "${CalendarContract.Instances.CALENDAR_ID} = ?"
        return try {
            val rows = mutableListOf<Busy>()
            context.contentResolver.query(
                uri,
                projection,
                selection,
                arrayOf(config.calendarId),
                "${CalendarContract.Instances.BEGIN} ASC",
            )?.use { c ->
                val titleCol = c.getColumnIndexOrThrow(CalendarContract.Instances.TITLE)
                val beginCol = c.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
                val endCol = c.getColumnIndexOrThrow(CalendarContract.Instances.END)
                while (c.moveToNext()) {
                    val begin = if (c.isNull(beginCol)) 0L else c.getLong(beginCol)
                    val end = if (c.isNull(endCol)) 0L else c.getLong(endCol)
                    if (end <= begin) continue
                    val title = c.getString(titleCol).orEmpty().ifBlank { "日历安排" }
                    rows += Busy(begin, end, title)
                }
            }
            ToolResult.ok(rows.sortedBy { it.start }, "读到 ${rows.size} 段忙闲", now)
        } catch (e: SecurityException) {
            ToolResult.fail(ToolError.REVOKED, "日历权限被撤销了：读不到忙闲", now)
        } catch (e: Exception) {
            ToolResult.fail(ToolError.FAILED, "读日历失败：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    override fun insertEvent(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> {
        if (!config.calendarOn) return ToolResult.fail(ToolError.NOT_CONFIGURED, "日历没开", now)
        if (!SystemPermissions.calendarGranted(context)) {
            return ToolResult.fail(ToolError.PERMISSION_DENIED, "没给日历权限：打不开系统日程编辑页", now)
        }
        if (event.end <= event.start) return ToolResult.fail(ToolError.INVALID_TARGET, "结束时间要晚于开始时间", now)
        val intent = Intent(Intent.ACTION_INSERT)
            .setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, event.title)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (event.location.isNotBlank()) intent.putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
        // 能指定就指定到用户选中的日历；系统日历页不认这个 extra 时也仍会让用户自己选，不伪造结果。
        config.calendarId.toLongOrNull()?.let { intent.putExtra(CalendarContract.Events.CALENDAR_ID, it) }
        return try {
            context.startActivity(intent)
            ToolResult.ok(
                CalendarHandoff(
                    action = Intent.ACTION_INSERT,
                    opened = true,
                    needsUserSave = true,
                    message = "打开了系统日程编辑页：你在那里点保存才算写入；墨灵不知道你保存没有，不会写成已完成",
                ),
                "已打开系统日程编辑页（还没保存）",
                now,
            )
        } catch (e: ActivityNotFoundException) {
            ToolResult.fail(ToolError.APP_MISSING, "这台设备没有能打开日程编辑页的日历应用", now)
        } catch (e: SecurityException) {
            ToolResult.fail(ToolError.PERMISSION_DENIED, "日历权限被撤销了：打不开日程编辑页", now)
        } catch (e: Exception) {
            ToolResult.fail(ToolError.FAILED, "打开日程编辑页失败：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    /** 直接写入要单独的 WRITE_CALENDAR；本批不申请、不开放，明确拒绝。 */
    override fun writeDirect(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> =
        ToolResult.fail(ToolError.NOT_CONFIGURED, "没开启直接写入，也不会申请日历写入权限", now)
}

class AndroidDndPort(
    private val context: Context,
    private val config: SystemToolConfig,
) : DndPort {

    private fun manager(): NotificationManager? =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager

    private fun accessGranted(): Boolean = runCatching { manager()?.isNotificationPolicyAccessGranted == true }.getOrDefault(false)

    override fun capability(): Capability = SystemToolRules.dndCapability(
        enabled = config.dndOn,
        supported = manager() != null,
        accessGranted = accessGranted(),
        now = System.currentTimeMillis(),
    )

    override fun current(): ToolResult<DndMode> {
        val now = System.currentTimeMillis()
        if (!config.dndOn) return ToolResult.fail(ToolError.NOT_CONFIGURED, "勿扰没开", now)
        val m = manager() ?: return ToolResult.fail(ToolError.UNSUPPORTED, "这台设备没有通知策略接口", now)
        if (!accessGranted()) return ToolResult.fail(ToolError.PERMISSION_DENIED, "没给通知策略访问：读不到勿扰状态", now)
        val mode = runCatching { filterToMode(m.currentInterruptionFilter) }.getOrNull()
            ?: return ToolResult.fail(ToolError.UNSUPPORTED, "系统返回了不认识的勿扰档位", now)
        return ToolResult.ok(mode, "现在是${mode.label}", now)
    }

    override fun apply(mode: DndMode, reason: String, now: Long): ToolResult<DndChange> {
        if (!config.dndOn) return ToolResult.fail(ToolError.NOT_CONFIGURED, "勿扰没开", now)
        val m = manager() ?: return ToolResult.fail(ToolError.UNSUPPORTED, "这台设备没有通知策略接口", now)
        if (!accessGranted()) {
            return ToolResult.fail(ToolError.PERMISSION_DENIED, "没给通知策略访问：勿扰没有生效，去系统设置里打开", now)
        }
        val before = runCatching { filterToMode(m.currentInterruptionFilter) }.getOrNull()
            ?: return ToolResult.fail(ToolError.UNSUPPORTED, "系统返回了不认识的勿扰档位", now)
        return try {
            m.setInterruptionFilter(modeToFilter(mode))
            val after = runCatching { filterToMode(m.currentInterruptionFilter) }.getOrNull()
            if (after != mode) {
                ToolResult.fail(ToolError.FAILED, "系统没有接受勿扰设置（现在是 ${after?.label ?: "未知"}）", now)
            } else {
                val change = DndChange(UUID.randomUUID().toString(), before, mode, now)
                ToolResult.ok(change, if (before == mode) "本来就是${mode.label}，没有改动" else "已切到${mode.label}（${reason.ifBlank { "专注" }}）", now)
            }
        } catch (e: SecurityException) {
            ToolResult.fail(ToolError.PERMISSION_DENIED, "通知策略访问被撤销了：勿扰没有生效", now)
        } catch (e: Exception) {
            ToolResult.fail(ToolError.FAILED, "改勿扰失败：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    override fun restore(change: DndChange, now: Long): ToolResult<DndRestorePlan> {
        if (!config.dndOn) return ToolResult.fail(ToolError.NOT_CONFIGURED, "勿扰没开", now)
        val m = manager() ?: return ToolResult.fail(ToolError.UNSUPPORTED, "这台设备没有通知策略接口", now)
        if (!accessGranted()) {
            return ToolResult.fail(ToolError.PERMISSION_DENIED, "没给通知策略访问：恢复没生效", now)
        }
        val current = runCatching { filterToMode(m.currentInterruptionFilter) }.getOrNull()
            ?: return ToolResult.fail(ToolError.UNSUPPORTED, "系统返回了不认识的勿扰档位", now)
        val plan = DndPolicy.plan(change, current, now)
        if (plan.kind != DndRestoreKind.RESTORED || plan.restoreTo == null) {
            return ToolResult.ok(plan, plan.message, now)
        }
        return try {
            m.setInterruptionFilter(modeToFilter(plan.restoreTo))
            val after = runCatching { filterToMode(m.currentInterruptionFilter) }.getOrNull()
            if (after != plan.restoreTo) {
                ToolResult.fail(ToolError.FAILED, "系统没有接受恢复（现在是 ${after?.label ?: "未知"}）", now)
            } else {
                ToolResult.ok(plan, plan.message, now)
            }
        } catch (e: SecurityException) {
            ToolResult.fail(ToolError.PERMISSION_DENIED, "通知策略访问被撤销了：恢复没生效", now)
        } catch (e: Exception) {
            ToolResult.fail(ToolError.FAILED, "恢复勿扰失败：${e.message ?: e.javaClass.simpleName}", now)
        }
    }

    private fun filterToMode(filter: Int): DndMode? = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> DndMode.OFF
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> DndMode.PRIORITY
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> DndMode.ALARMS
        NotificationManager.INTERRUPTION_FILTER_NONE -> DndMode.NONE
        else -> null
    }

    private fun modeToFilter(mode: DndMode): Int = when (mode) {
        DndMode.OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
        DndMode.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        DndMode.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        DndMode.NONE -> NotificationManager.INTERRUPTION_FILTER_NONE
    }
}

class AndroidPlaylistPort(
    private val context: Context,
    private val config: SystemToolConfig,
) : PlaylistPort {

    override fun capability(appPackage: String, uri: String): Capability = SystemToolRules.playlistCapability(
        enabled = config.playlistOn,
        appPackage = appPackage,
        uri = uri,
        lastMissing = config.playlistMissing,
        now = System.currentTimeMillis(),
    )

    override fun open(appPackage: String, uri: String, now: Long): ToolResult<PlaylistHandoff> {
        if (!config.playlistOn) return ToolResult.fail(ToolError.NOT_CONFIGURED, "歌单没开", now)
        val target = PlaylistTargets.check(appPackage, uri)
        if (!target.usable) return ToolResult.fail(ToolError.INVALID_TARGET, target.detail, now)
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .setPackage(appPackage)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            ToolResult.ok(
                PlaylistHandoff(
                    started = true,
                    appPackage = appPackage,
                    uri = uri,
                    message = "系统接受了打开 $appPackage；墨灵不确认是否开始在播，也不模拟播放成功",
                ),
                "已交给系统打开（不表示正在播放）",
                now,
            )
        } catch (e: ActivityNotFoundException) {
            ToolResult.fail(ToolError.APP_MISSING, "找不到 $appPackage 能打开这个 URI：检查应用是否安装、URI 对不对", now)
        } catch (e: SecurityException) {
            ToolResult.fail(ToolError.PERMISSION_DENIED, "系统不让打开 $appPackage", now)
        } catch (e: Exception) {
            ToolResult.fail(ToolError.FAILED, "打开歌单失败：${e.message ?: e.javaClass.simpleName}", now)
        }
    }
}
