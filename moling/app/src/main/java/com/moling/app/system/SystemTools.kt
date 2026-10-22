package com.moling.app.system

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.CalendarContract
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.moling.app.Notify
import com.moling.app.R
import com.moling.core.flow.Busy
import com.moling.core.flow.ChannelKind
import com.moling.core.flow.Flow
import com.moling.core.flow.FlowPlan
import com.moling.core.system.CalendarEventTemplate
import com.moling.core.system.CalendarHandoff
import com.moling.core.system.CalendarPort
import com.moling.core.system.Capability
import com.moling.core.system.CapabilityKind
import com.moling.core.system.CapabilityState
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
import kotlinx.serialization.json.Json

/*
 * 系统环境工具的 Android 适配（第十五批 B5，接 core/system/SystemPorts.kt 的端口）。
 * 每样工具一个开关，默认都关；没开的那样什么都不做，也不影响案板 / 聊天 / 陪练。
 *  - 日历：他开了、在系统弹窗里给了“读日历”、选了哪几个日历之后，排水道时绕开那些忙的时段。加日程只打开系统编辑页，他点保存才算。
 *  - 勿扰：他开了、在系统设置里给了“勿扰权限”之后，进主河道时把勿扰调到“仅优先”，出来时恢复——只恢复墨灵自己改的，他期间手动改过就不动。
 *  - 歌单：他填了链接、选了用哪个 App 打开之后，主河道开始时发一条通知，点了才打开。墨灵不在后台自己开别的 App。
 */
class ToolsStore(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("systools", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }

    var calendarOn: Boolean
        get() = prefs.getBoolean("calendar_on", false)
        set(v) = prefs.edit().putBoolean("calendar_on", v).apply()
    var calendarIds: Set<Long>
        get() = prefs.getStringSet("calendar_ids", emptySet()).orEmpty().mapNotNull { it.toLongOrNull() }.toSet()
        set(v) = prefs.edit().putStringSet("calendar_ids", v.map { it.toString() }.toSet()).apply()
    var lastCalendarSync: Long
        get() = prefs.getLong("calendar_sync", 0)
        set(v) = prefs.edit().putLong("calendar_sync", v).apply()
    var dndOn: Boolean
        get() = prefs.getBoolean("dnd_on", false)
        set(v) = prefs.edit().putBoolean("dnd_on", v).apply()
    var dndChange: DndChange?
        get() = prefs.getString("dnd_change", null)?.let { runCatching { json.decodeFromString(DndChange.serializer(), it) }.getOrNull() }
        set(v) = prefs.edit().apply { if (v == null) remove("dnd_change") else putString("dnd_change", json.encodeToString(DndChange.serializer(), v)) }.apply()
    var playlistOn: Boolean
        get() = prefs.getBoolean("playlist_on", false)
        set(v) = prefs.edit().putBoolean("playlist_on", v).apply()
    var playlistPkg: String
        get() = prefs.getString("playlist_pkg", "").orEmpty()
        set(v) = prefs.edit().putString("playlist_pkg", v).apply()
    var playlistUri: String
        get() = prefs.getString("playlist_uri", "").orEmpty()
        set(v) = prefs.edit().putString("playlist_uri", v).apply()
    /** 上一次心跳时所在的主河道（channel id）；空 = 不在主河道。 */
    var mainChannel: String
        get() = prefs.getString("main_channel", "").orEmpty()
        set(v) = prefs.edit().putString("main_channel", v).apply()
}

class AndroidCalendar(private val context: Context, private val store: ToolsStore) : CalendarPort {
    fun permitted(): Boolean = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    override fun capability(): Capability = when {
        !store.calendarOn -> Capability(CapabilityKind.CALENDAR_READ, CapabilityState.NOT_CONFIGURED, "没开")
        !permitted() -> Capability(CapabilityKind.CALENDAR_READ, CapabilityState.PERMISSION_DENIED, "没给读日历的权限")
        store.calendarIds.isEmpty() -> Capability(CapabilityKind.CALENDAR_READ, CapabilityState.NOT_CONFIGURED, "还没选看哪几个日历")
        else -> Capability(CapabilityKind.CALENDAR_READ, CapabilityState.READY, "看 ${store.calendarIds.size} 个日历")
    }

