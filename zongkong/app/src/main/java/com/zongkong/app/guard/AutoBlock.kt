package com.zongkong.app.guard

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import com.zongkong.app.zk
import com.zongkong.core.Defaults

/**
 * 娱乐应用自动进拦截名单（0.10）。
 *
 * 以前拦截名单只在你第一次打开“拦截名单”页时勾上常见娱乐应用，不点保存就一直是空的——
 * 名单是空的，严管、额度、答题全都管不到任何应用。现在：
 *   - 手机上装的常见娱乐应用（[Defaults.suggestedBlocked]），和系统标成“游戏 / 视频”的应用，自动加进名单
 *   - 加进名单是收紧，马上生效；你自己从名单里拿掉的（[com.zongkong.core.Config.unblocked]）不再加回去
 *   - 微信、QQ、钉钉这类联系人用的，系统也常标成“社交”，不按分类加（要管就自己勾）
 */
object AutoBlock {
    private const val TAG = "AutoBlock"

    fun detect(context: Context): Set<String> = try {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        pm.queryIntentActivities(intent, 0)
            .map { it.activityInfo.applicationInfo }
            .distinctBy { it.packageName }
            .filter { ai ->
                ai.packageName in Defaults.suggestedBlocked ||
                    (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && (ai.category == ApplicationInfo.CATEGORY_GAME || ai.category == ApplicationInfo.CATEGORY_VIDEO))
            }
            .map { it.packageName }
            .filter { it != context.packageName && it !in Defaults.neverBlock }
            .toSet()
    } catch (e: Exception) {
        Log.w(TAG, "读应用列表失败", e)
        emptySet()
    }

    /** 把新发现的娱乐应用加进名单，返回新加了几个。 */
    fun apply(context: Context): Int {
        val found = detect(context)
        if (found.isEmpty()) return 0
        var added = emptySet<String>()
        context.zk.store.updateConfig { c ->
            added = found - c.blocked - c.unblocked
            if (added.isEmpty()) c else c.copy(blocked = c.blocked + added)
        }
        if (added.isNotEmpty()) {
            Log.i(TAG, "自动加进拦截名单：$added")
            runCatching { Diag.log(context, "autoblock", "自动加进拦截名单 ${added.size} 个：" + added.joinToString("、") { com.zongkong.app.data.AppNames.label(context, it) }) }
        }
        return added.size
    }
}
