package com.zongkong.app.link

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.core.os.bundleOf
import com.zongkong.app.ZkApp
import com.zongkong.core.moling.Moling
import com.zongkong.core.moling.MolingLink
import kotlinx.coroutines.launch

/**
 * 总控对墨灵开的口子：只走 call()，只认墨灵的签名（见 [Partner.callerIsMoling]），别的应用调用一律拒绝。
 *  - status：总控此刻的状态（键值都是字符串）
 *  - command：交一条建议。总控检查后挂起来，发通知等你点“执行”；这里不执行任何东西
 *  - result：问一条建议后来怎么样了（arg = id）
 */
class MolingProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        val ctx = context ?: return null
        if (!Partner.callerIsMoling(ctx)) return bundleOf(MolingLink.ERROR to "只接受墨灵的连接")
        val app = ctx.applicationContext as ZkApp
        val now = System.currentTimeMillis()
        return when (method) {
            MolingLink.STATUS -> {
                val map = MolingBridge.status(ctx, now).toMap()
                bundleOf(*map.toList().toTypedArray())
            }
            MolingLink.COMMAND -> {
                val fields = extras?.keySet().orEmpty().mapNotNull { k -> extras?.getString(k)?.let { k to it } }.toMap()
                var receipt: com.zongkong.core.moling.Receipt? = null
                val before = Moling.pending(app.store.moling.value, now).map { it.id }.toSet()
                val book = app.store.updateMoling { b -> Moling.receive(b, fields, now).also { receipt = it.second }.first }
                val r = receipt!!
                // 新收下的才发通知（重复交的、正在倒计时的那条已经有通知了）
                val fresh = r.ok && r.id !in before && book.orders.firstOrNull { it.id == r.id }?.receivedAt == now
                if (fresh) book.orders.firstOrNull { it.id == r.id }?.let { MolingBridge.notify(ctx, it) }
                // 授权内的：倒计时一到就执行（总控的每分钟巡检也会兜底）
                if (fresh && r.auto) app.appScope.launch {
                    kotlinx.coroutines.delay(Moling.VETO_MS + 1_000)
                    MolingBridge.runDue(ctx)
                }
                bundleOf(MolingLink.OK to r.ok.toString(), MolingLink.MESSAGE to r.message, MolingLink.ID to r.id, MolingLink.AUTO to r.auto.toString())
            }
            MolingLink.RESULT -> {
                val o = arg?.let { MolingBridge.result(ctx, it) }
                    ?: return bundleOf(MolingLink.STATE to "", MolingLink.MESSAGE to "总控里没有这条")
                bundleOf(MolingLink.STATE to o.state.name, MolingLink.MESSAGE to o.result.ifBlank { o.state.label })
            }
            else -> bundleOf(MolingLink.ERROR to "不认识的请求：$method")
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