    /** 手机上的日历（id, 名字）。没权限返回空。 */
    fun calendars(): List<Pair<Long, String>> {
        if (!permitted()) return emptyList()
        val out = mutableListOf<Pair<Long, String>>()
        runCatching {
            context.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, CalendarContract.Calendars.ACCOUNT_NAME),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) out += c.getLong(0) to listOfNotNull(c.getString(1), c.getString(2)?.takeIf { it.isNotBlank() }?.let { "（$it）" }).joinToString("")
            }
        }
        return out
    }

    override fun readBusy(from: Long, to: Long): ToolResult<List<Busy>> {
        val cap = capability()
        if (!cap.usable) return ToolResult.fail(if (cap.state == CapabilityState.PERMISSION_DENIED) ToolError.PERMISSION_DENIED else ToolError.NOT_CONFIGURED, cap.detail)
        val ids = store.calendarIds
        val out = mutableListOf<Busy>()
        val r = runCatching {
            val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
                ContentUris.appendId(it, from)
                ContentUris.appendId(it, to)
            }.build()
            context.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.BEGIN, CalendarContract.Instances.END, CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.CALENDAR_ID, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.AVAILABILITY,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    if (c.getLong(3) !in ids) continue
                    if (c.getInt(4) == 1) continue // 全天的不当成“这段时间忙”
                    if (c.getInt(5) == CalendarContract.Events.AVAILABILITY_FREE) continue
                    out += Busy(c.getLong(0), c.getLong(1), c.getString(2).orEmpty().ifBlank { "（日历上的安排）" }.take(30))
                }
            }
        }
        if (r.isFailure) return ToolResult.fail(ToolError.REVOKED, "读日历失败（权限可能被收回了）")
        store.lastCalendarSync = System.currentTimeMillis()
        return ToolResult.ok(out.sortedBy { it.start }, "读到 ${out.size} 个安排")
    }

    override fun insertEvent(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> {
        val intent = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, event.start)
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, event.end)
            .putExtra(CalendarContract.Events.TITLE, event.title)
            .putExtra(CalendarContract.Events.EVENT_LOCATION, event.location)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            ToolResult.ok(CalendarHandoff(Intent.ACTION_INSERT, opened = true, needsUserSave = true, "打开了系统日程编辑页：你在那里点保存才算加上"), at = now)
        }.getOrElse { ToolResult.fail(ToolError.UNSUPPORTED, "手机上没有能编辑日程的应用") }
    }

    override fun writeDirect(event: CalendarEventTemplate, now: Long): ToolResult<CalendarHandoff> =
        ToolResult.fail(ToolError.NOT_CONFIGURED, "不直接写日历：用系统编辑页，你自己点保存")
}

class AndroidDnd(private val context: Context, private val store: ToolsStore) : DndPort {
    private val nm get() = context.getSystemService(NotificationManager::class.java)

    fun permitted(): Boolean = nm?.isNotificationPolicyAccessGranted == true

    override fun capability(): Capability = when {
        !store.dndOn -> Capability(CapabilityKind.DND, CapabilityState.NOT_CONFIGURED, "没开")
        nm == null -> Capability(CapabilityKind.DND, CapabilityState.UNSUPPORTED, "设备不支持")
        !permitted() -> Capability(CapabilityKind.DND, CapabilityState.PERMISSION_DENIED, "没给勿扰权限（系统设置里给）")
        else -> Capability(CapabilityKind.DND, CapabilityState.READY, "进主河道时调成仅优先")
    }

    override fun current(): ToolResult<DndMode> {
        val m = nm ?: return ToolResult.fail(ToolError.UNSUPPORTED, "设备不支持")
        return when (m.currentInterruptionFilter) {
            NotificationManager.INTERRUPTION_FILTER_ALL -> ToolResult.ok(DndMode.OFF)
            NotificationManager.INTERRUPTION_FILTER_PRIORITY -> ToolResult.ok(DndMode.PRIORITY)
            NotificationManager.INTERRUPTION_FILTER_ALARMS -> ToolResult.ok(DndMode.ALARMS)
            NotificationManager.INTERRUPTION_FILTER_NONE -> ToolResult.ok(DndMode.NONE)
            else -> ToolResult.fail(ToolError.UNSUPPORTED, "读不懂现在的勿扰状态")
        }
    }

    private fun filterOf(mode: DndMode) = when (mode) {
        DndMode.OFF -> NotificationManager.INTERRUPTION_FILTER_ALL
        DndMode.PRIORITY -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
        DndMode.ALARMS -> NotificationManager.INTERRUPTION_FILTER_ALARMS
        DndMode.NONE -> NotificationManager.INTERRUPTION_FILTER_NONE
    }

    override fun apply(mode: DndMode, reason: String, now: Long): ToolResult<DndChange> {
        if (!capability().usable) return ToolResult.fail(ToolError.PERMISSION_DENIED, capability().detail)
        val orig = current().value ?: return ToolResult.fail(ToolError.UNSUPPORTED, "读不到现在的勿扰状态，不改")
        return runCatching {
            nm!!.setInterruptionFilter(filterOf(mode))
            val change = DndChange("dnd-$now", orig, mode, now)
            store.dndChange = change
            ToolResult.ok(change, "勿扰调成「${mode.label}」（$reason）", now)
        }.getOrElse { ToolResult.fail(ToolError.FAILED, "勿扰没改成") }
    }

