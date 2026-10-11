package com.zongkong.app.link

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import com.zongkong.app.ZkApp
import com.zongkong.core.Link

/**
 * 总控对一手开的口子：只有 status（严管没有、正在做哪一步、会弹回哪些应用）。
 * 只认一手的签名（见 [Partner]），别的应用调用一律拒绝。
 */
class LinkProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (!Partner.callerIsYishou(ctx)) return bundleOf(Link.ERROR to "只接受一手的连接")
        val app = ctx.applicationContext as ZkApp
        return when (method) {
            Link.STATUS -> {
                val store = app.store
                val map = Link.zongkongStatus(store.status(), store.config.value, store.work.value, store.zone)
                bundleOf(*map.toList().toTypedArray())
            }
            else -> bundleOf(Link.ERROR to "不认识的请求：$method")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
