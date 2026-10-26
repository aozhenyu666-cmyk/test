package com.yishou.app.link

import android.content.Context
import android.content.Intent
import android.net.Uri

/** 一手这边读总控的状态。总控没装、版本太旧或签名不对时返回 null。 */
object ZongkongClient {
    private val uri: Uri = Uri.parse("content://${Link.ZONGKONG_AUTHORITY}")

    /** 短时间缓存：无障碍事件一秒能来好几次，不必每次都问。 */
    @Volatile private var cached: Pair<Long, ZongkongStatus?>? = null

    fun status(context: Context, maxAgeMs: Long = 3_000): ZongkongStatus? {
        val now = System.currentTimeMillis()
        cached?.let { (at, s) -> if (now - at < maxAgeMs) return s }
        val s = fetch(context)
        cached = now to s
        return s
    }

    private fun fetch(context: Context): ZongkongStatus? {
        if (!Partner.zongkongGenuine(context)) return null
        return try {
            val b = context.contentResolver.call(uri, Link.STATUS, null, null) ?: return null
            if (b.containsKey(Link.ERROR)) return null
            @Suppress("DEPRECATION")
            ZongkongStatus.fromMap(b.keySet().associateWith { b.get(it) })
        } catch (e: Exception) {
            null
        }
    }

    fun open(context: Context, route: String? = null): Boolean {
        val intent = context.packageManager.getLaunchIntentForPackage(Partner.ZONGKONG) ?: return false
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (route != null) intent.putExtra("route", route)
        return try {
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            false
        }
    }
}