    override fun restore(change: DndChange, now: Long): ToolResult<DndRestorePlan> {
        val cur = current().value ?: return ToolResult.fail(ToolError.UNSUPPORTED, "读不到现在的勿扰状态，不动")
        val plan = DndPolicy.plan(change, cur, now)
        if (plan.kind == DndRestoreKind.RESTORED && plan.restoreTo != null) {
            val ok = runCatching { nm!!.setInterruptionFilter(filterOf(plan.restoreTo)) }.isSuccess
            if (!ok) return ToolResult.fail(ToolError.FAILED, "勿扰没恢复成（权限可能被收回了）")
        }
        store.dndChange = null
        return ToolResult.ok(plan, plan.message, now)
    }
}

class AndroidPlaylist(private val context: Context) : PlaylistPort {
    fun viewIntent(pkg: String, uri: String): Intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** 能打开这个链接的 App（包名, 名字）。 */
    fun handlers(uri: String): List<Pair<String, String>> {
        if (!uri.contains("://")) return emptyList()
        val pm = context.packageManager
        return runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse(uri)), 0)
                .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
                .distinctBy { it.first }
        }.getOrDefault(emptyList())
    }

    override fun capability(appPackage: String, uri: String): Capability {
        val target = PlaylistTargets.check(appPackage, uri)
        if (!target.usable) return target
        val ok = runCatching { context.packageManager.resolveActivity(viewIntent(appPackage, uri), 0) != null }.getOrDefault(false)
        return if (ok) target else Capability(CapabilityKind.PLAYLIST, CapabilityState.APP_MISSING, "选的 App 打不开这个链接（没装或不支持）")
    }

    override fun open(appPackage: String, uri: String, now: Long): ToolResult<PlaylistHandoff> {
        val cap = capability(appPackage, uri)
        if (!cap.usable) return ToolResult.fail(if (cap.state == CapabilityState.APP_MISSING) ToolError.APP_MISSING else ToolError.NOT_CONFIGURED, cap.detail)
        return runCatching {
            context.startActivity(viewIntent(appPackage, uri))
            ToolResult.ok(PlaylistHandoff(true, appPackage, uri, "交给了音乐 App（放没放成看那边）"), at = now)
        }.getOrElse { ToolResult.fail(ToolError.FAILED, "没打开") }
    }
}

/** 心跳里调：主河道开始 / 结束时，按开关处理勿扰和歌单提醒。返回给日志看的一句（没动作返回 null）。 */
object SystemCues {
    private const val NOTE_ID = 3101

    fun inMain(flow: FlowPlan?, now: Long): String {
        if (flow == null) return ""
        val minute = Flow.minuteOf(now)
        val c = Flow.current(flow, minute) ?: return ""
        return if (c.kind == ChannelKind.MAIN && minute in c.startMin until c.endMin) c.id else ""
    }

    fun tick(context: Context, flow: FlowPlan?, now: Long): String? {
        val store = ToolsStore(context)
        val main = inMain(flow, now)
        val was = store.mainChannel
        if (main == was) return null
        store.mainChannel = main
        val notes = mutableListOf<String>()
        val dnd = AndroidDnd(context, store)
        // 出了主河道：恢复墨灵改过的勿扰
        if (was.isNotEmpty()) {
            store.dndChange?.let { ch -> dnd.restore(ch, now).let { notes += it.message } }
        }
        if (main.isNotEmpty()) {
            val title = flow?.channels?.firstOrNull { it.id == main }?.title.orEmpty()
            // 进主河道：勿扰（只在现在是“关”的时候调，别人 / 他自己已经开着就不动）
            if (dnd.capability().usable && store.dndChange == null && dnd.current().value == DndMode.OFF) {
                dnd.apply(DndMode.PRIORITY, "主河道「$title」开始", now).let { notes += it.message }
            }
            // 歌单：只发通知，点了才打开
            if (store.playlistOn) {
                val pl = AndroidPlaylist(context)
                if (pl.capability(store.playlistPkg, store.playlistUri).usable) {
                    postPlaylist(context, pl.viewIntent(store.playlistPkg, store.playlistUri), title)
                    notes += "提醒放歌单"
                }
            }
        }
        return notes.filter { it.isNotBlank() }.joinToString("；").ifBlank { null }
    }

    private fun postPlaylist(context: Context, view: Intent, title: String) {
        Notify.ensureChannels(context)
        val pi = PendingIntent.getActivity(context, NOTE_ID, view, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(context, Notify.CH_ALERT)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle("主河道开始了" + if (title.isNotBlank()) "：$title" else "")
            .setContentText("点这里放你的歌单")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        runCatching { context.getSystemService(NotificationManager::class.java)?.notify(NOTE_ID, n) }
    }
}
