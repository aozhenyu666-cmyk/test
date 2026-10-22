package com.zongkong.sentinel

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle

/**
 * 总控往这里写：心跳 + 拦截名单（state），或者只问一句哨兵在不在跑（ping）。
 * 只认总控（包名 + 同一把签名），别的调用一律拒绝。
 */
class LinkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val c = context ?: return null
        if (!Sys.callerIsMain(c)) return Bundle().apply { putString(Proto.ERROR, "只接受总控的连接") }
        return try {
            when (method) {
                Proto.STATE -> {
                    val e = extras ?: Bundle()
                    val s = Decide.State(
                        e.getLong(Proto.AT), e.getStringArray(Proto.BLOCKED)?.toSet().orEmpty(),
                        e.getBoolean(Proto.STRICT), e.getLong(Proto.FREE_UNTIL),
                    )
                    val before = Store.state(c)
                    val wasDown = before != null && !Decide.mainAlive(before, System.currentTimeMillis())
                    Store.put(c, s)
                    if (s.at == 0L) { if (!wasDown) Store.log(c, "总控说自己下线了") } else if (wasDown) Store.log(c, "总控的心跳回来了")
                    status(c, withLog = false)
                }
                Proto.PING -> status(c, withLog = true)
                else -> Bundle().apply { putString(Proto.ERROR, "不认识的请求：$method") }
            }
        } catch (e: Throwable) {
            Bundle().apply { putString(Proto.ERROR, e.message ?: e.javaClass.simpleName) }
        }
    }

    private fun status(c: android.content.Context, withLog: Boolean) = Bundle().apply {
        putBoolean(Proto.RUNNING, SentinelService.running)
        putLong(Proto.BEAT, Store.beat)
        putInt(Proto.TAKEOVERS, Store.takeovers(c))
        putLong(Proto.LAST_TAKEOVER, Store.lastTakeover(c))
        putBoolean(Proto.CAN_WRITE, Sys.canWrite(c))
        putString(Proto.VERSION, try { c.packageManager.getPackageInfo(c.packageName, 0).versionName } catch (_: Throwable) { "" })
        if (withLog) putStringArray(Proto.LOG, Store.recent(c, 20).toTypedArray())
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
