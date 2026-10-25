package com.yishou.app.link

import android.content.Context
import android.content.Intent

/**
 * 一手这边「打开总控」的入口。
 *
 * v2 起**不再提供 `status()`**：无障碍事件线程里同步读总控状态会把整条事件通道拖死（报告 §8）。
 * 读总控状态的正确入口是 [com.yishou.app.healthwire.ZongkongLink]（缓存 + 后台 + 超时 + 失联态）。
 */
object ZongkongClient {

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
