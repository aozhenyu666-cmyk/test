package com.dshdriver.app

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context

/**
 * 设备所有者入口。
 *
 * 普通应用不能自己开无障碍；设备所有者可以绕过 WRITE_SECURE_SETTINGS 直接写 secure settings，
 * 也不需要 Shizuku。它需要用户在电脑上执行一次：
 *
 *   adb shell dpm set-device-owner com.dshdriver.app/.DriverDeviceAdmin
 *
 * 设置前要先把手机上的账号（小米/华为/Google）删掉，否则系统会拒绝。这是可选项，不设也能用
 * WRITE_SECURE_SETTINGS / Shizuku 两条路。
 */
class DriverDeviceAdmin : DeviceAdminReceiver() {
    companion object {
        fun component(context: Context): ComponentName = ComponentName(context, DriverDeviceAdmin::class.java)

        fun isOwner(context: Context): Boolean = runCatching {
            context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true
        }.getOrDefault(false)

        fun adbCommand(context: Context): String =
            "adb shell dpm set-device-owner ${context.packageName}/${DriverDeviceAdmin::class.java.name}"

        /** 设备所有者模式下可以禁卸载；返回是否成功。 */
        fun setUninstallBlocked(context: Context, blocked: Boolean): Boolean = runCatching {
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            if (!isOwner(context)) return false
            dpm.setUninstallBlocked(component(context), context.packageName, blocked)
            true
        }.getOrDefault(false)
    }
}
