package com.yishou.app.link

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import com.yishou.app.YishouApp
import com.yishou.app.data.Task
import com.yishou.app.log.RunLog
import com.yishou.app.training.Training
import com.yishou.app.ui.PermissionStatus
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.runBlocking
import java.time.ZoneId

/**
 * 一手对总控开的口子（只认总控，见 [Partner]）：
 * - status：今天练了几组、当前任务和断点、陪练窗口、昨晚的规则
 * - setTask：总控把一步行动交给一手陪练
 *
 * 只走 call()，不提供查询表格；别的应用调用一律拒绝。
 */
class LinkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (!Partner.callerIsZongkong(ctx)) return bundleOf(Link.ERROR to "只接受总控的连接")
        val app = ctx.applicationContext as YishouApp
        return try {
            when (method) {
                Link.STATUS -> bundleOf(*status(app).toMap().toList().toTypedArray())
                Link.SET_TASK -> {
                    val map = extras?.let { b -> b.keySet().associateWith { @Suppress("DEPRECATION") b.get(it) } }.orEmpty()
                    val order = TaskOrder.fromMap(map) ?: return bundleOf(Link.ERROR to "标题和目标不能空")
                    bundleOf(Link.OK to setTask(app, order))
                }
                Link.START_WINDOW -> {
                    val now = System.currentTimeMillis()
                    val prefs = app.settings.app.value
                    if (WindowClock.active(now, prefs, ZoneId.systemDefault()) != null) {
                        bundleOf(Link.OK to "一手的陪练窗口已经开着")
                    } else {
                        app.settings.updateApp { it.copy(manualWindowStart = now, windowPauseUntil = 0) }
                        bundleOf(Link.OK to "一手开局：陪练窗口 ${prefs.windowMinutes} 分钟")
                    }
                }
                else -> bundleOf(Link.ERROR to "不认识的请求：$method")
            }
        } catch (e: Exception) {
            RunLog.e("总控连接", "处理 $method 出错", e)
            bundleOf(Link.ERROR to (e.message ?: e.javaClass.simpleName))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        fun status(app: YishouApp): YishouStatus = runBlocking {
            val dao = app.database.dao()
            val zone = ZoneId.systemDefault()
            val now = System.currentTimeMillis()
            val prefs = app.settings.app.value
            val today = WindowClock.today(now, zone)
            val rounds = dao.roundsBetween(WindowClock.startOfDay(today.minusDays(60), zone), now + 1)
            val byDay = rounds.groupBy { WindowClock.today(it.createdAt, zone) }.mapValues { (_, rs) -> rs.count { it.effective } }
            val plan = Training.plan(byDay, today, prefs.setSize, prefs.baseSets)
            val todays = rounds.filter { WindowClock.today(it.createdAt, zone) == today }
            val task = dao.getCurrentTask()
            val bp = task?.let { dao.getBreakpoint(it.id) }
            val span = WindowClock.active(now, prefs, zone)
            val summary = dao.latestSummary()
            YishouStatus(
                appVersion = try {
                    app.packageManager.getPackageInfo(app.packageName, 0).versionName.orEmpty()
                } catch (e: Exception) {
                    ""
                },
                taskTitle = task?.title.orEmpty(),
                taskGoal = task?.goal.orEmpty(),
                known = bp?.known.orEmpty(),
                stuck = bp?.stuck.orEmpty(),
                next = bp?.nextQuestion?.takeIf { it.isNotBlank() } ?: bp?.pendingCoachMove.orEmpty(),
                ref = task?.let { LinkRefs.refOf(app, it.id) }.orEmpty(),
                roundsToday = todays.size,
                effectiveToday = plan.todayEffective,
                setSize = plan.setSize,
                targetSets = plan.targetSets,
                doneSets = plan.doneSets,
                streak = plan.streak,
                windowActive = span != null,
                windowEnd = span?.end ?: 0,
                gateOn = PermissionStatus.accessibility(app),
                summaryDate = summary?.date.orEmpty(),
                rule = summary?.rule.orEmpty(),
                tomorrowQuestion = summary?.tomorrowQuestion.orEmpty(),
                summaryNote = summary?.note.orEmpty(),
            )
        }

        /**
         * 交来的行动设为当前任务。同一步行动再交一次：内容变了就更新，没变就不动
         * （不打断陪练已经出好的那一手）。返回一句说明。
         */
        fun setTask(app: YishouApp, order: TaskOrder): String = runBlocking {
            val dao = app.database.dao()
            val now = System.currentTimeMillis()
            val existing = if (order.ref.isBlank()) null else LinkRefs.taskOf(app, order.ref)?.let { dao.getTask(it) }
            if (existing != null) {
                val bp = dao.getBreakpoint(existing.id)
                val same = existing.title == order.title && existing.goal == order.goal &&
                    (order.known.isBlank() || bp?.known == order.known) &&
                    (order.stuck.isBlank() || bp?.stuck == order.stuck) &&
                    (order.next.isBlank() || bp?.nextQuestion == order.next)
                if (!existing.isCurrent) dao.switchCurrentTask(existing.id)
                if (same) return@runBlocking "已经是一手的当前任务"
                dao.editTask(
                    existing.copy(title = order.title, goal = order.goal, material = order.material.ifBlank { existing.material }, isCurrent = true),
                    order.known.ifBlank { bp?.known.orEmpty() },
                    order.stuck.ifBlank { bp?.stuck.orEmpty() },
                    order.next.ifBlank { bp?.nextQuestion.orEmpty() },
                    now,
                )
                return@runBlocking "已更新一手的当前任务"
            }
            val id = dao.createCurrentTask(
                Task(title = order.title, goal = order.goal, material = order.material.ifBlank { null }, isCurrent = true, createdAt = now),
                order.known, order.stuck, order.next, now,
            )
            if (order.ref.isNotBlank()) LinkRefs.remember(app, id, order.ref)
            "已设为一手的当前任务"
        }
    }
}

/** 一手的任务 ↔ 总控的行动，记在一个小文件里（不改数据库结构）。 */
object LinkRefs {
    private fun prefs(app: android.content.Context) = app.getSharedPreferences("zongkong_link", android.content.Context.MODE_PRIVATE)

    fun remember(app: android.content.Context, taskId: Long, ref: String) {
        prefs(app).edit().putString("task_$taskId", ref).putLong("ref_$ref", taskId).apply()
    }

    fun refOf(app: android.content.Context, taskId: Long): String? = prefs(app).getString("task_$taskId", null)

    fun taskOf(app: android.content.Context, ref: String): Long? = prefs(app).getLong("ref_$ref", -1).takeIf { it > 0 }
}
