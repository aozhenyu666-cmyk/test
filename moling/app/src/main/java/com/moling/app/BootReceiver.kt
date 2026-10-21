package com.moling.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.moling.app.data.SettingsStore

/** 开机 / 覆盖安装后把后台心跳接回来（设置里开着才接）。 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching {
            if (SettingsStore(context.applicationContext).backgroundHeart()) HeartService.start(context)
        }.onFailure { Log.w("BootReceiver", "开机后接回心跳失败", it) }
    }
}
