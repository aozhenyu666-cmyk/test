package com.zongkong.app.guard

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

/**
 * 设备管理员组件：只为 `force-lock` 存在（报告 §4 / 规范 §5.1）。
 *
 * 明确边界：
 *  - 不启用防卸载、不改锁屏密码、不抹数据、不监视输入；
 *  - 不是 Device Owner，也不需要（本轮不要求）；
 *  - 用户在系统设置里随时可以撤销。
 */
class ZkDeviceAdmin : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        runCatching { Diag.log(context, "admin_on", "设备管理员已启用：可以用 force-lock 独立锁屏") }
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        runCatching { Diag.log(context, "admin_off", "设备管理员被撤销：独立锁屏不可用，只剩无障碍通道") }
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence =
        "撤销后「总控」无法独立锁屏（只剩无障碍通道，无障碍被系统停掉时兜底不了）。确定撤销？"

    override fun onPasswordChanged(context: Context, intent: Intent) {
        // 明确不做任何处理：总控不读、不写、不记录用户的锁屏凭据。
        Log.i("ZkDeviceAdmin", "用户改了密码：总控不读取、不保存")
    }
}
