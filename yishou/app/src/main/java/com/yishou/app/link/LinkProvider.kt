package com.yishou.app.link

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.util.Log
import com.yishou.app.YishouApp
import com.yishou.app.data.Task
import com.yishou.app.settings.AppZone
import com.yishou.app.window.WindowClock
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant

/**
 * 一手的连接口：只给「总控」读（认包名 + 签名，别的应用一律拒绝），只读、不改一手的任何数据。
 *
 *  - status：今天的一行账、接住几手（总控的「一手组数」关卡、参谋、四部页都看它）
 *  - ledger(since)：since 之后的每一手。总控每 15 分钟拉一次存一份，刷题记录就进了总控
 *
 * 读库在 Binder 线程上做，带超时；读不到回 error，不抛异常（异常会传到总控那边）。
 */
class LinkProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val ctx = context ?: return err("一手还没启动好")
        if (!Partner.callerIsZongkong(ctx)) return err("只接受总控的连接")
        val app = ctx.applicationContext as? YishouApp ?: return err("一手还没启动好")
        // 总控的调用可能把一手的进程刚拉起来：等 onCreate 把数据库建好（最多 3 秒）
        val deadline = System.currentTimeMillis() + 3_000
        while (!app.ready && System.currentTimeMillis() < deadline) Thread.sleep(50)
        if (!app.ready) return err("一手还没启动好")
        return try {
            when (method) {
                LinkWire.STATUS -> {
                    val zone = AppZone.zone
                    val now = System.currentTimeMillis()
                    val from = Instant.now().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().toEpochMilli()
                    val today = read { app.dayLedger.between(from, now + 1) } ?: return err("一手读账超时")
                    val version = runCatching { ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName }.getOrNull().orEmpty()
                    val gateOn = runCatching { com.yishou.app.ui.PermissionStatus.accessibility(ctx) }.getOrDefault(false)
                    // 总控还要知道一手正在练哪一步、陪练窗口开没开：它据此显示「在练：…」并决定要不要拉回。
                    val dao = app.database.dao()
                    val task = read { dao.getCurrentTask() }
                    val bp = task?.let { t -> read { dao.getBreakpoint(t.id) } }
                    val ref = ctx.getSharedPreferences(LINK_PREFS, Context.MODE_PRIVATE).getString(REF_KEY, "").orEmpty()
                    val span = WindowClock.active(now, app.settings.app.value, zone)
                    bundle(
                        LinkWire.status(today, version, gateOn) +
                            LinkWire.taskFields(
                                task?.title.orEmpty(), task?.goal.orEmpty(),
                                bp?.known.orEmpty(), bp?.stuck.orEmpty(), bp?.nextQuestion.orEmpty(), ref,
                            ) +
                            LinkWire.windowFields(span != null, span?.end ?: 0L),
                    )
                }
                LinkWire.LEDGER -> {
                    val now = System.currentTimeMillis()
                    // 最多回看 7 天：总控第一次连上时补一周，之后只拉新的
                    val since = (extras?.getLong("since", 0L) ?: 0L).coerceIn(now - 7 * 86_400_000L, now)
                    val entries = read { app.dayLedger.between(since, now + 1) } ?: return err("一手读账超时")
                    bundle(LinkWire.ledger(entries.filter { it.at >= since }))
                }
                LinkWire.SET_TASK -> {
                    val order = LinkWire.order(extras?.let { b -> b.keySet().associateWith { b.get(it) } } ?: emptyMap())
                    if (!order.valid) return err("总控没给任务标题")
                    val now = System.currentTimeMillis()
                    val prefs = ctx.getSharedPreferences(LINK_PREFS, Context.MODE_PRIVATE)
                    val same = order.ref.isNotBlank() && order.ref == prefs.getString(REF_KEY, "").orEmpty()
                    val dao = app.database.dao()
                    val msg = runBlocking {
                        withTimeoutOrNull(5_000) {
                            val cur = dao.getCurrentTask()
                            if (same && cur != null) {
                                dao.editTask(
                                    cur.copy(title = order.title, goal = order.goal, material = order.material.ifBlank { null }),
                                    order.known, order.stuck, order.next, now,
                                )
                                "已经是一手的当前任务"
                            } else {
                                dao.createCurrentTask(
                                    Task(title = order.title, goal = order.goal, material = order.material.ifBlank { null }, isCurrent = true, createdAt = now),
                                    order.known, order.stuck, order.next, now,
                                )
                                "已经把这一步交给一手"
                            }
                        }
                    } ?: return err("一手写任务超时")
                    prefs.edit().putString(REF_KEY, order.ref).apply()
                    bundle(mapOf(LinkWire.OK to msg))
                }
                LinkWire.START_WINDOW -> {
                    val now = System.currentTimeMillis()
                    val p = app.settings.updateApp { it.copy(manualWindowStart = now, windowStoppedAt = 0) }
                    val span = WindowClock.active(now, p, AppZone.zone) ?: return err("陪练窗口没开起来")
                    bundle(mapOf(LinkWire.OK to "已经开了一局陪练，${(span.end - now + 59_999) / 60_000} 分钟"))
                }
                else -> err("一手不认识 $method")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "连接出错：$method", e)
            err("一手出错：${e.javaClass.simpleName}")
        }
    }

    private fun <T> read(block: suspend () -> T): T? = runBlocking { withTimeoutOrNull(4_000) { block() } }

    private fun err(msg: String) = Bundle().apply {
        putInt("v", LinkWire.VERSION)
        putString(LinkWire.ERROR, msg)
    }

    @Suppress("UNCHECKED_CAST")
    private fun bundle(m: Map<String, Any>) = Bundle().apply {
        for ((k, v) in m) when (v) {
            is String -> putString(k, v)
            is Int -> putInt(k, v)
            is Long -> putLong(k, v)
            is Boolean -> putBoolean(k, v)
            is LongArray -> putLongArray(k, v)
            is Array<*> -> putStringArray(k, (v as Array<String>))
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        const val TAG = "YishouLink"
        /** 总控派的这一步是哪一步：ref 用「事项编号/行动键」，同一 ref 再交一次只更新、不新建。 */
        const val LINK_PREFS = "link"
        const val REF_KEY = "taskRef"
    }
}
